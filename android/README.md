# Fullbars for Android

The Fullbars app (package `app.irlsupreme`, the working name). GPL-3.0-or-later, see [LICENSE](LICENSE).

## Modules

| Module | What it holds |
|---|---|
| `:app` | The app people install. `ui/` is the Compose UI (Home, the Twitch, Camera, Connection and Quality pages, Settings, the live screen). `service/` holds the foreground services (`StreamService` owns the engine while live, `HelperService` lends a helper phone's mobile data), pairing with your BRB server, helper phones, alerts, tips and read-aloud. `dji/` drives a DJI Pocket 3 over Bluetooth LE and Wi‑Fi. |
| `:engine` | Capture, compositing (scenes, overlays, the web overlay), encoding, and the outputs: RTMP(S) straight to Twitch, or SRT bonded over several networks through [`srtla-kt`](../srtla-kt). Also the automatic bitrate and the RTMP ingest the Pocket streams into. |
| `:twitch` | Twitch sign-in (device code flow), stream key and ingest, chat, alerts (EventSub) and mod commands. Plain Kotlin, tested on the JVM. See [twitch/README.md](twitch/README.md). |

`srtla-kt` (Apache-2.0) is a separate Gradle build, pulled in as a composite build (`includeBuild("../srtla-kt")`).

Versions: AGP 9.5.1 (built-in Kotlin, KGP 2.4.21), Gradle 9.8.2, JDK 21 (Android Studio's), compileSdk/targetSdk 36,
minSdk 31 (Android 12).

## One-time setup

Create `android/local.properties` (git-ignored, never commit it) with your SDK path and your Twitch application's
Client ID:

```properties
sdk.dir=/Users/you/Library/Android/sdk
twitch.clientId=your-client-id
```

How to register the Twitch application is in [twitch/README.md](twitch/README.md). Without a Client ID a debug build
still streams with a stream key, but Twitch sign-in doesn't work; a release build needs one.

## Build and install a debug build

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
cd android
./gradlew :app:installDebug        # needs a phone with USB debugging on
```

Tests and lint:

```sh
./gradlew :app:testDebugUnitTest :engine:testDebugUnitTest :twitch:testDebugUnitTest :app:lintDebug
(cd ../srtla-kt && ./gradlew test)
```

## A signed release build

The version lives in `gradle.properties` (`appVersionCode`, `appVersionName`). Bump the code for every build you
hand out, or phones won't install it over the last one.

The release key never goes in the repo. Make a keystore once and keep it, with a small properties file next to it,
somewhere outside the repo (and back both up: a lost key means testers have to uninstall to update):

```sh
keytool -genkeypair -v -keystore ~/keys/fullbars-release.jks -alias fullbars -keyalg RSA -keysize 4096 -validity 10000
```

`~/keys/fullbars-keystore.properties`:

```properties
storeFile=fullbars-release.jks
storePassword=…
keyAlias=fullbars
keyPassword=…
```

`storeFile` may be absolute or relative to the properties file. Tell Gradle where the properties file is, either in
`~/.gradle/gradle.properties`:

```properties
fullbars.keystoreProperties=/Users/you/keys/fullbars-keystore.properties
```

or with the environment variable `FULLBARS_KEYSTORE_PROPERTIES`. Then:

```sh
./gradlew :app:assembleRelease     # APK: app/build/outputs/apk/release/app-release.apk
```

Without signing set up, everything else still builds; only packaging a release stops, and says what's missing. A
properties file inside the repo is refused, so it can't be committed by accident.

R8 (code shrinking) is off for now. The rules are in `app/proguard-rules.pro`; it gets turned on once a minified build
has passed a hands-on check of bonding, the Pocket 3, a helper phone, a web overlay, chat and alerts.

## Debug controls (debug builds only)

Debug builds can be driven from adb for tests. Both entry points need the `DUMP` permission, which adb's shell has
and other apps can't get, and neither exists in a release build.

```sh
# Start a stream, change scene, stop (the full list is in app/src/debug/.../DebugActivity.kt)
adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_url rtmp://127.0.0.1:1935/live/test
adb shell am start -n app.irlsupreme/.debug.DebugActivity --es debug_scene BRB
adb shell am start -n app.irlsupreme/.debug.DebugActivity --ez debug_stop true
# These also work with the screen off
adb shell am broadcast -a app.irlsupreme.debug.CONTROL -p app.irlsupreme --es cmd mic --es value off
```
