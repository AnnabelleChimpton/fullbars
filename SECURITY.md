# Security

## Reporting a problem

Please report security problems privately, through GitHub's private vulnerability reporting: open this repository's
**Security** tab and choose **Report a vulnerability** ([direct link](../../security/advisories/new)).

Don't open a public issue, pull request or discussion for a security problem, and don't post details anywhere public
until a fix is out.

Include what's affected (the app, the BRB server or srtla-kt, and which version or commit), how to reproduce it, and
what an attacker could do with it. You'll get a reply in the advisory thread.

## What's in scope

- The Android app (`android/`), including how it stores your Twitch sign-in, stream keys and pairing.
- The BRB server (`server/`): pairing, the control API, the mod remote, the NOALBS stats and the stream it accepts.
- srtla-kt (`srtla-kt/`).

Problems in BELABOX's `srtla_rec`, ffmpeg, gosrt or other upstream projects should go to those projects. If the way
Fullbars uses them makes a problem worse, report that here.

## Supported versions

Fullbars is pre-release. Fixes go into the latest code on the main branch and the next test build on the Releases
page.

## Keeping your own setup safe

- Treat your BRB server's pairing code and `fullbars://pair` link, mod remote links and the `brb obs` output like
  passwords. If one leaks, run `docker exec -it fullbars-brb brb pair -reset` on the server and pair again.
- Never paste stream keys, pairing codes or server logs with secrets into an issue.
