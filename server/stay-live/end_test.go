// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"context"
	"errors"
	"strings"
	"testing"
	"time"
)

// How the keeper tells "the streamer ended" from a dropout (end.go, keeper.onEnded). The first real stream went off
// Twitch twice because any session closing right after data counted as the streamer ending: once on a demux error,
// once when the phone closed its old session to reconnect.

type endFixture struct {
	k   *keeper
	evs *[]string
	t0  time.Time
	dts int64
}

func liveKeeper(t *testing.T, appEnds bool) *endFixture {
	t.Helper()
	evs := &[]string{}
	k, err := newKeeper(&rec{}, testSlate(), 3000, 1500*time.Millisecond, 10*time.Minute, func(e string) { *evs = append(*evs, e) })
	if err != nil {
		t.Fatal(err)
	}
	k.appEnds = appEnds
	k.st = stateWaiting
	f := &endFixture{k: k, evs: evs, t0: time.Unix(5000, 0), dts: 1000}
	f.connect(1)
	f.frame(1, true, f.t0)
	if k.st != stateLive {
		t.Fatal("expected live")
	}
	return f
}

func (f *endFixture) connect(s int) { _ = f.k.onLive(liveEvent{session: s, connected: true}, f.t0) }

func (f *endFixture) frame(s int, key bool, at time.Time) {
	f.dts += 3000
	_ = f.k.onLive(liveEvent{session: s, a: &au{kind: kindVideo, pts: f.dts, dts: f.dts, key: key}}, at)
}

func (f *endFixture) end(s int, err error, at time.Time) {
	_ = f.k.onLive(liveEvent{session: s, ended: true, err: err}, at)
}

func (f *endFixture) log() string { return strings.Join(*f.evs, "\n") }

func (f *endFixture) has(t *testing.T, sub string) {
	t.Helper()
	if !strings.Contains(f.log(), sub) {
		t.Errorf("missing event %q in:\n%s", sub, f.log())
	}
}

func TestSessionEndsAreDropouts(t *testing.T) {
	for _, c := range []struct {
		name  string
		err   error
		after time.Duration
		why   string
	}{
		{"closed by the phone", nil, 200 * time.Millisecond, "(closed by the phone)"},
		{"demux error", errors.New("astits: building new data failed: slice length is 2568, offset 5493 is invalid"), 100 * time.Millisecond, "offset 5493 is invalid"},
		{"timed out", nil, 31 * time.Second, "(timed out 31s after its last data)"},
	} {
		t.Run(c.name, func(t *testing.T) {
			f := liveKeeper(t, true)
			at := f.t0.Add(c.after)
			f.end(1, c.err, at)
			if f.k.finished {
				t.Fatal("a session ending must not end the stream")
			}
			if c.after < time.Second && (f.k.st != stateSlate || f.k.onWeak) {
				t.Fatalf("expected the BRB slate on air, st=%v onWeak=%v", f.k.st, f.k.onWeak)
			}
			f.has(t, "SLATE: session 1 lost (")
			f.has(t, c.why)
			f.k.checkEnd(at.Add(time.Hour)) // no fallback for an app that calls /v1/end
			if f.k.finished {
				t.Fatal("ended by the clean-close fallback although the app calls /v1/end")
			}
			// The phone reconnects: live again on its keyframe.
			f.connect(2)
			f.frame(2, true, at.Add(2*time.Second))
			if f.k.st != stateLive || f.k.session != 2 {
				t.Fatalf("expected live on session 2, st=%v session=%d", f.k.st, f.k.session)
			}
		})
	}
}

func TestEndRequestWhileLiveWaitsForTheSessionToClose(t *testing.T) {
	f := liveKeeper(t, true)
	f.k.onEndRequest(f.t0.Add(100 * time.Millisecond))
	if f.k.finished || f.k.st != stateLive {
		t.Fatal("the last frames should still go out until the phone closes the session")
	}
	f.frame(1, false, f.t0.Add(200*time.Millisecond))
	f.end(1, nil, f.t0.Add(300*time.Millisecond))
	if !f.k.finished {
		t.Fatal("the session closing after /v1/end should end the stream")
	}
	f.has(t, "stream ended by the streamer (the app said so; session 1 closed)")
}

func TestEndRequestAfterTheSessionClosedEndsAtOnce(t *testing.T) {
	f := liveKeeper(t, false)
	f.end(1, nil, f.t0.Add(100*time.Millisecond)) // the phone closed first: a dropout so far
	if f.k.finished || f.k.st != stateSlate {
		t.Fatal("expected the slate")
	}
	f.k.onEndRequest(f.t0.Add(400 * time.Millisecond))
	if !f.k.finished {
		t.Fatal("/v1/end on the slate should end the stream at once")
	}
	f.has(t, "stream ended by the streamer (the app said so)")
}

