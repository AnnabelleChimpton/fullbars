// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"context"
	"testing"
	"time"
)

// MPEG-TS timestamps are 33 bits and wrap about every 26.5 hours of continuous stream. The phone's clock can wrap
// in the middle of a stream; before the keeper unwrapped them, every AU after the wrap looked like it went
// backwards and the picture froze until the phone reconnected.

func TestLiveTimestampWrapKeepsOutputMoving(t *testing.T) {
	s := newPhoneSim(t)
	s.clock0 = tsWrap - 2*90000 // the phone's clock wraps 2 s in
	s.nextV, s.nextA = s.clock0, s.clock0
	s.run(5 * time.Second)
	if s.k.st != stateLive {
		t.Fatalf("expected live through the wrap, got %v (%q)", s.k.st, s.evs)
	}
	c := s.k.counters.snapshot()
	if c.VideoBackwards != 0 || c.AudioBackwards != 0 || c.AudioOverlap != 0 || c.Resyncs != 0 {
		t.Fatalf("drops across the wrap: %+v", c)
	}
	for i := 1; i < len(s.r.v); i++ {
		if s.r.v[i]-s.r.v[i-1] != 3000 {
			t.Fatalf("video DTS step %d at output %d", s.r.v[i]-s.r.v[i-1], i)
		}
	}
	if n := len(s.r.v); n < 145 {
		t.Fatalf("only %d frames in 5 s", n)
	}
	checkContinuous(t, "audio", s.r.a, s.r.aEnd, 90)
}

// The phone's timestamps jump back within a session (an encoder restart, a clock reset): nothing fits the output
// any more, so after resyncAfter the keeper splices the session in again at its next keyframe.
func TestBackwardJumpResyncsAtNextKeyframe(t *testing.T) {
	s := newPhoneSim(t)
	s.run(3 * time.Second)
	s.gop = 1 << 30
	s.clock0 -= 10 * 90000 // the phone's clock now reads 10 s earlier
	s.nextV, s.nextA = s.phoneClock(), s.phoneClock()
	s.run(600 * time.Millisecond)
	if !s.has("live timestamps stopped fitting the output") || s.k.liveSynced {
		t.Fatalf("expected a resync after %v of nothing fitting: %q", resyncAfter, s.evs)
	}
	if !s.status.NeedKeyframe {
		t.Fatal("the app should be asked for a keyframe to resync on")
	}
	s.forceKF = true
	s.run(100 * time.Millisecond)
	if s.k.st != stateLive || !s.k.liveSynced || !s.has("LIVE resynced on session 1") {
		t.Fatalf("expected live again on the keyframe (st=%v synced=%v): %q", s.k.st, s.k.liveSynced, s.evs)
	}
	for i := 1; i < len(s.r.v); i++ {
		if s.r.v[i]-s.r.v[i-1] != 3000 {
			t.Fatalf("video DTS step %d at output %d", s.r.v[i]-s.r.v[i-1], i)
		}
	}
	if s.k.counters.resyncs.Load() != 1 {
		t.Fatalf("resyncs = %d", s.k.counters.resyncs.Load())
	}
}

// The keeper's own output passes 2^33 after 26.5 h too. The muxer writes the low 33 bits (as MPEG-TS requires) and
// what comes back unwraps into a continuous timeline.
func TestOutputAboveWrapReadsBackContinuous(t *testing.T) {
	var buf bytes.Buffer
	out := newTSOut(&buf)
	start := tsWrap - 10*3000
	for i := int64(0); i < 20; i++ {
		d := pFrame(500)
		if i%10 == 0 {
			d = idr(0x64, 0xee)
		}
		if err := out.write(&au{kind: kindVideo, data: d, key: i%10 == 0}, start+i*3000+3000, start+i*3000); err != nil {
			t.Fatal(err)
		}
	}
	k := &keeper{unwrapRef: tsUnset}
	var got []int64
	err := demuxAUs(context.Background(), bytes.NewReader(buf.Bytes()), func(a *au) {
		if a.pts >= tsWrap || a.dts >= tsWrap {
			t.Fatalf("timestamps must be written as 33 bits: pts %d dts %d", a.pts, a.dts)
		}
		pts, dts := k.unwrap(a)
		if pts-dts != 3000 {
			t.Fatalf("pts-dts %d after unwrapping, want 3000", pts-dts)
		}
		got = append(got, dts)
	})
	if err != nil {
		t.Fatal(err)
	}
	if len(got) != 20 {
		t.Fatalf("read back %d frames", len(got))
	}
	for i, d := range got {
		if d != start+int64(i)*3000 {
			t.Fatalf("frame %d: dts %d, want %d", i, d, start+int64(i)*3000)
		}
	}
}
