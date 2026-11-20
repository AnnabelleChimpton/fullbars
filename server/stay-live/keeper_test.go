// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"context"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

type rec struct {
	v, a []int64
	aEnd []int64
}

func (r *rec) write(a *au, pts, dts int64) error {
	if a.kind == kindVideo {
		r.v = append(r.v, dts)
	} else {
		r.a = append(r.a, pts)
		r.aEnd = append(r.aEnd, pts+a.dur)
	}
	return nil
}

// adts builds a PES payload of n fake 48 kHz ADTS frames (header only, 7 bytes each).
func adts(n int) []byte {
	var b []byte
	for i := 0; i < n; i++ {
		b = append(b, 0xFF, 0xF1, 0x4C, 0x80, 0x00, 0xE0, 0x00) // sfi=3 (48k), frame_length=7
	}
	return b
}

// slate: 2 s of video at 30 fps, audio PES of 8 frames (~170 ms) that stop ~120 ms short of the video, like ffmpeg output.
func testSlate() []*au {
	var s []*au
	for i := int64(0); i < 60; i++ {
		s = append(s, &au{kind: kindVideo, pts: 1000 + i*3000, dts: 1000 + i*3000, key: i == 0})
	}
	for p := int64(0); p < 11; p++ {
		d := adts(8)
		s = append(s, &au{kind: kindAudio, pts: 1000 + p*15360, dts: 1000 + p*15360, data: d, dur: adtsDuration(d)})
	}
	return s
}

func checkContinuous(t *testing.T, name string, starts, ends []int64, tol int64) {
	t.Helper()
	for i := 1; i < len(starts); i++ {
		if gap := starts[i] - ends[i-1]; gap > tol || gap < -tol {
			t.Fatalf("%s: discontinuity of %.3fs at output %d", name, float64(gap)/90000, i)
		}
	}
}

func TestAdtsDuration(t *testing.T) {
	if got := adtsDuration(adts(8)); got != 8*1024*90000/48000 {
		t.Fatalf("adtsDuration = %d", got)
	}
}

// A phone slate upload is only installed and saved when SRT lost nothing and it carries audio (the phone always
// sends silent AAC, so no audio means the upload was cut short).
func TestCheckSlateUpload(t *testing.T) {
	if err := checkSlateUpload(0, testSlate()); err != nil {
		t.Fatalf("clean slate rejected: %v", err)
	}
	if err := checkSlateUpload(3, testSlate()); err == nil || !strings.Contains(err.Error(), "3 packets lost") {
		t.Fatalf("lossy upload accepted: %v", err)
	}
	var videoOnly []*au
	for _, a := range testSlate() {
		if a.kind == kindVideo {
			videoOnly = append(videoOnly, a)
		}
	}
	if err := checkSlateUpload(0, videoOnly); err == nil || !strings.Contains(err.Error(), "no audio") {
		t.Fatalf("video-only upload accepted: %v", err)
	}
}

func TestSlateLoopsAndSplicesWithoutHoles(t *testing.T) {
	r := &rec{}
	k, err := newKeeper(r, testSlate(), 3000, 1500*time.Millisecond, 0, func(string) {})
	if err != nil {
		t.Fatal(err)
	}
	t0 := time.Unix(0, 0)
	k.enterSlate(t0, "test")
	_ = k.run // run() is the wall-clock loop; this test drives the same steps on a fake clock
	// 7 s of slate = 3.5 loops
	for ms := 0; ms <= 7000; ms += 5 {
		if err := k.pumpSlate(t0.Add(time.Duration(ms) * time.Millisecond)); err != nil {
			t.Fatal(err)
		}
	}
	// live session: IDR then frames, with its own unrelated timestamps; audio per frame-ish
	now := t0.Add(7 * time.Second)
	for i := int64(0); i < 30; i++ {
		_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: 5_000_000 + i*3000, dts: 5_000_000 + i*3000, key: i == 0}}, now)
		d := adts(1)
		_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindAudio, pts: 5_000_000 + i*1920, data: d, dur: adtsDuration(d)}}, now)
	}
	// back to slate
	k.enterSlate(now.Add(2*time.Second), "stall")
	for ms := 0; ms <= 3000; ms += 5 {
		_ = k.pumpSlate(now.Add(2*time.Second + time.Duration(ms)*time.Millisecond))
	}

	vEnds := make([]int64, len(r.v))
	for i, v := range r.v {
		vEnds[i] = v + 3000
	}
	checkContinuous(t, "video", r.v, vEnds, 1)
	// audio: tolerate one frame of overlap/gap at live<->slate splices, never a hole bigger than that
	checkContinuous(t, "audio", r.a, r.aEnd, 1920)
	if len(r.v) < 250 || len(r.a) < 40 {
		t.Fatalf("too little output: %d video, %d audio", len(r.v), len(r.a))
	}
}

