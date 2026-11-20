// SPDX-License-Identifier: Apache-2.0
package main

import (
	"math/rand"
	"net"
	"reflect"
	"sort"
	"testing"

	"github.com/datarhei/gosrt/circular"
	"github.com/datarhei/gosrt/congestion"
	"github.com/datarhei/gosrt/congestion/live"
	"github.com/datarhei/gosrt/packet"
)

// The vendored gosrt's reorder tolerance (third_party/gosrt/PATCHES.md). Bonding delivers packets out of order
// across links; the receiver must not ask for a resend of a packet that is merely late, and must still ask quickly
// for one that is really gone.

type nakRecorder struct{ naks [][]uint32 }

func (n *nakRecorder) receiver(ttl uint32, maxWait uint64) congestion.Receiver {
	return live.NewReceiver(live.ReceiveConfig{
		InitialSequenceNumber: circular.New(0, packet.MAX_SEQUENCENUMBER),
		PeriodicACKInterval:   10_000,
		PeriodicNAKInterval:   20_000,
		OnSendNAK: func(list []circular.Number) {
			var v []uint32
			for _, x := range list {
				v = append(v, x.Val())
			}
			n.naks = append(n.naks, v)
		},
		LossMaxTTL:  ttl,
		LossMaxWait: maxWait,
	})
}

var testAddr, _ = net.ResolveIPAddr("ip", "127.0.0.1")

// push hands the receiver data packets with these sequence numbers. They are due far in the future, so nothing is
// delivered (or given up on) during a test.
func push(r congestion.Receiver, seqs ...uint32) {
	for _, s := range seqs {
		p := packet.NewPacket(testAddr)
		p.Header().PacketSequenceNumber = circular.New(s, packet.MAX_SEQUENCENUMBER)
		p.Header().PktTsbpdTime = 60_000_000
		r.Push(p)
	}
}

func seqRange(from, to uint32) []uint32 {
	var s []uint32
	for i := from; i <= to; i++ {
		s = append(s, i)
	}
	return s
}

// ticks runs the receiver's clock from 'from' to 'to' microseconds, every 10 ms like a connection does.
func ticks(r congestion.Receiver, from, to uint64) {
	for t := from; t <= to; t += 10_000 {
		r.Tick(t)
	}
}

func TestReorderWithinToleranceSendsNoNAK(t *testing.T) {
	var n nakRecorder
	r := n.receiver(64, 250_000)
	r.Tick(1_000_000)
	push(r, seqRange(0, 9)...)
	push(r, seqRange(12, 40)...) // 10 and 11 took the slower link...
	ticks(r, 1_010_000, 1_060_000)
	push(r, 11, 10) // ...and arrive 60 ms later, out of order
	ticks(r, 1_070_000, 2_000_000)
	if len(n.naks) != 0 {
		t.Fatalf("reordered packets were NAKed: %v", n.naks)
	}
	if lost := r.Stats().PktLoss; lost != 0 {
		t.Fatalf("reordered packets counted as lost: %d", lost)
	}

	// Upstream behaviour (no tolerance) for comparison: the same arrival order asks for a resend at once.
	var u nakRecorder
	r = u.receiver(0, 0)
	push(r, seqRange(0, 9)...)
	push(r, 12)
	if !reflect.DeepEqual(u.naks, [][]uint32{{10, 11}}) {
		t.Fatalf("without a tolerance the gap should be NAKed immediately, got %v", u.naks)
	}
}

func TestRealLossIsNAKedAfterTolerancePackets(t *testing.T) {
	var n nakRecorder
	r := n.receiver(64, 250_000)
	r.Tick(1_000_000)
	push(r, seqRange(0, 9)...)
	push(r, seqRange(11, 11+62)...) // 63 newer packets: still within the tolerance
	r.Tick(1_010_000)
	if len(n.naks) != 0 {
		t.Fatalf("NAKed before the tolerance ran out: %v", n.naks)
	}
	push(r, 74) // the 64th
	r.Tick(1_020_000)
	if len(n.naks) != 1 || !reflect.DeepEqual(n.naks[0], []uint32{10, 10}) {
		t.Fatalf("expected one NAK for packet 10 right after the 64th newer packet, got %v", n.naks)
	}
	if lost := r.Stats().PktLoss; lost != 1 {
		t.Fatalf("expected 1 packet counted lost, got %d", lost)
	}
	// Still missing: the periodic NAK keeps asking for it.
	ticks(r, 1_030_000, 1_100_000)
	if len(n.naks) < 3 || !reflect.DeepEqual(n.naks[len(n.naks)-1], []uint32{10, 10}) {
		t.Fatalf("expected periodic NAKs repeating packet 10, got %v", n.naks)
	}
}

