// SPDX-License-Identifier: Apache-2.0
package main

import (
	"math"
	"math/rand"
	"strings"
	"testing"
	"time"
)

// The keeper on a fake clock, driven the way run() drives it: live AUs as the phone's clock produces them, and a
// tick every 5 ms. Dropouts, new sessions and keyframes are scripted.

type phoneSim struct {
	t       *testing.T
	k       *keeper
	r       *rec
	evs     []string
	status  keeperStatus
	now     time.Time
	session int
	clock0  int64     // the phone's 90 kHz clock at start
	wall0   time.Time // ...and the wall time then
	nextV   int64     // phone timestamps of the next AUs
	nextA   int64
	frames  int  // video frames produced (the phone's GOP counts these)
	sendV   bool // which kinds the phone is sending right now
	sendA   bool
	forceKF bool // the next frame is a keyframe (the phone's encoder was asked for one)
	gop     int

	// Output-vs-wall check: the first video written, and the worst gap seen whenever output was written.
	out0    int64
	outWall time.Time
	worst   float64
	ppm     float64 // how much faster the phone's clock runs than the server's
}

func newPhoneSim(t *testing.T) *phoneSim {
	t.Helper()
	s := &phoneSim{t: t, r: &rec{}, now: time.Unix(10_000, 0), session: 1, clock0: 3_000_000, gop: 60}
	k, err := newKeeper(s.r, testSlate(), 3000, 1500*time.Millisecond, 0, func(e string) { s.evs = append(s.evs, e) })
	if err != nil {
		t.Fatal(err)
	}
	k.status = func(st keeperStatus) { s.status = st }
	k.st = stateWaiting
	s.k = k
	s.wall0 = s.now
	s.nextV, s.nextA = s.clock0, s.clock0
	s.sendV, s.sendA = true, true
	return s
}

// phoneClock is the phone's timestamp for the current wall time.
func (s *phoneSim) phoneClock() int64 {
	return s.clock0 + int64(float64(s.now.Sub(s.wall0))*9/100000*(1+s.ppm/1e6))
}

// run advances the fake clock by d in 5 ms steps, delivering what the phone sends and ticking the keeper.
func (s *phoneSim) run(d time.Duration) {
	s.t.Helper()
	for end := s.now.Add(d); s.now.Before(end); {
		s.now = s.now.Add(5 * time.Millisecond)
		pc := s.phoneClock()
		for s.nextV <= pc || s.nextA <= pc {
			if s.nextV <= s.nextA {
				key := s.frames%s.gop == 0 || s.forceKF
				if s.sendV {
					s.forceKF = s.forceKF && !key
					data := pFrame(10)
					if key {
						data = idr(0x64, 0xee)
					}
					s.deliver(&au{kind: kindVideo, pts: s.nextV, dts: s.nextV, key: key, data: data})
				}
				s.nextV += 3000
				s.frames++
			} else {
				if s.sendA {
					d := adts(1)
					s.deliver(&au{kind: kindAudio, pts: s.nextA, dts: s.nextA, data: d, dur: adtsDuration(d)})
				}
				s.nextA += 1920
			}
		}
		if _, err := s.k.tick(s.now); err != nil {
			s.t.Fatal(err)
		}
		s.checkLock()
	}
}

func (s *phoneSim) deliver(a *au) {
	s.t.Helper()
	a.pts &= tsWrap - 1 // the TS carries 33 bits
	a.dts &= tsWrap - 1
	if err := s.k.onLive(liveEvent{session: s.session, a: a}, s.now); err != nil {
		s.t.Fatal(err)
	}
}

// checkLock compares the output timeline with the wall clock whenever video was written in this step.
func (s *phoneSim) checkLock() {
	if len(s.r.v) == 0 || !s.k.lastVWall.Equal(s.now) {
		return
	}
	if s.outWall.IsZero() {
		s.out0, s.outWall = s.r.v[0], s.now
		return
	}
	out := float64(s.r.v[len(s.r.v)-1]-s.out0) / 90000
	wall := s.now.Sub(s.outWall).Seconds()
	s.worst = math.Max(s.worst, math.Abs(out-wall))
}

func (s *phoneSim) has(sub string) bool { return strings.Contains(strings.Join(s.evs, "\n"), sub) }

func (s *phoneSim) eventAt(sub string) bool {
	return len(s.evs) > 0 && strings.Contains(s.evs[len(s.evs)-1], sub)
}

