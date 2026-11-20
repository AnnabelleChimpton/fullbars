# Fullbars with your home OBS and NOALBS (advanced)

Most streams don't need this: with a [BRB server](BRB-SERVER.md), Fullbars goes straight to Twitch and stays live
through dropouts with nothing running at home (recommended). Use this when you'd rather produce the stream in your
own OBS at home (scenes, alerts, overlays) than have the BRB server send it straight to Twitch.

```
phone ──(bonded, encrypted)──▶ BRB server ──(SRT, encrypted)──▶ home OBS ──▶ Twitch
                                   ▲  stats (bitrate, RTT)
                                   └────────────── NOALBS (switches OBS scenes)
```

- **OBS pulls from the server.** Nothing at home needs a forwarded port or a VPN: OBS connects out to your server.
- **The server stays between the phone and OBS.** When the phone goes through a tunnel, OBS keeps receiving a
  stream (your Signal lost card) and never has to reconnect. Pointed straight at OBS, a dropped phone means OBS
  waits out a dead session (~30 s) before it accepts the new one.
- **NOALBS** reads the server's stats and switches OBS to your *Offline* scene when the phone drops out (and to a
  *Low* scene on low bitrate, if you set that up), then back.

## Set up (once)

1. **Pair the app with your server** (if you haven't): on the server run
   `docker exec -it fullbars-brb brb pair` and scan the code in Fullbars.
2. **Get your OBS settings** on the server:

   ```
   docker exec -it fullbars-brb brb obs
   ```

   It prints the OBS input URL and a NOALBS config entry. Both contain secrets derived from your pairing: treat them
   like passwords. (They can only *watch* the stream; they can't publish to your server.) Re-pairing
   (`brb pair -reset`) changes them.
3. **OBS › Sources › + › Media Source:** untick *Local File*, paste the *Input*, set *Input Format* to `mpegts`,
   *Reconnect Delay* 2 s, tick *Restart playback when source becomes active* and *Use hardware decoding*.
4. **NOALBS** (optional): add the printed `streamServers` entry to its `config.json` (type `Belabox`), and set its
   OBS connection and scenes as usual.
5. **In Fullbars:** turn on Settings › Combine connections, then Settings › Twitch › *Where the stream goes* ›
   *My OBS*, then *Start sending to My OBS* on Home.

## What to expect

- OBS shows the picture within ~2 s of the phone going live: a viewer joining mid-stream starts at the next
  keyframe, so there's no grey or smeared frame at connect.
- Phone drops out: the server sends your Signal lost card (made by your phone), NOALBS sees the publisher go away
  and switches to your Offline scene. Phone back: NOALBS switches back.
- You stop streaming in the app: the server ends the session; OBS's source goes idle and reconnects for the next
  stream on its own.
- Chat and mod commands in the app still work if you're signed in to Twitch.

## Ports on the server

| Port | What |
|---|---|
| UDP 5000 | the phone's bonded stream |
| TCP 5000 | the app's control API (TLS, pinned) |
| UDP 5001 | OBS pulls the stream here |
| TCP 8181 | NOALBS stats (plain HTTP, secret path) |
