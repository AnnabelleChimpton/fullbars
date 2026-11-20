// SPDX-License-Identifier: Apache-2.0
package main

import (
	"io"
	"sync/atomic"
)

// ccFilter sits between the SRT reader and the TS demuxer and repairs one quirk of the phone's muxer.
//
// RootEncoder's MPEG-TS packetizer keeps ONE continuity counter for all its PES packets, video and audio together,
// where MPEG-TS wants one per PID. A demuxer checks the counter per PID, so every time audio packets sit between two
// video packets it sees a jump on the video PID and throws away the video data it was holding. That is harmless for
// most frames: a video PES under 64 KB carries its length and is complete before any audio arrives. But a frame over
// 64 KB (a 1080p keyframe at 6 Mbps) is sent with PES_packet_length 0, which a demuxer can only finish at the next
// video PES start, and by then audio has gone by and the "jump" makes it drop the whole keyframe. The picture then
// smears until the next keyframe, and the keeper can't splice on a keyframe it never sees.
//
// The filter learns which kind of input it has from the packets around each PID switch:
//   - shared counter (RootEncoder): a packet's counter follows the previous PES packet's, whatever its PID. Each PES
//     PID then gets its own counter written into its packets, so the demuxer sees what it expects.
//   - per-PID counters (OBS, Larix, Moblin, our own muxer): everything passes through byte for byte.
//
// With a shared counter a jump means packets really were lost on the way (an SRT too-late drop). The filter can't
// tell which PID lost them, but the phone writes each PES in one piece, so only the PES that was being sent when the
// gap opened can have lost its tail; a PES whose start was lost shows up as packets without a start. Both are dropped
// whole (the rest of their packets are left out, and a skipped counter makes the demuxer discard what it already
// holds), so the demuxer never stitches a damaged frame together. Up to 15 lost packets in a row are seen; exactly 16
// look continuous, and the demuxer's own length checks are the only guard then.
//
// PAT, PMT, other tables and null packets always pass through untouched: RootEncoder counts those per PID already.
type ccFilter struct {
	r       io.Reader
	pkt     [tsPacket]byte
	pending []byte // what is left of the current packet to hand out
	err     error  // the reader's error, handed out once the data before it is gone

	pids  [maxFilterPIDs]pidCC
	npids int

	lastPID int   // PID of the last PES packet with a payload; -1 before the first
	lastCC  uint8 // its counter, as it arrived

	decided bool
	score   int  // evidence: above 0 for a shared counter, below 0 for per-PID counters
	shared  bool // rewriting counters (RootEncoder layout)

	// Damage seen before the layout was known, counted only if it turns out to be the phone's.
	pendGaps, pendPES int64

	stats *ccStats // optional
}

// maxFilterPIDs: the phone sends one video and one audio PID (plus PAT and PMT). PIDs beyond this many pass through.
const maxFilterPIDs = 16

type pidCC struct {
	pid        uint16
	classified bool
	pes        bool // carries PES; otherwise a table, passed through untouched
	seen       bool // lastIn is valid
	lastIn     uint8
	out        uint8 // the counter the demuxer last saw on this PID
	open       bool  // a PES has started and isn't known to be complete
	remaining  int   // bytes still to come in the open PES; -1 = unknown length (PES_packet_length 0)
	damaged    bool  // drop this PID's packets until its next PES start
	discard    bool  // at its next PES start, make the demuxer drop the partial PES it holds
}

// ccStats counts what the filter did, for the session's stats line. Written by the demux goroutine, read by the
// stats goroutine.
type ccStats struct {
	shared      atomic.Bool  // the input uses one counter for all PES (RootEncoder)
	gaps        atomic.Int64 // jumps in the shared counter: packets lost on the way
	droppedPES  atomic.Int64 // PES left out because a gap cut into them
	droppedPkts atomic.Int64 // their packets that reached the server
}

func newCCFilter(r io.Reader, stats *ccStats) *ccFilter {
	return &ccFilter{r: r, lastPID: -1, stats: stats}
}

// Read hands out at most one TS packet per call, so the demuxer gets each packet as soon as it arrives: reading ahead
// would block a live stream on data that hasn't been sent yet.
func (f *ccFilter) Read(p []byte) (int, error) {
	for len(f.pending) == 0 {
		if f.err != nil {
			return 0, f.err
		}
		n, err := io.ReadFull(f.r, f.pkt[:])
		switch {
		case err == nil:
			if !f.process(f.pkt[:]) {
				continue // dropped
			}
			f.pending = f.pkt[:]
		case n > 0: // a cut packet at the end: hand it on as it is
			f.pending = f.pkt[:n]
			f.err = err
			if err == io.ErrUnexpectedEOF {
				f.err = io.EOF
			}
		default:
			return 0, err
		}
	}
	n := copy(p, f.pending)
	f.pending = f.pending[n:]
	return n, nil
}

func (f *ccFilter) state(pid uint16) *pidCC {
	for i := 0; i < f.npids; i++ {
		if f.pids[i].pid == pid {
			return &f.pids[i]
		}
	}
	if f.npids == maxFilterPIDs {
		return nil
	}
	f.pids[f.npids] = pidCC{pid: pid, remaining: -1}
	f.npids++
	return &f.pids[f.npids-1]
}

