// SPDX-License-Identifier: Apache-2.0
package main

import (
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Extra outputs ("Also stream to" in the app): the same stream, passed through untouched to Kick, YouTube or any
// RTMP(S)/SRT ingest next to the main output. The phone still uploads once; the server runs one ffmpeg -c copy per
// destination, each with its own queue and retries, so a refused Kick key never touches Twitch.
//
//	PUT    /v1/extra-outputs  {"outputs": [{"name": "Kick", "url": "rtmps://.../app/<key>"}]}  (at most maxExtras)
//	DELETE /v1/extra-outputs  none
//
// They only go out with a real stream: never with a Twitch bandwidth test (a Test stream must reach nobody) and never
// when the server is only recording.
const (
	extrasFile = "extra-outputs.json"
	maxExtras  = 3
	maxNameLen = 24
)

type extraOutput struct {
	Name string `json:"name"`
	URL  string `json:"url"`
}

// extraSummary is what the status reports: the name and ingest host, and the last error if it's failing.
type extraSummary struct {
	Name  string `json:"name"`
	Host  string `json:"host,omitempty"`
	Error string `json:"error,omitempty"`
}

var nameOK = regexp.MustCompile(`^[\p{L}\p{N} ._'()-]+$`)

// validExtras checks a list the app sent: usable URLs, readable distinct names, no duplicate destinations.
func validExtras(list []extraOutput) error {
	if len(list) > maxExtras {
		return fmt.Errorf("at most %d extra outputs", maxExtras)
	}
	names, urls := map[string]bool{}, map[string]bool{}
	for _, e := range list {
		n := strings.TrimSpace(e.Name)
		if n == "" || len(n) > maxNameLen || !nameOK.MatchString(n) {
			return errors.New("each extra output needs a short name")
		}
		if err := validOutput(e.URL); err != nil {
			return fmt.Errorf("%s: %v", n, err)
		}
		if names[strings.ToLower(n)] {
			return fmt.Errorf("two extra outputs are both called %s", n)
		}
		if urls[e.URL] {
			return errors.New("the same destination is listed twice")
		}
		names[strings.ToLower(n)], urls[e.URL] = true, true
	}
	return nil
}

func loadExtras(dir string) []extraOutput {
	b, err := os.ReadFile(filepath.Join(dir, extrasFile))
	if err != nil {
		return nil
	}
	var list []extraOutput
	if json.Unmarshal(b, &list) != nil || validExtras(list) != nil {
		log.Printf("extra outputs: ignoring an unreadable %s", extrasFile)
		return nil
	}
	return list
}

func (c *control) putExtras(w http.ResponseWriter, r *http.Request) {
	var body struct {
		Outputs []extraOutput `json:"outputs"`
	}
	if err := json.NewDecoder(io.LimitReader(r.Body, 16<<10)).Decode(&body); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": `expected {"outputs": [{"name": ..., "url": ...}]}`})
		return
	}
	for i := range body.Outputs {
		body.Outputs[i].Name = strings.TrimSpace(body.Outputs[i].Name)
	}
	if err := validExtras(body.Outputs); err != nil {
		writeJSON(w, http.StatusBadRequest, map[string]string{"error": err.Error()})
		return
	}
	if len(body.Outputs) == 0 {
		_ = os.Remove(filepath.Join(c.dir, extrasFile))
		log.Printf("control: extra outputs cleared")
		writeJSON(w, http.StatusOK, map[string]any{"extras": []extraSummary{}})
		return
	}
	b, _ := json.Marshal(body.Outputs)
	if err := writeFileAtomic(filepath.Join(c.dir, extrasFile), b, 0o600); err != nil {
		log.Printf("control: can't store extra outputs: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "can't store them"})
		return
	}
	d := make([]string, len(body.Outputs))
	for i, e := range body.Outputs {
		d[i] = e.Name + " " + describeURL(e.URL)
	}
	log.Printf("control: extra outputs set: %s", strings.Join(d, ", "))
	writeJSON(w, http.StatusOK, map[string]any{"extras": summarizeExtras(body.Outputs, nil)})
}

func (c *control) deleteExtras(w http.ResponseWriter, r *http.Request) {
	_ = os.Remove(filepath.Join(c.dir, extrasFile))
	log.Printf("control: extra outputs cleared")
	w.WriteHeader(http.StatusNoContent)
}

func summarizeExtras(list []extraOutput, errs map[string]string) []extraSummary {
	out := make([]extraSummary, 0, len(list))
	for _, e := range list {
		out = append(out, extraSummary{Name: e.Name, Host: summarize(e.URL).Host, Error: errs[e.Name]})
	}
	return out
}

// extrasAllowed: extra outputs go out only next to a real stream.
func extrasAllowed(main string) bool {
	if main == "" {
		return false // recording only: a server test, nobody is watching
	}
	if main == obsMode {
		return true
	}
	return !summarize(main).Test
}

// Extra outputs report through their own event names, so their trouble never reads as the main output's: the
// status shows "error" (viewers see nothing) only when the main output fails.
const (
	extraErrPrefix = "EXTRA OUTPUT ERROR ["
	extraOKPrefix  = "extra output ["
)

func extraEvents(name string, events func(string)) func(string) {
	return func(e string) {
		switch {
		case strings.HasPrefix(e, "OUTPUT ERROR: "):
			events(extraErrPrefix + name + "]: " + strings.TrimPrefix(e, "OUTPUT ERROR: "))
		case strings.HasPrefix(e, "output: "):
			events(extraOKPrefix + name + "]: " + strings.TrimPrefix(e, "output: "))
		default:
			events(extraOKPrefix + name + "] " + e)
		}
	}
}

// extraEvent splits an extra output's event into its name, whether it's an error, and the rest. ok is false for
// other events.
func extraEvent(e string) (name string, isErr bool, rest string, ok bool) {
	var after string
	switch {
	case strings.HasPrefix(e, extraErrPrefix):
		after, isErr = strings.TrimPrefix(e, extraErrPrefix), true
	case strings.HasPrefix(e, extraOKPrefix):
		after = strings.TrimPrefix(e, extraOKPrefix)
	default:
		return "", false, "", false
	}
	i := strings.IndexByte(after, ']')
	if i <= 0 {
		return "", false, "", false
	}
	return after[:i], isErr, strings.TrimLeft(after[i+1:], ": "), true
}

// fanOut writes the stream to the main output and every extra one. None of them block (ffmpegOut queues), so a slow
// extra can't hold up the main output.
type fanOut struct {
	main   io.Writer
	extras []*ffmpegOut
}

func (f *fanOut) Write(p []byte) (int, error) {
	for _, e := range f.extras {
		_, _ = e.Write(p)
	}
	return f.main.Write(p)
}

func (f *fanOut) closeExtras() {
	for _, e := range f.extras {
		_ = e.Close()
	}
}
