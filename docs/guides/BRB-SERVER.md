# Your BRB server: stay live through dropouts

The BRB server is a small program on a rented server (a VPS) that sits between your phone and Twitch. With
**Combine connections** on, your phone sends the stream over Wi‑Fi and mobile data together, the server puts it back
together, and passes it on to Twitch.

```
phone ──(Wi‑Fi + mobile data, encrypted)──▶ BRB server ──▶ Twitch
```

**Why bother:**
- **Dropouts don't end your stream.** In a tunnel or a dead zone, the server keeps the Twitch stream going with
  *your* Signal lost card for up to 10 minutes (your phone makes it, so it matches your stream). When you're back,
  the picture returns within seconds.
- **Weak spots hurt less.** Two connections at once, and Fullbars lowers the quality before things stall rather than
  after. If packets keep getting lost on the way anyway, viewers see your Weak signal screen instead of a smeared
  picture.
- **Nothing has to run at home.** No PC, no home internet. This is the recommended everyday setup.

Your scenes, chat and alerts are drawn by your phone before it sends, so they all work through the BRB server.

## Set it up (once)

You need a small Linux VPS with Docker. Any provider works (about €4–8/month; Oracle's free tier works too). It's
capped at 256 MB of memory and uses almost no CPU, because it doesn't re-encode your video. It can share a server
with other things.

You also need a Mac or Linux computer (on Windows, use WSL) that can SSH into the VPS as root. If the VPS doesn't
have Docker yet, install it with your provider's Docker image or
[Docker's install guide](https://docs.docker.com/engine/install/).

1. **Install the server.** From a computer with this repository, run:

   ```bash
   server/brb/deploy.sh root@YOUR_SERVER_ADDRESS
   ```

   It builds and starts one container, `fullbars-brb` (the first build takes a few minutes). It only touches
   `/opt/fullbars-brb`. It uses:

   | Port | What |
   |---|---|
   | UDP 5000 | your phone's stream |
   | TCP 5000 | the app's secure control connection |
   | UDP 5001 | only for My OBS |
   | TCP 8181 | only for NOALBS stats |

   **Open UDP 5000 and TCP 5000 in your provider's firewall.** Some providers have two: on Oracle Cloud, allow them
   on the server itself *and* in the subnet's security list (both UDP and TCP). If either is missing, the app can't
   reach the server.

   If you connect with an SSH alias, `deploy.sh` looks up the real address for the pairing code. If phones need a
   different address than SSH does, set it yourself, with your server's public address in place of `203.0.113.10`:
   `PUBLIC_HOST=203.0.113.10 server/brb/deploy.sh ...`.

2. **Sign in to Twitch in Fullbars first** (Home › **Continue with Twitch**), or add your stream key (**Use a stream
   key instead**). The app sends whichever you chose to the server.

3. **Pair the app.** On the server:

   ```bash
   docker exec -it fullbars-brb brb pair
   ```

   A QR code appears in the terminal. In Fullbars: **Settings › Combine connections**, turn on
   **Use Wi‑Fi and mobile data together**, tap **Scan pairing code**, and scan it. The code also comes as a `fullbars://pair` link
   you can open on the phone (or paste in the app).

   **Treat the code and link like a password:** whoever has them can stream through your server.

4. **Done.** Fullbars now sends your Twitch details to the server each time you go live. You never copy a stream key
   onto the server.

The server **restarts on its own** after a reboot or a crash. To see what it's doing, or why it stopped:
`docker logs fullbars-brb`.

## Going live

- Pick **TEST · private** or **LIVE · public** at the bottom of Home and start. You see a preview first; nothing is
  sent until you tap GO LIVE (START TEST for a test). A test stream is a private Twitch bandwidth test: viewers can't
  see it.
- The live screen shows the health of each connection (Wi‑Fi, your carrier). "Live · Weak signal" means Fullbars has
  lowered the quality to stay live.
- Drop out completely and, after 1.5 seconds, your viewers see your Signal lost card and you see the phone
  reconnecting. Stop the stream yourself and Twitch ends at once (no card left hanging).
- If you're gone for 10 minutes, the server ends the Twitch stream. To change that, set `HOLD` (for example
  `HOLD=20m`) in the server's settings file; see [Changing settings](#changing-ports-and-settings).
- If the server's own connection to Twitch drops, it reconnects by itself within seconds.

## Privacy and security

- **The phone-to-server stream is encrypted** (AES), with a key that comes from pairing. The server refuses anything
  not from your paired phone, and refuses everything until it's paired.
- **The app talks to the server over TLS**, and it trusts only your server's key (it's in the QR code). No domain or
  certificate is needed.
- **Your Twitch key is stored on the server** so only it can read it. It's never shown by the server or written to
  its logs.

## If something's wrong

| You see | Try |
|---|---|
| "This BRB server doesn't know this phone any more" | The server was re-paired (`brb pair -reset`) or rebuilt. Pair again. |
| "That isn't your BRB server (its security key changed)" | The server's data folder was wiped. Pair again. |
| "Can't reach your BRB server" | Is it running (`docker ps`)? Are UDP and TCP 5000 allowed through every firewall your provider has (on Oracle, the security list too)? |
| Live, but only one connection shows | Mobile data off, or Wi‑Fi not connected. Combine uses whatever is available. |
| Viewers see nothing, and `docker logs fullbars-brb` says "Twitch refused the stream" | Twitch rejected the stream key. In Fullbars, check you're signed in to the right channel, then go live again. |

**Lost the pairing, or shared the link by accident?** On the server, run
`docker exec -it fullbars-brb brb pair -reset` and pair again. The old link stops working, and so do old mod remote
links.

## Changing ports and settings

The settings live on the server, in `/opt/fullbars-brb/env` (one `NAME=value` per line):

- `SRTLA_PORT=6000` moves your phone's stream and the control connection (UDP and TCP).
- `OBS_PORT=6001` moves the My OBS stream (UDP).
- `STATS_PORT=6002` moves the NOALBS stats (TCP).
- `HOLD=20m` changes how long the server keeps the Twitch stream up while the phone is gone (default `10m`).

Then run `server/brb/deploy.sh` again; it lists the ports to open in your firewall. After moving `SRTLA_PORT`, run
`docker exec -it fullbars-brb brb pair` and scan the new code (the port is part of it). After moving `OBS_PORT` or
`STATS_PORT`, run `docker exec -it fullbars-brb brb obs` for the new OBS and NOALBS settings.

To update the server later, run `deploy.sh` again. The pairing and your phone's cards are kept.

## Advanced

- **[My OBS at home](HOME-OBS.md):** the server feeds your home OBS instead (scenes, alerts, overlays), and NOALBS
  can switch scenes from the server's stats. Any cloud OBS machine works the same way.
- **How it works, and the server itself:** [server/README.md](../../server/README.md).