// vote takes one piece of evidence about the input's counters. The first one decides; after that it takes a clear
// majority to change, so a coincidence after a real loss doesn't flip the mode.
func (f *ccFilter) vote(v int) {
	f.score = min(max(f.score+v, -8), 8)
	was, wasDecided := f.shared, f.decided
	switch {
	case !f.decided:
		f.decided, f.shared = true, f.score > 0
	case f.shared && f.score <= -2:
		f.shared = false
	case !f.shared && f.score >= 2:
		f.shared = true
	}
	if !wasDecided && f.shared {
		// The phone's layout from the start: the damage noted so far was real.
		if f.stats != nil {
			f.stats.gaps.Add(f.pendGaps)
			f.stats.droppedPES.Add(f.pendPES)
		}
	} else if !wasDecided || was != f.shared {
		// Per-PID counters, or a change of mind: what the shared-counter rules noted means nothing.
		for i := 0; i < f.npids; i++ {
			f.pids[i].damaged, f.pids[i].discard = false, false
		}
	}
	f.pendGaps, f.pendPES = 0, 0
	if f.stats != nil {
		f.stats.shared.Store(f.shared)
	}
}

// tsPayload returns a TS packet's payload, or nil if it has none (or a malformed adaptation field).
func tsPayload(p []byte) []byte {
	afc := (p[3] >> 4) & 3
	if afc&1 == 0 {
		return nil
	}
	off := 4
	if afc == 3 {
		off += 1 + int(p[4])
	}
	if off >= len(p) {
		return nil
	}
	return p[off:]
}

// process looks at one 188-byte packet, rewrites its counter if needed, and says whether to keep it.
func (f *ccFilter) process(p []byte) bool {
	if p[0] != 0x47 {
		return true
	}
	pid := uint16(p[1]&0x1f)<<8 | uint16(p[2])
	if pid < 0x20 || pid == 0x1fff { // PAT, other tables, null packets
		return true
	}
	s := f.state(pid)
	if s == nil {
		return true
	}
	pusi := p[1]&0x40 != 0
	payload := tsPayload(p)
	if !s.classified {
		if !pusi || payload == nil {
			return true // can't tell yet what this PID carries
		}
		s.classified = true
		s.pes = len(payload) >= 3 && payload[0] == 0 && payload[1] == 0 && payload[2] == 1
	}
	if !s.pes {
		return true // a table (the PMT): counted per PID by every muxer
	}
	if payload == nil {
		// Adaptation field only: doesn't advance the counter. The demuxer ignores these anyway.
		if f.shared {
			p[3] = p[3]&0xf0 | s.out
		}
		return true
	}
	cc := p[3] & 0x0f
	gap := false
	if f.lastPID >= 0 {
		if f.lastPID == int(pid) && cc == f.lastCC {
			return !f.shared // a duplicate packet; the demuxer drops those itself
		}
		expect := (f.lastCC + 1) & 0x0f
		if f.lastPID != int(pid) && s.seen {
			g, q := cc == expect, cc == (s.lastIn+1)&0x0f
			switch {
			case g && !q:
				f.vote(1)
			case q && !g:
				f.vote(-1)
			}
		}
		gap = cc != expect
	}
	var prev *pidCC
	if f.lastPID >= 0 && f.lastPID != int(pid) {
		prev = f.state(uint16(f.lastPID))
	}
	f.lastPID, f.lastCC = int(pid), cc
	s.seen, s.lastIn = true, cc

	if f.decided && !f.shared {
		s.out = cc
		s.track(pusi, payload)
		return true
	}
	// The phone's layout, or not known yet (the first packets of a stream). Until it is known everything passes
	// through as it came, but damage is noted: the phone's first keyframe is sent before the first PID switch
	// shows which layout this is, and a loss in it must still be kept from the demuxer.
	if gap {
		if !f.decided {
			f.pendGaps++
		} else if f.stats != nil {
			f.stats.gaps.Add(1)
		}
		// The phone writes each PES in one piece: only the one being sent when the packets went missing can have
		// lost its tail. The demuxer holds the part before the gap; it must not be finished with what follows.
		lost := prev
		if lost == nil {
			lost = s
		}
		if lost.open && !lost.discard {
			lost.damaged, lost.discard = true, true
			f.dropped()
		}
		// Another PID carrying on without a PES start lost its start in the gap.
		if prev != nil && !pusi && !s.damaged {
			s.damaged = true
			f.dropped()
		}
	}
	if !f.shared {
		if pusi {
			s.damaged, s.discard = false, false // the demuxer finishes the old PES here: too late to keep it out
		}
		s.out = cc
		s.track(pusi, payload)
		return true
	}
	if pusi {
		s.out = (s.out + 1) & 0x0f
		if s.discard {
			// Skip a counter value: the demuxer then sees a discontinuity and throws away the partial PES it holds.
			s.out = (s.out + 1) & 0x0f
		}
		s.damaged, s.discard = false, false
		s.track(true, payload)
		p[3] = p[3]&0xf0 | s.out
		return true
	}
	if !s.open && !s.damaged {
		// Packets of a PES whose start never arrived.
		s.damaged = true
		f.dropped()
	}
	if s.damaged {
		if f.stats != nil {
			f.stats.droppedPkts.Add(1)
		}
		return false
	}
	s.track(false, payload)
	s.out = (s.out + 1) & 0x0f
	p[3] = p[3]&0xf0 | s.out
	return true
}

func (f *ccFilter) dropped() {
	switch {
	case !f.decided:
		f.pendPES++
	case f.stats != nil:
		f.stats.droppedPES.Add(1)
	}
}

// track follows the PES lengths, to know whether a PES is complete.
func (s *pidCC) track(start bool, payload []byte) {
	if start {
		s.open, s.remaining = true, -1
		if len(payload) >= 6 && payload[0] == 0 && payload[1] == 0 && payload[2] == 1 {
			if l := int(payload[4])<<8 | int(payload[5]); l != 0 {
				s.remaining = l + 6 - len(payload)
				s.open = s.remaining > 0
			}
		}
		return
	}
	if s.remaining >= 0 {
		s.remaining -= len(payload)
		if s.remaining <= 0 {
			s.open = false
		}
	}
}
