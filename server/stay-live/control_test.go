// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bytes"
	"context"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"encoding/base64"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	srt "github.com/datarhei/gosrt"
)

func TestControlAPIPairingAndOutput(t *testing.T) {
	dir := t.TempDir()
	p, err := loadPairing(dir, false)
	if err != nil {
		t.Fatal(err)
	}
	cert, pin, err := loadOrCreateCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	events := filepath.Join(dir, "events.jsonl")
	_ = os.WriteFile(events, []byte(`{"event":"WAITING for the phone"}`+"\n"+`{"event":"LIVE (session 1), output started"}`+"\n"), 0o644)
	c := &control{dir: dir, events: events}
	srv := httptest.NewUnstartedServer(c.handler())
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	srv.StartTLS()
	defer srv.Close()

	// The app's side: trust exactly the pinned key, nothing else.
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{
		InsecureSkipVerify: true,
		VerifyPeerCertificate: func(raw [][]byte, _ [][]*x509.Certificate) error {
			leaf, _ := x509.ParseCertificate(raw[0])
			spki, _ := x509.MarshalPKIXPublicKey(leaf.PublicKey)
			sum := sha256.Sum256(spki)
			if base64.RawURLEncoding.EncodeToString(sum[:]) != pin {
				t.Fatal("pin mismatch")
			}
			return nil
		},
	}}}
	call := func(method, path, tok, body string) (*http.Response, map[string]any) {
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
		if tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var m map[string]any
		_ = json.NewDecoder(resp.Body).Decode(&m)
		return resp, m
	}

	if r, _ := call("GET", "/v1/status", "wrong", ""); r.StatusCode != 401 {
		t.Fatalf("wrong token: %d", r.StatusCode)
	}
	r, st := call("GET", "/v1/status", p.Token, "")
	if r.StatusCode != 200 || st["state"] != "live" || st["output"].(map[string]any)["kind"] != "record" {
		t.Fatalf("status: %d %v", r.StatusCode, st)
	}
	if r, _ := call("PUT", "/v1/output", p.Token, `{"url":"file:///etc/passwd"}`); r.StatusCode != 400 {
		t.Fatalf("bad scheme accepted: %d", r.StatusCode)
	}
	key := "live_123456_SECRETKEY"
	u := "rtmps://usw20.contribute.live-video.net:443/app/" + key + "?bandwidthtest=true"
	if r, _ := call("PUT", "/v1/output", p.Token, `{"url":"`+u+`"}`); r.StatusCode != 200 {
		t.Fatalf("put: %d", r.StatusCode)
	}
	if fi, _ := os.Stat(filepath.Join(dir, outputFile)); fi.Mode().Perm() != 0o600 {
		t.Fatalf("output file mode %v", fi.Mode().Perm())
	}
	_, st = call("GET", "/v1/status", p.Token, "")
	out := st["output"].(map[string]any)
	if out["kind"] != "twitch" || out["test"] != true || out["source"] != "app" {
		t.Fatalf("output summary: %v", out)
	}
	b, _ := json.Marshal(st)
	if strings.Contains(string(b), key) {
		t.Fatal("status leaked the stream key")
	}
	if got, _ := currentOutput(dir, ""); got != u {
		t.Fatalf("currentOutput = %q", got)
	}
	if r, _ := call("DELETE", "/v1/output", p.Token, ""); r.StatusCode != 204 {
		t.Fatalf("delete: %d", r.StatusCode)
	}
	if got, src := currentOutput(dir, "rtmp://env/app/k"); got != "rtmp://env/app/k" || src != "environment" {
		t.Fatalf("fallback: %q %q", got, src)
	}

	// POST /v1/end: paired only; leaves a request for the keeper.
	if r, _ := call("POST", "/v1/end", "wrong", ""); r.StatusCode != 401 {
		t.Fatalf("end with a wrong token: %d", r.StatusCode)
	}
	if _, ok := takeEndRequest(dir); ok || appCallsEnd(dir) {
		t.Fatal("an unauthorised /v1/end left a request")
	}
	if r, body := call("POST", "/v1/end", p.Token, ""); r.StatusCode != 200 || body["ok"] != true {
		t.Fatalf("end: %d %v", r.StatusCode, body)
	}
	if _, ok := takeEndRequest(dir); !ok || !appCallsEnd(dir) {
		t.Fatal("/v1/end left no request for the keeper")
	}
}

