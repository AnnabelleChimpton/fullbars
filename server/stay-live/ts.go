// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bufio"
	"context"
	"errors"
	"io"

	"github.com/asticode/go-astits"
)

type kind int

const (
	kindVideo kind = iota
	kindAudio
)

// au is one access unit (a PES payload) with 90 kHz timestamps.
type au struct {
	kind     kind
	pts, dts int64
	data     []byte
	key      bool  // video only: contains an IDR slice
	dur      int64 // audio only: duration of the ADTS frames in this PES (90 kHz); 0 if unknown
}

var adtsRates = [16]int64{96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350}

// adtsDuration sums the durations of the ADTS frames in a PES payload. ffmpeg packs several AAC frames per PES, so
// a PES can cover hundreds of milliseconds; looping or splicing audio needs the real figure, not "one frame".
func adtsDuration(b []byte) int64 {
	var samples, rate int64
	for i := 0; i+7 <= len(b); {
		if b[i] != 0xFF || b[i+1]&0xF0 != 0xF0 {
			break
		}
		r := adtsRates[(b[i+2]>>2)&0x0F]
		if r == 0 {
			break
		}
		flen := int(b[i+3]&0x03)<<11 | int(b[i+4])<<3 | int(b[i+5])>>5
		if flen < 7 {
			break
		}
		samples += 1024
		rate = r
		i += flen
	}
	if rate == 0 {
		return 0
	}
	return samples * 90000 / rate
}

// demuxAUs reads MPEG-TS and calls emit for every H.264 / AAC PES. Returns when the reader ends. Damaged data is an
// error: slates are looped for a whole stream, so they must be whole.
func demuxAUs(ctx context.Context, r io.Reader, emit func(*au)) error {
	return demux(ctx, r, emit, nil)
}

// demuxLive is demuxAUs for the live feed. A PES that can't be parsed (TS packets lost or dropped too late on the
// way, e.g. "slice length is 2568, offset 5493 is invalid") is reported to skipped and demuxing carries on with the
// next TS packets: the TS layer is still in step (SRT carries whole 188-byte packets), and one broken audio or video
// frame is a glitch, where ending the session took the stream off air. A failing reader still ends it, and so does
// a run of maxSkipsInARow errors with nothing good between them (that isn't a damaged stream, it isn't MPEG-TS).
func demuxLive(ctx context.Context, r io.Reader, emit func(*au), skipped func(error)) error {
	return demux(ctx, r, emit, skipped)
}

const maxSkipsInARow = 100

// readTracker remembers the first error its reader returned, so the demuxer's errors can be told apart: the input
// failing (end the session) or the data being damaged (skip it).
type readTracker struct {
	r   io.Reader
	err error
}

func (t *readTracker) Read(p []byte) (int, error) {
	n, err := t.r.Read(p)
	if err != nil && t.err == nil {
		t.err = err
	}
	return n, err
}

func demux(ctx context.Context, r io.Reader, emit func(*au), skipped func(error)) error {
	in := &readTracker{r: r}
	// astits detects the packet size by peeking, which it can only do on a *bufio.Reader (or by seeking back);
	// without one it would lose the first packets.
	dmx := astits.NewDemuxer(ctx, bufio.NewReader(in))
	var vpid, apid uint16
	inARow := 0
	for {
		d, err := dmx.NextData()
		if err != nil {
			if errors.Is(err, astits.ErrNoMorePackets) {
				return nil
			}
			if skipped == nil || in.err != nil || ctx.Err() != nil || inARow >= maxSkipsInARow {
				return err
			}
			inARow++
			skipped(err)
			continue
		}
		inARow = 0
		if d.PMT != nil {
			for _, es := range d.PMT.ElementaryStreams {
				switch es.StreamType {
				case astits.StreamTypeH264Video:
					vpid = es.ElementaryPID
				case astits.StreamTypeAACAudio:
					apid = es.ElementaryPID
				}
			}
			continue
		}
		if d.PES == nil || d.PES.Header == nil || d.PES.Header.OptionalHeader == nil || d.PES.Header.OptionalHeader.PTS == nil {
			continue
		}
		oh := d.PES.Header.OptionalHeader
		pts := oh.PTS.Base
		dts := pts
		if oh.DTS != nil {
			dts = oh.DTS.Base
		}
		data := append([]byte(nil), d.PES.Data...)
		switch {
		case vpid != 0 && d.PID == vpid:
			emit(&au{kind: kindVideo, pts: pts, dts: dts, data: data, key: hasIDR(data)})
		case apid != 0 && d.PID == apid:
			emit(&au{kind: kindAudio, pts: pts, dts: pts, data: data, dur: adtsDuration(data)})
		}
	}
}

// hasIDR scans an Annex-B H.264 access unit for an IDR slice (nal_unit_type 5).
func hasIDR(b []byte) bool {
	for i := 0; i+3 < len(b); i++ {
		if b[i] == 0 && b[i+1] == 0 && b[i+2] == 1 {
			if b[i+3]&0x1f == 5 {
				return true
			}
			i += 2
		}
	}
	return false
}

