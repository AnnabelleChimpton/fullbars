// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bufio"
	"errors"
	"fmt"
	"io"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"
)

// sink is where the keeper's MPEG-TS goes. It opens on the first write, i.e. when the phone actually goes live
// (with -wait-live), and decides then: the output the app set (or OUTPUT_URL) through ffmpeg -c copy, or else a
// recording. Deciding at go-live means an output the app sets while the server waits applies to this stream.
//
// Once open, Write never fails and never blocks for long: an output that breaks (Twitch ingest blip, stale key,
// full disk) is reported as an "OUTPUT ERROR" event and retried or dropped, while the keeper keeps the phone's
// session and the slate running. An error here used to end the keeper, which dropped the phone's connection too.
type sink struct {
	dir     string // data dir: output.url, recordings
	env     string // OUTPUT_URL fallback
	recKeep int    // recordings kept (the newest), counting the one being made
	events  func(string)
	pull    func() (*pullServer, error) // the "My OBS" output's SRT server (started on demand)
	w       io.Writer
	close   func() error
}

func (s *sink) Write(p []byte) (int, error) {
	if s.w == nil {
		s.open()
	}
	return s.w.Write(p)
}

func (s *sink) open() {
	s.w, s.close = io.Discard, func() error { return nil }
	u, _ := currentOutput(s.dir, s.env)
	switch {
	case u == obsMode:
		p, err := s.pull()
		if err != nil {
			s.events(fmt.Sprintf("OUTPUT ERROR: home OBS can't connect: %v", err))
			return
		}
		s.w = p
		s.events("output: served to home OBS")
	case u == "":
		s.openRecording()
	default:
		o := newFFmpegOut(u, s.events)
		s.w, s.close = o, o.Close
		mainOut.Store(o)
		s.events("output: " + describeURL(u))
	}
	s.openExtras(u)
}

// openExtras adds the "Also stream to" outputs next to the main one, when this is a real stream.
func (s *sink) openExtras(main string) {
	list := loadExtras(s.dir)
	if len(list) == 0 {
		return
	}
	if !extrasAllowed(main) {
		s.events(fmt.Sprintf("extra outputs: not sent (%d set): this stream isn't going anywhere public", len(list)))
		return
	}
	f := &fanOut{main: s.w}
	for _, e := range list {
		ev := extraEvents(e.Name, s.events)
		o := newExtraOut(e.URL, e.Name, ev)
		f.extras = append(f.extras, o)
		ev("output: " + describeURL(e.URL))
	}
	closeMain := s.close
	s.w = f
	s.close = func() error {
		f.closeExtras()
		return closeMain()
	}
}

// Recordings are a test mode (no output set): they must never fill the server's disk.
const (
	recMinFree       = 2 << 30 // don't start a recording with less free than this...
	recMinFreePct    = 10      // ...or less than this share of the disk
	recStopFree      = 1 << 30 // stop one that has brought free space down to this
	recCheckInterval = 64 << 20
)

func (s *sink) openRecording() {
	keep := s.recKeep
	if keep < 1 {
		keep = 5
	}
	pruneRecordings(s.dir, keep-1, s.events)
	if free, total, err := diskSpace(s.dir); err == nil && (free < recMinFree || free*100 < total*recMinFreePct) {
		s.events(fmt.Sprintf("OUTPUT ERROR: not recording: only %.1f GB free on the server", float64(free)/(1<<30)))
		return
	}
	path := filepath.Join(s.dir, "rec-"+time.Now().UTC().Format("20060102-150405")+".ts")
	f, err := os.Create(path)
	if err != nil {
		s.events("OUTPUT ERROR: can't record: " + err.Error())
		return
	}
	r := &recorder{f: f, dir: s.dir, events: s.events}
	s.w, s.close = r, f.Close
	s.events("output: recording to " + filepath.Base(path))
}

// Close ends the output: ffmpeg gets the end of its input so it finishes the FLV cleanly.
func (s *sink) Close() error {
	if s.close == nil {
		return nil
	}
	return s.close()
}