// idr builds an Annex-B IDR access unit with the given SPS/PPS bytes (4-byte start codes, like MediaCodec output).
func idr(sps, pps byte) []byte {
	return []byte{0, 0, 0, 1, 0x67, sps, 0x1f, 0, 0, 0, 1, 0x68, pps, 0, 0, 0, 1, 0x65, 0x88, 0x84}
}

func TestParamSets(t *testing.T) {
	got := paramSets(idr(0x64, 0xee))
	want := []byte{0, 0, 1, 0x67, 0x64, 0x1f, 0, 0, 1, 0x68, 0xee}
	if string(got) != string(want) {
		t.Fatalf("paramSets = % x, want % x", got, want)
	}
	twice := append(idr(0x64, 0xee), idr(0x64, 0xee)...)
	if string(paramSets(twice)) != string(want) {
		t.Fatalf("repeated parameter sets must compare equal: % x", paramSets(twice))
	}
	if len(paramSets([]byte{0, 0, 1, 0x41, 1, 2})) != 0 {
		t.Fatal("a P slice has no parameter sets")
	}
}

func slateWith(sps byte) []*au {
	s := testSlate()
	s[0].data = idr(sps, 0xee)
	return s
}

// The phone's slate replaces the default one: immediately while live, but never in the middle of a slate (that
// would cut a GOP), and the live video's SPS/PPS are checked against whichever slate is installed.
func TestPhoneSlateReplacesDefaultAndIsChecked(t *testing.T) {
	r := &rec{}
	var evs []string
	k, err := newKeeper(r, slateWith(0x42), 3000, 1500*time.Millisecond, 0, func(e string) { evs = append(evs, e) })
	if err != nil {
		t.Fatal(err)
	}
	t0 := time.Now()
	k.enterSlate(t0, "startup")
	k.replaceSlate(slateUpdate{aus: slateWith(0x64)}) // on slate: deferred
	if k.pendingSlate == nil || k.slateParams[4] != 0x42 {
		t.Fatal("slate must not change while it is on air")
	}
	live := func(i int64, key bool) {
		a := &au{kind: kindVideo, pts: i * 3000, dts: i * 3000, key: key}
		if key {
			a.data = idr(0x64, 0xee)
		}
		if err := k.onLive(liveEvent{a: a, session: 1}, t0.Add(time.Duration(i)*33*time.Millisecond)); err != nil {
			t.Fatal(err)
		}
	}
	live(0, true)
	has := func(sub string) bool {
		for _, e := range evs {
			if strings.Contains(e, sub) {
				return true
			}
		}
		return false
	}
	if !has("WARNING: slate's SPS/PPS differ") {
		t.Fatalf("expected a mismatch warning against the default slate, got %q", evs)
	}
	k.enterSlate(t0.Add(2*time.Second), "dropout") // the pending phone slate goes in now
	if k.pendingSlate != nil || k.slateParams[4] != 0x64 {
		t.Fatal("pending slate not installed at the next dropout")
	}
	k.session = 1
	k.liveSynced = false
	k.st = stateLive
	evs = nil
	live(100, true)
	if !has("slate matches the live video format") {
		t.Fatalf("expected a match, got %q", evs)
	}
	k.replaceSlate(slateUpdate{aus: slateWith(0x4d)}) // live: immediate
	if k.slateParams[4] != 0x4d {
		t.Fatal("slate should be replaced at once while live")
	}
	k.replaceSlate(slateUpdate{aus: []*au{{kind: kindVideo, key: false}}}) // invalid: ignored
	if k.slateParams[4] != 0x4d {
		t.Fatal("an invalid slate must be ignored")
	}
}

