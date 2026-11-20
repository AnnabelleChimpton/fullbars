// SPDX-License-Identifier: Apache-2.0
package main

import (
	"crypto/tls"
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

func TestExtraOutputsAPI(t *testing.T) {
	dir := t.TempDir()
	p, err := loadPairing(dir, false)
	if err != nil {
		t.Fatal(err)
	}
	cert, _, err := loadOrCreateCert(dir)
	if err != nil {
		t.Fatal(err)
	}
	c := &control{dir: dir, events: filepath.Join(dir, "events.jsonl")}
	srv := httptest.NewUnstartedServer(c.handler())
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{cert}}
	srv.StartTLS()
	defer srv.Close()
	client := &http.Client{Transport: &http.Transport{TLSClientConfig: &tls.Config{InsecureSkipVerify: true}}}
	call := func(method, path, tok, body string) (int, map[string]any, string) {
		req, _ := http.NewRequest(method, srv.URL+path, strings.NewReader(body))
		if tok != "" {
			req.Header.Set("Authorization", "Bearer "+tok)
		}
		resp, err := client.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		defer resp.Body.Close()
		var raw json.RawMessage
		_ = json.NewDecoder(resp.Body).Decode(&raw)
		var m map[string]any
		_ = json.Unmarshal(raw, &m)
		return resp.StatusCode, m, string(raw)
	}

	kickKey, ytKey := "sk_us-west-2_KICKSECRET", "abcd-efgh-YTSECRET"
	good := fmt.Sprintf(`{"outputs":[{"name":"Kick","url":"rtmps://fa723fc1b171.global-contribute.live-video.net/app/%s"},`+
		`{"name":"YouTube","url":"rtmps://a.rtmps.youtube.com/live2/%s"}]}`, kickKey, ytKey)

	if code, _, _ := call("PUT", "/v1/extra-outputs", "wrong", good); code != 401 {
		t.Fatalf("wrong token: %d", code)
	}
	if code, _, _ := call("PUT", "/v1/extra-outputs", p.Token, good); code != 200 {
		t.Fatalf("put: %d", code)
	}
	if fi, err := os.Stat(filepath.Join(dir, extrasFile)); err != nil || fi.Mode().Perm() != 0o600 {
		t.Fatalf("extras file: %v", err)
	}
	code, st, raw := call("GET", "/v1/status", p.Token, "")
	if code != 200 || strings.Contains(raw, kickKey) || strings.Contains(raw, ytKey) {
		t.Fatalf("status: %d, leaks a key: %v", code, strings.Contains(raw, "SECRET"))
	}
	ex := st["extras"].([]any)
	if len(ex) != 2 || ex[0].(map[string]any)["name"] != "Kick" || ex[1].(map[string]any)["host"] != "a.rtmps.youtube.com" {
		t.Fatalf("extras in status: %v", ex)
	}

	for _, bad := range []string{
		`{"outputs":[{"name":"Kick","url":"http://example.com/x"}]}`,
		`{"outputs":[{"name":"","url":"rtmp://a/app/k"}]}`,
		`{"outputs":[{"name":"A","url":"rtmp://a/app/k"},{"name":"a","url":"rtmp://b/app/k"}]}`,
		`{"outputs":[{"name":"A","url":"rtmp://a/app/k"},{"name":"B","url":"rtmp://a/app/k"}]}`,
		`{"outputs":[{"name":"A","url":"rtmp://a/app/1"},{"name":"B","url":"rtmp://a/app/2"},{"name":"C","url":"rtmp://a/app/3"},{"name":"D","url":"rtmp://a/app/4"}]}`,
		`{"outputs":[{"name":"<script>","url":"rtmp://a/app/k"}]}`,
		`not json`,
	} {
		if code, _, _ := call("PUT", "/v1/extra-outputs", p.Token, bad); code != 400 {
			t.Fatalf("accepted %s: %d", bad, code)
		}
	}
	if len(loadExtras(dir)) != 2 {
		t.Fatal("a refused PUT changed the stored list")
	}
	if code, _, _ := call("PUT", "/v1/extra-outputs", p.Token, `{"outputs":[]}`); code != 200 || loadExtras(dir) != nil {
		t.Fatalf("an empty list must clear them: %d", code)
	}
	_, _, _ = call("PUT", "/v1/extra-outputs", p.Token, good)
	if code, _, _ := call("DELETE", "/v1/extra-outputs", p.Token, ""); code != 204 || loadExtras(dir) != nil {
		t.Fatalf("delete: %d", code)
	}
	_, st, _ = call("GET", "/v1/status", p.Token, "")
	if ex, ok := st["extras"].([]any); !ok || len(ex) != 0 {
		t.Fatalf("no extras should be an empty list, got %v", st["extras"])
	}
}

func TestExtrasOnlyWithARealStream(t *testing.T) {
	cases := map[string]bool{
		"": false, // recording only
		"rtmps://usw20.contribute.live-video.net/app/live_1?bandwidthtest=true": false,
		"rtmps://usw20.contribute.live-video.net/app/live_1":                    true,
		obsMode:              true,
		"srt://host:9000":    true,
		"rtmp://host/app/k1": true,
	}
	for u, want := range cases {
		if extrasAllowed(u) != want {
			t.Errorf("extrasAllowed(%q) = %v", u, !want)
		}
	}
}

