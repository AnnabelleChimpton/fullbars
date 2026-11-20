// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"fmt"
	"log"
	"math"
	"os"
	"runtime"
	"strconv"
	"strings"
	"sync/atomic"
	"time"
)

type state int

const (
	stateSlate state = iota
	stateLive
	stateWaiting // -wait-live: no output at all until the first live keyframe
)

func (s state) String() string {
	switch s {
	case stateLive:
		return "LIVE"
	case stateWaiting:
		return "WAITING"
	}
	return "SLATE"
}

type liveEvent struct {
	a       *au
	session int
	// connected: a new SRT session was accepted (it may not have sent anything yet).
	connected bool
	// ended: the SRT session closed, with err (nil: the reader simply ended). A session ending is never by itself
	// the end of the stream: the phone also closes its old session when it reconnects, and the server ends one when
	// its data can't be read. Only the app says the stream is over (POST /v1/end, see end.go).
	ended bool
	err   error
}

const (
	// endGrace: a session that closes within this long of its last data was closed by the phone (an SRT shutdown);
	// a dropout stops the data first and only times out much later (-peer-idle).
	endGrace = 3 * time.Second
	// endWait: after the app says the stream ended, how long the keeper waits for the live session to close before
	// ending the output anyway.
	endWait = 5 * time.Second
	// cleanEndWait: for apps that never call /v1/end, a session the phone closed is the end of the stream if no new
	// session connects within this long. A reconnect takes a second or two.
	cleanEndWait = 20 * time.Second
	// resyncAfter: live data that keeps arriving but none of which can go on air for this long (its timestamps jumped
	// back) is spliced in again at its next keyframe, instead of freezing the picture until the phone reconnects.
	resyncAfter = 500 * time.Millisecond
	// keyframeWanted: while waiting for a keyframe to splice live back in, the app is told to ask its encoder for one
	// as long as the phone sent something within this long.
	keyframeWanted = 500 * time.Millisecond
	// maxClockFix: how much a stall's slate may correct the output clock towards the previous slate's.
	maxClockFix = 250 * time.Millisecond
)

// Audio timing, in 90 kHz ticks. An AAC frame (1024 samples at 48 kHz) is 1920.
const (
	// audioSlack: a live audio AU up to this much early against the live AU before it is moved to where that one
	// ends rather than dropped: a few ms of phone timestamp jitter must not cost a whole frame. Half a frame: more
	// than that is a real overlap. At a splice (the AU before was the slate's) only rounding (1 ms) is moved: live
	// audio then starts at its first AU at or after the slate's end, keeping its own offset against the live video,
	// and leaving a hole of less than one frame.
	audioSlack = 960
	// maxAudioFill: holes in live audio up to this long are filled with the slate's silence. Longer than that it's
	// not a hole, the audio has stopped.
	maxAudioFill = 10 * 90000
	// tsWrap: MPEG-TS timestamps are 33 bits; they wrap about every 26.5 hours.
	tsWrap  = int64(1) << 33
	tsUnset = math.MinInt64
)

// track is one elementary stream of the slate, looped on its own true duration. Video and audio are looped
// independently: a slate's audio is rarely exactly as long as its video (AAC frames are 1024 samples and muxers
// pack several per PES), and looping audio on the video's length leaves an audible hole every loop.
type track struct {
	aus      []*au // normalised: first AU at 0
	dur      int64 // loop length
	frameDur int64 // video: one frame, measured from the slate's own timestamps
	idx      int
	loopBase int64 // output timestamp of aus[0] in the current loop
}

func (t *track) next() *au { return t.aus[t.idx] }

func (t *track) advance() {
	t.idx++
	if t.idx == len(t.aus) {
		t.idx = 0
		t.loopBase += t.dur
	}
}

// keeper owns the single continuous output timeline. Live access units pass through with a per-session timestamp
// offset; when the feed stalls a pre-encoded slate is looped on the same timeline. Switching back happens only on
// a live IDR so the decoder never sees a broken GOP. No transcoding.
// auWriter is where the keeper's output goes (tsOut in production, a recorder in tests).
type auWriter interface {
	write(a *au, pts, dts int64) error
}

// slateUpdate is a slate the phone just sent: its BRB slate, or (weak) its Weak signal slate.
type slateUpdate struct {
	weak bool
	aus  []*au
}