func TestEndRequestWithoutTheSessionClosingEndsAfterEndWait(t *testing.T) {
	f := liveKeeper(t, true)
	asked := f.t0.Add(time.Second)
	f.k.onEndRequest(asked)
	f.k.checkEnd(asked.Add(endWait - 100*time.Millisecond))
	if f.k.finished {
		t.Fatal("ended before endWait")
	}
	f.k.checkEnd(asked.Add(endWait + 100*time.Millisecond))
	if !f.k.finished {
		t.Fatal("expected the stream to end endWait after /v1/end")
	}
	f.has(t, "session 1 was still open after 5s")
}

func TestEndRequestWhileWaitingIsIgnored(t *testing.T) {
	k, _ := newKeeper(&rec{}, testSlate(), 3000, 1500*time.Millisecond, 0, func(string) {})
	k.st = stateWaiting
	k.onEndRequest(time.Unix(1, 0))
	k.checkEnd(time.Unix(100, 0))
	if k.finished {
		t.Fatal("nothing is live: nothing to end")
	}
}

func TestCleanCloseFallbackForAppsThatDontCallEnd(t *testing.T) {
	// No reconnect: the stream ends cleanEndWait after the phone closed the session.
	f := liveKeeper(t, false)
	closed := f.t0.Add(500 * time.Millisecond)
	f.end(1, nil, closed)
	f.k.checkEnd(closed.Add(cleanEndWait - time.Second))
	if f.k.finished {
		t.Fatal("ended before cleanEndWait")
	}
	f.k.checkEnd(closed.Add(cleanEndWait + time.Second))
	if !f.k.finished {
		t.Fatal("expected the fallback to end the stream")
	}
	f.has(t, "stream ended by the streamer (the phone closed session 1 and nothing reconnected within 20s)")

	// A reconnect cancels it.
	f = liveKeeper(t, false)
	f.end(1, nil, closed)
	f.connect(2)
	f.k.checkEnd(closed.Add(time.Hour))
	if f.k.finished {
		t.Fatal("the phone reconnected: not the end")
	}
	f.has(t, "session 2 connected: the phone is reconnecting, not ending")

	// A session that times out (a dropout, not the phone closing it) never starts the fallback.
	f = liveKeeper(t, false)
	f.end(1, nil, f.t0.Add(31*time.Second))
	f.k.checkEnd(f.t0.Add(time.Hour))
	if f.k.finished {
		t.Fatal("a timeout is a dropout: only the hold timer ends it")
	}
}

// The second interruption of the first stream, replayed: two handshakes at once (sessions 2 and 3), the stream on
// session 3, then the duplicate times out and the phone closes session 3 to reconnect.
func TestDuplicateAndSupersededSessionsDontAffectTheLiveOne(t *testing.T) {
	for _, appEnds := range []bool{true, false} {
		f := liveKeeper(t, appEnds)
		at := f.t0.Add(time.Minute)
		f.end(1, errors.New("astits: building new data failed"), at) // session 1 lost
		f.connect(2)
		f.connect(3)
		f.frame(3, true, at.Add(6*time.Second))
		if f.k.st != stateLive || f.k.session != 3 {
			t.Fatalf("expected live on session 3, st=%v session=%d", f.k.st, f.k.session)
		}
		f.end(2, nil, at.Add(36*time.Second)) // the duplicate that never carried the stream
		if f.k.st != stateLive || f.k.finished || !f.k.cleanEndAt.IsZero() {
			t.Fatalf("the duplicate session's end changed the keeper (st=%v finished=%v)", f.k.st, f.k.finished)
		}
		f.has(t, "session 2 ended, but session 3 is the live one: ignored")
		f.frame(3, false, at.Add(36*time.Second))
		f.end(3, nil, at.Add(36100*time.Millisecond)) // the phone reconnects: closes session 3 cleanly
		if f.k.finished || f.k.st != stateSlate {
			t.Fatalf("a reconnect must show the slate, not end the stream (st=%v finished=%v)", f.k.st, f.k.finished)
		}
		f.connect(4)
		f.frame(4, true, at.Add(38*time.Second))
		f.k.checkEnd(at.Add(time.Hour))
		if f.k.finished || f.k.st != stateLive || f.k.session != 4 {
			t.Fatalf("expected live on session 4 (appEnds=%v): st=%v session=%d finished=%v", appEnds, f.k.st, f.k.session, f.k.finished)
		}
		// Session 3's old data still draining changes nothing.
		f.frame(3, true, at.Add(38*time.Second))
		if f.k.session != 4 {
			t.Fatal("an older session took over again")
		}
	}
}

