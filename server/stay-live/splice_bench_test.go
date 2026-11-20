// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"context"
	"fmt"
	"strconv"
	"testing"
	"time"
)

// The keeper's real TS output across live -> slate -> live -> Weak signal slate -> live, checked at packet level the
// way a demuxer and a player see it: continuity counters, PCR, keyframe flags and parameter sets, the video timeline,
// audio holes, and the phone's audio/video offset after every splice back to live.
//
// Every AU carries a tag at the end of its payload: where it came from ('L' live, 'S' BRB slate, 'W' Weak signal
// slate) and its source timestamp, so the output can be traced back to its input.

func srcTag(src byte, ts int64) []byte {
	return append([]byte{src}, fmt.Sprintf("%016x", uint64(ts))...)
}

// readTag returns the tag at the end of a video AU or the first ADTS frame of an audio AU.
func readTag(a *au) (src byte, ts int64, ok bool) {
	b := a.data
	if a.kind == kindAudio {
		if len(b) < 7 {
			return 0, 0, false
		}
		flen := int(b[3]&0x03)<<11 | int(b[4])<<3 | int(b[5])>>5
		if flen > len(b) {
			return 0, 0, false
		}
		b = b[:flen]
	}
	if len(b) < 17 {
		return 0, 0, false
	}
	t := b[len(b)-17:]
	v, err := strconv.ParseUint(string(t[1:]), 16, 64)
	if err != nil {
		return 0, 0, false
	}
	return t[0], int64(v), true
}

// taggedADTS is one 48 kHz stereo AAC-LC ADTS frame whose payload is the tag.
func taggedADTS(src byte, ts int64) []byte {
	p := srcTag(src, ts)
	flen := 7 + len(p)
	return append([]byte{0xFF, 0xF1, 0x4C, 0x80 | byte(flen>>11)&0x03, byte(flen >> 3), byte(flen&7) << 5, 0x00}, p...)
}

// benchSlate: 2 s of 30 fps video (a keyframe, then P frames) and audio in PES of perPES AAC frames.
func benchSlate(src byte, sps byte, perPES int) []*au {
	var s []*au
	for i := int64(0); i < 60; i++ {
		d := append(pFrame(20), srcTag(src, i*3000)...)
		if i == 0 {
			d = append(idr(sps, 0xee), srcTag(src, 0)...)
		}
		s = append(s, &au{kind: kindVideo, pts: i * 3000, dts: i * 3000, key: i == 0, data: d})
	}
	for f := int64(0); f < 96; f += int64(perPES) {
		var d []byte
		for j := int64(0); j < int64(perPES); j++ {
			d = append(d, taggedADTS(src, (f+j)*1920)...)
		}
		s = append(s, &au{kind: kindAudio, pts: f * 1920, dts: f * 1920, data: d, dur: adtsDuration(d)})
	}
	return s
}

// tsPkt is what a player's demuxer sees of one TS packet.
type tsPkt struct {
	pid      uint16
	pusi     bool
	payload  bool
	cc       uint8
	rai      bool
	pcr      int64 // -1 if none
	pesPTS   int64 // video/audio PES start: its timestamps (-1 if none)
	pesDTS   int64
	position int
}

