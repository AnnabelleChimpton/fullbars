// SPDX-License-Identifier: Apache-2.0

// stay-live (installed as `brb` in the BRB server image): the BRB server's slate keeper, plus its control API
// (`control`), pairing (`pair`) and home OBS settings (`obs`).
//
// The keeper accepts the stream over SRT (normally from srtla_rec) and writes one continuous MPEG-TS, covering feed
// dropouts with a looped pre-encoded slate, so the RTMP session to Twitch never ends while the phone is in a tunnel.
// With -data the output goes where the app said (ffmpeg -c copy); without it, to stdout (for testing).
// Pure remux: no decoding or encoding.
package main

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"os/signal"
	"path/filepath"
	"strconv"
	"strings"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	srt "github.com/datarhei/gosrt"
)

func main() {
	if len(os.Args) > 1 {
		switch os.Args[1] {
		case "control":
			fs := flag.NewFlagSet("control", flag.ExitOnError)
			listen := fs.String("listen", ":5000", "TLS control API address (TCP)")
			statsListen := fs.String("stats-listen", ":8181", "plain-HTTP NOALBS stats address (secret path); empty = off")
			dir := fs.String("data", "/data", "data directory")
			events := fs.String("events", "/data/events.jsonl", "the keeper's event log")
			_ = fs.Parse(os.Args[2:])
			log.SetFlags(log.Ltime)
			if *statsListen != "" {
				go func() { log.Fatal(runStats(*statsListen, *dir)) }()
			}
			log.Fatal(runControl(*listen, *dir, *events))
		case "obs":
			fs := flag.NewFlagSet("obs", flag.ExitOnError)
			dir := fs.String("data", "/data", "data directory")
			host := fs.String("host", os.Getenv("PUBLIC_HOST"), "this server's public address")
			playPort := fs.Int("play-port", envInt("OBS_PORT", 5001), "UDP port OBS pulls from")
			statsPort := fs.Int("stats-port", envInt("STATS_PORT", 8181), "TCP port of the NOALBS stats")
			_ = fs.Parse(os.Args[2:])
			if err := runObs(*dir, *host, *playPort, *statsPort); err != nil {
				fmt.Fprintln(os.Stderr, "obs:", err)
				os.Exit(1)
			}
			return
		case "pair":
			fs := flag.NewFlagSet("pair", flag.ExitOnError)
			dir := fs.String("data", "/data", "data directory")
			host := fs.String("host", os.Getenv("PUBLIC_HOST"), "this server's public address, as the phone reaches it")
			port := fs.Int("port", envInt("SRTLA_PORT", 5000), "SRTLA port (UDP) = control port (TCP)")
			name := fs.String("name", envOr("SERVER_NAME", "Your BRB server"), "name shown in the app")
			reset := fs.Bool("reset", false, "make a new pairing; the old one stops working")
			_ = fs.Parse(os.Args[2:])
			if err := runPair(*dir, *host, *port, *name, *reset); err != nil {
				fmt.Fprintln(os.Stderr, "pair:", err)
				os.Exit(1)
			}
			return
		}
	}
	listen := flag.String("listen", ":4001", "SRT listen address")
	slatePath := flag.String("slate", "slate.ts", "pre-encoded slate (MPEG-TS, H.264+AAC, must start with IDR)")
	fps := flag.Float64("fps", 30, "output frame rate")
	stall := flag.Duration("stall", 1500*time.Millisecond, "switch to slate after this long without live data")
	hold := flag.Duration("hold", 10*time.Minute, "end the output after this long on slate (0 = never)")
	latency := flag.Duration("latency", 2*time.Second, "SRT receiver latency")
	idle := flag.Duration("peer-idle", 30*time.Second, "SRT peer idle timeout (tunnels!)")
	reorder := flag.Uint("reorder", 64, "SRT reorder tolerance (SRTO_LOSSMAXTTL) in packets: how many newer packets may overtake a missing one before a resend is asked for; 0 = ask at once")
	eventsPath := flag.String("events", "", "append JSON lines of state changes to this file")
	waitLive := flag.Bool("wait-live", false, "write nothing until the first live keyframe; exit after -hold on slate")
	pullListen := flag.String("pull-listen", ":5001", "with -data: SRT address home OBS pulls from in \"My OBS\" mode")
	dataDir := flag.String("data", "", "data directory: output to the app's output (or OUTPUT_URL) / a recording there, and only accept paired streams; empty = TS to stdout, any stream")
	phoneSlate := flag.String("phone-slate", "", "where to keep the slate the phone sends (stream id \"slate\"); preferred over -slate when present. Its Weak signal slate (\"slate-weak\") goes next to it")
	flag.Parse()
	log.SetOutput(os.Stderr)
	log.SetFlags(log.Ltime | log.Lmicroseconds)

	ctx, cancel := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer cancel()
	t0 := time.Now()
	var evf *os.File
	if *eventsPath != "" {
		rotateLog(*eventsPath, maxEventLog)
		var err error
		if evf, err = os.OpenFile(*eventsPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644); err != nil {
			log.Fatal(err)
		}
		defer evf.Close()
	}
	// Events come from the keeper, the accept loop and the output's goroutine. Each one also moves the state the
	// control API reports (state.json), so the app and the mod remote see the keeper's state without parsing logs.
	var evMu sync.Mutex
	var air airTracker
	writeState := func(now time.Time) { // with evMu held
		if *dataDir == "" {
			return
		}
		st := air.state()
		st.At = now.UTC()
		b, _ := json.Marshal(st)
		_ = writeFileAtomic(filepath.Join(*dataDir, stateFile), b, 0o644)
	}
	events := func(msg string) {
		evMu.Lock()
		defer evMu.Unlock()
		log.Print(msg)
		now := time.Now()
		if evf != nil {
			b, _ := json.Marshal(map[string]any{"t": now.Sub(t0).Seconds(), "at": now.UTC().Format(time.RFC3339Nano), "event": msg})
			fmt.Fprintln(evf, string(b))
		}
		if air.add(msg) {
			writeState(now)
		}
	}
	// fatal records why the keeper stopped where the app and `docker logs` can see it, then exits.
	fatal := func(format string, args ...any) {
		events("ERROR: keeper stopped: " + fmt.Sprintf(format, args...))
		os.Exit(1)
	}

	slate, err := loadSlate(*slatePath)
	if err != nil {
		fatal("slate: %v", err)
	}
	defaultSlate, saved := slate, false
	if *phoneSlate != "" {
		if ps, err := loadSlate(*phoneSlate); err == nil {
			slate, saved = ps, true
			events("using the phone's slate from its last stream")
		} else if !errors.Is(err, os.ErrNotExist) {
			events(fmt.Sprintf("phone slate unusable (%v); using the default", err))
		}
	}
	var dst io.Writer = os.Stdout
	var out *sink
	var pull *pullServer
	if *dataDir != "" {
		getPull := func() (*pullServer, error) {
			if pull == nil {
				p, err := startPull(*pullListen, *dataDir, events)
				if err != nil {
					return nil, err
				}
				pull = p
				events("OBS output: waiting for OBS on " + *pullListen)
			}
			return pull, nil
		}
		out = &sink{dir: *dataDir, env: os.Getenv("OUTPUT_URL"), recKeep: envInt("REC_KEEP", 5), events: events, pull: getPull}
		dst = out
		// In "My OBS" mode, let OBS connect while the server waits for the phone, so the picture appears at go-live.
		if u, _ := currentOutput(*dataDir, out.env); u == obsMode {
			if _, err := getPull(); err != nil {
				events(fmt.Sprintf("OBS output: can't listen on %s: %v", *pullListen, err))
			}
		}
		writeStats(*dataDir, nil)
	}
	// -fps is only the fallback frame length: the slates' and the live sessions' own timestamps say what it is.
	k, err := newKeeper(newTSOut(dst), slate, int64(90000 / *fps), *stall, *hold, events)
	if err != nil {
		fatal("slate: %v", err)
	}
	if saved {
		k.useSavedSlate(defaultSlate)
	}
	k.status = func(ks keeperStatus) {
		evMu.Lock()
		defer evMu.Unlock()
		if air.setKeeper(ks) {
			writeState(time.Now())
		}
	}
	if *phoneSlate != "" {
		if ws, err := loadSlate(weakSlatePath(*phoneSlate)); err == nil {
			k.installWeak(ws)
		} else if !errors.Is(err, os.ErrNotExist) {
			events(fmt.Sprintf("weak signal slate unusable (%v)", err))
		}
	}

	cfg := srt.DefaultConfig()
	cfg.ReceiverLatency = *latency
	cfg.PeerLatency = *latency
	cfg.PeerIdleTimeout = *idle
	// Bonding sends packets over links with different delays, so they arrive out of order all the time. Without a
	// reorder tolerance every overtaken packet is asked for again (a quarter of all packets in the first real stream)
	// and the phone's bitrate control reads those resends as congestion.
	cfg.LossMaxTTL = uint32(*reorder)
	ln, err := srt.Listen("srt", *listen, cfg)
	if err != nil {
		fatal("can't listen for the stream on %s: %v", *listen, err)
	}
	defer ln.Close()
	live := make(chan liveEvent, 512)
	slates := make(chan slateUpdate, 2)
	quality := make(chan qualityEvent, 8)
	k.updates = slates
	k.quality = quality
	if *dataDir != "" {
		ends := make(chan struct{}, 1)
		k.ends = ends
		k.appEnds = appCallsEnd(*dataDir)
		go watchEndRequests(ctx, *dataDir, t0, ends)
	}
	go acceptLoop(ctx, ln, live, slates, quality, *phoneSlate, *dataDir, events)

	runErr := k.run(live, ctx.Done(), *waitLive)
	if out != nil {
		_ = out.Close()
		writeStats(*dataDir, nil)
	}
	if pull != nil {
		pull.shutdown()
	}
	if runErr != nil {
		fatal("output: %v", runErr)
	}
}