// The duplicate is the newer handshake and the stream stays on the older one: the newer one ending is ignored too.
func TestNewerSessionThatNeverCarriedTheStreamIsIgnored(t *testing.T) {
	f := liveKeeper(t, true)
	f.connect(2)
	f.end(2, nil, f.t0.Add(30*time.Second))
	if f.k.st != stateLive || f.k.finished {
		t.Fatal("session 2 never carried the stream; its end must not matter")
	}
	f.has(t, "but session 1 is the live one: ignored")
}

func TestEndRequestFiles(t *testing.T) {
	dir := t.TempDir()
	if appCallsEnd(dir) {
		t.Fatal("no request yet")
	}
	if _, ok := takeEndRequest(dir); ok {
		t.Fatal("no request yet")
	}
	now := time.Now()
	if err := requestEnd(dir, now); err != nil {
		t.Fatal(err)
	}
	if !appCallsEnd(dir) {
		t.Fatal("expected the app to be recorded as calling /v1/end")
	}
	at, ok := takeEndRequest(dir)
	if !ok || at.Sub(now).Abs() > time.Millisecond {
		t.Fatalf("got %v %v, want %v", at, ok, now)
	}
	if _, ok := takeEndRequest(dir); ok {
		t.Fatal("a request must be taken only once")
	}
	if !appCallsEnd(dir) {
		t.Fatal("app-calls-end must stay")
	}
}

func TestWatchEndRequestsDropsStaleOnes(t *testing.T) {
	dir := t.TempDir()
	started := time.Now()
	_ = requestEnd(dir, started.Add(-time.Second)) // an earlier stream's
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	ends := make(chan struct{}, 1)
	go watchEndRequests(ctx, dir, started, ends)
	select {
	case <-ends:
		t.Fatal("a request from before the keeper started was passed on")
	case <-time.After(3 * endPoll):
	}
	_ = requestEnd(dir, time.Now())
	select {
	case <-ends:
	case <-time.After(2 * time.Second):
		t.Fatal("the request was not passed on")
	}
}

// demuxLive skips a damaged PES and carries on; demuxAUs (slates) still refuses it. The damage is the first stream's:
// an audio PES whose declared length runs past the data that arrived.
func TestDemuxLiveSkipsDamagedPES(t *testing.T) {
	var buf bytes.Buffer
	out := newTSOut(&buf)
	for i := int64(0); i < 10; i++ {
		_ = out.write(&au{kind: kindVideo, data: []byte{0, 0, 0, 1, 0x65, 0x88, byte(i)}, key: true}, 9000+i*3000, 9000+i*3000)
		a := adts(4)
		_ = out.write(&au{kind: kindAudio, data: a, dur: adtsDuration(a)}, 9000+i*3000, 9000+i*3000)
	}
	ts := buf.Bytes()
	// Find the 4th audio PES start and make its PES_packet_length claim 1000 bytes more than it has.
	n := 0
	for i := 0; i+188 <= len(ts); i += 188 {
		p := ts[i : i+188]
		if uint16(p[1]&0x1f)<<8|uint16(p[2]) != outAudioPID || p[1]&0x40 == 0 {
			continue
		}
		if n++; n == 4 {
			off := 4
			if p[3]&0x20 != 0 {
				off += 1 + int(p[4])
			}
			l := int(p[off+4])<<8 | int(p[off+5]) + 1000
			p[off+4], p[off+5] = byte(l>>8), byte(l)
			break
		}
	}
	if n != 4 {
		t.Fatal("test stream has too few audio PES")
	}

	var strict int
	if err := demuxAUs(context.Background(), bytes.NewReader(ts), func(*au) { strict++ }); err == nil {
		t.Fatal("demuxAUs should refuse damaged data (slates must be whole)")
	}

	var v, a int
	var skipped []error
	err := demuxLive(context.Background(), bytes.NewReader(ts), func(x *au) {
		if x.kind == kindVideo {
			v++
		} else {
			a++
		}
	}, func(err error) { skipped = append(skipped, err) })
	if err != nil {
		t.Fatalf("demuxLive ended on damaged data: %v", err)
	}
	if len(skipped) != 1 || v != 10 || a != 9 {
		t.Fatalf("got %d video, %d audio, %d skipped (%v); want 10, 9, 1", v, a, len(skipped), skipped)
	}
}

// Garbage that isn't MPEG-TS at all still ends the session instead of being skipped forever.
func TestDemuxLiveGivesUpOnGarbage(t *testing.T) {
	junk := bytes.Repeat([]byte{0x12, 0x34, 0x56, 0x78}, 188*(maxSkipsInARow+50)/4)
	skips := 0
	err := demuxLive(context.Background(), bytes.NewReader(junk), func(*au) {}, func(error) { skips++ })
	if err == nil || skips != maxSkipsInARow {
		t.Fatalf("err=%v skips=%d", err, skips)
	}
}