func TestPairingPersistsAndResets(t *testing.T) {
	dir := t.TempDir()
	a, _ := loadPairing(dir, false)
	b, _ := loadPairing(dir, false)
	if a.Token != b.Token || len(a.Token) < 40 {
		t.Fatalf("token not stable: %q %q", a.Token, b.Token)
	}
	c, _ := loadPairing(dir, true)
	if c.Token == a.Token || tokenMatches(dir, a.Token) || !tokenMatches(dir, c.Token) {
		t.Fatal("reset must replace the token")
	}
	if fi, _ := os.Stat(filepath.Join(dir, pairingFile)); fi.Mode().Perm() != 0o600 {
		t.Fatalf("pairing file mode %v", fi.Mode().Perm())
	}
	_, pin1, _ := loadOrCreateCert(dir)
	_, pin2, _ := loadOrCreateCert(dir)
	if pin1 != pin2 || pin1 == "" {
		t.Fatal("certificate must persist")
	}
	link := pairingLink("203.0.113.5", 5000, c.Token, pin1, "My VPS")
	if !strings.HasPrefix(link, "fullbars://pair?") || !strings.Contains(link, "k="+c.Token) {
		t.Fatalf("link: %s", link)
	}
	if art, err := renderQR(link); err != nil || strings.Count(art, "\n") < 20 {
		t.Fatalf("qr: %v", err)
	}
}

func TestRedactKeys(t *testing.T) {
	in := "[flv] Error opening output rtmps://ingest.global-contribute.live-video.net:443/app/live_1_SECRET?bandwidthtest=true: I/O error and srt://h:9000?streamid=abc&passphrase=xyz"
	out := redactKeys(in)
	for _, s := range []string{"SECRET", "bandwidthtest", "passphrase", "xyz"} {
		if strings.Contains(out, s) {
			t.Fatalf("leaked %q: %s", s, out)
		}
	}
	if !strings.Contains(out, "live-video.net:443/app/<key redacted>") {
		t.Fatalf("unexpected: %s", out)
	}
	if d := describeURL("rtmps://x.live-video.net:443/app/live_1_SECRET?bandwidthtest=true"); strings.Contains(d, "SECRET") {
		t.Fatalf("describeURL leaked: %s", d)
	}
}

func TestObsModeAndStats(t *testing.T) {
	dir := t.TempDir()
	p, _ := loadPairing(dir, false)
	if err := writeFileAtomic(filepath.Join(dir, outputFile), []byte(obsMode), 0o600); err != nil {
		t.Fatal(err)
	}
	if u, _ := currentOutput(dir, ""); u != obsMode || summarize(u).Kind != "obs" || describeURL(u) != "home OBS (pull)" {
		t.Fatalf("obs mode: %q %v", u, summarize(u))
	}
	// OBS's credentials must not let anyone publish: they differ from the phone's.
	if playID(p.Token) == publishID(p.Token) || playPassphrase(p.Token) == srtPassphrase(p.Token) || statsKey(p.Token) == publishID(p.Token) {
		t.Fatal("OBS credentials must differ from the phone's")
	}
	if string(readStats(dir)) != `{"publishers":{}}` {
		t.Fatal("no stats yet = no publishers")
	}
	writeStats(dir, &publisherStats{Bitrate: 5800, RTT: 42.5, DroppedPkts: 3})
	var got struct {
		Publishers map[string]publisherStats `json:"publishers"`
	}
	if err := json.Unmarshal(readStats(dir), &got); err != nil || got.Publishers["live"].Bitrate != 5800 || got.Publishers["live"].RTT != 42.5 {
		t.Fatalf("stats: %s", readStats(dir))
	}
	writeStats(dir, nil)
	if string(readStats(dir)) != `{"publishers":{}}` {
		t.Fatalf("offline: %s", readStats(dir))
	}
}

