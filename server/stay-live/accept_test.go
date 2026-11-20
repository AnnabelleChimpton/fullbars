// SPDX-License-Identifier: Apache-2.0
package main

import (
	"context"
	"testing"
	"time"

	srt "github.com/datarhei/gosrt"
)

// At the end of a stream main closes the listener while the process is still shutting down: the accept loop must
// stop instead of spinning on Accept2's immediate error (it logged ~200 lines in half a millisecond).
func TestAcceptLoopStopsWhenListenerCloses(t *testing.T) {
	ln, err := srt.Listen("srt", "127.0.0.1:0", srt.DefaultConfig())
	if err != nil {
		t.Fatalf("listen: %v", err)
	}
	done := make(chan struct{})
	go func() {
		acceptLoop(context.Background(), ln, make(chan liveEvent, 1), make(chan slateUpdate, 1), make(chan qualityEvent, 1), "", "", func(string) {})
		close(done)
	}()
	time.Sleep(50 * time.Millisecond)
	ln.Close()
	select {
	case <-done:
	case <-time.After(2 * time.Second):
		t.Fatal("accept loop still running after the listener closed")
	}
}