const maxEventLog = 5 << 20

// rotateLog keeps the event log from growing without limit: past max bytes it becomes path.1 (replacing the older
// one). The keeper starts once per stream, so this runs often enough.
func rotateLog(path string, max int64) {
	if fi, err := os.Stat(path); err == nil && fi.Size() > max {
		_ = os.Rename(path, path+".1")
	}
}

// rejectLog limits "rejected" events to one per address a minute: anyone can knock on the port, and the event log
// shouldn't fill up with it.
type rejectLog struct{ last map[string]time.Time }

func (r *rejectLog) allow(addr net.Addr, now time.Time) bool {
	host := addr.String()
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	if r.last == nil || len(r.last) > 1000 {
		r.last = map[string]time.Time{}
	}
	if t, ok := r.last[host]; ok && now.Sub(t) < time.Minute {
		return false
	}
	r.last[host] = now
	return true
}

func acceptLoop(ctx context.Context, ln srt.Listener, live chan<- liveEvent, slates chan slateUpdate, quality chan<- qualityEvent, store, dataDir string, events func(string)) {
	session := 0
	var newest atomic.Int64
	var rejects rejectLog
	unpairedSaid := false
	reject := func(req srt.ConnRequest, why srt.RejectionReason, msg string) {
		req.Reject(why)
		if rejects.allow(req.RemoteAddr(), time.Now()) {
			events(msg)
		}
	}
	for {
		req, err := ln.Accept2()
		if err != nil {
			// Closed at the end of a stream: Accept2 fails at once from then on, so retrying would only spin.
			if ctx.Err() != nil || errors.Is(err, srt.ErrListenerClosed) {
				return
			}
			log.Printf("accept: %v", err)
			continue
		}
		token := ""
		if dataDir != "" {
			// A real server (with a data dir) only streams for its paired app. If pairing.json is missing or
			// unreadable there is no token to check against, and accepting anyone would let strangers stream to the
			// channel: refuse until it's paired.
			if token = readToken(dataDir); token == "" {
				req.Reject(srt.REJ_PEER)
				if !unpairedSaid {
					unpairedSaid = true
					events(fmt.Sprintf("rejected a stream from %s: server not paired (run `brb pair`)", req.RemoteAddr()))
				}
				continue
			}
		}
		kind, ok := classifyStream(req.StreamId(), token)
		if !ok {
			reject(req, srt.REJ_PEER, fmt.Sprintf("rejected a stream from %s: not from the paired app", req.RemoteAddr()))
			continue
		}
		// A paired app's streams must be encrypted with the pairing's passphrase; unpaired, none is known.
		if token != "" {
			if !req.IsEncrypted() {
				reject(req, srt.REJ_BADSECRET, fmt.Sprintf("rejected an unencrypted stream from %s", req.RemoteAddr()))
				continue
			}
			if err := req.SetPassphrase(srtPassphrase(token)); err != nil {
				reject(req, srt.REJ_BADSECRET, fmt.Sprintf("rejected a stream from %s: %v", req.RemoteAddr(), err))
				continue
			}
		} else if req.IsEncrypted() {
			reject(req, srt.REJ_BADSECRET, fmt.Sprintf("rejected an encrypted stream from %s: this server isn't paired", req.RemoteAddr()))
			continue
		}
		conn, err := req.Accept()
		if err != nil {
			log.Printf("accept: %v", err)
			continue
		}
		if kind == "slate" || kind == "slate-weak" {
			weak := kind == "slate-weak"
			path := store
			if weak && store != "" {
				path = weakSlatePath(store)
			}
			go receiveSlate(ctx, conn, weak, slates, path, events)
			continue
		}
		session++
		s := session
		newest.Store(int64(s))
		events(fmt.Sprintf("SRT session %d connected from %s (stream id %q)", s, conn.RemoteAddr(), conn.StreamId()))
		go func() {
			defer conn.Close()
			stop := make(chan struct{})
			ingest := &ingestStats{}
			go reportStats(conn, s, ingest, stop, events)
			go watchLoss(conn, s, quality, stop)
			if dataDir != "" {
				go publishStats(conn, s, &newest, dataDir, stop)
			}
			live <- liveEvent{session: s, connected: true}
			// Damaged data (TS packets lost on the way) is skipped, not the end of the session: ending it made the
			// phone reconnect, and viewers saw the slate, for one broken audio packet.
			skipped, lastSaid := 0, time.Time{}
			err := demuxLive(ctx, bufio.NewReaderSize(conn, 256*1024), func(a *au) {
				live <- liveEvent{a: a, session: s}
			}, func(err error) {
				skipped++
				if now := time.Now(); now.Sub(lastSaid) >= 10*time.Second {
					lastSaid = now
					events(fmt.Sprintf("SRT session %d: skipped damaged data and carried on (%d so far): %v", s, skipped, err))
				}
			}, ingest)
			close(stop)
			events(fmt.Sprintf("SRT session %d ended (%v); skipped damaged %d; %s", s, err, skipped, statsLine(conn)))
			live <- liveEvent{session: s, ended: true, err: err}
		}()
	}
}