type keeper struct {
	out    auWriter
	vSlate track
	aSlate track
	// The phone's Weak signal slate, shown while packets keep getting lost on the way (lossDetector), if it sent one.
	vWeak, aWeak track
	hasWeak      bool
	pendingWeak  []*au
	// The slate on air (or last on air): the BRB slate or the Weak signal one.
	curV, curA   *track
	onWeak       bool
	weakHeld     bool // the feed is arriving damaged: stay on the Weak signal slate, ignore live data
	noWeakWarned bool
	frameDur     int64
	stall        time.Duration
	hold         time.Duration
	finished     bool
	events       func(string)
	// updates delivers replacement slates (the phone's own encode) at any time; nil = never.
	updates <-chan slateUpdate
	// quality says when the live feed turns weak or recovers (watchLoss); nil = never.
	quality <-chan qualityEvent
	// ends delivers the app's "the streamer ended the stream" (POST /v1/end); nil = never.
	ends <-chan struct{}
	// appEnds: this server's app calls /v1/end when the streamer stops, so a session the phone closes is a
	// reconnect, never the end (no cleanEndWait fallback).
	appEnds bool

	endAsked     bool      // the app said the stream ended: finish when the live session closes
	endAskedAt   time.Time // when it said so (endWait)
	cleanEndAt   time.Time // fallback: when the phone closed the live session; zero = not waiting on it
	cleanEndConn int       // the newest session accepted at cleanEndAt; a newer one cancels the fallback
	newestConn   int       // the newest session accepted (it may not have sent anything yet)

	pendingSlate []*au  // received while the slate was on air; swapped in at the next enterSlate
	slateParams  []byte // SPS+PPS of the slate, to check it against the live video
	checkedLive  int    // last session whose parameter sets were compared with the slate's
	// Where the BRB slate comes from: "default" (the server's), "saved" (the phone's, from an earlier stream) or
	// "phone" (sent during this stream). A saved one may be from another orientation or resolution.
	slateSource string
	fallback    []*au // the default slate, kept while a saved phone slate is in use
	silent      *au   // one silent AAC frame cut from the slate, to fill holes in live audio; nil without one

	st             state
	session        int
	liveOffset     int64
	liveSynced     bool
	lastLiveAt     time.Time // when the live session last sent anything (onEnded tells a close from a timeout by it)
	lastV, lastA   int64
	lastADur       int64
	lastALive      bool // the last audio written was live (not the slate's or a filled silence)
	slateWallStart time.Time
	slateTSStart   int64
	slateSince     time.Time
	written        int

	// What actually went on air, and when. The stall detector and the slate's wall-clock anchor go by these, not by
	// what arrived: a new session without its keyframe, or audio with no video, arrives but shows a frozen picture.
	lastVWall      time.Time // when the last video AU (live or slate) was written
	lastLiveOutAt  time.Time // when live video last went on air, or live was spliced in
	unemittedSince time.Time // first live AU since the last one written that couldn't go on air; zero = none
	lastVLive      bool      // the last video written was live (its length is the session's frame step)
	lastVDur       int64     // otherwise: the length of the last slate frame written
	// slateClock: the wall time of output position 0 on the last slate's clock; zero before the first slate.
	slateClock time.Time

	// Per live session: 33-bit timestamp unwrapping and the measured frame step.
	unwrapRef int64 // the session's last unwrapped timestamp; tsUnset before its first
	prevVDTS  int64 // the session's last video DTS (unwrapped); tsUnset before its first
	steps     [9]int64
	nsteps    int

	// Output clock, for the lag figure: the first video written, in wall time and output DTS.
	outWall0 time.Time
	outDTS0  int64

	counters   *keeperCounters
	status     func(keeperStatus) // called when what the app polls changes (state.json); nil = nobody listens
	lastStatus keeperStatus
	liveSize   string
	slateMatch string
	filledSaid time.Time
	nextStats  time.Time
	nextMem    time.Time
}

// keeperStatus is what the app polls besides the events (state.json, then /v1/status).
type keeperStatus struct {
	// NeedKeyframe: the keeper is waiting for a live keyframe to put the phone back on air, and the phone is sending.
	// The app asks its encoder for one, instead of viewers waiting out the rest of a 2 s GOP.
	NeedKeyframe bool   `json:"needKeyframe,omitempty"`
	SlateMatch   string `json:"slateMatch,omitempty"`  // match | mismatch | unknown; "" until a live keyframe was compared
	SlateSource  string `json:"slateSource,omitempty"` // phone | saved | default
	SlateSize    string `json:"slateSize,omitempty"`   // e.g. 1920x1080
	LiveSize     string `json:"liveSize,omitempty"`
}

// keeperCounters prove the output is clean: every AU the keeper didn't pass on as it came, and how far the output
// runs behind the wall clock. Written by the keeper, read by the stats writer.
type keeperCounters struct {
	videoBackwards atomic.Int64 // live video dropped: its DTS didn't move forward
	audioBackwards atomic.Int64 // audio dropped: its PTS didn't move forward
	audioOverlap   atomic.Int64 // audio dropped: it overlapped what was sent by half a frame or more (splices)
	audioRetimed   atomic.Int64 // audio moved by more than 1 ms to follow on from the previous AU
	audioFilled    atomic.Int64 // silent frames put into holes in live audio
	audioFilledMs  atomic.Int64
	waitKeyframe   atomic.Int64 // live AUs not shown while waiting for a keyframe to splice in
	stale          atomic.Int64 // AUs from a session already superseded
	resyncs        atomic.Int64 // live spliced in again because its timestamps stopped fitting
	lagMs          atomic.Int64 // wall time since output start minus output time: positive = behind
	heapBytes      atomic.Int64
	rssBytes       atomic.Int64
}

// liveCounters is the running keeper's counters, for the stats file; nil before it starts.
var liveCounters atomic.Pointer[keeperCounters]

// keeperStats is the counters as the stats file and the stats line show them.
type keeperStats struct {
	VideoBackwards int64          `json:"videoBackwards"`
	AudioBackwards int64          `json:"audioBackwards"`
	AudioOverlap   int64          `json:"audioOverlap"`
	AudioRetimed   int64          `json:"audioRetimed"`
	AudioFilled    int64          `json:"audioFilledFrames"`
	AudioFilledMs  int64          `json:"audioFilledMs"`
	WaitKeyframe   int64          `json:"waitKeyframe"`
	Stale          int64          `json:"stale"`
	Resyncs        int64          `json:"resyncs"`
	OutputLagMs    int64          `json:"outputLagMs"`
	HeapBytes      int64          `json:"heapBytes"`
	RSSBytes       int64          `json:"rssBytes"`
	Output         outputCounters `json:"output"`
}

