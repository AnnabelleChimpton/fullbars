// SPDX-License-Identifier: Apache-2.0
package main

import (
	"crypto/tls"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

// The mod remote end to end: the phone fetches the link, a mod opens it and picks a scene, the phone collects it on
// its next poll (once), and the page shows what the phone reported. A wrong or old link gets nothing.
func TestModRemote(t *testing.T) {
	dir := t.TempDir()
	p, err := loadPairing(dir, false)
	if err != nil {
		t.Fatal(err)
	}
	cert, _, err := loadOrCreateCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	c := &control{dir: dir, events: ""}
	srv := httptest.NewUnstartedServer(c.handler())
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	srv.StartTLS()
	defer srv.Close()
	client := srv.Client()
	client.Transport.(*http.Transport).TLSClientConfig.InsecureSkipVerify = true // a mod clicking past the warning

	call := func(method, path, tok, body string) (int, string) {
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
		if tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		b, _ := io.ReadAll(resp.Body)
		return resp.StatusCode, string(b)
	}
	field := func(js, key string) any {
		var m map[string]any
		_ = json.Unmarshal([]byte(js), &m)
		return m[key]
	}

	// Only the paired phone gets the link.
	if code, _ := call("GET", "/v1/remote", "", ""); code != 401 {
		t.Fatalf("unpaired: %d", code)
	}
	code, body := call("GET", "/v1/remote", p.Token, "")
	path, _ := field(body, "path").(string)
	if code != 200 || !strings.HasPrefix(path, "/remote/") || len(path) != len("/remote/")+32 {
		t.Fatalf("remote link: %d %q", code, body)
	}
	if _, again := call("GET", "/v1/remote", p.Token, ""); field(again, "path") != path {
		t.Fatal("the link must stay the same until reset")
	}

	// The page, with headers that keep the secret from leaking.
	req, _ := http.NewRequest("GET", srv.URL+path, nil)
	resp, err := client.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	page, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !strings.Contains(string(page), "Mod remote") || resp.Header.Get("Referrer-Policy") != "no-referrer" {
		t.Fatalf("page: %d %v", resp.StatusCode, resp.Header)
	}

	// Before the phone polls: offline.
	if _, st := call("GET", path+"/status", "", ""); field(st, "online") != false {
		t.Fatalf("status before any poll: %s", st)
	}
	// A mod picks BRB; an unknown scene is refused.
	if code, _ := call("POST", path+"/scene", "", `{"scene":"BRB"}`); code != 200 {
		t.Fatalf("scene: %d", code)
	}
	if code, _ := call("POST", path+"/scene", "", `{"scene":"EVIL"}`); code != 400 {
		t.Fatalf("unknown scene: %d", code)
	}
	// The phone collects it once, and reports what viewers see.
	_, polled := call("POST", "/v1/remote/poll", p.Token, `{"live":true,"scene":"LIVE","mic":true}`)
	cmds, _ := field(polled, "commands").([]any)
	if len(cmds) != 1 || cmds[0].(map[string]any)["scene"] != "BRB" {
		t.Fatalf("poll: %s", polled)
	}
	_, polled = call("POST", "/v1/remote/poll", p.Token, `{"live":true,"scene":"BRB","mic":true}`)
	if cmds, _ := field(polled, "commands").([]any); len(cmds) != 0 {
		t.Fatalf("a command must be delivered once: %s", polled)
	}
	if _, st := call("GET", path+"/status", "", ""); field(st, "online") != true || field(st, "scene") != "BRB" {
		t.Fatalf("status after poll: %s", st)
	}

	// Wrong and old links get nothing; a reset retires the old one.
	if code, _ := call("GET", "/remote/00000000000000000000000000000000", "", ""); code != 404 {
		t.Fatalf("wrong secret: %d", code)
	}
	_, reset := call("POST", "/v1/remote/reset", p.Token, "")
	if field(reset, "path") == path {
		t.Fatal("reset must change the link")
	}
	if code, _ := call("POST", path+"/scene", "", `{"scene":"LIVE"}`); code != 404 {
		t.Fatalf("old link after reset: %d", code)
	}
}

// Picks the phone doesn't collect in time are dropped (a mod pressing buttons while the phone is offline).
func TestModRemoteDropsStalePicks(t *testing.T) {
	rm := &remote{dir: t.TempDir()}
	t0 := time.Unix(1000, 0)
	rm.add("BRB", t0)
	rm.add("ENDING", t0.Add(25*time.Second))
	got := rm.poll(remoteStatus{Live: true, Scene: "LIVE"}, t0.Add(40*time.Second))
	if len(got) != 1 || got[0].Scene != "ENDING" {
		t.Fatalf("expected only the fresh pick, got %+v", got)
	}
	for i := 0; i < 8; i++ {
		rm.add("BRB", t0)
	}
	if got := rm.poll(remoteStatus{}, t0); len(got) != remoteMaxCmds {
		t.Fatalf("expected at most %d, got %d", remoteMaxCmds, len(got))
	}
}

// During a dropout the phone can't poll, but viewers are watching the BRB slate: the page gets the keeper's own
// state ("air") and says so instead of "isn't live". The control API wires it to state.json.
func TestModRemoteShowsTheSlateDuringADropout(t *testing.T) {
	dir := t.TempDir()
	c := &control{dir: dir, events: filepath.Join(dir, "events.jsonl")}
	c.handler() // wires the remote to the keeper's state
	t0 := time.Unix(1000, 0)
	c.remote.poll(remoteStatus{Live: true, Scene: "LIVE", Mic: true}, t0)

	_ = os.WriteFile(filepath.Join(dir, stateFile), []byte(`{"state":"live"}`), 0o644)
	if s := c.remote.snapshot(t0.Add(time.Second)); s["online"] != true || s["air"] != "live" {
		t.Fatalf("live: %v", s)
	}
	_ = os.WriteFile(filepath.Join(dir, stateFile), []byte(`{"state":"slate","detail":"SLATE: feed stalled > 1.5s"}`), 0o644)
	s := c.remote.snapshot(t0.Add(time.Minute)) // the phone stopped polling a while ago
	if s["online"] != false || s["live"] != false || s["air"] != "slate" {
		t.Fatalf("dropout: %v", s)
	}
	if !strings.Contains(remotePage, "Viewers see the") || !strings.Contains(remotePage, "reconnecting; scene buttons come back when it does") {
		t.Fatal("the page must explain the BRB screen during a dropout")
	}
	if s := (&remote{dir: dir}).snapshot(t0); s["air"] != "unknown" {
		t.Fatalf("no keeper wired: %v", s)
	}
}

// The streamer's own scenes: the phone reports them with their names, the page gets them back checked (known slots,
// once each, named, names cut to 20 runes), and a mod's pick of one reaches the phone like any other.
func TestModRemoteOwnScenes(t *testing.T) {
	rm := &remote{dir: t.TempDir()}
	t0 := time.Unix(1000, 0)
	long := strings.Repeat("é", 30)
	rm.poll(remoteStatus{Live: true, Scene: "CUSTOM_2", Mic: true, Scenes: []remoteScene{
		{ID: "CUSTOM_2", Name: " Lunch "},
		{ID: "CUSTOM_2", Name: "Again"}, // a slot once only
		{ID: "EVIL", Name: "Nope"},      // not a slot
		{ID: "CUSTOM_3", Name: "  "},    // no name
		{ID: "CUSTOM_4", Name: long},
	}}, t0)
	s := rm.snapshot(t0.Add(time.Second))
	got, _ := s["scenes"].([]remoteScene)
	want := []remoteScene{{ID: "CUSTOM_2", Name: "Lunch"}, {ID: "CUSTOM_4", Name: strings.Repeat("é", remoteNameMax)}}
	if len(got) != len(want) || got[0] != want[0] || got[1] != want[1] {
		t.Fatalf("scenes: %+v", got)
	}
	if s["scene"] != "CUSTOM_2" {
		t.Fatalf("scene: %v", s["scene"])
	}
	// An older phone sends none: an empty list, never null, so the page draws no extra buttons.
	rm.poll(remoteStatus{Live: true, Scene: "LIVE"}, t0)
	if b, _ := json.Marshal(rm.snapshot(t0)); !strings.Contains(string(b), `"scenes":[]`) {
		t.Fatalf("no scenes: %s", b)
	}
	for _, id := range []string{"CUSTOM_1", "CUSTOM_2", "CUSTOM_3", "CUSTOM_4"} {
		if !remoteScenes[id] {
			t.Fatalf("%s must be a scene mods can pick", id)
		}
	}
	rm.add("CUSTOM_1", t0)
	if cmds := rm.poll(remoteStatus{}, t0); len(cmds) != 1 || cmds[0].Scene != "CUSTOM_1" {
		t.Fatalf("pick: %+v", cmds)
	}
	if !strings.Contains(remotePage, `id="own"`) || !strings.Contains(remotePage, "textContent=x.name") {
		t.Fatal("the page must draw the streamer's own scenes, as text")
	}
}