// A real SRT caller (standing in for OBS) gets the stream with the OBS credentials; the phone's credentials or no
// encryption are refused.
func TestPullServerServesOBS(t *testing.T) {
	dir := t.TempDir()
	p, _ := loadPairing(dir, false)
	var evs []string
	var mu sync.Mutex
	events := func(e string) { mu.Lock(); evs = append(evs, e); mu.Unlock() }
	ps, err := startPull("127.0.0.1:0", dir, events)
	if err != nil {
		t.Fatal(err)
	}
	defer ps.shutdown()
	addr := ps.ln.Addr().String()

	dial := func(streamID, pass string) (srt.Conn, error) {
		cfg := srt.DefaultConfig()
		cfg.StreamId = streamID
		cfg.Passphrase = pass
		return srt.Dial("srt", addr, cfg)
	}
	if c, err := dial("play/"+playID(p.Token), ""); err == nil {
		c.Close()
		t.Fatal("unencrypted pull accepted")
	}
	if c, err := dial("live/"+publishID(p.Token), srtPassphrase(p.Token)); err == nil {
		c.Close()
		t.Fatal("the phone's credentials must not pull")
	}
	c, err := dial("play/"+playID(p.Token), playPassphrase(p.Token))
	if err != nil {
		t.Fatalf("OBS pull refused: %v", err)
	}
	defer c.Close()
	for i := 0; i < 50 && func() bool { ps.mu.Lock(); defer ps.mu.Unlock(); return len(ps.subs) == 0 }(); i++ {
		time.Sleep(20 * time.Millisecond)
	}
	// Real TS: a keyframe then P-frames, so the viewer starts (at the keyframe, PAT/PMT first).
	var ts bytes.Buffer
	mux := newTSOut(&ts)
	for i := int64(0); i < 30; i++ {
		d := []byte{0, 0, 0, 1, 0x41, 1, 2, 3}
		if i == 0 {
			d = idr(0x64, 0xee)
		}
		_ = mux.write(&au{kind: kindVideo, data: d, key: i == 0}, 9000+i*3000, 9000+i*3000)
	}
	ts.Write(make([]byte, 2*srtPayload)) // push the tail out
	if _, err := ps.Write(ts.Bytes()); err != nil {
		t.Fatal(err)
	}
	type res struct {
		b   []byte
		err error
	}
	rc := make(chan res, 1)
	go func() {
		var got []byte
		buf := make([]byte, 1500)
		for len(got) < 6*srtPayload {
			n, err := c.Read(buf)
			if err != nil {
				rc <- res{got, err}
				return
			}
			got = append(got, buf[:n]...)
		}
		rc <- res{got, nil}
	}()
	var got []byte
	select {
	case r := <-rc:
		if r.err != nil {
			t.Fatal(r.err)
		}
		got = r.b
	case <-time.After(5 * time.Second):
		t.Fatal("OBS stand-in received nothing")
	}
	var aus []*au
	_ = demuxAUs(context.Background(), bytes.NewReader(got), func(a *au) { aus = append(aus, a) })
	if len(aus) < 5 || !aus[0].key {
		t.Fatalf("OBS stand-in got %d AUs, first key=%v", len(aus), len(aus) > 0 && aus[0].key)
	}
}