// classifyStream decides what an incoming SRT stream is from its stream id: "live", "slate" (the phone's BRB slate
// upload) or "slate-weak" (its Weak signal slate). Once the server is paired, only "live/ID", "slate/ID" and
// "slate-weak/ID" with the pairing's publish ID are accepted, so nobody else can stream through it to your channel.
// Unpaired, anything is a live stream except the two slates. RootEncoder puts the URL path in the stream id, so a
// leading slash and a query are tolerated.
func classifyStream(streamID, token string) (string, bool) {
	id, _, _ := strings.Cut(strings.TrimPrefix(streamID, "/"), "?")
	if token == "" {
		if id == "slate" || id == "slate-weak" {
			return id, true
		}
		return "live", true
	}
	kind, pid, _ := strings.Cut(id, "/")
	if (kind == "live" || kind == "slate" || kind == "slate-weak") && pid == publishID(token) {
		return kind, true
	}
	return "", false
}

// reportStats logs the session's SRT receive counters every 30 s. Bonding reorders packets across links; the
// vendored gosrt waits for -reorder newer packets (or a short time) before asking for a missing one, so "resent"
// should be close to the packets really lost on the way, not to "reordered but in time". The video line shows
// whether every keyframe got through.
func reportStats(conn srt.Conn, session int, ingest *ingestStats, stop <-chan struct{}, events func(string)) {
	const every = 30 * time.Second
	t := time.NewTicker(every)
	defer t.Stop()
	for {
		select {
		case <-stop:
			return
		case <-t.C:
			events(fmt.Sprintf("SRT session %d stats: %s", session, statsLine(conn)))
			events(fmt.Sprintf("SRT session %d video: %s", session, ingest.report(every.Seconds())))
		}
	}
}