func parsePackets(t *testing.T, ts []byte) []tsPkt {
	t.Helper()
	if len(ts)%tsPacket != 0 {
		t.Fatalf("output is not whole TS packets: %d bytes", len(ts))
	}
	var out []tsPkt
	readTS := func(b []byte) int64 {
		return int64(b[0]>>1&0x07)<<30 | int64(b[1])<<22 | int64(b[2]>>1)<<15 | int64(b[3])<<7 | int64(b[4]>>1)
	}
	for i := 0; i < len(ts); i += tsPacket {
		p := ts[i : i+tsPacket]
		if p[0] != 0x47 {
			t.Fatalf("lost sync at packet %d", i/tsPacket)
		}
		k := tsPkt{pid: uint16(p[1]&0x1f)<<8 | uint16(p[2]), pusi: p[1]&0x40 != 0, payload: p[3]&0x10 != 0, cc: p[3] & 0x0f,
			pcr: -1, pesPTS: -1, pesDTS: -1, position: i / tsPacket}
		if p[3]&0x20 != 0 && p[4] > 0 {
			k.rai = p[5]&0x40 != 0
			if p[5]&0x10 != 0 {
				k.pcr = int64(p[6])<<25 | int64(p[7])<<17 | int64(p[8])<<9 | int64(p[9])<<1 | int64(p[10]>>7)
			}
		}
		if pl := tsPayload(p); k.pusi && pl != nil && len(pl) >= 14 && pl[0] == 0 && pl[1] == 0 && pl[2] == 1 {
			flags := pl[7] >> 6
			if flags&2 != 0 {
				k.pesPTS = readTS(pl[9:])
				k.pesDTS = k.pesPTS
			}
			if flags == 3 && len(pl) >= 19 {
				k.pesDTS = readTS(pl[14:])
			}
		}
		out = append(out, k)
	}
	return out
}

func TestSpliceBench(t *testing.T) {
	for _, perPES := range []int{1, 8} {
		for _, offMs := range []int64{-200, -40, 0, 40, 120} {
			t.Run(fmt.Sprintf("audio %+dms, %d AAC frames per slate PES", offMs, perPES), func(t *testing.T) {
				spliceBench(t, offMs*90, perPES)
			})
		}
	}
}

