// SPDX-License-Identifier: Apache-2.0
package main

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"
)

// The mod remote: a web page the streamer's mods open to switch scenes, served by the control API (TLS with the
// server's own key, so browsers warn once). Mods get a secret link, never the pairing token. The server only holds
// their picks: the phone collects them while it's live (POST /v1/remote/poll, every ~2 s) and applies them like the
// mods' chat commands, and reports what viewers see so the page can show it.
//
//	GET  /v1/remote          (paired) the remote's path, creating its secret if needed
//	POST /v1/remote/reset    (paired) a new secret: old links stop working
//	POST /v1/remote/poll     (paired) {"live","scene","mic","scenes"} in, {"commands":[{"id","scene"}]} out
//	GET  /remote/{secret}         the page
//	GET  /remote/{secret}/status  {"online","live","scene","mic","air","scenes"}
//	POST /remote/{secret}/scene   {"scene":"BRB"}
//
// "scenes" are the streamer's own scenes the phone reports ([{"id":"CUSTOM_1","name":"Lunch"}]), so the page can
// offer a button for each; an older phone sends none and the page shows the built-in five.
type remote struct {
	dir     string
	mu      sync.Mutex
	pending []remoteCmd
	nextID  int
	status  remoteStatus
	seen    time.Time // last poll from the phone
	keeper  func() string
}

type remoteCmd struct {
	ID    int       `json:"id"`
	Scene string    `json:"scene"`
	at    time.Time // dropped if the phone hasn't collected it within remoteCmdTTL
}

type remoteStatus struct {
	Live   bool          `json:"live"`
	Scene  string        `json:"scene"`
	Mic    bool          `json:"mic"`
	Scenes []remoteScene `json:"scenes,omitempty"`
}

// remoteScene is one of the streamer's own scenes: its fixed slot and the name they gave it.
type remoteScene struct {
	ID   string `json:"id"`
	Name string `json:"name"`
}

const (
	remoteFile    = "remote.token"
	remoteCmdTTL  = 30 * time.Second
	remoteOnline  = 10 * time.Second // the phone counts as connected if it polled within this
	remoteMaxCmds = 5
	remoteNameMax = 20 // runes; the phone keeps names to 20 characters too
)

// The streamer's own scenes are fixed slots; the phone only acts on one it has.
var remoteCustom = map[string]bool{"CUSTOM_1": true, "CUSTOM_2": true, "CUSTOM_3": true, "CUSTOM_4": true}

var remoteScenes = map[string]bool{
	"LIVE": true, "BRB": true, "PRIVACY": true, "STARTING_SOON": true, "ENDING": true,
	"CUSTOM_1": true, "CUSTOM_2": true, "CUSTOM_3": true, "CUSTOM_4": true,
}

// ownScenes keeps what the phone reported that can be one of the streamer's scenes: known slots, once each, in
// order, with a name (trimmed, at most remoteNameMax runes). The page only ever sets names as text.
func ownScenes(in []remoteScene) []remoteScene {
	out := []remoteScene{}
	seen := map[string]bool{}
	for _, sc := range in {
		if !remoteCustom[sc.ID] || seen[sc.ID] {
			continue
		}
		name := strings.TrimSpace(sc.Name)
		if r := []rune(name); len(r) > remoteNameMax {
			name = strings.TrimSpace(string(r[:remoteNameMax]))
		}
		if name == "" {
			continue
		}
		seen[sc.ID] = true
		out = append(out, remoteScene{ID: sc.ID, Name: name})
	}
	return out
}

func (rm *remote) secret(create bool) (string, error) {
	path := filepath.Join(rm.dir, remoteFile)
	if b, err := os.ReadFile(path); err == nil && len(strings.TrimSpace(string(b))) >= 32 {
		return strings.TrimSpace(string(b)), nil
	}
	if !create {
		return "", os.ErrNotExist
	}
	return rm.newSecret()
}

func (rm *remote) newSecret() (string, error) {
	b := make([]byte, 16) // 128 bits
	if _, err := rand.Read(b); err != nil {
		return "", err
	}
	s := hex.EncodeToString(b)
	tmp := filepath.Join(rm.dir, remoteFile+".tmp")
	if err := os.WriteFile(tmp, []byte(s+"\n"), 0o600); err != nil {
		return "", err
	}
	return s, os.Rename(tmp, filepath.Join(rm.dir, remoteFile))
}