func TestStreamIDsBeforeAndAfterPairing(t *testing.T) {
	for id, want := range map[string]string{"slate": "slate", "slate-weak": "slate-weak", "/slate": "slate", "/slate?latency=2000": "slate", "live": "live", "/live": "live", "": "live"} {
		if kind, ok := classifyStream(id, ""); !ok || kind != want {
			t.Errorf("unpaired %q = %q %v, want %q", id, kind, ok, want)
		}
	}
	tok := "test-token"
	pid := publishID(tok)
	for id, want := range map[string]string{"/live/" + pid + "?latency=2000": "live", "slate/" + pid: "slate", "slate-weak/" + pid: "slate-weak"} {
		if kind, ok := classifyStream(id, tok); !ok || kind != want {
			t.Errorf("paired %q = %q %v, want %q", id, kind, ok, want)
		}
	}
	for _, id := range []string{"live", "slate", "slate-weak", "live/0000000000000000", "/live/" + pid + "x", "other/" + pid} {
		if _, ok := classifyStream(id, tok); ok {
			t.Errorf("paired server accepted %q", id)
		}
	}
}

// A live muxer may send its first PAT/PMT after the first frames; the slate's only IDR must still be found.
func TestTablesFirstRecoversLeadingIDR(t *testing.T) {
	var buf bytes.Buffer
	out := newTSOut(&buf)
	for i := int64(0); i < 3; i++ {
		d := []byte{0, 0, 0, 1, 0x41, 1, 2, 3}
		if i == 0 {
			d = idr(0x64, 0xee)
		}
		if err := out.write(&au{kind: kindVideo, data: d, key: i == 0}, 9000+i*3000, 9000+i*3000); err != nil {
			t.Fatal(err)
		}
	}
	all := buf.Bytes()
	// Find the tables (PID 0 and the PMT) and move them after the first video packet, like a live muxer.
	var tables, media [][]byte
	for i := 0; i+188 <= len(all); i += 188 {
		p := all[i : i+188]
		if id := uint16(p[1]&0x1f)<<8 | uint16(p[2]); id == outVideoPID || id == outAudioPID {
			media = append(media, p)
		} else {
			tables = append(tables, p)
		}
	}
	if len(tables) < 2 || len(media) < 3 {
		t.Fatalf("unexpected mux layout: %d tables, %d media", len(tables), len(media))
	}
	var late []byte
	late = append(late, media[0]...)
	for _, p := range tables {
		late = append(late, p...)
	}
	for _, p := range media[1:] {
		late = append(late, p...)
	}
	count := func(ts []byte) (n int, firstKey bool) {
		_ = demuxAUs(context.Background(), bytes.NewReader(ts), func(a *au) {
			if n == 0 {
				firstKey = a.key
			}
			n++
		})
		return
	}
	if n, key := count(tablesFirst(late)); !key || n != 3 {
		t.Fatalf("after tablesFirst: %d AUs, first key=%v; want 3, true", n, key)
	}
}

func TestSRTPassphraseFromPairing(t *testing.T) {
	a, b := srtPassphrase("token-a"), srtPassphrase("token-b")
	if a == b || len(a) < 10 || len(a) > 79 {
		t.Fatalf("passphrase: %q %q", a, b)
	}
	if a == publishID("token-a") {
		t.Fatal("passphrase must differ from the publish id, which travels in the clear")
	}
}