func TestRealLossAtALowRateIsNAKedAfterMaxWait(t *testing.T) {
	var n nakRecorder
	r := n.receiver(64, 250_000)
	r.Tick(1_000_000)
	push(r, seqRange(0, 9)...)
	push(r, 11, 12, 13) // a trickle: the packet count alone would take a long time
	ticks(r, 1_010_000, 1_240_000)
	if len(n.naks) != 0 {
		t.Fatalf("NAKed before LossMaxWait: %v", n.naks)
	}
	ticks(r, 1_250_000, 1_250_000)
	if len(n.naks) != 1 || !reflect.DeepEqual(n.naks[0], []uint32{10, 10}) {
		t.Fatalf("expected a NAK for packet 10 at LossMaxWait, got %v", n.naks)
	}
}

func TestPartlyFilledGapReportsOnlyWhatIsStillMissing(t *testing.T) {
	var n nakRecorder
	r := n.receiver(64, 250_000)
	r.Tick(1_000_000)
	push(r, seqRange(0, 9)...)
	push(r, 15) // 10..14 missing
	push(r, 12) // one of them was only late
	r.Tick(1_010_000)
	push(r, 20, 21) // a second gap, 16..19, found 10 ms later
	ticks(r, 1_020_000, 1_100_000)
	push(r, 17)
	ticks(r, 1_110_000, 1_250_000) // the first gap has waited 250 ms, the second 240 ms
	want := [][]uint32{{10, 11, 13, 14}}
	if !reflect.DeepEqual(n.naks, want) {
		t.Fatalf("got NAKs %v, want %v", n.naks, want)
	}
	// The next periodic NAK repeats the reported gap and adds the second one once it is due, minus packet 17.
	ticks(r, 1_260_000, 1_300_000)
	last := n.naks[len(n.naks)-1]
	if !reflect.DeepEqual(last, []uint32{10, 11, 13, 14, 16, 16, 18, 19}) {
		t.Fatalf("got last NAK %v (all: %v)", last, n.naks)
	}
}

func TestPeriodicNAKLeavesOutGapsWithinTolerance(t *testing.T) {
	var n nakRecorder
	r := n.receiver(64, 250_000)
	r.Tick(1_000_000)
	push(r, seqRange(0, 9)...)
	push(r, seqRange(11, 80)...) // packet 10 lost for real: 70 newer packets
	r.Tick(1_010_000)
	push(r, 82, 83) // a fresh gap at 81
	n.naks = nil
	ticks(r, 1_020_000, 1_100_000)
	for _, nak := range n.naks {
		if !reflect.DeepEqual(nak, []uint32{10, 10}) {
			t.Fatalf("a periodic NAK asked for a packet still within the tolerance: %v", n.naks)
		}
	}
	if len(n.naks) == 0 {
		t.Fatal("the real loss was not repeated by the periodic NAK")
	}
}

// bondSim runs a receiver against a simulated bonded sender: 1316-byte packets at mbps, sent alternately over two
// links with these one-way delays (µs) and a little jitter, each packet lost on the way with probability loss. The
// receiver's NAKs are answered with a resend over the faster link one round trip later (resends can be lost too).
// TSBPD is 2 s, as on the server.
type bondResult struct {
	naks      int    // NAK packets the receiver sent
	nakedSeqs int    // sequence numbers in them
	spurious  int    // distinct sequence numbers asked for that were never lost (only reordered)
	sent      int    // original packets sent
	lostOnWay int    // originals lost on the links
	delivered int    // packets handed to the reader by the end
	lost      uint64 // the receiver's count of packets it gave up on (PktLost)
	tolerance uint32 // the packet tolerance in use at the end of sending
}

