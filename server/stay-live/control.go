// SPDX-License-Identifier: Apache-2.0
package main

import (
	"bufio"
	"bytes"
	"crypto/tls"
	"encoding/json"
	"errors"
	"io"
	"log"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// The control API the paired app talks to, over TLS with a self-signed key the app pins (see pairing.go).
// Every /v1 call needs "Authorization: Bearer <pairing token>".
//
//	GET    /v1/status   what the server is doing and where its output goes (never the stream key)
//	PUT    /v1/output   {"url": "rtmps://..."} or {"mode": "obs"}: where the stream goes from the next go-live on
//	DELETE /v1/output   forget it (falls back to OUTPUT_URL from the environment, else recording)
//	POST   /v1/end      the streamer ended the stream on purpose: the keeper ends the output (end.go)
//	PUT    /v1/extra-outputs  the "Also stream to" destinations (extras.go)
//	GET    /healthz     liveness only, no auth
//	...and the mod remote's routes (remote.go)
type control struct {
	dir    string
	events string
	env    string // OUTPUT_URL fallback
	remote *remote
}

type statusReply struct {
	Version string         `json:"version"`
	State   string         `json:"state"` // waiting | live | slate | weak | error | unknown
	Detail  string         `json:"detail,omitempty"`
	Updated time.Time      `json:"updated,omitzero"`
	Output  outputSummary  `json:"output"`
	Slate   string         `json:"slate"`  // phone | default: whether a phone slate is saved here
	Extras  []extraSummary `json:"extras"` // "Also stream to" destinations (names and hosts, never keys)

	// NeedKeyframe: the keeper is waiting for a live keyframe to put the phone back on air and the phone is sending.
	// While a slate is on air the app polls this and asks its encoder for a keyframe when it is set.
	NeedKeyframe bool `json:"needKeyframe"`
	// SlateMatches: the slate's SPS/PPS equal the live video's, so every splice is a clean remux. Absent until a
	// live keyframe has been compared (or when the live video carries no parameter sets).
	SlateMatches  *bool  `json:"slateMatches,omitempty"`
	SlateSource   string `json:"slateSource,omitempty"` // the slate in use: phone (this stream) | saved | default
	SlateSize     string `json:"slateSize,omitempty"`
	LiveSize      string `json:"liveSize,omitempty"`
	SlateRejected string `json:"slateRejected,omitempty"` // why the phone's last slate upload was refused, if it was
}

type outputSummary struct {
	Kind   string `json:"kind"`             // twitch | rtmp | srt | record
	Host   string `json:"host,omitempty"`   // ingest host only
	Test   bool   `json:"test,omitempty"`   // Twitch bandwidth-test mode (private)
	Source string `json:"source,omitempty"` // app | environment
}

const version = "fullbars-brb/1"

func (c *control) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true, "version": version})
	})
	mux.HandleFunc("GET /v1/status", c.auth(c.status))
	mux.HandleFunc("PUT /v1/output", c.auth(c.putOutput))
	mux.HandleFunc("DELETE /v1/output", c.auth(c.deleteOutput))
	mux.HandleFunc("POST /v1/end", c.auth(c.end))
	mux.HandleFunc("PUT /v1/extra-outputs", c.auth(c.putExtras))
	mux.HandleFunc("DELETE /v1/extra-outputs", c.auth(c.deleteExtras))
	if c.remote == nil {
		c.remote = &remote{dir: c.dir}
	}
	if c.remote.keeper == nil {
		c.remote.keeper = func() string { return readAirState(c.dir, c.events).State }
	}
	c.remote.routes(mux, c.auth)
	return mux
}

func (c *control) auth(next http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		tok, ok := strings.CutPrefix(r.Header.Get("Authorization"), "Bearer ")
		if !ok || !tokenMatches(c.dir, tok) {
			time.Sleep(time.Second) // make guessing slow; the token is 256 bits anyway
			writeJSON(w, http.StatusUnauthorized, map[string]string{"error": "not paired with this server"})
			return
		}
		next(w, r)
	}
}