// A dropout: the slate fills the stretch the stall detector needed to notice it, so the output timeline doesn't
// fall behind real time.
func TestStallBackfillsTheDetectionTime(t *testing.T) {
	s := newPhoneSim(t)
	s.run(3 * time.Second)
	if s.k.st != stateLive {
		t.Fatal("expected live")
	}
	lastLive := s.r.v[len(s.r.v)-1]
	s.sendV, s.sendA = false, false
	t0 := s.k.lastVWall
	for s.k.st == stateLive {
		s.run(5 * time.Millisecond)
	}
	if in := s.now.Sub(t0); in < 1500*time.Millisecond || in > 1520*time.Millisecond {
		t.Fatalf("slate came up %v after the last live video, want 1.5 s", in)
	}
	// The same tick pumped the slate: the output has caught up with the 1.5 s of wall time.
	if got, want := s.r.v[len(s.r.v)-1], lastLive+135000-3000; got < want {
		t.Fatalf("after the first pump the output is at %d, want at least %d (1.5 s after the last live frame)", got, want)
	}
	vEnds := make([]int64, len(s.r.v))
	for i, v := range s.r.v {
		vEnds[i] = v + 3000
	}
	checkContinuous(t, "video", s.r.v, vEnds, 0)
	checkContinuous(t, "audio", s.r.a, s.r.aEnd, 90)
}

// Fifty dropouts in a row: the output stays within 100 ms of the wall clock, video stays frame-exact and audio
// has no hole of a frame or more. Also with a phone clock that runs 500 ppm fast or slow against the server's.
func TestDropoutSoakStaysOnWallClock(t *testing.T) {
	for _, ppm := range []float64{0, 500, -500} {
		s := newPhoneSim(t)
		s.ppm = ppm
		for i := 0; i < 50; i++ {
			s.sendV, s.sendA = true, true
			s.run(3 * time.Second)
			s.sendV, s.sendA = false, false
			s.run(2*time.Second + time.Duration(i)*37*time.Millisecond)
		}
		s.sendV, s.sendA = true, true
		s.run(3 * time.Second)
		if s.worst > 0.1 {
			t.Fatalf("%+.0f ppm: output drifted %.3f s from the wall clock", ppm, s.worst)
		}
		if got := strings.Count(strings.Join(s.evs, "\n"), "SLATE: feed stalled"); got != 50 {
			t.Fatalf("%+.0f ppm: expected 50 stalls, got %d", ppm, got)
		}
		vEnds := make([]int64, len(s.r.v))
		for i, v := range s.r.v {
			vEnds[i] = v + 3000
		}
		checkContinuous(t, "video", s.r.v, vEnds, 0)
		// At a splice back to live a hole of less than one frame can stay: closing it would shift live audio
		// against its video. Whole frames of it are filled.
		checkContinuous(t, "audio", s.r.a, s.r.aEnd, 1919)
		if lag := s.k.counters.lagMs.Load(); lag > 100 || lag < -100 {
			t.Fatalf("%+.0f ppm: output lag counter says %d ms", ppm, lag)
		}
	}
}

// A new session that sends no keyframe yet: its data arrives but nothing goes on air, so the slate must come up
// 1.5 s after the last live video, stay on continuously, and give way at the new session's first keyframe.
func TestNewSessionWithoutKeyframeBringsUpSlate(t *testing.T) {
	s := newPhoneSim(t)
	s.run(3 * time.Second)
	lastOut := s.k.lastLiveOutAt
	s.session = 2
	s.frames = 1 // mid-GOP: the next keyframe is 59 frames away
	s.gop = 1 << 30
	s.run(1490 * time.Millisecond)
	if s.k.st != stateLive {
		t.Fatal("slate too early")
	}
	s.run(30 * time.Millisecond)
	if s.k.st != stateSlate {
		t.Fatalf("no slate %v after the last live video while session 2 sends without a keyframe", s.now.Sub(lastOut))
	}
	if !s.status.NeedKeyframe {
		t.Fatal("the app should be asked for a keyframe while session 2 sends without one")
	}
	s.run(1500 * time.Millisecond)
	if s.k.st != stateSlate {
		t.Fatal("the slate must stay on air until a keyframe")
	}
	s.forceKF = true
	s.run(40 * time.Millisecond)
	if s.k.st != stateLive || s.k.session != 2 || !s.has("LIVE (session 2) after") {
		t.Fatalf("session 2's keyframe should bring live back (st=%v session=%d)", s.k.st, s.k.session)
	}
	s.run(100 * time.Millisecond)
	if s.status.NeedKeyframe {
		t.Fatal("needKeyframe should clear once live")
	}
	vEnds := make([]int64, len(s.r.v))
	for i, v := range s.r.v {
		vEnds[i] = v + 3000
	}
	checkContinuous(t, "video", s.r.v, vEnds, 0)
}

// Video stops but audio keeps coming: the picture is frozen, so the slate goes up.
func TestAudioOnlyBringsUpSlate(t *testing.T) {
	s := newPhoneSim(t)
	s.run(3 * time.Second)
	s.sendV = false
	s.run(1600 * time.Millisecond)
	if s.k.st != stateSlate {
		t.Fatal("audio alone must not keep a frozen picture on air")
	}
	s.run(1400 * time.Millisecond)
	if s.k.st != stateSlate {
		t.Fatal("still no video: slate")
	}
}