// A viewer that joins mid-GOP gets nothing until the next keyframe, and then the PAT/PMT first.
func TestPullViewerStartsAtKeyframe(t *testing.T) {
	var ts bytes.Buffer
	out := newTSOut(&ts)
	for i := int64(0); i < 4; i++ { // P, P, IDR, P
		key := i == 2
		d := []byte{0, 0, 0, 1, 0x41, 1, 2, 3}
		if key {
			d = idr(0x64, 0xee)
		}
		_ = out.write(&au{kind: kindVideo, data: d, key: key}, 9000+i*3000, 9000+i*3000)
	}
	all := ts.Bytes()
	// Count packets per chunk so we know where the IDR lands.
	p := &pullServer{subs: map[*subscriber]struct{}{}, events: func(string) {}}
	s := &subscriber{ch: make(chan []byte, 64)}
	p.subs[s] = struct{}{}
	_, _ = p.Write(all)
	_, _ = p.Write(make([]byte, srtPayload)) // flush the tail
	var got []byte
	for len(s.ch) > 0 {
		got = append(got, <-s.ch...)
	}
	if len(got) == 0 {
		t.Fatal("viewer got nothing")
	}
	if pid := int(got[1]&0x1f)<<8 | int(got[2]); pid != 0 {
		t.Fatalf("first packet PID %d, want the PAT", pid)
	}
	var aus []*au
	_ = demuxAUs(context.Background(), bytes.NewReader(got), func(a *au) { aus = append(aus, a) })
	if len(aus) == 0 || !aus[0].key {
		t.Fatalf("viewer's first AU must be the keyframe: %d AUs", len(aus))
	}
}

// The keeper's events fold into one state: what viewers see, unless the output is broken.
func TestAirTracker(t *testing.T) {
	var a airTracker
	if a.state().State != "unknown" {
		t.Fatal("no events yet: unknown")
	}
	steps := []struct {
		event, state string
		changed      bool
	}{
		{"WAITING for the phone", "waiting", true},
		{"SRT session 1 connected from 1.2.3.4:5 (stream id \"live\")", "waiting", false},
		{"output: twitch", "waiting", false},
		{"LIVE (session 1), output started", "live", true},
		{"WEAK SIGNAL slate: weak signal (40 packets lost in one second)", "weak", true},
		{"LIVE (session 1) after 6.0s of slate", "live", true},
		{"OUTPUT ERROR: Connection reset by peer; reconnecting in 1s", "error", true},
		{"SLATE: feed stalled > 1.5s", "error", false}, // the output error still wins
		{"OUTPUT ERROR: Twitch refused the stream (check the stream key)", "error", true},
		{"output: reconnected to rtmps://ingest.example", "slate", true},
		{"hold timer (10m0s) expired, ending output", "waiting", true},
		{"ERROR: keeper stopped: slate: no access units", "error", true},
		{"WAITING for the phone", "waiting", true}, // the next session starts clean
	}
	for _, s := range steps {
		changed := a.add(s.event)
		if got := a.state(); got.State != s.state || changed != s.changed {
			t.Fatalf("after %q: state %q changed=%v, want %q changed=%v", s.event, got.State, changed, s.state, s.changed)
		}
	}
	a.add("OUTPUT ERROR: Twitch refused the stream (check the stream key)")
	if d := a.state().Detail; !strings.Contains(d, "check the stream key") {
		t.Fatalf("detail should carry the reason: %q", d)
	}
}

// /v1/status reads state.json (the keeper writes it on every change), so a long stream whose last state change is
// far back in the event log still reports correctly; without the file it falls back to the log.
func TestStatusPrefersStateFile(t *testing.T) {
	dir := t.TempDir()
	events := filepath.Join(dir, "events.jsonl")
	var lines bytes.Buffer
	lines.WriteString(`{"event":"LIVE (session 1), output started"}` + "\n")
	for lines.Len() < 80<<10 { // pushes the LIVE line out of the 64 KB tail
		lines.WriteString(`{"event":"SRT session 1 stats: received 1 (unique 1), reordered but in time 0"}` + "\n")
	}
	_ = os.WriteFile(events, lines.Bytes(), 0o644)
	if st := readAirState(dir, events); st.State != "unknown" {
		t.Fatalf("without state.json the tail has no state: %q", st.State)
	}
	at := time.Date(2026, 11, 18, 12, 0, 0, 0, time.UTC)
	b, _ := json.Marshal(airState{State: "weak", Detail: "WEAK SIGNAL slate: weak signal", At: at})
	_ = os.WriteFile(filepath.Join(dir, stateFile), b, 0o644)
	if st := readAirState(dir, events); st.State != "weak" || !st.At.Equal(at) {
		t.Fatalf("state.json: %+v", st)
	}
	_ = os.Remove(filepath.Join(dir, stateFile))
	_ = os.WriteFile(events, []byte(`{"event":"WEAK SIGNAL slate: x"}`+"\n"), 0o644)
	if st := readAirState(dir, events); st.State != "weak" {
		t.Fatalf("log fallback: %q", st.State)
	}
}

