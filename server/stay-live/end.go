// SPDX-License-Identifier: Apache-2.0
package main

import (
	"context"
	"errors"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"time"
)

// Ending the stream on purpose. An SRT session ending says nothing about why: the phone also closes its old session
// when it reconnects, and the server ends one it can't read. Treating every clean close as "the streamer ended"
// took the first real stream off Twitch twice. So the app says it: POST /v1/end on the control API when the streamer
// stops. The control API and the keeper are separate processes sharing the data directory, so the request is a file
// the keeper picks up (watchEndRequests).
//
//	POST /v1/end   (paired) the streamer ended the stream deliberately -> 200 {"ok":true}

const (
	// endRequestFile holds the time of the app's last POST /v1/end. The keeper takes it (deletes it) when it reads it.
	endRequestFile = "end.request"
	// appEndsFile records that this server's app calls /v1/end, so the keeper can drop its fallback for apps that
	// don't (a clean close with no reconnect within cleanEndWait). It stays until the data directory is cleared.
	appEndsFile = "app-calls-end"
	// endPoll is how often the keeper looks for a request.
	endPoll = 250 * time.Millisecond
)

func (c *control) end(w http.ResponseWriter, r *http.Request) {
	if err := requestEnd(c.dir, time.Now()); err != nil {
		log.Printf("control: can't record the end of the stream: %v", err)
		writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "can't record it"})
		return
	}
	log.Printf("control: the app ended the stream")
	writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
}

// requestEnd records an end request for the keeper.
func requestEnd(dir string, now time.Time) error {
	if _, err := os.Stat(filepath.Join(dir, appEndsFile)); errors.Is(err, os.ErrNotExist) {
		_ = writeFileAtomic(filepath.Join(dir, appEndsFile), []byte(now.UTC().Format(time.RFC3339)+"\n"), 0o644)
	}
	return writeFileAtomic(filepath.Join(dir, endRequestFile), []byte(now.UTC().Format(time.RFC3339Nano)+"\n"), 0o644)
}

// takeEndRequest returns the time of a pending end request and removes it, so it is acted on once.
func takeEndRequest(dir string) (time.Time, bool) {
	path := filepath.Join(dir, endRequestFile)
	b, err := os.ReadFile(path)
	if err != nil {
		return time.Time{}, false
	}
	_ = os.Remove(path)
	at, err := time.Parse(time.RFC3339Nano, strings.TrimSpace(string(b)))
	if err != nil {
		return time.Time{}, false
	}
	return at, true
}

// appCallsEnd reports whether this server's app has ever called /v1/end.
func appCallsEnd(dir string) bool {
	_, err := os.Stat(filepath.Join(dir, appEndsFile))
	return err == nil
}

// watchEndRequests hands the keeper each end request made after it started (started). One from before belongs to an
// earlier stream (made while no keeper was running, or a retry after it had already ended) and is dropped.
func watchEndRequests(ctx context.Context, dir string, started time.Time, ends chan<- struct{}) {
	t := time.NewTicker(endPoll)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			if at, ok := takeEndRequest(dir); ok && !at.Before(started) {
				select {
				case ends <- struct{}{}:
				default: // one is already waiting
				}
			}
		}
	}
}