func spliceBench(t *testing.T, aOff int64, perPES int) {
	var buf bytes.Buffer
	s := newPhoneSimWith(t, benchSlate('S', 0x42, perPES), newTSOut(&buf))
	s.k.installWeak(benchSlate('W', 0x42, perPES))
	s.aOff = aOff
	s.vData = func(key bool, dts int64) []byte {
		if key {
			return append(idr(0x64, 0xee), srcTag('L', dts)...)
		}
		return append(pFrame(200), srcTag('L', dts)...)
	}
	s.aData = func(pts int64) []byte { return taggedADTS('L', pts) }

	s.run(3 * time.Second) // live
	s.sendV, s.sendA = false, false
	s.run(2 * time.Second) // dropout: BRB slate
	s.sendV, s.sendA = true, true
	s.run(3 * time.Second) // live again at the next keyframe
	s.k.onQuality(qualityEvent{session: 1, weak: true, lost: 50}, s.now)
	s.run(3 * time.Second) // Weak signal slate, live data ignored
	s.k.onQuality(qualityEvent{session: 1, weak: false}, s.now)
	s.run(3 * time.Second) // live again
	for _, want := range []string{"SLATE: feed stalled", "WEAK SIGNAL slate", "signal clean again"} {
		if !s.has(want) {
			t.Fatalf("scenario didn't happen: missing %q in %q", want, s.evs)
		}
	}
	if s.k.st != stateLive {
		t.Fatalf("should end live, is %v", s.k.st)
	}

	pkts := parsePackets(t, buf.Bytes())
	var aus []*au
	if err := demuxAUs(context.Background(), bytes.NewReader(buf.Bytes()), func(a *au) { aus = append(aus, a) }); err != nil {
		t.Fatal(err)
	}

	// Continuity counters: +1 per payload packet on each PID, unchanged on adaptation-only packets.
	last := map[uint16]int{}
	for _, p := range pkts {
		if p.pid != outVideoPID && p.pid != outAudioPID {
			continue
		}
		if prev, ok := last[p.pid]; ok {
			want := uint8(prev)
			if p.payload {
				want = (want + 1) & 0x0f
			}
			if p.cc != want {
				t.Fatalf("PID %d: continuity counter %d after %d at packet %d", p.pid, p.cc, prev, p.position)
			}
		}
		last[p.pid] = int(p.cc)
	}

	// Video PES: PCR on the first packet of each, DTS-9000, increasing, no more than 100 ms apart.
	var vstarts []tsPkt
	for _, p := range pkts {
		if p.pid == outVideoPID && p.pusi {
			vstarts = append(vstarts, p)
		}
	}
	var video, audio []*au
	for _, a := range aus {
		if a.kind == kindVideo {
			video = append(video, a)
		} else {
			audio = append(audio, a)
		}
	}
	if len(vstarts) != len(video) || len(video) < 300 {
		t.Fatalf("%d video PES starts, %d video AUs", len(vstarts), len(video))
	}
	for i, p := range vstarts {
		if p.pcr < 0 {
			t.Fatalf("video PES %d has no PCR", i)
		}
		if p.pcr != (p.pesDTS-9000)&(tsWrap-1) {
			t.Fatalf("video PES %d: PCR %d, DTS %d: want DTS-9000", i, p.pcr, p.pesDTS)
		}
		if i > 0 {
			if d := p.pcr - vstarts[i-1].pcr; d <= 0 || d > 9000 {
				t.Fatalf("video PES %d: PCR step %d ticks", i, d)
			}
		}
	}

	// Video timeline frame-exact; keyframe flag and parameter sets exactly on keyframes; every splice on a keyframe.
	splices := 0
	var prevSrc byte
	for i, v := range video {
		src, _, ok := readTag(v)
		if !ok {
			t.Fatalf("video AU %d has no tag", i)
		}
		if i > 0 && v.dts-video[i-1].dts != 3000 {
			t.Fatalf("video DTS step %d at AU %d (%c after %c)", v.dts-video[i-1].dts, i, src, prevSrc)
		}
		if vstarts[i].rai != v.key || (len(paramSets(v.data)) > 0) != v.key {
			t.Fatalf("video AU %d: keyframe %v, random access flag %v, parameter sets %v", i, v.key, vstarts[i].rai, len(paramSets(v.data)) > 0)
		}
		if i > 0 && src != prevSrc {
			splices++
			if !v.key || !vstarts[i].rai {
				t.Fatalf("splice %c -> %c at video AU %d is not on a keyframe", prevSrc, src, i)
			}
		}
		prevSrc = src
	}
	if splices != 4 {
		t.Fatalf("expected 4 splices (live/slate/live/weak/live), saw %d", splices)
	}

	// Audio: never an overlap, never a hole of a whole frame (holes that big are filled with the slate's silence).
	for i := 1; i < len(audio); i++ {
		end := audio[i-1].pts + audio[i-1].dur
		if gap := audio[i].pts - end; gap < -90 || gap >= 1920 {
			s1, _, _ := readTag(audio[i-1])
			s2, _, _ := readTag(audio[i])
			t.Fatalf("audio gap of %d ticks between AU %d (%c) and %d (%c)", gap, i-1, s1, i, s2)
		}
	}

	// After every splice back to live, the phone's audio keeps its offset against its video: within a live
	// stretch every live AU's output timestamp minus its source timestamp is the same, audio and video alike.
	segOff, inSeg, segs := int64(0), false, 0
	all := append([]*au(nil), aus...)
	for _, a := range all {
		src, ts, ok := readTag(a)
		if !ok {
			continue
		}
		if a.kind == kindVideo {
			if src != 'L' {
				inSeg = false
				continue
			}
			if !inSeg {
				inSeg, segOff = true, a.dts-ts
				segs++
			}
		}
		if src != 'L' || !inSeg {
			continue
		}
		out := a.dts
		if a.kind == kindAudio {
			out = a.pts
		}
		if d := out - ts - segOff; d < -90 || d > 90 {
			t.Fatalf("live segment %d: %v AU moved %d ticks against its video (offset %d ms)", segs, a.kind, d, aOff/90)
		}
	}
	if segs != 3 {
		t.Fatalf("expected 3 live stretches, saw %d", segs)
	}
}