// tablesFirst returns the TS with copies of its first PAT and PMT packets moved to the front. A live muxer
// (RootEncoder) repeats the tables periodically rather than leading with them, and a demuxer can't attribute any
// packet before the first PAT+PMT. For live video that only delays the first keyframe; for a one-GOP slate it loses
// the only one. Input must be 188-byte aligned (SRT payloads are 7 packets); otherwise it is returned unchanged.
func tablesFirst(ts []byte) []byte {
	const n = 188
	if len(ts) < n || len(ts)%n != 0 || ts[0] != 0x47 {
		return ts
	}
	pid := func(p []byte) uint16 { return uint16(p[1]&0x1f)<<8 | uint16(p[2]) }
	payload := func(p []byte) []byte {
		off := 4
		if p[3]&0x20 != 0 { // adaptation field
			off += 1 + int(p[4])
		}
		if off >= n {
			return nil
		}
		return p[off:]
	}
	var pat, pmt []byte
	var pmtPID uint16 = 0xffff
	for i := 0; i+n <= len(ts) && (pat == nil || pmt == nil); i += n {
		p := ts[i : i+n]
		if p[0] != 0x47 {
			return ts
		}
		switch id := pid(p); {
		case id == 0 && pat == nil && p[1]&0x40 != 0:
			pl := payload(p)
			if len(pl) < 1 {
				continue
			}
			sec := pl[1+int(pl[0]):] // skip pointer field
			if len(sec) < 12 {
				continue
			}
			for e := 8; e+4 <= len(sec)-4 && e+4 <= 3+int(uint16(sec[1]&0x0f)<<8|uint16(sec[2]))-4; e += 4 {
				if prog := uint16(sec[e])<<8 | uint16(sec[e+1]); prog != 0 {
					pmtPID = uint16(sec[e+2]&0x1f)<<8 | uint16(sec[e+3])
					pat = p
					break
				}
			}
		case pat != nil && id == pmtPID && p[1]&0x40 != 0:
			pmt = p
		}
	}
	if pat == nil || pmt == nil {
		return ts
	}
	out := make([]byte, 0, len(ts)+2*n)
	out = append(append(append(out, pat...), pmt...), ts...)
	return out
}

// paramSets returns the first SPS and first PPS NAL units (type 7 and 8, with start codes) of an Annex-B access
// unit, in that order; nil if it has neither.
func paramSets(b []byte) []byte {
	var sps, pps []byte
	start := -1
	flush := func(end int) {
		if start >= 0 && start < end {
			if t := b[start] & 0x1f; (t == 7 && sps == nil) || (t == 8 && pps == nil) {
				nal := b[start:end]
				for len(nal) > 0 && nal[len(nal)-1] == 0 { // trailing zero of the next 4-byte start code
					nal = nal[:len(nal)-1]
				}
				nal = append([]byte{0, 0, 1}, nal...)
				if t == 7 {
					sps = nal
				} else {
					pps = nal
				}
			}
		}
	}
	for i := 0; i+2 < len(b); i++ {
		if b[i] == 0 && b[i+1] == 0 && b[i+2] == 1 {
			flush(i)
			start = i + 3
			i += 2
		}
	}
	flush(len(b))
	return append(sps, pps...)
}

const (
	outVideoPID = 256
	outAudioPID = 257
)

type tsOut struct{ mx *astits.Muxer }

func newTSOut(w io.Writer) *tsOut {
	mx := astits.NewMuxer(context.Background(), w)
	_ = mx.AddElementaryStream(astits.PMTElementaryStream{ElementaryPID: outVideoPID, StreamType: astits.StreamTypeH264Video})
	_ = mx.AddElementaryStream(astits.PMTElementaryStream{ElementaryPID: outAudioPID, StreamType: astits.StreamTypeAACAudio})
	mx.SetPCRPID(outVideoPID)
	return &tsOut{mx: mx}
}

func (t *tsOut) write(a *au, pts, dts int64) error {
	pid, sid := uint16(outVideoPID), uint8(0xE0)
	if a.kind == kindAudio {
		pid, sid = outAudioPID, 0xC0
	}
	oh := &astits.PESOptionalHeader{MarkerBits: 2, DataAlignmentIndicator: true}
	if a.kind == kindVideo && dts != pts {
		oh.PTSDTSIndicator = astits.PTSDTSIndicatorBothPresent
		oh.PTS = &astits.ClockReference{Base: pts}
		oh.DTS = &astits.ClockReference{Base: dts}
	} else {
		oh.PTSDTSIndicator = astits.PTSDTSIndicatorOnlyPTS
		oh.PTS = &astits.ClockReference{Base: pts}
	}
	md := &astits.MuxerData{
		PID: pid,
		PES: &astits.PESData{Header: &astits.PESHeader{StreamID: sid, OptionalHeader: oh}, Data: a.data},
	}
	if a.kind == kindVideo {
		md.AdaptationField = &astits.PacketAdaptationField{
			RandomAccessIndicator: a.key,
			HasPCR:                true,
			PCR:                   &astits.ClockReference{Base: dts - 9000}, // 100 ms ahead of decode
		}
	}
	_, err := t.mx.WriteData(md)
	return err
}
