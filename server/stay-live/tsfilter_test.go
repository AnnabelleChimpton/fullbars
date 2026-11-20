// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bufio"
	"bytes"
	"context"
	"errors"
	"io"
	"testing"

	"github.com/asticode/go-astits"
)

// The continuity filter (tsfilter.go) against the phone's real TS layout: RootEncoder counts all its PES packets
// with one shared continuity counter and sends video PES over 64 KB with PES_packet_length 0.

// bigIDR is an IDR access unit of about n bytes. The filler never contains a start code.
func bigIDR(n int) []byte {
	return append(idr(0x64, 0xee), bytes.Repeat([]byte{0xaa}, n)...)
}

func pFrame(n int) []byte { return append([]byte{0, 0, 0, 1, 0x41}, bytes.Repeat([]byte{0xbb}, n)...) }

// phoneLayoutAUs: per GOP a 100 KB IDR, two 2-packet audio PES, a P frame, two more audio PES and a last P frame.
// Each PES is written in one piece, as RootEncoder does.
func phoneLayoutAUs(gops int) []*au {
	var aus []*au
	for g := int64(0); g < int64(gops); g++ {
		t := 90000 + g*9000
		aud := func(i int64) *au {
			d := adts(36) // 252 bytes: two TS packets
			return &au{kind: kindAudio, pts: t + i*1920, dts: t + i*1920, data: d, dur: adtsDuration(d)}
		}
		aus = append(aus,
			&au{kind: kindVideo, pts: t, dts: t, data: bigIDR(100 << 10), key: true},
			aud(0), aud(1),
			&au{kind: kindVideo, pts: t + 3000, dts: t + 3000, data: pFrame(1000)},
			aud(2), aud(3),
			&au{kind: kindVideo, pts: t + 6000, dts: t + 6000, data: pFrame(800)},
		)
	}
	return aus
}

// muxPerPID writes AUs with our own muxer: per-PID continuity counters, as MPEG-TS wants.
func muxPerPID(t *testing.T, aus []*au) []byte {
	t.Helper()
	var buf bytes.Buffer
	out := newTSOut(&buf)
	for _, a := range aus {
		if err := out.write(a, a.pts, a.dts); err != nil {
			t.Fatal(err)
		}
	}
	return buf.Bytes()
}

// toPhoneLayout rewrites a per-PID TS into RootEncoder's layout: one counter across all PES packets, and
// PES_packet_length 0 on every video PES over 64 KB. Tables keep their own counters, as RootEncoder's do.
func toPhoneLayout(ts []byte) []byte {
	out := append([]byte(nil), ts...)
	var shared uint8
	for i := 0; i+tsPacket <= len(out); i += tsPacket {
		p := out[i : i+tsPacket]
		pid := uint16(p[1]&0x1f)<<8 | uint16(p[2])
		if pid != outVideoPID && pid != outAudioPID {
			continue
		}
		pl := tsPayload(p)
		if pl == nil {
			continue
		}
		p[3] = p[3]&0xf0 | shared
		shared = (shared + 1) & 0x0f
		if pid == outVideoPID && p[1]&0x40 != 0 && len(pl) >= 6 {
			pl[4], pl[5] = 0, 0 // PES_packet_length 0, as for any frame too big for the 16-bit length
		}
	}
	return out
}

// plainDemux demuxes without the filter, like the server did before it.
func plainDemux(ts []byte) (video []*au) {
	dmx := astits.NewDemuxer(context.Background(), bufio.NewReader(bytes.NewReader(ts)))
	for {
		d, err := dmx.NextData()
		if err != nil {
			return
		}
		if d.PES != nil && d.PID == outVideoPID {
			video = append(video, &au{kind: kindVideo, data: d.PES.Data, key: hasIDR(d.PES.Data)})
		}
	}
}