func (c *keeperCounters) snapshot() keeperStats {
	if c == nil {
		return keeperStats{Output: mainOut.Load().counters()}
	}
	return keeperStats{
		VideoBackwards: c.videoBackwards.Load(), AudioBackwards: c.audioBackwards.Load(), AudioOverlap: c.audioOverlap.Load(),
		AudioRetimed: c.audioRetimed.Load(), AudioFilled: c.audioFilled.Load(), AudioFilledMs: c.audioFilledMs.Load(),
		WaitKeyframe: c.waitKeyframe.Load(), Stale: c.stale.Load(), Resyncs: c.resyncs.Load(), OutputLagMs: c.lagMs.Load(),
		HeapBytes: c.heapBytes.Load(), RSSBytes: c.rssBytes.Load(), Output: mainOut.Load().counters(),
	}
}

func (s keeperStats) String() string {
	return fmt.Sprintf("dropped: live video backwards %d, audio backwards %d, audio overlap %d; audio retimed %d, holes filled %d frames (%d ms); "+
		"waiting for a keyframe %d, stale %d, resyncs %d; output lag %d ms; output overflows %d (%d KB dropped), peak queue %d KB, ffmpeg restarts %d; heap %d MB, RSS %d MB",
		s.VideoBackwards, s.AudioBackwards, s.AudioOverlap, s.AudioRetimed, s.AudioFilled, s.AudioFilledMs,
		s.WaitKeyframe, s.Stale, s.Resyncs, s.OutputLagMs, s.Output.Overflows, s.Output.DroppedBytes>>10, s.Output.PeakQueued>>10,
		s.Output.Restarts, s.HeapBytes>>20, s.RSSBytes>>20)
}

// prepareSlate splits a slate into looping video and audio tracks, normalised to start at 0. The frame length comes
// from the slate's own timestamps (a 60 fps slate loops on 1500-tick frames); frameDur is only the fallback for a
// one-frame slate.
func prepareSlate(slate []*au, frameDur int64) (v, a track, params []byte, err error) {
	var video, audio []*au
	for _, x := range slate {
		c := *x // never mutate the caller's AUs
		if c.kind == kindVideo {
			video = append(video, &c)
		} else {
			audio = append(audio, &c)
		}
	}
	if len(video) == 0 || !video[0].key {
		return v, a, nil, fmt.Errorf("slate must start with a video IDR")
	}
	vBase := video[0].dts
	for _, x := range video {
		x.pts -= vBase
		x.dts -= vBase
	}
	if fd := medianStep(video); fd > 0 {
		frameDur = fd
	}
	v = track{aus: video, dur: video[len(video)-1].dts + frameDur, frameDur: frameDur}
	if len(audio) > 0 {
		aBase := audio[0].pts
		var end int64
		for _, x := range audio {
			x.pts -= aBase
			x.dts = x.pts
			if e := x.pts + x.dur; e > end {
				end = e
			}
		}
		if end <= 0 {
			return v, a, nil, fmt.Errorf("slate audio has no measurable duration (expected ADTS AAC)")
		}
		a = track{aus: audio, dur: end}
	}
	return v, a, paramSets(video[0].data), nil
}

// medianStep is the median DTS step of a run of video AUs (0 if there are fewer than two).
func medianStep(video []*au) int64 {
	var steps []int64
	for i := 1; i < len(video); i++ {
		if d := video[i].dts - video[i-1].dts; d > 0 {
			steps = append(steps, d)
		}
	}
	if len(steps) == 0 {
		return 0
	}
	sortInt64(steps)
	return steps[len(steps)/2]
}

func sortInt64(s []int64) { // insertion sort: a few dozen values at most, and no allocation
	for i := 1; i < len(s); i++ {
		for j := i; j > 0 && s[j] < s[j-1]; j-- {
			s[j], s[j-1] = s[j-1], s[j]
		}
	}
}

// silentFrame cuts the first ADTS frame out of a slate's audio. The phone's slates (and the default one) carry
// silence, so it can fill a hole in live audio without a re-encode.
func silentFrame(a track) *au {
	if len(a.aus) == 0 {
		return nil
	}
	b := a.aus[0].data
	if len(b) < 7 || b[0] != 0xFF || b[1]&0xF0 != 0xF0 {
		return nil
	}
	flen := int(b[3]&0x03)<<11 | int(b[4])<<3 | int(b[5])>>5
	if flen < 7 || flen > len(b) {
		return nil
	}
	f := &au{kind: kindAudio, data: append([]byte(nil), b[:flen]...)}
	if f.dur = adtsDuration(f.data); f.dur == 0 {
		return nil
	}
	return f
}

// sameAAC reports whether two ADTS frames have the same profile, sample rate and channel layout, so one can follow
// the other in a stream copied without re-encoding.
func sameAAC(x, y []byte) bool {
	return len(x) >= 4 && len(y) >= 4 && x[0] == 0xFF && y[0] == 0xFF && x[1]&0xF0 == 0xF0 && y[1]&0xF0 == 0xF0 &&
		x[2]&0xFD == y[2]&0xFD && x[3]&0xC0 == y[3]&0xC0
}