// While packets keep getting lost on the way, the Weak signal slate goes on air and live data is ignored; once
// the feed is clean again, live comes back at the next keyframe. The output timeline never breaks.
func TestWeakSignalSlateWhileTheFeedIsDamaged(t *testing.T) {
	r := &rec{}
	var evs []string
	k, err := newKeeper(r, testSlate(), 3000, 1500*time.Millisecond, 0, func(e string) { evs = append(evs, e) })
	if err != nil {
		t.Fatal(err)
	}
	k.installWeak(testSlate())
	k.st = stateWaiting
	t0 := time.Unix(1000, 0)
	frame := int64(0)
	live := func(at time.Time, key bool) {
		_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: 7_000_000 + frame*3000, dts: 7_000_000 + frame*3000, key: key}}, at)
		frame++
	}
	for i := 0; i < 30; i++ {
		live(t0.Add(time.Duration(i)*33*time.Millisecond), i == 0)
	}
	if k.st != stateLive {
		t.Fatal("should be live")
	}
	now := t0.Add(time.Second)
	k.onQuality(qualityEvent{session: 2, weak: true}, now) // an old or other session: ignored
	if k.st != stateLive {
		t.Fatal("a quality event for another session must be ignored")
	}
	k.onQuality(qualityEvent{session: 1, weak: true, lost: 40}, now)
	if k.st != stateSlate || !k.onWeak || k.curV != &k.vWeak {
		t.Fatalf("expected the Weak signal slate on air (st=%v weak=%v)", k.st, k.onWeak)
	}
	// Damaged live data keeps arriving, keyframes included: still on the Weak signal slate.
	for ms := 0; ms <= 6000; ms += 5 {
		at := now.Add(time.Duration(ms) * time.Millisecond)
		if ms%33 == 0 {
			live(at, frame%60 == 0)
		}
		_ = k.pumpSlate(at)
	}
	if k.st != stateSlate {
		t.Fatal("live data must not end the Weak signal slate while the feed is weak")
	}
	now = now.Add(6 * time.Second)
	k.onQuality(qualityEvent{session: 1, weak: false}, now)
	live(now, false) // not a keyframe: still slate
	if k.st != stateSlate {
		t.Fatal("must wait for a live keyframe")
	}
	live(now.Add(33*time.Millisecond), true)
	if k.st != stateLive {
		t.Fatal("back to live at the first keyframe after recovering")
	}
	for i := 1; i < len(r.v); i++ {
		if r.v[i]-r.v[i-1] != 3000 {
			t.Fatalf("video timeline broke at %d: %d -> %d", i, r.v[i-1], r.v[i])
		}
	}
	joined := strings.Join(evs, "\n")
	for _, want := range []string{"WEAK SIGNAL slate: weak signal (40 packets lost", "signal clean again", "LIVE (session 1) after"} {
		if !strings.Contains(joined, want) {
			t.Errorf("missing event %q in %q", want, joined)
		}
	}
}

// Without a Weak signal slate from the phone (an older app), a damaged feed stays live and the server says why once.
func TestWeakSignalWithoutAWeakSlateStaysLive(t *testing.T) {
	var evs []string
	k, _ := newKeeper(&rec{}, testSlate(), 3000, 1500*time.Millisecond, 0, func(e string) { evs = append(evs, e) })
	k.st = stateWaiting
	now := time.Unix(1000, 0)
	_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: 1000, dts: 1000, key: true}}, now)
	k.onQuality(qualityEvent{session: 1, weak: true, lost: 9}, now)
	k.onQuality(qualityEvent{session: 1, weak: false}, now)
	k.onQuality(qualityEvent{session: 1, weak: true, lost: 9}, now)
	if k.st != stateLive {
		t.Fatal("no Weak signal slate: stay live")
	}
	n := 0
	for _, e := range evs {
		if strings.Contains(e, "hasn't sent a Weak signal slate") {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("expected one explanation, got %d in %q", n, evs)
	}
}

