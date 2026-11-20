// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
	"time"
)

// An ingest that stalls for a few seconds (a slow RTMP handshake, a congested uplink) must be absorbed by the output
// queue: 4 s at 6 Mbps is about 3 MB, under its 4 MB budget. The muxer hands the output one write per access unit, so
// the queue holds about 45 entries a second, not one per 188-byte packet.
func TestOutputQueueAbsorbsAFourSecondStall(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "out.ts")
	l := &evLog{}
	o := &ffmpegOut{
		url: "rtmps://ingest.example/app/k", events: l.add,
		// ffmpeg that doesn't read for a while, then takes everything.
		newCmd:     func(string) *exec.Cmd { return exec.Command("sh", "-c", fmt.Sprintf("sleep 1; exec cat > '%s'", path)) },
		minBackoff: time.Millisecond, maxBackoff: time.Millisecond, healthy: time.Hour,
		settled: time.Hour, stallKill: time.Minute,
	}
	o.start()
	mux := newTSOut(o)

	// 4 s of a 6 Mbps stream, written while ffmpeg isn't reading: 30 fps video (25 KB frames, a keyframe every 2 s)
	// and 48 kHz AAC (one 1920-tick frame per PES, as the phone sends).
	const secs = 4
	aus := 0
	var want int
	for f := int64(0); f < secs*30; f++ {
		d := pFrame(25_000)
		if f%60 == 0 {
			d = bigIDR(25_000)
		}
		if err := mux.write(&au{kind: kindVideo, data: d, key: f%60 == 0}, 90000+f*3000, 90000+f*3000); err != nil {
			t.Fatal(err)
		}
		aus++
		want++
		for a := f * 3000 / 1920; a < (f+1)*3000/1920; a++ {
			ad := adts(1)
			if err := mux.write(&au{kind: kindAudio, data: append(ad, make([]byte, 380)...), dur: 1920}, 90000+a*1920, 90000+a*1920); err != nil {
				t.Fatal(err)
			}
			aus++
		}
	}
	c := o.counters()
	if c.Overflows != 0 || c.DroppedBytes != 0 {
		t.Fatalf("a 4 s stall overflowed the queue: %+v (peak %d KB)", c, c.PeakQueued>>10)
	}
	if c.PeakQueued < 2<<20 {
		t.Fatalf("expected about 3 MB waiting while ffmpeg stalled, peak was %d KB", c.PeakQueued>>10)
	}
	if n := o.chunks.Load(); n != int64(aus) {
		t.Fatalf("queue entries: %d for %d access units (%.0f a second); want one per AU", n, aus, float64(n)/secs)
	}
	if err := o.Close(); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(path)
	var video int
	_ = demuxAUs(context.Background(), bytes.NewReader(got), func(a *au) {
		if a.kind == kindVideo {
			video++
		}
	})
	if video != want {
		t.Fatalf("ffmpeg got %d of %d video frames after the stall", video, want)
	}
	if l.count("falling behind") != 0 {
		t.Fatalf("unexpected events: %q", l.all())
	}
}

// Overflow still works the old way, and is now counted.
func TestOutputOverflowIsCounted(t *testing.T) {
	l := &evLog{}
	o := testOutput("rtmps://ingest.example/app/k", l, func(int) string { return "exec sleep 30" })
	defer o.Close()
	feed := frameFeeder(o)
	for i := 0; i < 4000; i++ { // ~6 MB
		feed()
	}
	c := o.counters()
	if c.Overflows == 0 || c.DroppedBytes == 0 || c.PeakQueued > maxQueueBytes {
		t.Fatalf("counters after an overflow: %+v", c)
	}
}