func newKeeper(out auWriter, slate []*au, frameDur int64, stall, hold time.Duration, events func(string)) (*keeper, error) {
	v, a, params, err := prepareSlate(slate, frameDur)
	if err != nil {
		return nil, err
	}
	k := &keeper{
		out:      out,
		vSlate:   v,
		aSlate:   a,
		frameDur: frameDur, stall: stall, hold: hold, events: events,
		slateParams: params,
		slateSource: "default",
		silent:      silentFrame(a),
		lastV:       10 * 90000, lastA: 10 * 90000, lastVDur: frameDur,
		unwrapRef: tsUnset, prevVDTS: tsUnset,
		counters: &keeperCounters{},
	}
	k.curV, k.curA = &k.vSlate, &k.aSlate
	events(fmt.Sprintf("slate loaded: video %.3fs (%s, %s), audio %.3fs", float64(k.vSlate.dur)/90000, paramSize(params), fpsOf(v.frameDur), float64(k.aSlate.dur)/90000))
	return k, nil
}

func fpsOf(frameDur int64) string {
	if frameDur <= 0 {
		return "? fps"
	}
	return strconv.FormatFloat(90000/float64(frameDur), 'f', -1, 64) + " fps"
}

// useSavedSlate marks the slate the keeper started with as the phone's, saved from an earlier stream, and keeps the
// default one in reserve: the phone may be streaming in another orientation or resolution now.
func (k *keeper) useSavedSlate(def []*au) {
	k.slateSource = "saved"
	k.fallback = def
}

// replaceSlate installs a new slate (the phone's own encode). Never mid-slate: switching loops while that slate is
// on air would cut a GOP in half, so it waits for the next time it goes on air.
func (k *keeper) replaceSlate(u slateUpdate) {
	if _, _, _, err := prepareSlate(u.aus, k.frameDur); err != nil {
		k.events("slate from the phone rejected: " + err.Error())
		return
	}
	onAir := k.st == stateSlate && k.onWeak == u.weak
	switch {
	case u.weak && onAir:
		k.pendingWeak = u.aus
	case u.weak:
		k.installWeak(u.aus)
	case onAir:
		k.pendingSlate = u.aus
	default:
		k.installSlate(u.aus)
	}
}

// installWeak sets the Weak signal slate (from the phone now, or saved from its last stream).
func (k *keeper) installWeak(slate []*au) {
	v, a, _, err := prepareSlate(slate, k.frameDur)
	if err != nil {
		k.events("weak signal slate rejected: " + err.Error())
		return
	}
	k.vWeak, k.aWeak, k.hasWeak = v, a, true
	k.events(fmt.Sprintf("weak signal slate installed: video %.3fs, audio %.3fs", float64(v.dur)/90000, float64(a.dur)/90000))
}

// onQuality: the live feed turned weak (packets keep getting lost on the way) or recovered.
func (k *keeper) onQuality(q qualityEvent, now time.Time) {
	if q.session != k.session {
		return
	}
	if q.weak {
		if k.weakHeld {
			return
		}
		if !k.hasWeak {
			if !k.noWeakWarned {
				k.noWeakWarned = true
				k.events("packets are being lost on the way, but the phone hasn't sent a Weak signal slate")
			}
			return
		}
		k.weakHeld = true
		if k.st == stateLive {
			k.enterSlateKind(now, fmt.Sprintf("weak signal (%d packets lost in one second)", q.lost), true)
		}
		return
	}
	if k.weakHeld {
		k.weakHeld = false
		k.slateSince = now // the hold timer is for dropouts, not for time spent on the Weak signal screen
		k.events("signal clean again: back to live at the next keyframe")
	}
}

func (k *keeper) installSlate(slate []*au) {
	k.setSlate(slate, "phone")
	k.fallback = nil
	k.events(fmt.Sprintf("slate from the phone installed: video %.3fs (%s, %s), audio %.3fs", float64(k.vSlate.dur)/90000,
		paramSize(k.slateParams), fpsOf(k.vSlate.frameDur), float64(k.aSlate.dur)/90000))
}

func (k *keeper) setSlate(slate []*au, source string) {
	v, a, params, _ := prepareSlate(slate, k.frameDur)
	k.vSlate, k.aSlate, k.slateParams = v, a, params
	k.silent = silentFrame(a)
	k.slateSource = source
	k.checkedLive = 0
	k.slateMatch = ""
}

// checkParams compares the live video's SPS/PPS with the slate's once per session. They must be identical for the
// splice to be a pure remux that every player decodes; a mismatch means the phone's slate is missing or stale.
//
// A slate saved from an earlier stream that has another picture size (the phone turned from landscape to portrait,
// or changed resolution) is not used: the default slate covers until the phone sends this stream's.
func (k *keeper) checkParams(a *au) {
	if k.checkedLive == k.session || !a.key {
		return
	}
	k.checkedLive = k.session
	live := paramSets(a.data)
	if len(live) > 0 {
		k.liveSize = paramSize(live)
	}
	switch {
	case len(live) == 0:
		k.slateMatch = "unknown"
		k.events("live video carries no in-band SPS/PPS; can't check the slate against it")
	case bytes.Equal(live, k.slateParams):
		k.slateMatch = "match"
		k.events("slate matches the live video format (SPS/PPS identical)")
	default:
		k.slateMatch = "mismatch"
		slate := paramSize(k.slateParams)
		if k.slateSource == "saved" && k.fallback != nil && k.liveSize != "" && slate != k.liveSize {
			k.events(fmt.Sprintf("the phone's saved slate is %s but the live video is %s: using the default slate until the phone sends this stream's", slate, k.liveSize))
			k.setSlate(k.fallback, "default")
			k.fallback = nil
			k.checkParams(a)
			return
		}
		k.events(fmt.Sprintf("WARNING: slate's SPS/PPS differ from the live video (slate %s, live %s); splices may glitch until the phone sends its slate", orUnknown(slate), orUnknown(k.liveSize)))
	}
}