// An extra output failing shows on its own line in the status, never as the main output's error.
func TestAirTrackerKeepsExtrasApart(t *testing.T) {
	var a airTracker
	a.add("WAITING for the phone")
	a.add("LIVE (session 1)")
	ev := extraEvents("Kick", func(e string) { a.add(e) })
	ev("output: rtmps://kick.example")
	if !a.add("EXTRA OUTPUT ERROR [Kick]: Kick refused the stream (check its stream key)") {
		t.Fatal("an extra's error must change the state")
	}
	st := a.state()
	if st.State != "live" || st.Extras["Kick"] != "Kick refused the stream (check its stream key)" {
		t.Fatalf("state: %+v", st)
	}
	ev("output: reconnected to rtmps://kick.example")
	if st := a.state(); len(st.Extras) != 0 {
		t.Fatalf("a reconnect must clear the extra's error: %+v", st)
	}
	ev("OUTPUT ERROR: Connection refused; reconnecting in 1s")
	a.add("WAITING for the phone")
	if st := a.state(); len(st.Extras) != 0 {
		t.Fatalf("a new session must clear extras' errors: %+v", st)
	}
	if _, _, _, ok := extraEvent("output: rtmps://twitch"); ok {
		t.Fatal("the main output's events aren't an extra's")
	}
}

// The sink sends the same stream to the main output and each extra; a refused extra doesn't touch the main one; and a
// bandwidth test never reaches the extras.
func TestSinkFansOutToExtras(t *testing.T) {
	dir := t.TempDir()
	var mu sync.Mutex
	runs := map[string]int{}
	old := ffmpegCmd
	defer func() { ffmpegCmd = old }()
	ffmpegCmd = func(u string) *exec.Cmd {
		mu.Lock()
		runs[u]++
		n := runs[u]
		mu.Unlock()
		switch {
		case strings.Contains(u, "refuses"):
			return exec.Command("sh", "-c", `cat >/dev/null & sleep 0.01; echo "rtmps://refuses.example/app/SECRET: I/O error" >&2; exit 1`)
		case strings.Contains(u, "main"):
			return exec.Command("sh", "-c", fmt.Sprintf("exec cat > '%s/main-%d.ts'", dir, n))
		default:
			return exec.Command("sh", "-c", fmt.Sprintf("exec cat > '%s/extra-%d.ts'", dir, n))
		}
	}
	write := func(name, s string) { _ = os.WriteFile(filepath.Join(dir, name), []byte(s), 0o600) }
	write(outputFile, "rtmp://main.example/app/live_MAINKEY\n")
	b, _ := json.Marshal([]extraOutput{{"YouTube", "rtmp://yt.example/live2/YTKEY"}, {"Kick", "rtmps://refuses.example/app/SECRET"}})
	write(extrasFile, string(b))

	l := &evLog{}
	var a airTracker
	var amu sync.Mutex
	events := func(e string) { l.add(e); amu.Lock(); a.add(e); amu.Unlock() }
	s := &sink{dir: dir, events: events, pull: nil}
	mux := newTSOut(s)
	i := int64(0)
	feed := func() {
		key := i%10 == 0
		d := append([]byte{0, 0, 0, 1, 0x41}, make([]byte, 1500)...)
		if key {
			d = append(idr(0x64, 0xee), make([]byte, 1500)...)
		}
		_ = mux.write(&au{kind: kindVideo, data: d, key: key}, 9000+i*3000, 9000+i*3000)
		i++
	}
	size := func(name string) int64 {
		fi, err := os.Stat(filepath.Join(dir, name))
		if err != nil {
			return 0
		}
		return fi.Size()
	}
	waitFor(t, "main and YouTube to get the stream", 5*time.Second, func() bool {
		return size("main-1.ts") > 50000 && size("extra-1.ts") > 50000
	}, feed)
	waitFor(t, "Kick to be refused", 5*time.Second, func() bool { return l.count("EXTRA OUTPUT ERROR [Kick]") > 0 }, feed)
	_ = s.Close()

	evs := l.all()
	if strings.Contains(evs, "SECRET") || strings.Contains(evs, "YTKEY") || strings.Contains(evs, "MAINKEY") {
		t.Fatalf("a key reached the events:\n%s", evs)
	}
	if strings.Contains(evs, "\nOUTPUT ERROR") || strings.HasPrefix(evs, "OUTPUT ERROR") {
		t.Fatalf("Kick's failure was reported as the main output's:\n%s", evs)
	}
	amu.Lock()
	st := a.state()
	amu.Unlock()
	if st.State == "error" || st.Extras["Kick"] == "" {
		t.Fatalf("state after a refused extra: %+v", st)
	}
	mu.Lock()
	mainRuns := runs["rtmp://main.example/app/live_MAINKEY"]
	mu.Unlock()
	if mainRuns != 1 {
		t.Fatalf("the main output restarted %d times because of an extra", mainRuns)
	}

	// A bandwidth test: the extras stay quiet.
	write(outputFile, "rtmp://main.example/app/live_MAINKEY?bandwidthtest=true\n")
	l2 := &evLog{}
	s2 := &sink{dir: dir, events: l2.add}
	mux = newTSOut(s2)
	mu.Lock()
	before := runs["rtmp://yt.example/live2/YTKEY"]
	mu.Unlock()
	for j := 0; j < 30; j++ {
		feed()
	}
	_ = s2.Close()
	mu.Lock()
	after := runs["rtmp://yt.example/live2/YTKEY"]
	mu.Unlock()
	if after != before || l2.count("extra outputs: not sent (2 set)") != 1 {
		t.Fatalf("a bandwidth test reached the extras:\n%s", l2.all())
	}
}