// valid reports whether a mod's link carries the current secret (constant time; slow on a miss).
func (rm *remote) valid(got string) bool {
	want, err := rm.secret(false)
	if err != nil || subtle.ConstantTimeCompare([]byte(got), []byte(want)) != 1 {
		time.Sleep(time.Second)
		return false
	}
	return true
}

func (rm *remote) routes(mux *http.ServeMux, auth func(http.HandlerFunc) http.HandlerFunc) {
	mux.HandleFunc("GET /v1/remote", auth(func(w http.ResponseWriter, r *http.Request) {
		s, err := rm.secret(true)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "can't create the remote"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]string{"path": "/remote/" + s})
	}))
	mux.HandleFunc("POST /v1/remote/reset", auth(func(w http.ResponseWriter, r *http.Request) {
		s, err := rm.newSecret()
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]string{"error": "can't create the remote"})
			return
		}
		rm.mu.Lock()
		rm.pending = nil
		rm.mu.Unlock()
		writeJSON(w, http.StatusOK, map[string]string{"path": "/remote/" + s})
	}))
	mux.HandleFunc("POST /v1/remote/poll", auth(func(w http.ResponseWriter, r *http.Request) {
		var st remoteStatus
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&st); err != nil {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "bad status"})
			return
		}
		writeJSON(w, http.StatusOK, map[string]any{"commands": rm.poll(st, time.Now())})
	}))
	mux.HandleFunc("GET /remote/{secret}", func(w http.ResponseWriter, r *http.Request) {
		if !rm.valid(r.PathValue("secret")) {
			http.NotFound(w, r)
			return
		}
		h := w.Header()
		h.Set("Content-Type", "text/html; charset=utf-8")
		h.Set("Cache-Control", "no-store")
		h.Set("Referrer-Policy", "no-referrer") // the secret is in the URL
		h.Set("X-Frame-Options", "DENY")
		h.Set("Content-Security-Policy", "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'")
		_, _ = w.Write([]byte(remotePage))
	})
	mux.HandleFunc("GET /remote/{secret}/status", func(w http.ResponseWriter, r *http.Request) {
		if !rm.valid(r.PathValue("secret")) {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Cache-Control", "no-store")
		writeJSON(w, http.StatusOK, rm.snapshot(time.Now()))
	})
	mux.HandleFunc("POST /remote/{secret}/scene", func(w http.ResponseWriter, r *http.Request) {
		if !rm.valid(r.PathValue("secret")) {
			http.NotFound(w, r)
			return
		}
		var body struct {
			Scene string `json:"scene"`
		}
		if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&body); err != nil || !remoteScenes[body.Scene] {
			writeJSON(w, http.StatusBadRequest, map[string]string{"error": "unknown scene"})
			return
		}
		rm.add(body.Scene, time.Now())
		writeJSON(w, http.StatusOK, map[string]bool{"ok": true})
	})
}

// add queues a mod's pick; the newest few are kept.
func (rm *remote) add(scene string, now time.Time) {
	rm.mu.Lock()
	defer rm.mu.Unlock()
	rm.nextID++
	rm.pending = append(rm.pending, remoteCmd{ID: rm.nextID, Scene: scene, at: now})
	if len(rm.pending) > remoteMaxCmds {
		rm.pending = rm.pending[len(rm.pending)-remoteMaxCmds:]
	}
}

// poll records what viewers see and hands the phone the picks waiting for it (dropping stale ones).
func (rm *remote) poll(st remoteStatus, now time.Time) []remoteCmd {
	st.Scenes = ownScenes(st.Scenes)
	rm.mu.Lock()
	defer rm.mu.Unlock()
	rm.status, rm.seen = st, now
	out := []remoteCmd{}
	for _, c := range rm.pending {
		if now.Sub(c.at) <= remoteCmdTTL {
			out = append(out, c)
		}
	}
	rm.pending = nil
	return out
}