func orUnknown(s string) string {
	if s == "" {
		return "size unknown"
	}
	return s
}

// run drives the output. With waitLive, nothing is written until the phone's first keyframe arrives (so an idle
// server doesn't stream a BRB screen to Twitch all day), and run returns once the hold timer expires after a
// dropout, letting the caller close the output and wait for the next session.
func (k *keeper) run(live <-chan liveEvent, done <-chan struct{}, waitLive bool) error {
	if waitLive {
		k.st = stateWaiting
		k.events("WAITING for the phone")
	} else {
		k.enterSlate(time.Now(), "startup")
	}
	liveCounters.Store(k.counters)
	tick := time.NewTicker(5 * time.Millisecond)
	defer tick.Stop()
	for {
		select {
		case <-done:
			return nil
		case sl := <-k.updates:
			k.replaceSlate(sl)
		case q := <-k.quality:
			k.onQuality(q, time.Now())
		case <-k.ends:
			if k.onEndRequest(time.Now()); k.finished {
				return nil
			}
		case ev := <-live:
			if err := k.onLive(ev, time.Now()); err != nil {
				return err
			}
			if k.finished {
				return nil
			}
		case now := <-tick.C:
			if stop, err := k.tick(now); stop || err != nil {
				return err
			}
		}
	}
}

// tick is the keeper's clock: every 5 ms in run(), and called directly by tests on a fake clock. stop: the output
// is over (finished, or the hold timer ran out).
func (k *keeper) tick(now time.Time) (stop bool, err error) {
	if k.checkEnd(now); k.finished {
		return true, nil
	}
	// The stall is measured on what went on air: a session that sends no keyframe, or audio without video, arrives
	// but freezes the picture just the same.
	if k.st == stateLive && now.Sub(k.lastLiveOutAt) > k.stall {
		k.enterSlateAt(now, fmt.Sprintf("feed stalled > %v", k.stall), false, true)
	}
	if k.st == stateSlate {
		if k.hold > 0 && !k.weakHeld && now.Sub(k.slateSince) > k.hold {
			k.events(fmt.Sprintf("hold timer (%v) expired, ending output", k.hold))
			return true, nil
		}
		if err := k.pumpSlate(now); err != nil {
			return true, err
		}
	}
	k.publishStatus(now)
	k.periodic(now)
	return false, nil
}

// publishStatus tells the app what it polls for when it changes.
func (k *keeper) publishStatus(now time.Time) {
	st := keeperStatus{
		// Waiting for a keyframe to splice live in (on a slate, or a new session that hasn't sent one yet), and the
		// phone is sending: ask it for one. Not on the Weak signal slate, which ignores live data on purpose.
		NeedKeyframe: k.st != stateWaiting && !k.liveSynced && !k.weakHeld && k.session > 0 &&
			!k.lastLiveAt.IsZero() && now.Sub(k.lastLiveAt) <= keyframeWanted,
		SlateMatch:  k.slateMatch,
		SlateSource: k.slateSource,
		SlateSize:   paramSize(k.slateParams),
		LiveSize:    k.liveSize,
	}
	if st != k.lastStatus {
		k.lastStatus = st
		if k.status != nil {
			k.status(st)
		}
	}
}

// periodic samples memory every 10 s and logs the keeper's counters every 30 s, once there is output.
func (k *keeper) periodic(now time.Time) {
	if now.Before(k.nextMem) {
		return
	}
	k.nextMem = now.Add(10 * time.Second)
	var ms runtime.MemStats
	runtime.ReadMemStats(&ms)
	k.counters.heapBytes.Store(int64(ms.HeapAlloc))
	k.counters.rssBytes.Store(readRSS())
	if k.nextStats.IsZero() {
		k.nextStats = now.Add(30 * time.Second)
	}
	if now.Before(k.nextStats) {
		return
	}
	k.nextStats = now.Add(30 * time.Second)
	if k.written > 0 {
		k.events("keeper stats: " + k.counters.snapshot().String())
	}
}

// readRSS is the process's resident memory from /proc (Linux, where the server runs); 0 elsewhere.
func readRSS() int64 {
	b, err := os.ReadFile("/proc/self/statm")
	if err != nil {
		return 0
	}
	f := strings.Fields(string(b))
	if len(f) < 2 {
		return 0
	}
	pages, _ := strconv.ParseInt(f[1], 10, 64)
	return pages * int64(os.Getpagesize())
}

// finish ends the output: the stream is over.
func (k *keeper) finish(why string) {
	k.events("stream ended by the streamer (" + why + ")")
	k.finished = true
}

// onEndRequest: the app says the streamer ended the stream (POST /v1/end). While live, the phone is about to close
// its session and may still be sending its last frames, so the output ends when that session closes, or after
// endWait. On a slate the session is already gone or stalled: end now.
func (k *keeper) onEndRequest(now time.Time) {
	switch {
	case k.st == stateWaiting:
		k.events("the app says its stream ended, but nothing is live here: nothing to do")
	case k.st == stateLive:
		if !k.endAsked {
			k.endAsked, k.endAskedAt = true, now
			k.events(fmt.Sprintf("the app says the stream ended: ending the output when session %d closes", k.session))
		}
	default:
		k.finish("the app said so")
	}
}