// publishStats writes the newest live session's ingest stats for NOALBS once a second: bitrate (kbps) received,
// RTT and dropped packets. When the session ends, there is no publisher (NOALBS: offline).
func publishStats(conn srt.Conn, session int, newest *atomic.Int64, dir string, stop <-chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	var lastBytes uint64
	last := time.Now()
	for {
		select {
		case <-stop:
			if newest.Load() == int64(session) {
				writeStats(dir, nil)
			}
			return
		case now := <-t.C:
			var st srt.Statistics
			conn.Stats(&st)
			kbps := int64(float64(st.Accumulated.ByteRecv-lastBytes) * 8 / 1000 / now.Sub(last).Seconds())
			lastBytes, last = st.Accumulated.ByteRecv, now
			if newest.Load() == int64(session) {
				writeStats(dir, &publisherStats{Bitrate: kbps, RTT: st.Instantaneous.MsRTT, DroppedPkts: int64(st.Accumulated.PktRecvLost)})
			}
		}
	}
}

func statsLine(conn srt.Conn) string {
	var st srt.Statistics
	conn.Stats(&st)
	a := st.Accumulated
	return fmt.Sprintf("received %d (unique %d), reordered but in time %d, resent %d, asked for again %d, lost %d, dropped %d, NAKs sent %d",
		a.PktRecv, a.PktRecvUnique, a.PktRecvLate, a.PktRecvRetrans, a.PktRecvLoss, a.PktRecvLost, a.PktRecvDrop, a.PktSentNAK)
}