func demuxAll(t *testing.T, ts []byte, stats *ingestStats) (video, audio []*au) {
	t.Helper()
	err := demuxLive(context.Background(), bytes.NewReader(ts), func(a *au) {
		if a.kind == kindVideo {
			video = append(video, a)
		} else {
			audio = append(audio, a)
		}
	}, func(error) {}, stats)
	if err != nil {
		t.Fatal(err)
	}
	return
}

func TestPhoneLayoutKeepsTheBigKeyframe(t *testing.T) {
	ts := toPhoneLayout(muxPerPID(t, phoneLayoutAUs(1)))

	// Without the filter the 100 KB keyframe is lost: the audio in between looks like a counter jump on the video PID.
	for _, v := range plainDemux(ts) {
		if v.key {
			t.Fatal("the test stream doesn't reproduce the problem: a plain demux kept the keyframe")
		}
	}

	stats := &ingestStats{}
	video, audio := demuxAll(t, ts, stats)
	if len(video) != 3 || !video[0].key || len(video[0].data) != len(bigIDR(100<<10)) {
		t.Fatalf("got %d video AUs (first key=%v, %d bytes); want the whole IDR and two P frames", len(video), len(video) > 0 && video[0].key, len(video[0].data))
	}
	if len(audio) != 4 {
		t.Fatalf("got %d audio AUs, want 4", len(audio))
	}
	if !stats.cc.shared.Load() || stats.cc.gaps.Load() != 0 || stats.cc.droppedPES.Load() != 0 {
		t.Fatalf("filter stats: shared=%v gaps=%d dropped=%d", stats.cc.shared.Load(), stats.cc.gaps.Load(), stats.cc.droppedPES.Load())
	}
	if stats.keyframes.Load() != 1 || stats.bigAUs.Load() != 1 || stats.videoAUs.Load() != 3 {
		t.Fatalf("ingest stats: keyframes %d, over 64 KB %d, video %d", stats.keyframes.Load(), stats.bigAUs.Load(), stats.videoAUs.Load())
	}
}

// Input that already counts per PID (OBS, Larix, Moblin, our own muxer) goes through byte for byte.
func TestPerPIDInputPassesThroughUnchanged(t *testing.T) {
	ts := muxPerPID(t, phoneLayoutAUs(3))
	got, err := io.ReadAll(newCCFilter(bytes.NewReader(ts), nil))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(got, ts) {
		t.Fatal("per-PID input was changed")
	}
}

// packetsOf returns the indexes of the TS packets of a PID.
func packetsOf(ts []byte, pid uint16) []int {
	var idx []int
	for i := 0; i+tsPacket <= len(ts); i += tsPacket {
		if uint16(ts[i+1]&0x1f)<<8|uint16(ts[i+2]) == pid {
			idx = append(idx, i/tsPacket)
		}
	}
	return idx
}

func withoutPacket(ts []byte, n int) []byte {
	return append(append([]byte(nil), ts[:n*tsPacket]...), ts[(n+1)*tsPacket:]...)
}

// idrRuns returns the packet indexes of each keyframe: the long runs of consecutive video packets.
func idrRuns(ts []byte) [][]int {
	var runs [][]int
	var cur []int
	for _, i := range packetsOf(ts, outVideoPID) {
		if len(cur) > 0 && i != cur[len(cur)-1]+1 {
			if len(cur) > 100 {
				runs = append(runs, cur)
			}
			cur = nil
		}
		cur = append(cur, i)
	}
	if len(cur) > 100 {
		runs = append(runs, cur)
	}
	return runs
}