// A new Weak signal slate never replaces the one on air mid-loop; the BRB one can be replaced meanwhile.
func TestWeakSlateReplacementWaitsWhileOnAir(t *testing.T) {
	k, _ := newKeeper(&rec{}, slateWith(0x42), 3000, 1500*time.Millisecond, 0, func(string) {})
	k.installWeak(testSlate())
	k.enterSlateKind(time.Unix(0, 0), "test", true)
	k.replaceSlate(slateUpdate{weak: true, aus: testSlate()})
	if k.pendingWeak == nil {
		t.Fatal("the Weak signal slate on air must not change mid-loop")
	}
	k.replaceSlate(slateUpdate{aus: slateWith(0x64)})
	if k.pendingSlate != nil || k.slateParams[4] != 0x64 {
		t.Fatal("the BRB slate isn't on air: replace it at once")
	}
	k.enterSlateKind(time.Unix(5, 0), "again", true)
	if k.pendingWeak != nil {
		t.Fatal("pending Weak signal slate should go in the next time it goes on air")
	}
}

// The IRL tunnel: session 1 goes weak, then dies; the phone reconnects as session 2 before session 1's late
// "recovered" (or its end) arrives. Session 2's first keyframe must bring live back, and session 1's late events
// must change nothing.
func TestWeakSlateClearsOnReconnect(t *testing.T) {
	var evs []string
	k, err := newKeeper(&rec{}, testSlate(), 3000, 1500*time.Millisecond, 10*time.Minute, func(e string) { evs = append(evs, e) })
	if err != nil {
		t.Fatal(err)
	}
	k.installWeak(testSlate())
	k.st = stateWaiting
	t0 := time.Unix(2000, 0)
	_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: 1000, dts: 1000, key: true}}, t0)
	k.onQuality(qualityEvent{session: 1, weak: true, lost: 30}, t0.Add(time.Second))
	if k.st != stateSlate || !k.onWeak {
		t.Fatal("expected the Weak signal slate on air")
	}
	// Session 2 connects; its first data isn't a keyframe yet.
	at := t0.Add(20 * time.Second)
	_ = k.onLive(liveEvent{session: 2, a: &au{kind: kindVideo, pts: 9_000_000, dts: 9_000_000}}, at)
	if k.weakHeld {
		t.Fatal("a new session must not inherit the old one's weak hold")
	}
	if !k.slateSince.Equal(at) {
		t.Fatal("the hold timer should restart when the weak hold ends")
	}
	_ = k.onLive(liveEvent{session: 2, a: &au{kind: kindVideo, pts: 9_003_000, dts: 9_003_000, key: true}}, at.Add(33*time.Millisecond))
	if k.st != stateLive || k.session != 2 {
		t.Fatalf("session 2's keyframe should bring live back (st=%v session=%d)", k.st, k.session)
	}
	// Session 1's late events are no-ops.
	k.onQuality(qualityEvent{session: 1, weak: false}, at.Add(10*time.Second))
	k.onQuality(qualityEvent{session: 1, weak: true, lost: 50}, at.Add(10*time.Second))
	_ = k.onLive(liveEvent{session: 1, ended: true}, at.Add(11*time.Second))
	if k.st != stateLive || k.weakHeld || k.finished {
		t.Fatalf("late session 1 events changed the keeper (st=%v weakHeld=%v finished=%v)", k.st, k.weakHeld, k.finished)
	}
	if !strings.Contains(strings.Join(evs, "\n"), "new connection (session 2): no longer holding the Weak signal slate") {
		t.Errorf("missing the reconnect event in %q", evs)
	}
}

