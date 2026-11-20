# BRB server

Licence: Apache-2.0 (except `srtla_rec`, see below).

The BRB server receives a bonded stream from the Fullbars app and keeps one RTMP session to Twitch alive. During a dropout it covers the gap with your BRB screen, so viewers never see "offline". It never transcodes: everything is a remux, which is why it runs on a €4–8 VPS or on Oracle's free tier.

**Setting one up:** follow [the BRB server guide](../docs/guides/BRB-SERVER.md). For home OBS instead of straight to Twitch, see [My OBS at home](../docs/guides/HOME-OBS.md).

```
phone ──SRTLA──▶ srtla_rec ──SRT──▶ keeper ──TS──▶ ffmpeg -c copy ──RTMPS──▶ Twitch
                 (unmodified,         │              (restarted with backoff if the ingest drops)
                  separate AGPL       ├─ live: pass-through, continuous timestamps
                  program)            ├─ stall > 1.5 s, or the session ends: loop the phone's BRB slate
                                      ├─ packets keep getting lost: loop its Weak signal slate
                                      ├─ feed back: splice in at the next IDR
                                      └─ the app says you ended (POST /v1/end): end the output
```

## What's here

- **`brb/`**: the server image (`brb/Dockerfile`), its `entrypoint.sh`, and `deploy.sh`, which builds and starts it on a VPS over SSH. One container, `fullbars-brb`, bundles:
  - `srtla_rec`, BELABOX's SRTLA receiver, built **unmodified** from a pinned commit and run as a separate program (AGPL-3.0; its source is BELABOX's srtla repository, as noted in the Dockerfile);
  - `brb` (the same program as `stay-live`, kept as an alias): the slate keeper, the control API, pairing and the OBS settings printer;
  - ffmpeg, only for the remux to RTMP(S).
- **`stay-live/`**: the Go source of `brb`. Its own `Dockerfile` builds the bare keeper alone, for testing it in isolation; the real server is `brb/Dockerfile`.
- **`stay-live/third_party/gosrt/`**: gosrt v0.13.0 (MIT), the SRT library, with one patch: a reorder tolerance for bonded streams. `PATCHES.md` there says what changed and why.

## Status

Working, and used with the app:

- **Pairing.** `brb pair` prints a QR code and a `fullbars://pair` link with a random token and the server's TLS key pin. The app talks to the control API (TLS on the SRTLA port number, TCP) with that token and trusts only that key. The phone's SRT streams are encrypted with a passphrase derived from the token, and an unpaired server refuses every stream. `brb pair -reset` makes a new pairing and retires old mod remote links.
- **Stream key.** The app sends your Twitch ingest URL (with the key) at every go-live; the server keeps it in a 0600 file and never prints or logs it (ffmpeg's errors are redacted). `OUTPUT_URL` in the env file is only a fallback; with neither, the server records to `/data` (the newest five recordings are kept).
- **The phone's slates.** The app uploads its own BRB and Weak signal slates, encoded with the live encoder's settings, so splices are a pure remux. The server keeps them for the next stream.
- **Stays on air.** If the Twitch connection drops, ffmpeg restarts with backoff and resumes on a keyframe. The slate keeps running meanwhile. Five quick failures in a row are reported as a stream-key problem.
- **Ends only when you end.** The stream ends when the app says so (`POST /v1/end`) and the phone's session closes, or 5 s after the app said so. Any other end of a session is a dropout, whether the phone reconnected, the data was damaged or the session timed out. The BRB slate goes on air until the phone is back, or until `HOLD` (10 min) runs out. Only the newest session that carries the stream counts: when an older or duplicate session ends, nothing happens. Damaged data (TS packets lost on the way) is skipped instead of ending the session. Older apps never call `/v1/end`. For them, a session the phone closes with nothing reconnecting within 20 s is the end. Once the app has called `/v1/end`, the server stops using that fallback.
- **Bonding-friendly SRT.** Bonded links deliver packets out of order. The server asks for a missing packet again only once 64 newer packets have overtaken it (`-reorder`), or after at most 250 ms. It used to ask at once, and a quarter of all packets were resent for nothing.
- **Restarts itself.** The container runs with `--restart unless-stopped` and exits if one of its parts dies, so a reboot or a crash brings it back. `docker logs fullbars-brb` says why.
- **Status for the app.** `/v1/status` reports `waiting`, `live`, `slate`, `weak` or `error` (with the reason), from `state.json`, which the keeper rewrites on every change. Every line in `events.jsonl` has a wall-clock time; the log rotates past 5 MB.
- **NOALBS stats** (Belabox format) on TCP 8181, at a secret path derived from the pairing.
- **Mod remote.** A web page for mods to switch scenes, served by the control API at a secret link. During a dropout it says viewers see the BRB screen.
- **My OBS.** Home OBS can pull the stream over SRT (UDP 5001) instead of the server pushing to Twitch.

Scenes, chat and alerts are drawn by the phone before it sends; the server never decodes the video.

## The slate must match the live encoder

A pure splice is only valid if the slate uses **the same SPS/PPS and AAC config** as the live stream: same encoder, resolution, profile and audio format. That's why the phone encodes the slates itself and uploads them. Until it has, the server uses a default slate, and logs a warning if the parameter sets differ. `keeper_test.go` covers the comparison.

## Testing

```bash
docker run --rm -v "$PWD/server/stay-live":/src -w /src golang:1.26-bookworm sh -c 'gofmt -l .; go vet ./... && go test ./...'
```

The patched gosrt is its own module, so `./...` doesn't include its tests. To run them, run `go test . ./congestion/... ./circular/... ./packet/...` in `stay-live/third_party/gosrt`.