// recorder writes a recording until the disk runs low or a write fails, then reports it once and drops the rest.
type recorder struct {
	f       *os.File
	dir     string
	events  func(string)
	stopped bool
	since   int64 // bytes since the last free-space check
}

func (r *recorder) Write(p []byte) (int, error) {
	if r.stopped {
		return len(p), nil
	}
	if _, err := r.f.Write(p); err != nil {
		r.stopped = true
		r.events("OUTPUT ERROR: recording stopped: " + err.Error())
		return len(p), nil
	}
	if r.since += int64(len(p)); r.since >= recCheckInterval {
		r.since = 0
		if free, _, err := diskSpace(r.dir); err == nil && free < recStopFree {
			r.stopped = true
			r.events(fmt.Sprintf("OUTPUT ERROR: recording stopped: only %.1f GB free on the server", float64(free)/(1<<30)))
		}
	}
	return len(p), nil
}

// pruneRecordings deletes all but the newest keep recordings (rec-YYYYMMDD-HHMMSS.ts sorts by time).
func pruneRecordings(dir string, keep int, events func(string)) {
	names, err := filepath.Glob(filepath.Join(dir, "rec-*.ts"))
	if err != nil || len(names) <= keep {
		return
	}
	sort.Strings(names)
	for _, n := range names[:len(names)-max(keep, 0)] {
		if err := os.Remove(n); err == nil {
			events("deleted an old recording: " + filepath.Base(n))
		}
	}
}

func diskSpace(dir string) (free, total uint64, err error) {
	var st syscall.Statfs_t
	if err := syscall.Statfs(dir, &st); err != nil {
		return 0, 0, err
	}
	return uint64(st.Bavail) * uint64(st.Bsize), uint64(st.Blocks) * uint64(st.Bsize), nil
}

// ffmpegOut remuxes the TS to an RTMP(S)/SRT output with ffmpeg, and restarts ffmpeg when it fails. The keeper's
// writes go through a bounded queue to a writer goroutine, so a stalled ffmpeg (or a slow ingest) can only cost
// dropped output, never a frozen keeper. After a restart, or after the queue overflowed, output resumes at the next
// video keyframe: the muxer writes PAT/PMT right before every keyframe, and a copy of them is put in front, so the
// new ffmpeg (and Twitch) start on a clean, decodable picture.
type ffmpegOut struct {
	url    string
	label  string // an extra output's name ("Kick"); "" for the main output
	events func(string)
	newCmd func(url string) *exec.Cmd

	minBackoff, maxBackoff time.Duration
	healthy                time.Duration // a run this long resets the backoff and the quick-failure count
	settled                time.Duration // a restarted ffmpeg running this long counts as reconnected
	stallKill              time.Duration // a single write blocked this long means ffmpeg hung: kill it

	// Keeper side (Write).
	carry    []byte
	gen      int64 // the ffmpeg run this side is synced to
	synced   bool
	pat, pmt []byte
	pmtPID   int
	dropped  time.Time // last "falling behind" event
	behind   bool      // overflowed, and still waiting for the keyframe to resume on
	closed   bool

	// Shared.
	live   atomic.Int64 // the running ffmpeg's generation; 0 while there is none
	queued atomic.Int64 // bytes in ch
	ch     chan tsChunk
	done   chan struct{}

	// Counters for the stats (written by the keeper side and the loop, read by the stats goroutine).
	chunks       atomic.Int64 // queue entries: about one per access unit
	overflows    atomic.Int64 // times the queue was full and output skipped to the next keyframe
	droppedBytes atomic.Int64 // bytes never sent: the queue was full, or output waited for a keyframe after that
	peakQueued   atomic.Int64 // the most bytes ever waiting in the queue
	restarts     atomic.Int64 // ffmpeg runs that ended on their own and were started again
}

// mainOut is the main output's ffmpeg, for the stats; nil until one opens (or when the output isn't ffmpeg).
var mainOut atomic.Pointer[ffmpegOut]