// checkEnd runs on every tick: the deadlines of onEndRequest and of the clean-close fallback.
func (k *keeper) checkEnd(now time.Time) {
	switch {
	case k.endAsked && k.st != stateLive:
		k.finish("the app said so")
	case k.endAsked && now.Sub(k.endAskedAt) > endWait:
		k.finish(fmt.Sprintf("the app said so; session %d was still open after %v", k.session, endWait))
	case !k.cleanEndAt.IsZero() && now.Sub(k.cleanEndAt) > cleanEndWait:
		k.finish(fmt.Sprintf("the phone closed session %d and nothing reconnected within %v", k.session, cleanEndWait))
	}
}

// onConnected: a new SRT session was accepted. It only takes over once it sends data, but a reconnect cancels the
// clean-close fallback straight away.
func (k *keeper) onConnected(session int) {
	if session > k.newestConn {
		k.newestConn = session
	}
	if !k.cleanEndAt.IsZero() && session > k.cleanEndConn {
		k.cleanEndAt = time.Time{}
		k.events(fmt.Sprintf("session %d connected: the phone is reconnecting, not ending", session))
	}
}

// onEnded: an SRT session closed. Only the live session (the newest one that sent data) matters: the end of any
// other (superseded, or a duplicate handshake that never carried the stream) changes nothing. The live session
// ending is a dropout: the BRB slate (the phone's Signal lost screen) goes on air and the hold timer runs until the
// phone reconnects. The stream only ends here if the app already said so (/v1/end), or, for apps that never call
// it, through the cleanEndWait fallback.
func (k *keeper) onEnded(ev liveEvent, now time.Time) {
	if k.st == stateWaiting {
		return // never went live: nothing on air to cover
	}
	if ev.session != k.session {
		k.events(fmt.Sprintf("session %d ended, but session %d is the live one: ignored", ev.session, k.session))
		return
	}
	quiet := now.Sub(k.lastLiveAt)
	why := "closed by the phone"
	switch {
	case ev.err != nil:
		why = ev.err.Error()
	case quiet >= endGrace:
		why = fmt.Sprintf("timed out %.0fs after its last data", quiet.Seconds())
	}
	if k.endAsked {
		k.finish(fmt.Sprintf("the app said so; session %d closed", ev.session))
		return
	}
	switch {
	case k.weakHeld:
		// The weak connection is gone altogether: that's a dropout now. Show the BRB slate and let the hold timer
		// run, rather than holding the Weak signal screen (which pauses the timer) until a reconnect.
		k.weakHeld = false
		k.enterSlateAt(now, fmt.Sprintf("session %d lost while the signal was weak (%s); waiting for the phone to reconnect", ev.session, why), false, true)
	case k.st == stateLive || k.onWeak:
		k.enterSlateAt(now, fmt.Sprintf("session %d lost (%s); waiting for the phone to reconnect", ev.session, why), false, true)
	default:
		k.events(fmt.Sprintf("session %d lost (%s); the slate stays on air until the phone reconnects", ev.session, why))
	}
	if ev.err == nil && quiet < endGrace && !k.appEnds {
		k.cleanEndAt, k.cleanEndConn = now, k.newestConn
		k.events(fmt.Sprintf("this app doesn't say when it ends: if nothing reconnects within %v, that was the end of the stream", cleanEndWait))
	}
}

func (k *keeper) onLive(ev liveEvent, now time.Time) error {
	if ev.connected {
		k.onConnected(ev.session)
		return nil
	}
	if ev.ended {
		k.onEnded(ev, now)
		return nil
	}
	if ev.session < k.session {
		k.counters.stale.Add(1)
		return nil // stale connection still draining
	}
	if ev.session > k.session {
		k.session = ev.session
		k.liveSynced = false
		k.cleanEndAt = time.Time{} // a new session is sending: the phone is back
		k.newSessionClock()
		// A new connection (out of a tunnel, say) starts with a clean feed. The old session's end may arrive up to
		// the SRT idle timeout later and is ignored as stale (it never sends a "recovered"), so without this the Weak
		// signal slate would stay on air for the rest of the stream.
		if k.weakHeld {
			k.weakHeld = false
			k.slateSince = now // the hold timer counts dropouts, not time on the Weak signal screen
			k.events(fmt.Sprintf("new connection (session %d): no longer holding the Weak signal slate", k.session))
		}
	}
	k.lastLiveAt = now
	a := ev.a
	pts, dts := k.unwrap(a)
	if a.kind == kindVideo {
		k.noteStep(dts)
	}
	if k.weakHeld && k.st != stateLive {
		return nil // on the Weak signal slate until the feed is clean again
	}
	if !k.liveSynced {
		if a.kind != kindVideo || !a.key {
			k.counters.waitKeyframe.Add(1)
			return nil // wait for an IDR before splicing in
		}
		k.liveOffset = k.nextVideoTS() - dts
		k.liveSynced = true
		k.lastLiveOutAt = now
		k.unemittedSince = time.Time{}
		switch k.st {
		case stateWaiting:
			k.events(fmt.Sprintf("LIVE (session %d), output started", k.session))
		case stateSlate:
			k.events(fmt.Sprintf("LIVE (session %d) after %.1fs of slate", k.session, now.Sub(k.slateSince).Seconds()))
		default:
			k.events(fmt.Sprintf("LIVE resynced on session %d", k.session))
		}
		k.st = stateLive
	}
	if k.st != stateLive {
		return nil
	}
	if a.kind == kindVideo {
		k.checkParams(a)
	} else if err := k.fillAudio(a, pts+k.liveOffset, now); err != nil {
		return err
	}
	ok, err := k.emit(a, pts+k.liveOffset, dts+k.liveOffset, now, true)
	if err != nil {
		return err
	}
	switch {
	case ok:
		k.unemittedSince = time.Time{}
		if a.kind == kindVideo {
			k.lastLiveOutAt = now
			k.lastVLive = true
		}
	case k.unemittedSince.IsZero():
		k.unemittedSince = now
	case now.Sub(k.unemittedSince) > resyncAfter:
		// Live keeps arriving but nothing of it fits the output any more: its timestamps jumped back. Splice it in
		// again at its next keyframe rather than freeze until the phone reconnects.
		k.liveSynced = false
		k.unemittedSince = time.Time{}
		k.counters.resyncs.Add(1)
		k.events(fmt.Sprintf("session %d: live timestamps stopped fitting the output for %v; back on air at its next keyframe", k.session, resyncAfter))
	}
	return nil
}

