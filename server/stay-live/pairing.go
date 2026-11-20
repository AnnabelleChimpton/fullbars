// SPDX-License-Identifier: Apache-2.0
package main

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/subtle"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"encoding/pem"
	"errors"
	"fmt"
	"log"
	"math/big"
	"net/url"
	"os"
	"path/filepath"
	"strconv"
	"strings"
	"sync/atomic"
	"time"

	"rsc.io/qr"
)

// Pairing: the app and this server share a random token, and the app pins this server's self-signed TLS key, both
// carried in one QR code / link:
//
//	fullbars://pair?v=1&h=HOST&p=PORT&k=TOKEN&f=SPKI_SHA256&n=NAME
//
// No domain or CA is needed and nobody in between can read what the app sends (its Twitch stream key). Files live
// in the data directory: pairing.json (token, 0600), tls.crt / tls.key (0600).

const (
	pairingFile = "pairing.json"
	certFile    = "tls.crt"
	keyFile     = "tls.key"
	outputFile  = "output.url"
)

type pairing struct {
	Token   string    `json:"token"` // base64url, 32 random bytes
	Created time.Time `json:"created"`
}

// loadPairing reads the pairing, creating a new token when there is none (or when reset is set).
func loadPairing(dir string, reset bool) (*pairing, error) {
	path := filepath.Join(dir, pairingFile)
	if !reset {
		if b, err := os.ReadFile(path); err == nil {
			var p pairing
			if err := json.Unmarshal(b, &p); err == nil && p.Token != "" {
				return &p, nil
			}
		} else if !errors.Is(err, os.ErrNotExist) {
			return nil, err
		}
	}
	raw := make([]byte, 32)
	if _, err := rand.Read(raw); err != nil {
		return nil, err
	}
	p := &pairing{Token: base64.RawURLEncoding.EncodeToString(raw), Created: time.Now().UTC()}
	b, _ := json.Marshal(p)
	if err := writeFileAtomic(path, b, 0o600); err != nil {
		return nil, err
	}
	return p, nil
}

// readToken returns the current token, or "" when the server has never been paired or pairing.json can't be read.
// Callers treat "" as "refuse": the keeper rejects every stream and the control API every call.
func readToken(dir string) string {
	b, err := os.ReadFile(filepath.Join(dir, pairingFile))
	if err != nil {
		if !errors.Is(err, os.ErrNotExist) {
			logTokenProblem(fmt.Sprintf("can't read %s: %v", pairingFile, err))
		}
		return ""
	}
	var p pairing
	if err := json.Unmarshal(b, &p); err != nil {
		logTokenProblem(fmt.Sprintf("%s is damaged (%v); pair again with `brb pair -reset`", pairingFile, err))
		return ""
	}
	return p.Token
}

// lastTokenProblem keeps a broken pairing.json from logging on every request and every knock on the port.
var lastTokenProblem atomic.Value

func logTokenProblem(msg string) {
	if prev, _ := lastTokenProblem.Swap(msg).(string); prev != msg {
		log.Printf("pairing: %s", msg)
	}
}

// publishID is what a paired app puts in its SRT stream id ("live/ID", "slate/ID"). Derived from the token so it
// changes when the pairing is reset, and so the token itself never travels in the clear over SRT.
func publishID(token string) string {
	sum := sha256.Sum256([]byte("fullbars-publish:" + token))
	return hex.EncodeToString(sum[:8])
}

// srtPassphrase encrypts a paired app's SRT streams (AES, SRT's own key exchange). Derived from the token like
// publishID, so pairing sets it up with nothing to type; 43 characters (SRT allows 10-79).
func srtPassphrase(token string) string {
	sum := sha256.Sum256([]byte("fullbars-srt:" + token))
	return base64.RawURLEncoding.EncodeToString(sum[:])
}

func tokenMatches(dir, presented string) bool {
	want := readToken(dir)
	if want == "" || presented == "" {
		return false
	}
	return subtle.ConstantTimeCompare([]byte(want), []byte(presented)) == 1
}