// snapshot is what the page shows. "air" is the keeper's own view of what viewers see (live, slate, weak, waiting,
// error, unknown): during a dropout the phone can't poll, but viewers are watching the BRB slate, and the page
// should say so rather than "isn't live".
func (rm *remote) snapshot(now time.Time) map[string]any {
	air := "unknown"
	if rm.keeper != nil {
		air = rm.keeper() // a file read: outside the lock
	}
	rm.mu.Lock()
	defer rm.mu.Unlock()
	online := !rm.seen.IsZero() && now.Sub(rm.seen) <= remoteOnline
	scenes := rm.status.Scenes
	if scenes == nil {
		scenes = []remoteScene{}
	}
	return map[string]any{"online": online, "live": online && rm.status.Live, "scene": rm.status.Scene, "mic": rm.status.Mic, "air": air, "scenes": scenes}
}

// remotePage is the mods' page: what viewers see, and a button per scene (the streamer's own under the built-in
// five, named with textContent only). No external resources.
const remotePage = `<!doctype html><html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1"><meta name="robots" content="noindex">
<title>Fullbars mod remote</title>
<style>
:root{color-scheme:dark;--bg:#111317;--card:#1d2027;--ink:#f4f1ea;--dim:#a7abb4;--accent:#ffd666}
body{margin:0;background:var(--bg);color:var(--ink);font:16px/1.4 system-ui,sans-serif}
main{max-width:480px;margin:0 auto;padding:16px}
h1{font-size:20px;margin:8px 0 4px}#st{color:var(--dim);margin:0 0 16px;min-height:1.4em}
.grid{display:grid;grid-template-columns:1fr 1fr;gap:12px}
button{min-height:64px;border-radius:14px;border:2px solid #3a3f4a;background:var(--card);color:var(--ink);font:600 17px system-ui,sans-serif}
button.on{border-color:var(--accent);box-shadow:0 0 0 2px var(--accent) inset}
button:disabled{opacity:.45}#live{grid-column:1/-1;min-height:72px}
#own{margin-top:12px}#own:empty{display:none}
#msg{color:var(--dim);margin-top:16px;min-height:1.4em}
</style></head><body><main>
<h1>Mod remote</h1><p id="st">Connecting…</p>
<div class="grid">
<button id="live" data-s="LIVE">Camera</button>
<button data-s="BRB">BRB</button><button data-s="PRIVACY">Privacy (mic off)</button>
<button data-s="STARTING_SOON">Starting soon</button><button data-s="ENDING">Ending</button>
</div><div class="grid" id="own"></div><p id="msg"></p></main>
<script>
var base=location.pathname.replace(/\/$/,''),names={LIVE:'Camera',BRB:'BRB',PRIVACY:'Privacy',STARTING_SOON:'Starting soon',ENDING:'Ending'};
var own={},ownKey='',grid=document.getElementById('own'),st=document.getElementById('st'),msg=document.getElementById('msg');
function nameOf(id){return names[id]||own[id]||id}
function send(b){msg.textContent='Sending…';
fetch(base+'/scene',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({scene:b.dataset.s})})
.then(function(r){msg.textContent=r.ok?'Sent: '+nameOf(b.dataset.s)+'. It takes a couple of seconds.':'Didn\'t work. Reload the page.'})
.catch(function(){msg.textContent='Can\'t reach the server.'})}
function render(list){list=list||[];var k=JSON.stringify(list);if(k===ownKey)return;ownKey=k;own={};grid.textContent='';
list.forEach(function(x){own[x.id]=x.name;var b=document.createElement('button');b.dataset.s=x.id;b.textContent=x.name;b.onclick=function(){send(b)};grid.appendChild(b)})}
function show(s){render(s.scenes);var on=s.online&&s.live;
var held=s.air==='slate'||s.air==='weak',screen=s.air==='weak'?'Weak signal':'BRB';
st.textContent=!s.online?(held?'Viewers see the '+screen+' screen. The streamer\'s phone is reconnecting; scene buttons come back when it does.':'The streamer isn\'t live (or their phone can\'t reach the server).')
:s.air==='weak'?'Live · viewers see the Weak signal screen until the connection steadies'
:'Live · viewers see '+nameOf(s.scene)+(s.mic?'':' · mic off');
document.querySelectorAll('button').forEach(function(b){b.disabled=!on;b.classList.toggle('on',on&&b.dataset.s===s.scene)})}
function poll(){fetch(base+'/status',{cache:'no-store'}).then(function(r){return r.json()}).then(show).catch(function(){st.textContent='Can\'t reach the server.'})}
document.querySelectorAll('button').forEach(function(b){b.onclick=function(){send(b)}});
poll();setInterval(poll,2000);
</script></body></html>`