func envOr(name, def string) string {
	if v := os.Getenv(name); v != "" {
		return v
	}
	return def
}

func envInt(name string, def int) int {
	if v, err := strconv.Atoi(os.Getenv(name)); err == nil {
		return v
	}
	return def
}

// receiveSlate reads one slate upload to the end. It is only used if the connection closed cleanly with a complete,
// valid slate; it is then handed to the keeper and saved so the next session starts with it.
func receiveSlate(ctx context.Context, conn srt.Conn, weak bool, slates chan slateUpdate, store string, events func(string)) {
	name := "slate"
	if weak {
		name = "weak signal slate"
	}
	defer conn.Close()
	var raw bytes.Buffer
	_, err := io.Copy(&raw, io.LimitReader(conn, maxSlateBytes+1))
	if err != nil && !errors.Is(err, io.EOF) {
		events(fmt.Sprintf("%s upload failed: %v", name, err))
		return
	}
	if raw.Len() > maxSlateBytes {
		events(name + " upload rejected: too large")
		return
	}
	ts := tablesFirst(raw.Bytes())
	var aus []*au
	err = demuxAUs(ctx, bytes.NewReader(ts), func(a *au) { aus = append(aus, a) })
	if err == nil {
		_, _, _, err = prepareSlate(aus, defaultFrameDur) // the frame length only matters for a one-frame slate
	}
	if err == nil {
		var st srt.Statistics
		conn.Stats(&st)
		err = checkSlateUpload(st.Accumulated.PktRecvLost, aus)
	}
	if err != nil {
		var nv, na int
		for _, a := range aus {
			if a.kind == kindVideo {
				nv++
			} else {
				na++
			}
		}
		events(fmt.Sprintf("%s upload rejected (%d bytes, %d video + %d audio AUs): %v", name, raw.Len(), nv, na, err))
		if store != "" {
			_ = os.WriteFile(store+".rejected", raw.Bytes(), 0o644) // for diagnosis
		}
		return
	}
	events(fmt.Sprintf("%s received from the phone (%d KB)", name, raw.Len()/1024))
	if store != "" {
		tmp := store + ".tmp"
		if err := os.WriteFile(tmp, ts, 0o644); err == nil {
			_ = os.Rename(tmp, store)
		}
	}
	// Never blocks: the channel holds both kinds; if it's full the keeper is behind and the file on disk still has it.
	select {
	case slates <- slateUpdate{weak: weak, aus: aus}:
	default:
		events(name + " not applied now (the keeper is busy); it's saved for the next stream")
	}
}

// checkSlateUpload rejects a slate that demuxed fine but is probably damaged or cut short. The slate is a single GOP
// looped for every dropout and saved for later streams, so one lost slice would smear every BRB screen for the whole
// stream, and a cut upload still ends in a clean EOF (peer idle timeout, or the phone giving up and still sending
// SHUTDOWN). lost is SRT's PktRecvLost: packets skipped for good at delivery. PktRecvDrop is deliberately not used:
// it also counts harmless duplicates (a retransmission arriving after the original), which bonding makes common.
// The phone always sends silent AAC with its slates, so a slate with no audio means the upload stopped early.
// Rejecting keeps whatever slate is already in place (the default or a previous phone slate).
func checkSlateUpload(lost uint64, aus []*au) error {
	if lost > 0 {
		return fmt.Errorf("%d packets lost in transit; keeping the current slate", lost)
	}
	for _, a := range aus {
		if a.kind != kindVideo {
			return nil
		}
	}
	return fmt.Errorf("no audio (upload cut short?); keeping the current slate")
}

// weakSlatePath is where the Weak signal slate is kept, next to the BRB one: phone-slate.ts -> phone-slate-weak.ts.
func weakSlatePath(store string) string {
	ext := filepath.Ext(store)
	return strings.TrimSuffix(store, ext) + "-weak" + ext
}

const maxSlateBytes = 8 << 20

// defaultFrameDur is the frame length (90 kHz) assumed when nothing measured says otherwise: 30 fps.
const defaultFrameDur = 3000

func loadSlate(path string) ([]*au, error) {
	f, err := os.Open(path)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	var aus []*au
	if err := demuxAUs(context.Background(), bufio.NewReader(f), func(a *au) { aus = append(aus, a) }); err != nil {
		return nil, err
	}
	if len(aus) == 0 {
		return nil, fmt.Errorf("no access units in %s", path)
	}
	return aus, nil
}