// outputCounters is what the stats report about the main output.
type outputCounters struct {
	Overflows    int64 `json:"overflows"`
	DroppedBytes int64 `json:"droppedBytes"`
	PeakQueued   int64 `json:"peakQueuedBytes"`
	Queued       int64 `json:"queuedBytes"`
	Restarts     int64 `json:"restarts"`
}

func (o *ffmpegOut) counters() outputCounters {
	if o == nil {
		return outputCounters{}
	}
	return outputCounters{Overflows: o.overflows.Load(), DroppedBytes: o.droppedBytes.Load(), PeakQueued: o.peakQueued.Load(),
		Queued: o.queued.Load(), Restarts: o.restarts.Load()}
}

type tsChunk struct {
	gen  int64
	data []byte
}

const (
	tsPacket = 188
	// maxQueueBytes is how far ffmpeg may fall behind before output skips ahead: about 5 s at 6 Mbps. The channel
	// has room for that many bytes even in single packets, so the byte budget is the limit that binds.
	maxQueueBytes = 4 << 20
	quickFailures = 5
)

// ffmpegCmd builds an output's ffmpeg; tests swap it for a shell command.
var ffmpegCmd = ffmpegCommand

func ffmpegCommand(u string) *exec.Cmd {
	format := "flv"
	if strings.HasPrefix(u, "srt://") {
		format = "mpegts"
	}
	// -rw_timeout (µs): a network write stuck for 10 s ends ffmpeg, so a dead ingest is noticed and retried
	// instead of hanging the output forever.
	return exec.Command("ffmpeg", "-nostdin", "-loglevel", "warning", "-f", "mpegts", "-i", "pipe:0",
		"-map", "0", "-c", "copy", "-flvflags", "no_duration_filesize", "-rw_timeout", "10000000", "-f", format, u)
}

func newFFmpegOut(u string, events func(string)) *ffmpegOut {
	o := newFFmpegOutWith(u, events, ffmpegCmd)
	o.start()
	return o
}

func newExtraOut(u, label string, events func(string)) *ffmpegOut {
	o := newFFmpegOutWith(u, events, ffmpegCmd)
	o.label = label
	o.start()
	return o
}

func newFFmpegOutWith(u string, events func(string), cmd func(string) *exec.Cmd) *ffmpegOut {
	return &ffmpegOut{
		url: u, events: events, newCmd: cmd,
		minBackoff: time.Second, maxBackoff: 30 * time.Second, healthy: 30 * time.Second,
		settled: 10 * time.Second, stallKill: 20 * time.Second,
	}
}

func (o *ffmpegOut) start() {
	o.ch = make(chan tsChunk, maxQueueBytes/tsPacket+1)
	o.done = make(chan struct{})
	o.live.Store(1) // the first run's data queues up while ffmpeg starts: the go-live keyframe isn't lost
	go o.loop()
}

// Write queues whole TS packets for ffmpeg. It never fails and never blocks.
func (o *ffmpegOut) Write(p []byte) (int, error) {
	if o.closed {
		return len(p), nil
	}
	o.carry = append(o.carry, p...)
	n := len(o.carry) / tsPacket * tsPacket
	pkts := o.carry[:n]
	defer func() { o.carry = append(o.carry[:0], o.carry[n:]...) }()
	if n == 0 {
		return len(p), nil
	}
	g := o.live.Load()
	if g == 0 || g != o.gen {
		o.gen, o.synced = g, false
	}
	var out []byte
	if o.synced {
		out = append([]byte(nil), pkts...)
	} else {
		k := o.scan(pkts)
		if g == 0 || k < 0 || o.pat == nil || o.pmt == nil {
			if o.behind {
				o.droppedBytes.Add(int64(n))
			}
			return len(p), nil // ffmpeg is down, or waiting for a keyframe
		}
		out = make([]byte, 0, 2*tsPacket+n-k)
		out = append(append(append(out, o.pat...), o.pmt...), pkts[k:]...)
		if o.behind {
			o.droppedBytes.Add(int64(k))
		}
		o.synced, o.behind = true, false
	}
	if o.queued.Load()+int64(len(out)) > maxQueueBytes {
		o.overflow(len(out))
		return len(p), nil
	}
	select {
	case o.ch <- tsChunk{gen: g, data: out}:
		if q := o.queued.Add(int64(len(out))); q > o.peakQueued.Load() {
			o.peakQueued.Store(q) // only this side raises it
		}
		o.chunks.Add(1)
	default:
		o.overflow(len(out))
	}
	return len(p), nil
}