// newSessionClock forgets the old session's timestamps: each SRT session has its own clock.
func (k *keeper) newSessionClock() {
	if k.lastVLive {
		k.lastVDur, k.lastVLive = k.liveFrameDur(), false // the old session's frame step, measured while it lasted
	}
	k.unwrapRef, k.prevVDTS, k.nsteps = tsUnset, tsUnset, 0
	k.unemittedSince = time.Time{}
}

// unwrap extends a live AU's 33-bit timestamps past the wrap (about every 26.5 h), so a long stream keeps moving
// forward. Each value is placed in the 2^33 period nearest to the session's previous one, so audio and video
// (which interleave with a small offset) unwrap together and a small step back stays a step back.
func (k *keeper) unwrap(a *au) (pts, dts int64) {
	u := a.dts
	if k.unwrapRef != tsUnset {
		u += k.unwrapRef &^ (tsWrap - 1)
		switch {
		case u < k.unwrapRef-tsWrap/2:
			u += tsWrap
		case u > k.unwrapRef+tsWrap/2:
			u -= tsWrap
		}
	}
	k.unwrapRef = u
	d := (a.pts - a.dts) & (tsWrap - 1)
	if d >= tsWrap/2 {
		d -= tsWrap
	}
	return u + d, u
}

// noteStep records the session's video DTS steps; their median is its frame length.
func (k *keeper) noteStep(dts int64) {
	if k.prevVDTS != tsUnset {
		if st := dts - k.prevVDTS; st > 0 && st < 90000 {
			k.steps[k.nsteps%len(k.steps)] = st
			k.nsteps++
		}
	}
	k.prevVDTS = dts
}

func (k *keeper) liveFrameDur() int64 {
	n := min(k.nsteps, len(k.steps))
	if n == 0 {
		return k.frameDur
	}
	var s [len(k.steps)]int64
	copy(s[:], k.steps[:n])
	sortInt64(s[:n])
	return s[n/2]
}

// nextVideoTS is where the next video frame goes on the output timeline: right after the last one written.
func (k *keeper) nextVideoTS() int64 {
	if k.lastVLive {
		return k.lastV + k.liveFrameDur()
	}
	return k.lastV + k.lastVDur
}

// fillAudio fills a hole in live audio (a mic overrun, an encoder restart, packets lost on the way, a splice) with
// the slate's silent AAC frames, so the output's audio has no gaps. Only when the formats match (the frames are
// copied, not re-encoded) and the hole is shorter than maxAudioFill. Whole frames only: what is left is less than one
// frame (nothing, when the live audio carries on its own frame grid), since moving the live audio to close it would
// shift it against its video.
func (k *keeper) fillAudio(a *au, pts int64, now time.Time) error {
	if k.silent == nil || k.written == 0 || k.lastADur <= 0 {
		return nil
	}
	end := k.lastA + k.lastADur
	if gap := pts - end; gap < k.silent.dur || gap > maxAudioFill || !sameAAC(k.silent.data, a.data) {
		return nil
	}
	n := int64(0)
	for pts-end >= k.silent.dur {
		ok, err := k.emit(k.silent, end, end, now, false)
		if err != nil {
			return err
		}
		if !ok {
			break
		}
		end = k.lastA + k.lastADur
		n++
	}
	ms := n * k.silent.dur / 90
	k.counters.audioFilled.Add(n)
	k.counters.audioFilledMs.Add(ms)
	if now.Sub(k.filledSaid) >= 10*time.Second {
		k.filledSaid = now
		k.events(fmt.Sprintf("audio gap filled (%d ms of silence)", ms))
	}
	return nil
}

func (k *keeper) enterSlate(now time.Time, why string) { k.enterSlateKind(now, why, false) }

// enterSlateKind puts a slate on air: the BRB slate (a dropout) or the Weak signal one (a damaged feed).
func (k *keeper) enterSlateKind(now time.Time, why string, weak bool) {
	k.enterSlateAt(now, why, weak, false)
}

