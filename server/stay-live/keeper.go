// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"fmt"
	"log"
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
)

// track is one elementary stream of the slate, looped on its own true duration. Video and audio are looped
// independently: a slate's audio is rarely exactly as long as its video (AAC frames are 1024 samples and muxers
// pack several per PES), and looping audio on the video's length leaves an audible hole every loop.
type track struct {
	aus      []*au // normalised: first AU at 0
	dur      int64 // loop length
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

	st             state
	session        int
	liveOffset     int64
	liveSynced     bool
	lastLiveAt     time.Time
	lastV, lastA   int64
	lastADur       int64
	slateWallStart time.Time
	slateTSStart   int64
	slateSince     time.Time
	written        int
}

// prepareSlate splits a slate into looping video and audio tracks, normalised to start at 0.
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
	v = track{aus: video, dur: video[len(video)-1].dts + frameDur}
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
		lastV:       10 * 90000, lastA: 10 * 90000,
	}
	k.curV, k.curA = &k.vSlate, &k.aSlate
	events(fmt.Sprintf("slate loaded: video %.3fs, audio %.3fs", float64(k.vSlate.dur)/90000, float64(k.aSlate.dur)/90000))
	return k, nil
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
	v, a, params, _ := prepareSlate(slate, k.frameDur)
	k.vSlate, k.aSlate, k.slateParams = v, a, params
	k.checkedLive = 0
	k.events(fmt.Sprintf("slate from the phone installed: video %.3fs, audio %.3fs", float64(v.dur)/90000, float64(a.dur)/90000))
}

// checkParams compares the live video's SPS/PPS with the slate's once per session. They must be identical for the
// splice to be a pure remux that every player decodes; a mismatch means the phone's slate is missing or stale.
func (k *keeper) checkParams(a *au) {
	if k.checkedLive == k.session || !a.key {
		return
	}
	k.checkedLive = k.session
	live := paramSets(a.data)
	switch {
	case len(live) == 0:
		k.events("live video carries no in-band SPS/PPS; can't check the slate against it")
	case bytes.Equal(live, k.slateParams):
		k.events("slate matches the live video format (SPS/PPS identical)")
	default:
		k.events("WARNING: slate's SPS/PPS differ from the live video; splices may glitch until the phone sends its slate")
	}
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
			if k.checkEnd(now); k.finished {
				return nil
			}
			if k.st == stateLive && now.Sub(k.lastLiveAt) > k.stall {
				k.enterSlate(now, fmt.Sprintf("feed stalled > %v", k.stall))
			}
			if k.st == stateSlate {
				if k.hold > 0 && !k.weakHeld && now.Sub(k.slateSince) > k.hold {
					k.events(fmt.Sprintf("hold timer (%v) expired, ending output", k.hold))
					return nil
				}
				if err := k.pumpSlate(now); err != nil {
					return err
				}
			}
		}
	}
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
		k.enterSlate(now, fmt.Sprintf("session %d lost while the signal was weak (%s); waiting for the phone to reconnect", ev.session, why))
	case k.st == stateLive || k.onWeak:
		k.enterSlate(now, fmt.Sprintf("session %d lost (%s); waiting for the phone to reconnect", ev.session, why))
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
		return nil // stale connection still draining
	}
	if ev.session > k.session {
		k.session = ev.session
		k.liveSynced = false
		k.cleanEndAt = time.Time{} // a new session is sending: the phone is back
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
	if k.weakHeld && k.st != stateLive {
		return nil // on the Weak signal slate until the feed is clean again
	}
	a := ev.a
	if !k.liveSynced {
		if a.kind != kindVideo || !a.key {
			return nil // wait for an IDR before splicing in
		}
		k.liveOffset = (k.lastV + k.frameDur) - a.dts
		k.liveSynced = true
		if k.st == stateWaiting {
			k.events(fmt.Sprintf("LIVE (session %d), output started", k.session))
		} else if k.st == stateSlate {
			k.events(fmt.Sprintf("LIVE (session %d) after %.1fs of slate", k.session, now.Sub(k.slateSince).Seconds()))
		} else {
			k.events(fmt.Sprintf("LIVE resynced on new session %d", k.session))
		}
		k.st = stateLive
	}
	if k.st != stateLive {
		return nil
	}
	if a.kind == kindVideo {
		k.checkParams(a)
	}
	return k.emit(a, a.pts+k.liveOffset, a.dts+k.liveOffset)
}

func (k *keeper) enterSlate(now time.Time, why string) { k.enterSlateKind(now, why, false) }

// enterSlateKind puts a slate on air: the BRB slate (a dropout) or the Weak signal one (a damaged feed).
func (k *keeper) enterSlateKind(now time.Time, why string, weak bool) {
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
	k.slateWallStart = now
	k.slateSince = now
	k.slateTSStart = k.lastV + k.frameDur
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
			if err := k.emit(v, k.curV.loopBase+v.pts, vts); err != nil {
				return err
			}
			k.curV.advance()
		} else {
			if ats > target {
				return nil
			}
			if err := k.emit(k.curA.next(), ats, ats); err != nil {
				return err
			}
			k.curA.advance()
		}
	}
}

// emit writes with strictly increasing timestamps per stream; anything that would go backwards is dropped.
func (k *keeper) emit(a *au, pts, dts int64) error {
	switch a.kind {
	case kindVideo:
		if dts <= k.lastV && k.written > 0 {
			return nil
		}
		k.lastV = dts
	case kindAudio:
		// AAC can't be trimmed without re-encoding, so an AU that would overlap audio already sent is dropped
		// (worst case: one short packet of silence at a splice, never an overlap).
		if k.written > 0 && (pts <= k.lastA || pts < k.lastA+k.lastADur-90) { // 1 ms rounding tolerance
			return nil
		}
		k.lastA = pts
		k.lastADur = a.dur
	}
	k.written++
	if err := k.out.write(a, pts, dts); err != nil {
		log.Printf("write: %v", err)
		return err
	}
	return nil
}