// overflow: ffmpeg can't keep up. Skip to the next keyframe rather than send a broken picture.
func (o *ffmpegOut) overflow(n int) {
	o.overflows.Add(1)
	o.droppedBytes.Add(int64(n))
	o.synced, o.behind = false, true
	if time.Since(o.dropped) > time.Minute {
		o.dropped = time.Now()
		o.events("output is falling behind (slow connection to the ingest?); skipping to the next keyframe")
	}
}

// scan remembers the PAT and PMT and returns the offset of the first video keyframe packet (random access
// indicator set by the keeper's muxer), or -1.
func (o *ffmpegOut) scan(pkts []byte) int {
	for i := 0; i+tsPacket <= len(pkts); i += tsPacket {
		pkt := pkts[i : i+tsPacket]
		if pkt[0] != 0x47 {
			continue
		}
		pid := int(pkt[1]&0x1f)<<8 | int(pkt[2])
		pusi := pkt[1]&0x40 != 0
		switch {
		case pid == 0 && pusi:
			o.pat = append(o.pat[:0], pkt...)
			if v := pmtPIDFromPAT(pkt); v > 0 {
				o.pmtPID = v
			}
		case o.pmtPID != 0 && pid == o.pmtPID && pusi:
			o.pmt = append(o.pmt[:0], pkt...)
		case pid == outVideoPID && pusi && pkt[3]&0x20 != 0 && pkt[4] > 0 && pkt[5]&0x40 != 0:
			return i
		}
	}
	return -1
}

// Close sends ffmpeg the end of its input (the queued data first) and waits briefly for it to finish.
func (o *ffmpegOut) Close() error {
	if o.closed {
		return nil
	}
	o.closed = true
	close(o.ch)
	select {
	case <-o.done:
	case <-time.After(10 * time.Second):
	}
	return nil
}

var errOutputClosed = errors.New("output closed")

// loop runs ffmpeg, again and again, until Close: 1 s, 2 s, 4 s... up to 30 s between quick failures.
func (o *ffmpegOut) loop() {
	defer close(o.done)
	backoff := o.minBackoff
	quick := 0
	refusedSaid := false
	failedBefore := false
	for gen := int64(1); ; gen++ {
		startedAt := time.Now()
		o.live.Store(gen)
		reason, err := o.runOnce(gen, failedBefore)
		o.live.Store(0)
		if errors.Is(err, errOutputClosed) {
			return
		}
		failedBefore = true
		o.restarts.Add(1)
		if time.Since(startedAt) >= o.healthy {
			quick, backoff, refusedSaid = 0, o.minBackoff, false
		} else {
			quick++
		}
		wait := backoff
		switch {
		case quick >= quickFailures && !refusedSaid:
			refusedSaid = true
			who := "The output"
			check := "check its URL and key"
			switch {
			case o.label != "":
				who, check = o.label, "check its stream key"
			case summarize(o.url).Kind == "twitch":
				who, check = "Twitch", "check the stream key"
			}
			o.events(fmt.Sprintf("OUTPUT ERROR: %s refused the stream (%s)", who, check))
		case refusedSaid:
			// Already explained; keep retrying quietly so the explanation stays the server's status.
			log.Printf("output: %s; retrying in %v", reason, wait)
		default:
			o.events(fmt.Sprintf("OUTPUT ERROR: %s; reconnecting in %v", reason, wait))
		}
		if !o.idle(wait) {
			return
		}
		backoff = min(backoff*2, o.maxBackoff)
	}
}