// Back from a dropout in the same session: P frames don't end the slate, the app is told to ask for a keyframe,
// and the keyframe goes on air at once.
func TestSameSessionReturnWaitsForKeyframeAndAsksForOne(t *testing.T) {
	s := newPhoneSim(t)
	s.run(3 * time.Second)
	s.sendV, s.sendA = false, false
	s.run(2 * time.Second)
	if s.k.st != stateSlate || s.status.NeedKeyframe {
		t.Fatalf("expected the slate, no keyframe request while nothing arrives (st=%v need=%v)", s.k.st, s.status.NeedKeyframe)
	}
	s.gop = 1 << 30 // no natural keyframe
	s.sendV, s.sendA = true, true
	s.run(300 * time.Millisecond)
	if s.k.st != stateSlate {
		t.Fatal("P frames must not end the slate")
	}
	if !s.status.NeedKeyframe {
		t.Fatal("expected needKeyframe while the phone sends P frames")
	}
	s.forceKF = true
	s.run(40 * time.Millisecond)
	if s.k.st != stateLive || s.status.NeedKeyframe {
		t.Fatalf("expected live on the keyframe and the request cleared (st=%v need=%v)", s.k.st, s.status.NeedKeyframe)
	}
}

// Ten minutes of phone audio whose timestamps jitter by up to ±1.5 ms: nothing is dropped and there are no holes
// beyond the jitter itself.
func TestAudioJitterIsNeverDropped(t *testing.T) {
	r := &rec{}
	k, _ := newKeeper(r, testSlate(), 3000, 1500*time.Millisecond, 0, func(string) {})
	k.st = stateWaiting
	now := time.Unix(0, 0)
	_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: 1000, dts: 1000, key: true, data: idr(0x64, 0xee)}}, now)
	rng := rand.New(rand.NewSource(1))
	n := 10 * 60 * 48000 / 1024
	for i := 0; i < n; i++ {
		jitter := int64(rng.Intn(271)) - 135 // ±1.5 ms
		d := adts(1)
		pts := 1000 + int64(i)*1920 + jitter
		if err := k.onLive(liveEvent{session: 1, a: &au{kind: kindAudio, pts: pts, dts: pts, data: d, dur: adtsDuration(d)}}, now); err != nil {
			t.Fatal(err)
		}
		if i%15 == 0 && i > 0 { // keep video going so nothing stalls or resyncs
			v := 1000 + int64(i)*1920
			_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: v, dts: v, data: pFrame(10)}}, now)
		}
	}
	c := k.counters.snapshot()
	if len(r.a) != n || c.AudioBackwards != 0 || c.AudioOverlap != 0 {
		t.Fatalf("%d of %d audio AUs written; dropped backwards %d, overlap %d", len(r.a), n, c.AudioBackwards, c.AudioOverlap)
	}
	checkContinuous(t, "audio", r.a, r.aEnd, 271)
	if c.AudioFilled != 0 {
		t.Fatalf("jitter is not a hole: %d frames filled", c.AudioFilled)
	}
}

// Holes in live audio (a mic overrun, an encoder restart, lost packets) are filled with the slate's silence.
func TestLiveAudioHolesAreFilled(t *testing.T) {
	for _, hole := range []time.Duration{200 * time.Millisecond, 5 * time.Second} {
		s := newPhoneSim(t)
		s.run(2 * time.Second)
		s.sendA = false
		s.run(hole)
		s.sendA = true
		s.run(2 * time.Second)
		if s.k.st != stateLive {
			t.Fatalf("%v hole: video kept coming, should still be live", hole)
		}
		checkContinuous(t, "audio", s.r.a, s.r.aEnd, 90)
		c := s.k.counters.snapshot()
		if got, want := c.AudioFilledMs, hole.Milliseconds(); got < want-25 || got > want+25 {
			t.Fatalf("%v hole: filled %d ms", hole, got)
		}
		if !s.has("audio gap filled") {
			t.Fatalf("%v hole: no event", hole)
		}
	}
}

// Audio in another format than the slate's (here: 44.1 kHz) is never filled with the slate's frames.
func TestAudioHoleNotFilledAcrossFormats(t *testing.T) {
	r := &rec{}
	k, _ := newKeeper(r, testSlate(), 3000, 1500*time.Millisecond, 0, func(string) {})
	k.st = stateWaiting
	now := time.Unix(0, 0)
	_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindVideo, pts: 1000, dts: 1000, key: true, data: idr(0x64, 0xee)}}, now)
	frame := []byte{0xFF, 0xF1, 0x50, 0x80, 0x00, 0xE0, 0x00} // sfi=4: 44.1 kHz
	for _, pts := range []int64{1000, 1000 + 2090, 1000 + 2090*20} {
		_ = k.onLive(liveEvent{session: 1, a: &au{kind: kindAudio, pts: pts, dts: pts, data: frame, dur: adtsDuration(frame)}}, now)
	}
	if len(r.a) != 3 || k.counters.audioFilled.Load() != 0 {
		t.Fatalf("got %d audio AUs, %d filled; want 3 and none", len(r.a), k.counters.audioFilled.Load())
	}
}