// The weak connection dies and nothing reconnects: the BRB slate goes on air and the hold timer runs, so the
// Twitch stream still ends after -hold instead of showing Weak signal forever. This is exactly what production
// sends: watchLoss stops without a "recovered" (one would arrive before the ended event and hide it), so the ended
// event alone has to end the weak hold.
func TestWeakSessionLostFallsBackToBRB(t *testing.T) {
	var evs []string
	k, _ := newKeeper(&rec{}, testSlate(), 3000, 1500*time.Millisecond, 10*time.Minute, func(e string) { evs = append(evs, e) })
	k.installWeak(testSlate())
	k.st = stateWaiting
	t0 := time.Unix(3000, 0)
	_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: 1000, dts: 1000, key: true}}, t0)
	k.onQuality(qualityEvent{session: 1, weak: true, lost: 30}, t0.Add(time.Second))
	end := t0.Add(31 * time.Second)
	_ = k.onLive(liveEvent{session: 1, ended: true}, end)
	if k.weakHeld || k.onWeak || k.st != stateSlate || !k.slateSince.Equal(end) || k.finished {
		t.Fatalf("expected the BRB slate with a fresh hold timer (weakHeld=%v onWeak=%v st=%v)", k.weakHeld, k.onWeak, k.st)
	}
	log := strings.Join(evs, "\n")
	if !strings.Contains(log, "session 1 lost while the signal was weak") {
		t.Errorf("missing the lost-while-weak event in %q", evs)
	}
	if strings.Contains(log, "signal clean again") {
		t.Errorf("the dead session must not be reported as clean again: %q", evs)
	}
	// A late quality report for the dead session changes nothing.
	k.onQuality(qualityEvent{session: 1, weak: false}, end.Add(time.Second))
	if k.onWeak || k.st != stateSlate || !k.slateSince.Equal(end) {
		t.Fatal("a late recovered for the dead session changed the keeper")
	}
}

// --- the output (sink.go): ffmpeg is stood in for by shell commands ---------------------------------------------

type evLog struct {
	mu  sync.Mutex
	evs []string
}

func (l *evLog) add(e string) { l.mu.Lock(); l.evs = append(l.evs, e); l.mu.Unlock() }

func (l *evLog) all() string { l.mu.Lock(); defer l.mu.Unlock(); return strings.Join(l.evs, "\n") }

func (l *evLog) count(sub string) int { return strings.Count(l.all(), sub) }

// testOutput is an ffmpegOut whose n-th "ffmpeg" run is the shell command cmd(n), with millisecond timings.
func testOutput(u string, l *evLog, cmd func(n int) string) *ffmpegOut {
	var n atomic.Int32
	o := &ffmpegOut{
		url: u, events: l.add,
		newCmd:     func(string) *exec.Cmd { return exec.Command("sh", "-c", cmd(int(n.Add(1)))) },
		minBackoff: 5 * time.Millisecond, maxBackoff: 20 * time.Millisecond, healthy: time.Hour,
		settled: 50 * time.Millisecond, stallKill: 300 * time.Millisecond,
	}
	o.start()
	return o
}

// frameFeeder writes video frames through the keeper's muxer: a keyframe every 10, ~1.5 KB each.
func frameFeeder(w *ffmpegOut) func() {
	mux := newTSOut(w)
	i := int64(0)
	return func() {
		key := i%10 == 0
		d := append([]byte{0, 0, 0, 1, 0x41}, make([]byte, 1500)...)
		if key {
			d = append(idr(0x64, 0xee), make([]byte, 1500)...)
		}
		_ = mux.write(&au{kind: kindVideo, data: d, key: key}, 9000+i*3000, 9000+i*3000)
		i++
	}
}

func waitFor(t *testing.T, what string, d time.Duration, cond func() bool, tick func()) {
	t.Helper()
	deadline := time.Now().Add(d)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		if tick != nil {
			tick()
		}
		time.Sleep(time.Millisecond)
	}
}