func bondSim(t *testing.T, delays [2]uint64, mbps, loss float64, ttl uint32, maxWait uint64, secs int) bondResult {
	t.Helper()
	const latency = 2_000_000
	type arrival struct {
		at      uint64
		seq     uint32
		retrans bool
	}
	var pending []arrival
	add := func(a arrival) { pending = append(pending, a) }
	rng := rand.New(rand.NewSource(7))
	jitter := func() uint64 { return uint64(rng.Intn(6000)) } // 0-6 ms
	sendAt := map[uint32]uint64{}
	lostSet, asked := map[uint32]bool{}, map[uint32]bool{}
	var res bondResult
	var now uint64
	fast := min(delays[0], delays[1])
	var recv congestion.Receiver
	recv = live.NewReceiver(live.ReceiveConfig{
		InitialSequenceNumber: circular.New(0, packet.MAX_SEQUENCENUMBER),
		PeriodicACKInterval:   10_000,
		PeriodicNAKInterval:   20_000,
		OnSendNAK: func(list []circular.Number) {
			res.naks++
			for i := 0; i+1 < len(list); i += 2 {
				for s := list[i].Val(); s <= list[i+1].Val(); s++ {
					res.nakedSeqs++
					if !asked[s] && !lostSet[s] {
						res.spurious++
					}
					asked[s] = true
					if rng.Float64() >= loss { // the resend can be lost too; the periodic NAK asks again
						add(arrival{at: now + fast + fast + jitter(), seq: s, retrans: true})
					}
				}
			}
		},
		OnDeliver:   func(packet.Packet) { res.delivered++ },
		LossMaxTTL:  ttl,
		LossMaxWait: maxWait,
	})
	interval := uint64(1316 * 8 / mbps) // µs between packets
	end := uint64(secs) * 1_000_000
	var seq uint32
	for next := uint64(0); now <= end+latency+500_000; now += 1000 {
		for ; next <= now && next <= end; next += interval {
			sendAt[seq] = next
			res.sent++
			if rng.Float64() < loss {
				res.lostOnWay++
				lostSet[seq] = true
			} else {
				add(arrival{at: next + delays[seq%2] + jitter(), seq: seq})
			}
			seq++
		}
		kept := pending[:0]
		var due []arrival
		for _, a := range pending {
			if a.at <= now {
				due = append(due, a)
			} else {
				kept = append(kept, a)
			}
		}
		pending = kept
		sort.Slice(due, func(i, j int) bool { return due[i].at < due[j].at })
		for _, a := range due {
			p := packet.NewPacket(testAddr)
			p.Header().PacketSequenceNumber = circular.New(a.seq, packet.MAX_SEQUENCENUMBER)
			p.Header().PktTsbpdTime = sendAt[a.seq] + latency
			p.Header().RetransmittedPacketFlag = a.retrans
			recv.Push(p)
		}
		if now%10_000 == 0 {
			recv.Tick(now)
		}
		if lt, ok := recv.(interface{ LossTolerance() uint32 }); ok && now == end {
			res.tolerance = lt.LossTolerance() // while the stream still runs
		}
	}
	res.lost = recv.Stats().PktLost
	return res
}

// Two links whose one-way delays differ by 180 ms at 8 Mbps: about 140 packets overtake every packet on the slow
// link. With the tolerance following the receive rate nothing is asked for again; a fixed 64-packet tolerance (no
// time cap) asks for most of the slow link's packets.
func TestTwoLinkDelaySpreadSendsNoNAKs(t *testing.T) {
	r := bondSim(t, [2]uint64{40_000, 220_000}, 8, 0, 64, 250_000, 10)
	if r.naks != 0 || r.spurious != 0 || r.lost != 0 || r.delivered != r.sent {
		t.Fatalf("no loss: %d NAKs for %d packets, %d lost, %d of %d delivered", r.naks, r.nakedSeqs, r.lost, r.delivered, r.sent)
	}
	if r.tolerance < 200 {
		t.Fatalf("tolerance at 8 Mbps is %d packets; expected the 250 ms cap to set it (~235)", r.tolerance)
	}
	fixed := bondSim(t, [2]uint64{40_000, 220_000}, 8, 0, 64, 0, 10)
	if fixed.nakedSeqs < fixed.sent/4 {
		t.Fatalf("the fixed tolerance should ask for the slow link's packets: %d of %d", fixed.nakedSeqs, fixed.sent)
	}
}

// The same links with 1% real loss: every lost packet is asked for and arrives before its TSBPD time.
func TestTwoLinkRealLossIsRecoveredInTime(t *testing.T) {
	r := bondSim(t, [2]uint64{40_000, 220_000}, 8, 0.01, 64, 250_000, 10)
	if r.lostOnWay < 40 {
		t.Fatalf("the simulation lost only %d packets", r.lostOnWay)
	}
	if r.lost != 0 || r.delivered != r.sent {
		t.Fatalf("%d of %d packets lost for good, %d delivered", r.lost, r.sent, r.delivered)
	}
	// Only lost packets are asked for: the reorder between the links causes no resends on top.
	if r.spurious != 0 {
		t.Fatalf("%d packets that were only reordered were asked for again (%d lost)", r.spurious, r.lostOnWay)
	}
}
