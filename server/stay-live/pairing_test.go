// SPDX-License-Identifier: Apache-2.0
package main

import (
	"os"
	"path/filepath"
	"testing"
)

// Fixed vectors for everything derived from the pairing token. The app (BrbServer.kt: publishId, srtPassphrase)
// must derive the same strings byte for byte, or a paired phone is refused; a Kotlin test should use these same
// vectors. They were computed independently (Python hashlib), not by this code.
var pairingVectors = []struct {
	token                                  string
	publish, srtPass, play, playPass, stat string
}{
	{
		token:    "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8", // base64url of bytes 0..31, like a real token
		publish:  "d626a5398610ff11",
		srtPass:  "xINU-9uyBwkcERob11iEoXUyn6OuzyNkugtg3NT-6Lc",
		play:     "7c10c063c1785909",
		playPass: "wZF8HEBlLqSNUQHlTghbHioetxkf46eBzGig3RdM2po",
		stat:     "586e63785c38aaed9628a891",
	},
	{
		token:    "test-token",
		publish:  "cfb39034811c7a98",
		srtPass:  "Prj0-zwvLkuB0JOHMM0LaLPSJEVM_CzkoxoeBGLjrq0",
		play:     "c2c003c02a96536e",
		playPass: "zfdF8aFU2S98v23Ej2tsUuBY88NQviQmOww4gCdIHjA",
		stat:     "2b3d3232df4cf2d2274e32e9",
	},
}

func TestPairingDerivationVectors(t *testing.T) {
	for _, v := range pairingVectors {
		if got := publishID(v.token); got != v.publish {
			t.Errorf("publishID(%q) = %s, want %s", v.token, got, v.publish)
		}
		if got := srtPassphrase(v.token); got != v.srtPass {
			t.Errorf("srtPassphrase(%q) = %s, want %s", v.token, got, v.srtPass)
		}
		if got := playID(v.token); got != v.play {
			t.Errorf("playID(%q) = %s, want %s", v.token, got, v.play)
		}
		if got := playPassphrase(v.token); got != v.playPass {
			t.Errorf("playPassphrase(%q) = %s, want %s", v.token, got, v.playPass)
		}
		if got := statsKey(v.token); got != v.stat {
			t.Errorf("statsKey(%q) = %s, want %s", v.token, got, v.stat)
		}
	}
}

// `brb pair -reset` is what you run after a leak: the mod remote's link must stop working too.
func TestResetPairingRotatesModRemoteLink(t *testing.T) {
	dir := t.TempDir()
	old, _ := loadPairing(dir, false)
	rm := &remote{dir: dir}
	oldLink, err := rm.secret(true)
	if err != nil {
		t.Fatal(err)
	}
	p, rotated, err := resetPairing(dir)
	if err != nil {
		t.Fatal(err)
	}
	if p.Token == old.Token || !rotated {
		t.Fatalf("reset: token changed=%v, remote rotated=%v", p.Token != old.Token, rotated)
	}
	if now, _ := rm.secret(false); now == oldLink || now == "" {
		t.Fatal("the old mod remote link still works after a reset")
	}

	// No mod remote yet: nothing to rotate, and none is created.
	dir = t.TempDir()
	if _, rotated, err := resetPairing(dir); err != nil || rotated {
		t.Fatalf("reset without a remote: rotated=%v err=%v", rotated, err)
	}
	if _, err := os.Stat(filepath.Join(dir, remoteFile)); !os.IsNotExist(err) {
		t.Fatal("a reset must not create a mod remote link")
	}
}

// A damaged pairing.json means "not paired": nothing is accepted against it.
func TestDamagedPairingFailsClosed(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(filepath.Join(dir, pairingFile), []byte("{not json"), 0o600); err != nil {
		t.Fatal(err)
	}
	if readToken(dir) != "" || tokenMatches(dir, "") || tokenMatches(dir, "anything") {
		t.Fatal("a damaged pairing must not match any token")
	}
}
