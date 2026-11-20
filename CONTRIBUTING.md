# Contributing to Fullbars

Thanks for helping. Bug reports, test results from phones we haven't tried, and pull requests are all welcome.

## Reporting a bug

Open an issue with the phone model and Android version, what you did, what you expected and what happened. If it
happened while streaming, say whether you used a BRB server, Combine connections, a helper phone or a DJI Pocket 3.
Leave out stream keys, pairing codes and mod remote links: anyone with them can use your channel or your server.

Security problems go through [SECURITY.md](SECURITY.md), not a public issue.

## The three parts

| Part | Language | Licence |
|---|---|---|
| `android/` (the app: `:app`, `:engine`, `:twitch`) | Kotlin, Jetpack Compose | GPL-3.0-or-later |
| `srtla-kt/` (SRTLA bonding library) | Kotlin (JVM) | Apache-2.0 |
| `server/` (the BRB server, `stay-live/` and `brb/`) | Go, shell, Docker | Apache-2.0 |

Contributions are accepted under the licence of the part they change.

## Building and testing

### The app

You need Android Studio (its bundled JDK 21 and the Android SDK). One-time setup, including the optional Twitch
Client ID, is in [android/README.md](android/README.md).

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"   # macOS; point at your JDK 21
cd android
./gradlew :app:installDebug        # build and install on a phone with USB debugging on
./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest :twitch:testDebugUnitTest :app:lintDebug
```

The app includes srtla-kt as a composite build, so changes there are picked up without publishing anything.

### srtla-kt

```sh
cd srtla-kt
./gradlew test                     # unit tests and a real-UDP loopback test against an in-process fake receiver
```

srtla-kt is Apache-2.0 and must stay free of GPL and AGPL code. Read the Provenance section of
[srtla-kt/README.md](srtla-kt/README.md) before changing it.

### The BRB server

From the repository root, with Docker:

```sh
docker run --rm -v "$PWD/server/stay-live":/src -w /src golang:1.26-bookworm sh -c 'gofmt -l .; go vet ./... && go test ./...'
```

`gofmt -l` should print nothing. The patched gosrt in `server/stay-live/third_party/gosrt` is its own module; run its
tests from that folder with `go test . ./congestion/... ./circular/... ./packet/...`.

To try the whole server, deploy it to a test VPS with `server/brb/deploy.sh` as described in
[docs/guides/BRB-SERVER.md](docs/guides/BRB-SERVER.md).

## Pull requests

- Keep each pull request to one change. A bug fix, a feature or a refactor, not all three.
- Run the tests for the parts you touched, and say in the description what you tested by hand and on which phone.
  Changes to streaming, bonding, the camera or the mic are worth a Test stream before you send them.
- If you change what the user sees, add a screenshot.
- Don't commit `android/local.properties`, keystores, stream keys or any other secret.

## Code style

- Match the code around you. Kotlin follows the existing formatting; Go is `gofmt`-clean.
- Comments explain why the code is the way it is: the constraint, the phone quirk or the failure it guards against.
  Don't restate what the code does.
- Keep logic that can be tested on the JVM out of Android classes where you can, and add a unit test for it.
- Words in the app are plain and short, say what viewers see, and use the feature names already in the UI
  (Combine connections, BRB server, Helper phone, Camera link, Mod commands).
- Code adapted from another project needs its licence to fit the part it goes into and an entry in
  [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
