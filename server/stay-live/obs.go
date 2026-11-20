// SPDX-License-Identifier: Apache-2.0
package main

import (
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	srt "github.com/datarhei/gosrt"
)

// The "My OBS" output. Instead of pushing to Twitch, the keeper serves its continuous stream over
// SRT and home OBS pulls it with a Media Source. OBS connects out to the server, so nothing at home needs a forwarded
// port; and the keeper stays between phone and OBS, so a tunnel never becomes an OBS reconnect. That matters: a
// single-session SRT listener such as OBS's media source won't take the phone's new session until the old one times
// out, which turns a 15 s tunnel into a ~30 s outage. The keeper takes the newest session at once.
// NOALBS reads the server's stats (statsJSON, Belabox format) to switch OBS scenes on low bitrate / offline.
//
// OBS's credentials are derived from the pairing but differ from the phone's: a leaked OBS URL can watch the stream,
// not publish to the server.

const (
	obsMode   = "obs" // stored output value for this mode
	statsFile = "stats.json"
	publisher = "live" // the NOALBS "publisher" name
)

func playID(token string) string {
	sum := sha256.Sum256([]byte("fullbars-play:" + token))
	return hex.EncodeToString(sum[:8])
}

func playPassphrase(token string) string {
	sum := sha256.Sum256([]byte("fullbars-play-srt:" + token))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func statsKey(token string) string {
	sum := sha256.Sum256([]byte("fullbars-stats:" + token))
	return hex.EncodeToString(sum[:12])
}

// pullServer serves the keeper's MPEG-TS to SRT callers (OBS) that present the play stream id and passphrase.
type pullServer struct {
	ln     srt.Listener
	events func(string)
	mu     sync.Mutex
	subs   map[*subscriber]struct{}
	carry  []byte // bytes not yet forming a full SRT payload
	pat    []byte // latest PAT and PMT packets, so a joining viewer can decode from its first keyframe
	pmt    []byte
	pmtPID int
}

type subscriber struct {
	conn    srt.Conn
	ch      chan []byte
	started bool // has had its first keyframe; before that it gets nothing
}

const srtPayload = 7 * 188

func startPull(addr, dir string, events func(string)) (*pullServer, error) {
	cfg := srt.DefaultConfig()
	cfg.ReceiverLatency = 2 * time.Second
	cfg.PeerLatency = 2 * time.Second
	cfg.PeerIdleTimeout = 10 * time.Second
	ln, err := srt.Listen("srt", addr, cfg)
	if err != nil {
		return nil, err
	}
	p := &pullServer{ln: ln, events: events, subs: map[*subscriber]struct{}{}}
	go p.accept(dir)
	return p, nil
}

func (p *pullServer) accept(dir string) {
	for {
		req, err := p.ln.Accept2()
		if err != nil {
			return // listener closed
		}
		token := readToken(dir)
		id, _, _ := strings.Cut(strings.TrimPrefix(req.StreamId(), "/"), "?")
		if token == "" || id != "play/"+playID(token) || !req.IsEncrypted() {
			req.Reject(srt.REJ_PEER)
			p.events(fmt.Sprintf("OBS output: refused %s (wrong stream id or not encrypted)", req.RemoteAddr()))
			continue
		}
		if err := req.SetPassphrase(playPassphrase(token)); err != nil {
			req.Reject(srt.REJ_BADSECRET)
			continue
		}
		conn, err := req.Accept()
		if err != nil {
			continue
		}
		s := &subscriber{conn: conn, ch: make(chan []byte, 2048)} // ~2.7 MB: a few seconds at full bitrate
		p.mu.Lock()
		p.subs[s] = struct{}{}
		n := len(p.subs)
		p.mu.Unlock()
		p.events(fmt.Sprintf("OBS output: %s connected (%d watching)", conn.RemoteAddr(), n))
		go p.serve(s)
	}
}

func (p *pullServer) serve(s *subscriber) {
	defer func() {
		p.mu.Lock()
		delete(p.subs, s)
		p.mu.Unlock()
		_ = s.conn.Close()
	}()
	for b := range s.ch {
		if _, err := s.conn.Write(b); err != nil {
			p.events(fmt.Sprintf("OBS output: %s disconnected (%v)", s.conn.RemoteAddr(), err))
			return
		}
	}
}

// Write fans the TS out to every connected OBS in whole SRT payloads. A viewer that joins mid-stream starts at the
// next keyframe, preceded by the stream's PAT/PMT, so OBS never shows a broken picture while it waits for one. A
// viewer that falls seconds behind is dropped (it reconnects) rather than slowing the keeper down.
func (p *pullServer) Write(b []byte) (int, error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	p.carry = append(p.carry, b...)
	for len(p.carry) >= srtPayload {
		chunk := append([]byte(nil), p.carry[:srtPayload]...)
		p.carry = p.carry[srtPayload:]
		key := p.scan(chunk) // offset of a keyframe's first packet in the chunk, or -1
		for s := range p.subs {
			msgs := [][]byte{chunk}
			if !s.started {
				if key < 0 || p.pat == nil || p.pmt == nil {
					continue
				}
				s.started = true
				// Tables first, then the stream from the keyframe's own packet (each within one SRT payload).
				msgs = [][]byte{append(append([]byte(nil), p.pat...), p.pmt...), chunk[key:]}
			}
			for _, m := range msgs {
				select {
				case s.ch <- m:
					continue
				default:
				}
				close(s.ch)
				delete(p.subs, s)
				p.events(fmt.Sprintf("OBS output: %s fell behind, dropped", s.conn.RemoteAddr()))
				break
			}
		}
	}
	return len(b), nil
}

// scan remembers the newest PAT/PMT in a chunk and returns the offset of a video keyframe's first packet (the
// keeper's muxer sets random_access_indicator there), or -1.
func (p *pullServer) scan(chunk []byte) (keyframe int) {
	keyframe = -1
	for i := 0; i+188 <= len(chunk); i += 188 {
		pkt := chunk[i : i+188]
		if pkt[0] != 0x47 {
			continue
		}
		pid := int(pkt[1]&0x1f)<<8 | int(pkt[2])
		pusi := pkt[1]&0x40 != 0
		switch {
		case pid == 0 && pusi:
			p.pat = append(p.pat[:0], pkt...)
			if v := pmtPIDFromPAT(pkt); v > 0 {
				p.pmtPID = v
			}
		case pid == p.pmtPID && pusi:
			p.pmt = append(p.pmt[:0], pkt...)
		case pid == outVideoPID && pusi && pkt[3]&0x20 != 0 && pkt[4] > 0 && pkt[5]&0x40 != 0:
			if keyframe < 0 {
				keyframe = i
			}
		}
	}
	return keyframe
}

// pmtPIDFromPAT reads the first program's PMT PID from a single-packet PAT.
func pmtPIDFromPAT(pkt []byte) int {
	off := 4
	if pkt[3]&0x20 != 0 {
		off += 1 + int(pkt[4])
	}
	if off >= len(pkt) {
		return 0
	}
	off += 1 + int(pkt[off]) // pointer field
	if off+12 > len(pkt) {
		return 0
	}
	sec := pkt[off:]
	secLen := int(sec[1]&0x0f)<<8 | int(sec[2])
	for e := 8; e+4 <= 3+secLen-4 && e+4 <= len(sec); e += 4 {
		if prog := int(sec[e])<<8 | int(sec[e+1]); prog != 0 {
			return int(sec[e+2]&0x1f)<<8 | int(sec[e+3])
		}
	}
	return 0
}

// Close is a no-op for the sink: the listener lives as long as the keeper process (OBS stays connected through the
// session); shutdown closes it.
func (p *pullServer) Close() error { return nil }

func (p *pullServer) shutdown() {
	p.ln.Close()
	p.mu.Lock()
	for s := range p.subs {
		close(s.ch)
		delete(p.subs, s)
	}
	p.mu.Unlock()
}

// --- stats for NOALBS ---------------------------------------------------------------------------------------------

type publisherStats struct {
	Bitrate     int64   `json:"bitrate"`      // kbps
	RTT         float64 `json:"rtt"`          // ms
	DroppedPkts int64   `json:"dropped_pkts"` // packets actually lost (not gosrt's "dropped", which counts duplicate resends)
	// ReadQueueDrops: packets received but dropped because the keeper fell behind reading them; NOALBS ignores it.
	ReadQueueDrops int64 `json:"read_queue_drops,omitempty"`
}

// writeStats publishes the phone's current ingest stats (nil = no publisher: NOALBS treats it as offline), and next
// to them, under "server", the keeper's and the output's counters (NOALBS reads only "publishers").
func writeStats(dir string, st *publisherStats) {
	pubs := map[string]publisherStats{}
	if st != nil {
		pubs[publisher] = *st
	}
	b, _ := json.Marshal(map[string]any{"publishers": pubs, "server": liveCounters.Load().snapshot()})
	_ = writeFileAtomic(filepath.Join(dir, statsFile), b, 0o644)
}

// readStats returns the stats JSON, or no publishers when it is stale (keeper restarting / gone).
func readStats(dir string) []byte {
	path := filepath.Join(dir, statsFile)
	if fi, err := os.Stat(path); err == nil && time.Since(fi.ModTime()) < 3*time.Second {
		if b, err := os.ReadFile(path); err == nil {
			return b
		}
	}
	return []byte(`{"publishers":{}}`)
}

// runObs prints the OBS Media Source and NOALBS settings: `brb obs`.
func runObs(dir, host string, playPort, statsPort int) error {
	token := readToken(dir)
	if token == "" {
		return fmt.Errorf("this server isn't paired yet: run `brb pair` and pair the app first")
	}
	if host == "" {
		return fmt.Errorf("the server's public address is unknown: set PUBLIC_HOST (deploy.sh does) or pass -host")
	}
	input := fmt.Sprintf("srt://%s:%d?mode=caller&streamid=play/%s&passphrase=%s&pbkeylen=16&latency=2000000",
		host, playPort, playID(token), playPassphrase(token))
	stats := fmt.Sprintf("http://%s:%d/stats/%s", host, statsPort, statsKey(token))
	fmt.Printf(`
Home OBS settings (treat these like passwords)

1. In the Fullbars app: Combine connections > Send to: My OBS.

2. OBS > Sources > + > Media Source:
     Local File:          off
     Input:               %s
     Input Format:        mpegts
     Reconnect Delay:     2 s
     Restart playback when source becomes active: on
     Use hardware decoding when available: on

3. NOALBS config.json, "streamServers" entry:
     {
       "streamServer": { "type": "Belabox", "statsUrl": "%s", "publisher": "%s" },
       "name": "Fullbars BRB server",
       "priority": 0,
       "overrideScenes": null,
       "dependsOn": null,
       "enabled": true
     }
   NOALBS switches to your Offline scene when the phone drops out, and back when it returns.

`, input, stats, publisher)
	return nil
}