// A packet lost in the middle of a keyframe: that PES is dropped whole, everything after it still arrives. In the
// first keyframe the layout isn't known yet (nothing has switched PID), so the damage is only noted, and acted on
// once the layout shows; from the second on the filter knows and drops the rest of the PES itself.
func TestLostPacketDropsOnlyItsPES(t *testing.T) {
	ts := toPhoneLayout(muxPerPID(t, phoneLayoutAUs(2)))
	runs := idrRuns(ts)
	if len(runs) != 2 {
		t.Fatalf("expected 2 keyframes in the test stream, found %d", len(runs))
	}
	for gop, run := range runs {
		stats := &ingestStats{}
		video, audio := demuxAll(t, withoutPacket(ts, run[len(run)/4]), stats)
		keys := 0
		for _, v := range video {
			if v.key {
				keys++
			}
		}
		if len(video) != 5 || keys != 1 {
			t.Fatalf("keyframe %d damaged: got %d video AUs, %d keyframes; want the damaged one dropped, 5 AUs left", gop+1, len(video), keys)
		}
		if len(audio) != 8 {
			t.Fatalf("keyframe %d damaged: got %d audio AUs, want all 8", gop+1, len(audio))
		}
		if stats.cc.gaps.Load() != 1 || stats.cc.droppedPES.Load() != 1 {
			t.Fatalf("keyframe %d damaged: gaps %d, PES dropped %d", gop+1, stats.cc.gaps.Load(), stats.cc.droppedPES.Load())
		}
		if gop == 1 && stats.cc.droppedPkts.Load() == 0 {
			t.Fatal("once the layout is known the rest of the damaged PES should be left out")
		}
	}
}

// The last packet of a keyframe lost: the demuxer would otherwise finish the cut IDR at the next video PES start.
func TestLostTailDropsThePES(t *testing.T) {
	ts := toPhoneLayout(muxPerPID(t, phoneLayoutAUs(2)))
	for gop, run := range idrRuns(ts) {
		video, audio := demuxAll(t, withoutPacket(ts, run[len(run)-1]), nil)
		keys := 0
		for _, v := range video {
			if v.key {
				keys++
				if len(v.data) != len(bigIDR(100<<10)) {
					t.Fatalf("keyframe %d lost its tail and was passed on cut (%d bytes)", gop+1, len(v.data))
				}
			}
		}
		if len(video) != 5 || keys != 1 || len(audio) != 8 {
			t.Fatalf("keyframe %d lost its tail: got %d video (%d keys), %d audio; want 5 (1), 8", gop+1, len(video), keys, len(audio))
		}
	}
}

// An audio PES that lost its first packet is dropped; the keyframe before it is whole and kept.
func TestLostAudioStartDropsOnlyThatAudio(t *testing.T) {
	ts := toPhoneLayout(muxPerPID(t, phoneLayoutAUs(2)))
	ap := packetsOf(ts, outAudioPID)
	for _, n := range []int{2, 10} { // the second audio PES's start in each GOP
		video, audio := demuxAll(t, withoutPacket(ts, ap[n]), nil)
		keys := 0
		for _, v := range video {
			if v.key {
				keys++
			}
		}
		if len(video) != 6 || keys != 2 {
			t.Fatalf("audio packet %d lost: got %d video AUs (%d keyframes); want all 6 (2)", n, len(video), keys)
		}
		if len(audio) != 7 {
			t.Fatalf("audio packet %d lost: got %d audio AUs, want 7", n, len(audio))
		}
	}
}

// The filter hands a cut last packet on as it is, then the end of the input; a failing reader's error comes through.
func TestFilterPassesReaderEnds(t *testing.T) {
	ts := muxPerPID(t, phoneLayoutAUs(1))
	cut := ts[:len(ts)-100]
	got, err := io.ReadAll(newCCFilter(bytes.NewReader(cut), nil))
	if err != nil || !bytes.Equal(got, cut) {
		t.Fatalf("cut input: %d of %d bytes back, err %v", len(got), len(cut), err)
	}
	boom := errors.New("boom")
	_, err = io.ReadAll(newCCFilter(io.MultiReader(bytes.NewReader(ts[:188*3]), &failing{boom}), nil))
	if !errors.Is(err, boom) {
		t.Fatalf("reader error lost: %v", err)
	}
}

type failing struct{ err error }

func (f *failing) Read([]byte) (int, error) { return 0, f.err }