// enterSlateAt puts a slate on air. backfill (a stall, a lost session): the slate starts where the output stopped
// in wall time, not now, so its first pump sends the stretch the stall detector spent noticing (up to 1.5 stall
// times) and the output stays locked to real time. Without it every dropout added its detection time to the
// stream's delay and left a hole in the timeline. The anchor is the last video written, never a clock that spans
// live segments: the phone's clock drifts against the server's.
func (k *keeper) enterSlateAt(now time.Time, why string, weak, backfill bool) {
	if weak {
		if k.pendingWeak != nil {
			k.installWeak(k.pendingWeak)
			k.pendingWeak = nil
		}
		k.curV, k.curA = &k.vWeak, &k.aWeak
	} else {
		if k.pendingSlate != nil {
			k.installSlate(k.pendingSlate)
			k.pendingSlate = nil
		}
		k.curV, k.curA = &k.vSlate, &k.aSlate
	}
	k.onWeak = weak
	k.st = stateSlate
	k.liveSynced = false
	k.slateSince = now
	k.slateTSStart = k.nextVideoTS()
	k.slateWallStart = now
	if backfill && k.written > 0 && !k.lastVWall.IsZero() {
		if back := now.Sub(k.lastVWall); back > 0 && back <= k.stall+k.stall/2 {
			// The slate's first frame takes the slot after the last frame written.
			start := k.lastVWall.Add(tickDur(k.slateTSStart - k.lastV))
			if !k.slateClock.IsZero() {
				// Where the previous slate's clock puts that frame. Going by the last live frame alone, every dropout
				// would add the rounding of the last splice (a keyframe only fits the frame slot after the last slate
				// frame, up to a frame ahead) and that frame's arrival jitter, and those add up over a stream. The
				// correction is limited, so phone clock drift over a long live stretch comes back gradually rather
				// than as one long burst or pause.
				fix := k.slateClock.Add(tickDur(k.slateTSStart)).Sub(start)
				start = start.Add(min(max(fix, -maxClockFix), maxClockFix))
			}
			if start.After(now) {
				start = now
			}
			k.slateWallStart = start
		}
	}
	k.slateClock = k.slateWallStart.Add(-tickDur(k.slateTSStart))
	k.curV.idx, k.curV.loopBase = 0, k.slateTSStart
	// Audio continues exactly where the previous audio ended, so there is no hole at the switch.
	aStart := k.lastA + k.lastADur
	if k.written == 0 || aStart < k.slateTSStart-90000 || aStart > k.slateTSStart+90000 {
		aStart = k.slateTSStart // no usable audio history (or wildly off): align with video
	}
	k.curA.idx, k.curA.loopBase = 0, aStart
	if weak {
		k.events("WEAK SIGNAL slate: " + why)
	} else {
		k.events("SLATE: " + why)
	}
}

// tickDur converts a 90 kHz duration to wall time.
func tickDur(t int64) time.Duration { return time.Duration(t) * time.Second / 90000 }

func (k *keeper) pumpSlate(now time.Time) error {
	target := k.slateTSStart + int64(now.Sub(k.slateWallStart).Seconds()*90000)
	for {
		v := k.curV.next()
		vts := k.curV.loopBase + v.dts
		var ats int64 = 1 << 62
		if len(k.curA.aus) > 0 {
			ats = k.curA.loopBase + k.curA.next().pts
		}
		// Emit in timestamp order across both tracks, up to the wall-clock target.
		if vts <= ats {
			if vts > target {
				return nil
			}
			ok, err := k.emit(v, k.curV.loopBase+v.pts, vts, now, false)
			if err != nil {
				return err
			}
			if ok {
				k.lastVLive, k.lastVDur = false, k.curV.frameDur
			}
			k.curV.advance()
		} else {
			if ats > target {
				return nil
			}
			if _, err := k.emit(k.curA.next(), ats, ats, now, false); err != nil {
				return err
			}
			k.curA.advance()
		}
	}
}

// emit writes with strictly increasing timestamps per stream. Video that would go backwards is dropped. AAC can't be
// trimmed without re-encoding, so audio that overlaps what was sent is moved to follow on when the overlap is small
// (see audioSlack) and dropped otherwise. live: a live AU (not the slate's). ok: it was written.
func (k *keeper) emit(a *au, pts, dts int64, now time.Time, live bool) (ok bool, err error) {
	switch a.kind {
	case kindVideo:
		if dts <= k.lastV && k.written > 0 {
			k.counters.videoBackwards.Add(1)
			return false, nil
		}
		k.lastV = dts
		k.lastVWall = now
		if k.outWall0.IsZero() {
			k.outWall0, k.outDTS0 = now, dts
		}
		k.counters.lagMs.Store(now.Sub(k.outWall0).Milliseconds() - (dts-k.outDTS0)/90)
	case kindAudio:
		if k.written > 0 {
			if pts <= k.lastA {
				k.counters.audioBackwards.Add(1)
				return false, nil
			}
			if over := k.lastA + k.lastADur - pts; over > 0 {
				slack := int64(90) // rounding
				if live && k.lastALive {
					slack = audioSlack
				}
				if over >= slack {
					k.counters.audioOverlap.Add(1)
					return false, nil
				}
				if over > 90 {
					k.counters.audioRetimed.Add(1)
				}
				pts = k.lastA + k.lastADur
				dts = pts
			}
		}
		k.lastA = pts
		k.lastADur = a.dur
		k.lastALive = live
	}
	k.written++
	if err := k.out.write(a, pts, dts); err != nil {
		log.Printf("write: %v", err)
		return false, err
	}
	return true, nil
}
