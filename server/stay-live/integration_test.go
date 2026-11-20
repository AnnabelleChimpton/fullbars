// SPDX-License-Identifier: Apache-2.0

//go:build integration

package main

// End to end with the real ffmpeg: H.264/AAC test video through the keeper and the ffmpeg output stage, with
// scripted dropouts, a reconnect and a Weak signal episode. The output must decode without a single error and keep
// its timestamps moving forward. Needs ffmpeg and ffprobe on the PATH (skipped otherwise):
//
//	go test -tags integration -run Integration ./...

import (
	"bytes"
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"testing"
	"time"
)

func ffmpegTool(t *testing.T, args ...string) []byte {
	t.Helper()
	out, err := exec.Command("ffmpeg", append([]string{"-nostdin", "-v", "error", "-y"}, args...)...).CombinedOutput()
	if err != nil {
		t.Fatalf("ffmpeg %s: %v\n%s", strings.Join(args, " "), err, out)
	}
	return out
}

// encodeTS makes an MPEG-TS clip like the phone's: H.264 without B-frames, a keyframe every 2 s, 48 kHz stereo AAC.
func encodeTS(t *testing.T, path, video, audio string, secs int) {
	t.Helper()
	ffmpegTool(t, "-f", "lavfi", "-i", video, "-f", "lavfi", "-i", audio, "-t", strconv.Itoa(secs),
		"-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-profile:v", "high", "-pix_fmt", "yuv420p",
		"-g", "60", "-bf", "0", "-b:v", "1M", "-c:a", "aac", "-ac", "2", "-ar", "48000", "-b:a", "128k", "-f", "mpegts", path)
}