func (c *control) status(w http.ResponseWriter, r *http.Request) {
	st := statusReply{Version: version, Slate: "default"}
	air := readAirState(c.dir, c.events)
	st.State, st.Detail, st.Updated = air.State, air.Detail, air.At
	if _, err := os.Stat(filepath.Join(c.dir, "slate-phone.ts")); err == nil {
		st.Slate = "phone"
	}
	st.NeedKeyframe = air.NeedKeyframe
	switch air.SlateMatch {
	case "match", "mismatch":
		m := air.SlateMatch == "match"
		st.SlateMatches = &m
	}
	st.SlateSource, st.SlateSize, st.LiveSize, st.SlateRejected = air.SlateSource, air.SlateSize, air.LiveSize, air.SlateRejected
	u, src := currentOutput(c.dir, c.env)
	st.Output = summarize(u)
	st.Output.Source = src
	st.Extras = summarizeExtras(loadExtras(c.dir), air.Extras)
	writeJSON(w, http.StatusOK, st)
}

func (c *control) putOutput(w http.ResponseWriter, r *http.Request) {
	var body struct {
		URL  string `json:"url"`
		Mode string `json:"mode"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 4096)).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": "expected {\"url\": ...} or {\"mode\": \"obs\"}"})
		return
	}
	if body.Mode == obsMode {
		body.URL = obsMode
	} else if err := validOutput(body.URL); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if err := writeFileAtomic(filepath.Join(c.dir, outputFile), []byte(body.URL+"\n"), 0o600); err != nil {
		log.Printf("control: can't store output: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "can't store it"})
		return
	}
	log.Printf("control: output set to %s", describeURL(body.URL))
	writeJSON(w, http.StatusOK, summarize(body.URL))
}

func (c *control) deleteOutput(w http.ResponseWriter, r *http.Request) {
	_ = os.Remove(filepath.Join(c.dir, outputFile))
	log.Printf("control: output cleared")
	w.WriteHeader(http.StatusNoContent)
}

// currentOutput is where the next go-live sends the stream: the app's choice, else OUTPUT_URL, else "" (record).
func currentOutput(dir, env string) (string, string) {
	if b, err := os.ReadFile(filepath.Join(dir, outputFile)); err == nil {
		if u := strings.TrimSpace(string(b)); u != "" {
			return u, "app"
		}
	}
	if env != "" {
		return env, "environment"
	}
	return "", ""
}

func validOutput(raw string) error {
	if raw == "" || len(raw) > 2048 || strings.ContainsAny(raw, "\r\n\x00 ") {
		return errors.New("not a usable URL")
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host == "" {
		return errors.New("not a usable URL")
	}
	switch u.Scheme {
	case "rtmp", "rtmps", "srt":
		return nil
	}
	return errors.New("only rtmp, rtmps and srt outputs are supported")
}

func summarize(raw string) outputSummary {
	if raw == "" {
		return outputSummary{Kind: "record"}
	}
	if raw == obsMode {
		return outputSummary{Kind: "obs"}
	}
	u, err := url.Parse(raw)
	if err != nil {
		return outputSummary{Kind: "unknown"}
	}
	s := outputSummary{Kind: u.Scheme, Host: u.Hostname()}
	if strings.HasSuffix(s.Host, ".live-video.net") || strings.Contains(s.Host, "twitch") {
		s.Kind = "twitch"
	}
	if u.Query().Get("bandwidthtest") == "true" {
		s.Test = true
	}
	if s.Kind == "rtmps" || s.Kind == "rtmp" {
		s.Kind = "rtmp"
	}
	return s
}

// describeURL is a log-safe description: scheme and host only, never the path (stream key) or query.
func describeURL(raw string) string {
	if raw == obsMode {
		return "home OBS (pull)"
	}
	u, err := url.Parse(raw)
	if err != nil {
		return "(unparseable URL)"
	}
	d := u.Scheme + "://" + u.Host
	if u.Query().Get("bandwidthtest") == "true" {
		d += " (bandwidth test)"
	}
	return d
}

// airTracker folds the keeper's events into what viewers see (air) and whether the output works. Pure: the keeper
// feeds it every event and writes state.json when it changes; the control API reads that file.
type airTracker struct {
	air, airDetail string            // waiting | live | slate | weak | unknown
	outErr         string            // the last OUTPUT ERROR / ERROR, until the output works again
	extraErr       map[string]string // extra output name -> its last error, until it works again
	keeper         keeperStatus      // what the keeper reports besides its events
	slateRejected  string            // the last refused slate upload, until one is accepted
}

type airState struct {
	State  string            `json:"state"` // waiting | live | slate | weak | error | unknown
	Detail string            `json:"detail,omitempty"`
	At     time.Time         `json:"at,omitzero"`
	Extras map[string]string `json:"extras,omitempty"` // failing extra outputs: name -> error

	NeedKeyframe  bool   `json:"needKeyframe,omitempty"`
	SlateMatch    string `json:"slateMatch,omitempty"` // match | mismatch | unknown
	SlateSource   string `json:"slateSource,omitempty"`
	SlateSize     string `json:"slateSize,omitempty"`
	LiveSize      string `json:"liveSize,omitempty"`
	SlateRejected string `json:"slateRejected,omitempty"`
}

// same reports whether two states show the same thing (At aside).
func (a airState) same(b airState) bool {
	if a.State != b.State || a.Detail != b.Detail || len(a.Extras) != len(b.Extras) ||
		a.NeedKeyframe != b.NeedKeyframe || a.SlateMatch != b.SlateMatch || a.SlateSource != b.SlateSource ||
		a.SlateSize != b.SlateSize || a.LiveSize != b.LiveSize || a.SlateRejected != b.SlateRejected {
		return false
	}
	for k, v := range a.Extras {
		if b.Extras[k] != v {
			return false
		}
	}
	return true
}

// add takes one event and reports whether the state it shows changed.
func (t *airTracker) add(e string) bool {
	before := t.state()
	if name, isErr, rest, ok := extraEvent(e); ok {
		if isErr {
			if t.extraErr == nil {
				t.extraErr = map[string]string{}
			}
			t.extraErr[name] = rest
		} else if strings.HasPrefix(rest, "reconnected") || strings.HasPrefix(rest, "rtmp") || strings.HasPrefix(rest, "srt") {
			delete(t.extraErr, name) // opened, or reconnected after an error
		}
		return !t.state().same(before)
	}
	switch {
	case strings.HasPrefix(e, "WAITING"):
		t.air, t.airDetail, t.outErr = "waiting", e, "" // a new stream session: earlier output errors are over
		t.extraErr = nil
	case strings.HasPrefix(e, "stream ended"), strings.HasPrefix(e, "hold timer"):
		t.air, t.airDetail = "waiting", e
	case strings.HasPrefix(e, "LIVE"):
		t.air, t.airDetail = "live", e
	case strings.HasPrefix(e, "SLATE"):
		t.air, t.airDetail = "slate", e
	case strings.HasPrefix(e, "WEAK SIGNAL"):
		t.air, t.airDetail = "weak", e
	case strings.HasPrefix(e, "OUTPUT ERROR"), strings.HasPrefix(e, "ERROR"):
		t.outErr = e
	case strings.HasPrefix(e, "output: "): // opened, or reconnected after an error
		t.outErr = ""
	case strings.HasPrefix(e, "slate upload rejected"):
		t.slateRejected = strings.TrimPrefix(e, "slate upload rejected ")
	case strings.HasPrefix(e, "slate received from the phone"):
		t.slateRejected = ""
	}
	return !t.state().same(before)
}

// setKeeper takes the keeper's latest status and reports whether the state it shows changed.
func (t *airTracker) setKeeper(k keeperStatus) bool {
	before := t.state()
	t.keeper = k
	return !t.state().same(before)
}

// state: an output error wins, since it means viewers see nothing whatever the keeper is doing.
func (t *airTracker) state() airState {
	var extras map[string]string
	if len(t.extraErr) > 0 {
		extras = make(map[string]string, len(t.extraErr))
		for k, v := range t.extraErr {
			extras[k] = v
		}
	}
	st := airState{State: t.air, Detail: t.airDetail, Extras: extras,
		SlateMatch: t.keeper.SlateMatch, SlateSource: t.keeper.SlateSource, SlateSize: t.keeper.SlateSize,
		LiveSize: t.keeper.LiveSize, SlateRejected: t.slateRejected}
	switch {
	case t.outErr != "":
		st.State, st.Detail = "error", t.outErr
	case t.air == "":
		st.State, st.Detail = "unknown", ""
	}
	// A keyframe only helps while the keeper can splice one in and viewers can see it.
	st.NeedKeyframe = t.keeper.NeedKeyframe && (st.State == "slate" || st.State == "live")
	return st
}

const stateFile = "state.json"

// readAirState is the keeper's current state: state.json (written on every change), else the end of its event log
// (an older keeper, or one that hasn't written yet). Reading only the log's tail used to say "unknown" on long
// streams, once the last state change had scrolled out of it.
func readAirState(dir, events string) airState {
	if b, err := os.ReadFile(filepath.Join(dir, stateFile)); err == nil {
		var st airState
		if json.Unmarshal(b, &st) == nil && st.State != "" {
			return st
		}
	}
	state, detail, updated := keeperState(events)
	return airState{State: state, Detail: detail, At: updated}
}

// keeperState reads the newest meaningful lines of the keeper's event log.
func keeperState(path string) (state, detail string, updated time.Time) {
	f, err := os.Open(path)
	if err != nil {
		return "unknown", "", time.Time{}
	}
	defer f.Close()
	if fi, err := f.Stat(); err == nil {
		updated = fi.ModTime().UTC()
		if fi.Size() > 64<<10 {
			_, _ = f.Seek(-64<<10, io.SeekEnd)
		}
	}
	var t airTracker
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		var ev struct {
			Event string `json:"event"`
		}
		if json.Unmarshal(sc.Bytes(), &ev) != nil {
			continue
		}
		t.add(ev.Event)
	}
	st := t.state()
	return st.State, st.Detail, updated
}

func runControl(listen, dir, events string) error {
	if _, err := loadPairing(dir, false); err != nil { // first start creates the pairing
		return err
	}
	cert, _, err := loadOrCreateCert(dir)
	if err != nil {
		return err
	}
	c := &control{dir: dir, events: events, env: os.Getenv("OUTPUT_URL")}
	srv := &http.Server{
		Addr:              listen,
		Handler:           c.handler(),
		TLSConfig:         &tls.Config{Certificates: []tls.Certificate{cert}, MinVersion: tls.VersionTLS12},
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      10 * time.Second,
		ErrorLog:          log.New(io.Discard, "", 0), // scanners' TLS noise
	}
	log.Printf("control API on %s (TLS, pinned)", listen)
	return srv.ListenAndServeTLS("", "")
}

// runStats serves the NOALBS stats (Belabox format) over plain HTTP at /stats/<key derived from the pairing>.
// NOALBS can't use the control API: it only accepts a self-signed certificate with ACCEPT_INVALID_CERTS set.
// Bitrate and RTT aren't sensitive; the secret path just keeps strangers from reading them.
func runStats(listen, dir string) error {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /stats/{key}", func(w http.ResponseWriter, r *http.Request) {
		tok := readToken(dir)
		if tok == "" || r.PathValue("key") != statsKey(tok) {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		_, _ = w.Write(readStats(dir))
	})
	srv := &http.Server{Addr: listen, Handler: mux, ReadHeaderTimeout: 5 * time.Second, WriteTimeout: 5 * time.Second}
	log.Printf("NOALBS stats on %s", listen)
	return srv.ListenAndServe()
}

func writeJSON(w http.ResponseWriter, code int, v any) {
	var b bytes.Buffer
	_ = json.NewEncoder(&b).Encode(v)
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(code)
	_, _ = w.Write(b.Bytes())
}