// loadOrCreateCert returns this server's TLS certificate (self-signed ECDSA P-256, created on first use) and the
// base64url SHA-256 of its public key (what the app pins).
func loadOrCreateCert(dir string) (tls.Certificate, string, error) {
	cp, kp := filepath.Join(dir, certFile), filepath.Join(dir, keyFile)
	if _, err := os.Stat(cp); errors.Is(err, os.ErrNotExist) {
		key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
		if err != nil {
			return tls.Certificate{}, "", err
		}
		serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 62))
		tmpl := &x509.Certificate{
			SerialNumber: serial,
			Subject:      pkix.Name{CommonName: "fullbars-brb"},
			NotBefore:    time.Now().Add(-time.Hour),
			NotAfter:     time.Now().AddDate(20, 0, 0),
			KeyUsage:     x509.KeyUsageDigitalSignature,
			ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		}
		der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
		if err != nil {
			return tls.Certificate{}, "", err
		}
		kb, _ := x509.MarshalECPrivateKey(key)
		if err := writeFileAtomic(kp, pem.EncodeToMemory(&pem.Block{Type: "EC PRIVATE KEY", Bytes: kb}), 0o600); err != nil {
			return tls.Certificate{}, "", err
		}
		if err := writeFileAtomic(cp, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der}), 0o644); err != nil {
			return tls.Certificate{}, "", err
		}
	}
	cert, err := tls.LoadX509KeyPair(cp, kp)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	leaf, err := x509.ParseCertificate(cert.Certificate[0])
	if err != nil {
		return tls.Certificate{}, "", err
	}
	spki, err := x509.MarshalPKIXPublicKey(leaf.PublicKey)
	if err != nil {
		return tls.Certificate{}, "", err
	}
	sum := sha256.Sum256(spki)
	return cert, base64.RawURLEncoding.EncodeToString(sum[:]), nil
}

func pairingLink(host string, port int, token, pin, name string) string {
	q := url.Values{}
	q.Set("v", "1")
	q.Set("h", host)
	q.Set("p", strconv.Itoa(port))
	q.Set("k", token)
	q.Set("f", pin)
	if name != "" {
		q.Set("n", name)
	}
	return "fullbars://pair?" + q.Encode()
}

// renderQR draws a QR code for terminals with Unicode half blocks (two modules per character row), dark modules as
// the terminal's foreground on an inverted quiet zone so it scans on dark and light themes alike.
func renderQR(text string) (string, error) {
	code, err := qr.Encode(text, qr.M)
	if err != nil {
		return "", err
	}
	const quiet = 2
	n := code.Size
	dark := func(x, y int) bool {
		if x < 0 || y < 0 || x >= n || y >= n {
			return false
		}
		return code.Black(x, y)
	}
	var b strings.Builder
	for y := -quiet; y < n+quiet; y += 2 {
		for x := -quiet; x < n+quiet; x++ {
			top, bot := dark(x, y), dark(x, y+1)
			// Light modules are drawn as filled blocks (white on a dark terminal), dark ones as spaces.
			switch {
			case !top && !bot:
				b.WriteString("█")
			case !top && bot:
				b.WriteString("▀")
			case top && !bot:
				b.WriteString("▄")
			default:
				b.WriteString(" ")
			}
		}
		b.WriteString("\n")
	}
	return b.String(), nil
}

// resetPairing makes a new pairing token and, if the mod remote has a link, a new one of those too: a reset is what
// you do after a leak, and the mod remote's link is just as much a key to your stream.
func resetPairing(dir string) (p *pairing, remoteRotated bool, err error) {
	if p, err = loadPairing(dir, true); err != nil {
		return nil, false, err
	}
	rm := &remote{dir: dir}
	if _, err := rm.secret(false); err == nil {
		if _, err := rm.newSecret(); err != nil {
			return p, false, fmt.Errorf("new pairing made, but the mod remote link couldn't be changed: %w", err)
		}
		remoteRotated = true
	}
	return p, remoteRotated, nil
}

// runPair prints the pairing QR and link: `brb pair -host PUBLIC_HOST [-reset]`.
func runPair(dir, host string, port int, name string, reset bool) error {
	if host == "" {
		return fmt.Errorf("the server's public address is unknown: set PUBLIC_HOST (deploy.sh does) or pass -host")
	}
	var p *pairing
	var remoteRotated bool
	var err error
	if reset {
		p, remoteRotated, err = resetPairing(dir)
	} else {
		p, err = loadPairing(dir, false)
	}
	if err != nil {
		return err
	}
	_, pin, err := loadOrCreateCert(dir)
	if err != nil {
		return err
	}
	link := pairingLink(host, port, p.Token, pin, name)
	art, err := renderQR(link)
	if err != nil {
		return err
	}
	fmt.Println()
	fmt.Print(art)
	fmt.Println()
	fmt.Println("Scan this in Fullbars: Settings › Combine connections › Scan pairing code.")
	fmt.Println("Or open this link on the phone (treat it like a password):")
	fmt.Println()
	fmt.Println("  " + link)
	fmt.Println()
	if reset {
		fmt.Println("The old pairing no longer works; pair the app again.")
	}
	if remoteRotated {
		fmt.Println("Old mod remote links stopped working too. Share the new one from the app once it's paired.")
	}
	return nil
}

func writeFileAtomic(path string, b []byte, mode os.FileMode) error {
	tmp := path + ".tmp"
	if err := os.WriteFile(tmp, b, mode); err != nil {
		return err
	}
	if err := os.Chmod(tmp, mode); err != nil {
		return err
	}
	return os.Rename(tmp, path)
}