// A server with a data dir but no readable pairing refuses every stream (it used to accept anything), and says
// why once, not on every knock.
func TestKeeperRefusesStreamsWhenNotPaired(t *testing.T) {
	dir := t.TempDir()
	ln, err := srt.Listen("srt", "127.0.0.1:0", srt.DefaultConfig())
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	var mu sync.Mutex
	var evs []string
	events := func(e string) { mu.Lock(); evs = append(evs, e); mu.Unlock() }
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go acceptLoop(ctx, ln, make(chan liveEvent, 8), make(chan slateUpdate, 1), make(chan qualityEvent, 1), "", dir, events)
	for i := 0; i < 2; i++ {
		cfg := srt.DefaultConfig()
		cfg.StreamId = "live"
		if c, err := srt.Dial("srt", ln.Addr().String(), cfg); err == nil {
			c.Close()
			t.Fatal("an unpaired server accepted a stream")
		}
	}
	mu.Lock()
	defer mu.Unlock()
	n := 0
	for _, e := range evs {
		if strings.Contains(e, "server not paired") {
			n++
		}
	}
	if n != 1 {
		t.Fatalf("expected one 'server not paired' event, got %d in %q", n, evs)
	}
}

func TestRejectLogOncePerAddressPerMinute(t *testing.T) {
	var r rejectLog
	t0 := time.Unix(5000, 0)
	a := &net.UDPAddr{IP: net.ParseIP("198.51.100.7"), Port: 4000}
	b := &net.UDPAddr{IP: net.ParseIP("198.51.100.7"), Port: 4001} // same host, new port: still limited
	c := &net.UDPAddr{IP: net.ParseIP("203.0.113.9"), Port: 4000}
	if !r.allow(a, t0) || r.allow(b, t0.Add(10*time.Second)) || !r.allow(c, t0.Add(10*time.Second)) {
		t.Fatal("one event per host per minute")
	}
	if !r.allow(a, t0.Add(61*time.Second)) {
		t.Fatal("allowed again after a minute")
	}
}

func TestEventLogRotates(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "events.jsonl")
	_ = os.WriteFile(path, make([]byte, 100), 0o644)
	rotateLog(path, 200)
	if _, err := os.Stat(path); err != nil {
		t.Fatal("a small log stays")
	}
	_ = os.WriteFile(path, make([]byte, 300), 0o644)
	rotateLog(path, 200)
	if _, err := os.Stat(path); !os.IsNotExist(err) {
		t.Fatal("a big log is moved aside")
	}
	if fi, err := os.Stat(path + ".1"); err != nil || fi.Size() != 300 {
		t.Fatal("the old log is kept as .1")
	}
}

func TestPruneRecordingsKeepsNewest(t *testing.T) {
	dir := t.TempDir()
	names := []string{"rec-20261101-100000.ts", "rec-20261102-100000.ts", "rec-20261103-100000.ts", "rec-20261104-100000.ts"}
	for _, n := range names {
		_ = os.WriteFile(filepath.Join(dir, n), []byte("x"), 0o644)
	}
	_ = os.WriteFile(filepath.Join(dir, "slate.ts"), []byte("x"), 0o644)
	pruneRecordings(dir, 2, func(string) {})
	left, _ := filepath.Glob(filepath.Join(dir, "*.ts"))
	want := []string{"rec-20261103-100000.ts", "rec-20261104-100000.ts", "slate.ts"}
	if len(left) != len(want) {
		t.Fatalf("left %v", left)
	}
	for i, w := range want {
		if filepath.Base(left[i]) != w {
			t.Fatalf("left %v, want %v", left, want)
		}
	}
}