// idle waits out a backoff, discarding whatever is still queued for the dead ffmpeg. False once closed.
func (o *ffmpegOut) idle(d time.Duration) bool {
	t := time.NewTimer(d)
	defer t.Stop()
	for {
		select {
		case c, ok := <-o.ch:
			if !ok {
				return false
			}
			o.queued.Add(-int64(len(c.data)))
		case <-t.C:
			return true
		}
	}
}

// runOnce runs one ffmpeg until it exits (reason: its last error line, redacted) or the output is closed.
func (o *ffmpegOut) runOnce(gen int64, restarted bool) (reason string, err error) {
	cmd := o.newCmd(o.url)
	stdin, err := cmd.StdinPipe()
	if err != nil {
		return "can't start ffmpeg: " + err.Error(), err
	}
	stderr, err := cmd.StderrPipe()
	if err != nil {
		return "can't start ffmpeg: " + err.Error(), err
	}
	if err := cmd.Start(); err != nil {
		return "can't start ffmpeg: " + err.Error(), err
	}
	var mu sync.Mutex
	var hung atomic.Bool
	last := ""
	exited := make(chan error, 1)
	go func() { // ffmpeg's errors can quote the output URL, i.e. the stream key: never log it unredacted
		sc := bufio.NewScanner(stderr)
		for sc.Scan() {
			line := redactKeys(sc.Text())
			log.Printf("ffmpeg: %s", line)
			if strings.TrimSpace(line) != "" {
				mu.Lock()
				last = line
				mu.Unlock()
			}
		}
		exited <- cmd.Wait() // only after stderr is fully read
	}()
	why := func(exitErr error) string {
		mu.Lock()
		defer mu.Unlock()
		r := last
		if hung.Load() {
			r = "the connection to the output stopped moving"
		} else if r == "" {
			r = "ffmpeg stopped"
			if exitErr != nil {
				r += " (" + exitErr.Error() + ")"
			}
		}
		if len(r) > 200 {
			r = r[:200] + "…"
		}
		return r
	}
	kill := func() { _ = cmd.Process.Kill() }

	if restarted {
		t := time.AfterFunc(o.settled, func() {
			select {
			case <-o.done:
			default:
				if o.live.Load() == gen {
					o.events("output: reconnected to " + describeURL(o.url))
				}
			}
		})
		defer t.Stop()
	}
	stall := time.AfterFunc(time.Hour, func() { hung.Store(true); kill() })
	stall.Stop()
	defer stall.Stop()
	for {
		select {
		case c, ok := <-o.ch:
			if !ok { // closing: let ffmpeg finish the file cleanly
				_ = stdin.Close()
				select {
				case <-exited:
				case <-time.After(5 * time.Second):
					kill()
					<-exited
				}
				return "", errOutputClosed
			}
			o.queued.Add(-int64(len(c.data)))
			if c.gen != gen {
				continue
			}
			stall.Reset(o.stallKill)
			_, werr := stdin.Write(c.data)
			stall.Stop()
			if werr != nil { // ffmpeg is gone (or was killed for hanging)
				kill()
				return why(<-exited), nil
			}
		case e := <-exited:
			return why(e), nil
		}
	}
}

var (
	keyInPath  = regexp.MustCompile(`((?:rtmps?|srt)://[^\s/?]+/[^\s/?]+/)[^\s'"?]+`)
	keyInQuery = regexp.MustCompile(`((?:rtmps?|srt)://[^\s?]+)\?[^\s'"]+`)
)

// redactKeys removes secrets from URLs in a log line: whatever follows "scheme://host/app/" (Twitch: the stream
// key) and any query string (SRT outputs carry stream ids and passphrases there).
func redactKeys(line string) string {
	line = keyInQuery.ReplaceAllString(line, "${1}?<redacted>")
	return keyInPath.ReplaceAllString(line, "${1}<key redacted>")
}