// ffmpeg fails mid-stream: the keeper never sees an error, the reason is reported (key redacted), and the next
// ffmpeg starts on a keyframe with PAT/PMT in front, so Twitch gets a decodable picture from its first byte.
func TestOutputRestartsOnAKeyframe(t *testing.T) {
	dir := t.TempDir()
	l := &evLog{}
	o := testOutput("rtmps://ingest.example/app/live_123_SECRET", l, func(n int) string {
		if n == 1 {
			return `head -c 5000 >/dev/null; echo "Error writing trailer of rtmps://ingest.example/app/live_123_SECRET: Broken pipe" >&2; exit 1`
		}
		return fmt.Sprintf("exec cat > '%s/out%d.ts'", dir, n)
	})
	feed := frameFeeder(o)
	out2 := filepath.Join(dir, "out2.ts")
	waitFor(t, "the second ffmpeg to get data", 5*time.Second, func() bool {
		fi, err := os.Stat(out2)
		return err == nil && fi.Size() > 20000
	}, feed)
	waitFor(t, "the reconnected event", 2*time.Second, func() bool { return l.count("output: reconnected") > 0 }, feed)
	if err := o.Close(); err != nil {
		t.Fatal(err)
	}
	got, _ := os.ReadFile(out2)
	if len(got) < 188 || int(got[1]&0x1f)<<8|int(got[2]) != 0 {
		t.Fatal("the restarted output must start with the PAT")
	}
	var aus []*au
	_ = demuxAUs(context.Background(), bytes.NewReader(got), func(a *au) { aus = append(aus, a) })
	if len(aus) < 10 || !aus[0].key {
		t.Fatalf("restarted output: %d AUs, first key=%v", len(aus), len(aus) > 0 && aus[0].key)
	}
	evs := l.all()
	if !strings.Contains(evs, "OUTPUT ERROR: Error writing trailer of rtmps://ingest.example/app/<key redacted> Broken pipe; reconnecting in 5ms") {
		t.Fatalf("missing the output error in %q", evs)
	}
	if strings.Contains(evs, "SECRET") {
		t.Fatal("an event leaked the stream key")
	}
}

// A stale key: ffmpeg fails at once, every time. After five quick failures the server says what's wrong once and
// keeps retrying quietly; the keeper (and the slate) keep running, and Close doesn't wait out a backoff.
func TestOutputRefusedAfterQuickFailures(t *testing.T) {
	l := &evLog{}
	o := testOutput("rtmps://live.twitch.tv/app/live_1_SECRET", l, func(int) string { return `echo "Input/output error" >&2; exit 1` })
	feed := frameFeeder(o)
	waitFor(t, "the refused explanation", 5*time.Second, func() bool {
		return l.count("OUTPUT ERROR: Twitch refused the stream (check the stream key)") > 0
	}, feed)
	time.Sleep(100 * time.Millisecond) // more failures, quietly
	start := time.Now()
	_ = o.Close()
	if time.Since(start) > time.Second {
		t.Fatal("Close waited out the backoff")
	}
	if n := l.count("; reconnecting in"); n != quickFailures-1 {
		t.Fatalf("expected %d reconnect events before the explanation, got %d", quickFailures-1, n)
	}
	if n := l.count("OUTPUT ERROR"); n != quickFailures {
		t.Fatalf("expected %d output errors in all, got %d: %q", quickFailures, n, l.all())
	}
}

// ffmpeg hangs without reading (a stuck ingest): writes stay instant (they're dropped once the queue is full),
// and the hung ffmpeg is killed and replaced.
func TestStalledOutputNeverBlocksTheKeeper(t *testing.T) {
	l := &evLog{}
	o := testOutput("rtmps://ingest.example/app/k", l, func(n int) string {
		if n == 1 {
			return "exec sleep 30"
		}
		return "exec cat > /dev/null"
	})
	defer o.Close()
	feed := frameFeeder(o)
	start := time.Now()
	for i := 0; i < 6000; i++ { // ~9 MB, more than the queue holds
		feed()
	}
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("writes blocked for %v", d)
	}
	if l.count("output is falling behind") != 1 {
		t.Fatalf("expected one falling-behind event: %q", l.all())
	}
	waitFor(t, "the hung ffmpeg to be replaced", 5*time.Second, func() bool {
		return l.count("OUTPUT ERROR: the connection to the output stopped moving; reconnecting in") > 0
	}, nil)
}