func TestIntegrationOutputDecodesCleanly(t *testing.T) {
	for _, tool := range []string{"ffmpeg", "ffprobe"} {
		if _, err := exec.LookPath(tool); err != nil {
			t.Skipf("%s not found", tool)
		}
	}
	dir := t.TempDir()
	livePath, slatePath, weakPath, outPath := filepath.Join(dir, "live.ts"), filepath.Join(dir, "slate.ts"), filepath.Join(dir, "weak.ts"), filepath.Join(dir, "out.ts")
	encodeTS(t, livePath, "testsrc2=size=640x360:rate=30", "sine=frequency=440:sample_rate=48000", 16)
	encodeTS(t, slatePath, "color=c=0x0d0d0d:size=640x360:rate=30", "anullsrc=r=48000:cl=stereo", 2)
	encodeTS(t, weakPath, "color=c=0x402000:size=640x360:rate=30", "anullsrc=r=48000:cl=stereo", 2)

	live, err := loadSlate(livePath)
	if err != nil {
		t.Fatal(err)
	}
	slate, err := loadSlate(slatePath)
	if err != nil {
		t.Fatal(err)
	}
	weak, err := loadSlate(weakPath)
	if err != nil {
		t.Fatal(err)
	}

	l := &evLog{}
	o := newFFmpegOutWith("file", l.add, func(string) *exec.Cmd {
		return exec.Command("ffmpeg", "-nostdin", "-v", "error", "-f", "mpegts", "-i", "pipe:0", "-map", "0", "-c", "copy", "-f", "mpegts", "-y", outPath)
	})
	o.start()
	var evs []string
	k, err := newKeeper(newTSOut(o), slate, 3000, 1500*time.Millisecond, 0, func(e string) { evs = append(evs, e) })
	if err != nil {
		t.Fatal(err)
	}
	k.installWeak(weak)
	k.st = stateWaiting

	// The phone: its clock runs with the wall clock; it sends the clip's AUs as their time comes.
	base := live[0].dts
	for _, a := range live {
		base = min(base, a.dts)
	}
	wall0 := time.Unix(50_000, 0)
	now := wall0
	next, session, sending := 0, 1, true
	step := func(d time.Duration) {
		for end := now.Add(d); now.Before(end); {
			now = now.Add(5 * time.Millisecond)
			clock := base + int64(now.Sub(wall0))*9/100000
			for next < len(live) && live[next].dts <= clock {
				if sending {
					c := *live[next]
					if err := k.onLive(liveEvent{session: session, a: &c}, now); err != nil {
						t.Fatal(err)
					}
				}
				next++
			}
			if _, err := k.tick(now); err != nil {
				t.Fatal(err)
			}
		}
	}
	step(3 * time.Second) // live
	sending = false
	step(2 * time.Second) // dropout: slate
	sending = true
	step(3 * time.Second) // back at the next keyframe
	sending = false
	step(1 * time.Second) // the phone loses its connection...
	session, sending = 2, true
	_ = k.onLive(liveEvent{session: 2, connected: true}, now)
	step(3 * time.Second) // ...and reconnects: a new session, live at its first keyframe
	k.onQuality(qualityEvent{session: 2, weak: true, lost: 30}, now)
	step(2 * time.Second) // Weak signal slate
	k.onQuality(qualityEvent{session: 2, weak: false}, now)
	step(1500 * time.Millisecond) // live again
	if err := o.Close(); err != nil {
		t.Fatal(err)
	}
	joined := strings.Join(evs, "\n")
	for _, want := range []string{"SLATE: feed stalled", "LIVE (session 2)", "WEAK SIGNAL slate", "signal clean again"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("scenario didn't happen: missing %q in\n%s", want, joined)
		}
	}
	if strings.Contains(l.all(), "ERROR") {
		t.Fatalf("output errors: %s", l.all())
	}

	// Decodes with no error at all.
	cmd := exec.Command("ffmpeg", "-nostdin", "-xerror", "-v", "error", "-i", outPath, "-f", "null", "-")
	var stderr bytes.Buffer
	cmd.Stderr = &stderr
	if err := cmd.Run(); err != nil || stderr.Len() > 0 {
		t.Fatalf("decoding the output: %v\n%s", err, stderr.String())
	}

	// Timestamps move forward on every stream; the picture size never changes.
	probe, err := exec.Command("ffprobe", "-v", "error", "-show_entries", "packet=stream_index,dts", "-of", "csv=p=0", outPath).Output()
	if err != nil {
		t.Fatal(err)
	}
	last := map[string]int64{}
	counts := map[string]int{}
	for _, line := range strings.Split(strings.TrimSpace(string(probe)), "\n") {
		f := strings.Split(line, ",")
		if len(f) < 2 {
			continue
		}
		dts, err := strconv.ParseInt(f[1], 10, 64)
		if err != nil {
			t.Fatalf("packet without a DTS: %q", line)
		}
		if prev, ok := last[f[0]]; ok && dts <= prev {
			t.Fatalf("stream %s: DTS %d after %d", f[0], dts, prev)
		}
		last[f[0]] = dts
		counts[f[0]]++
	}
	if counts["0"] < 400 || counts["1"] < 400 {
		t.Fatalf("too little output: %v", counts)
	}
	sizes, err := exec.Command("ffprobe", "-v", "error", "-select_streams", "v", "-show_entries", "frame=width,height", "-of", "csv=p=0", outPath).Output()
	if err != nil {
		t.Fatal(err)
	}
	seen := map[string]bool{}
	for _, s := range strings.Fields(string(sizes)) {
		seen[strings.TrimRight(s, ",")] = true // some frames carry an empty extra field
	}
	if len(seen) != 1 {
		t.Fatalf("picture size changed: %v", seen)
	}

	// The keeper's own demux of its output agrees: whole frames only.
	var nv int
	f, _ := os.ReadFile(outPath)
	if err := demuxAUs(context.Background(), bytes.NewReader(f), func(a *au) {
		if a.kind == kindVideo {
			nv++
		}
	}); err != nil {
		t.Fatalf("demuxing the output: %v", err)
	}
	t.Logf("%d video frames, %v packets per stream; keeper: %s", nv, counts, k.counters.snapshot())
}
