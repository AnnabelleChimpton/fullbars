# Benchmarks: how Fullbars is measured

"Smooth", "in sync" and "survives a dying link" are claims, so each has a measurement behind it that anyone can
repeat. This page says what is measured, how to run it, what counts as a pass, and the numbers so far. A number is
only written here once it has been measured; "not measured yet" means exactly that.

| What | Where | Runs |
|---|---|---|
| Encoder settings, rate window, SPS reading | `android/engine/src/test` (JVM) | every build |
| Bonding behaviour when a link dies (fake clock) | `srtla-kt/src/test/kotlin/srtlakt/FailoverTest.kt` | every build |
| Encoder cadence, bitrate tracking, slate match, AAC level, pixel-exact copy | `android/engine/src/androidTest` | by hand on a phone, before each release |
| Bonding against real receivers over shaped links | `bench/netem` | weekly CI job, and by hand |
| A/V offset and repeated frames, end to end | `tools/avsync` | by hand with the phone, before each release |

## On-device checks (before each release)

With the phone connected over USB and developer options on:

```sh
cd android
./gradlew :engine:installDebugAndroidTest
adb shell am instrument -w app.irlsupreme.engine.test/android.test.InstrumentationTestRunner
adb logcat -d -s EncoderCadenceTest   # the encoder line, IDR spacing and bitrates, for the table below
```

They take about a minute. What each one proves:

- **EncoderCadenceTest**: noise frames through the live encoder setup at the real-time pace. The first frame is a sync
  frame; IDRs are never more than the keyframe interval + 0.5 s apart, also when frames arrive at 20 or 15 fps into
  an encoder set up for 30 (a thermal step-down: encoders that count frames instead of time stretch the GOP, which
  the server's splice and Twitch both feel); timestamps only go forward; 3-6 s after a bitrate change the output is
  within ±25% of the new target; and the phone's slate encoder produces byte-identical SPS/PPS.
- **AacRoundTripTest**: 10 s of a -3 dBFS tone through the real AAC encoder and decoder comes back within 0.5 dB RMS
  and without clipping.
- **CopyExactnessTest**: a one-pixel checkerboard copied through the compositor's 2D program reads back as exactly 0
  or 255. Worth running on a Mali phone (several Pixels) as well, where low-precision texture coordinates show first.

Colour signalling: `ffprobe -show_streams` on a recording of the stream should show `color_space=bt709`,
`color_transfer=bt709`, `color_primaries=bt709` and `color_range=tv`.

## Bonding over shaped links

`bench/netem/run.sh` runs the sender against BELABOX, irlserver or OpenIRL `srtla_rec`, or Stay-live's receive path,
over three links shaped with `tc netem` in local containers. Scenarios include a fixed 5.5 Mbit bond, random and
bursty loss, a link vanishing, an uplink that dies while its downlink still works, a 15 s tunnel, a capacity step
from 6 to 2.5 Mbit, handover flapping and 8-10 Mbps over a 30 ms and a 120 ms link. See
[bench/netem/README.md](../bench/netem/README.md).

Each run writes `result.json`. The headline numbers per scenario:

- **unrecoverable packets** (`srt_dropped_unrecoverable`): what the SRT receiver gave up on; each one is a visible
  glitch. Target: 0 outside the tunnel scenario.
- **time to divert** (`time_to_divert_s`): from a link dying to it carrying almost nothing, from per-second counts.
- **utilisation**: the bitrate the controller settles on, over the shaped capacity. With `SOURCE=synth`, `run.sh`
  sets the controller's ceiling above each scenario's capacity, so the links, not the ceiling, limit it;
  `abr_seconds_at_ceiling` in `result.json` shows when that didn't hold.
- **resend excess** (`retransmit_excess_ratio`): resends beyond the real losses; high means reordering is mistaken for
  loss.

The weekly job runs every scenario 3 times (more on demand) and posts medians and their spread next to
`bench/baseline.json`. It only reports: a difference smaller than the spread between runs is noise, and the baseline
stays empty until a few weekly runs have shown how big that spread is.

## A/V sync

The rig in [tools/avsync](../tools/avsync/README.md): a clip with a white flash and a click every second, played on a
monitor and filmed by the phone while streaming to a server that records; `analyze.py` reports the offset per flash.

Protocol, each case 10 minutes, with the server recording:

| Case | Camera | Stabilisation | Thermal |
|---|---|---|---|
| 1 | back | on | normal (rung 0) |
| 2 | back | off | normal |
| 3 | front | on | normal |
| 4 | front | off | normal |
| 5 | DJI Pocket 3 | (its own) | normal |
| 6 | back | on | hot (rung 2: let the phone heat up, or force the rung in a debug build) |

Pass: |mean offset| and p95 |offset| under 25 ms (20 ms is the aim), and under 0.1% repeated frames over 10 minutes
of real motion (a walk, not the clip; `analyze.py` counts repeats on any recording).

## Numbers so far

The dead-link rows are from the fake-clock simulation (three links, 40/60/80 ms one way, 3 Mbps, the last-added
link dies), not from a real network; FailoverTest prints them on every run. The test's limits are in brackets.

| Measure | Result | How |
|---|---|---|
| Dead link: last packet sent to it after the cut | 267 ms on a 160 ms RTT link, uplink-only or both ways (limit: RTT + 150 ms) | FailoverTest, simulated |
| Dead link: packets lost into it before traffic moves | 19, with an in-flight cap of 42 (limit: cap + 5) | FailoverTest, simulated |
| Dead link: declared down after the cut | 5.1 s, uplink-only ("uplink dead") or both ways ("timeout"); 48 packets into it in all (limit: 5 s timeout + RTT + 50 ms tick) | FailoverTest, simulated |
| Encoder (OnePlus 12) | not measured yet | EncoderCadenceTest log line |
| Max IDR interval at 30 / 20 / 15 fps in | not measured yet | EncoderCadenceTest |
| Bitrate 3-6 s after a change | not measured yet | EncoderCadenceTest |
| Slate GOP size at 1080p | not measured yet | EncoderCadenceTest |
| A/V offset, cases 1-6 | not measured yet | tools/avsync |
| Repeated frames, 10 min walk | not measured yet | tools/avsync |
| Unrecoverable packets per scenario | not measured yet | weekly netem job |

Known gap the simulation found: when the link that dies was added before the link that carries its resends, an
acknowledgement for the resend is credited to the dead link. It is then refilled every few seconds and never declared
down (FailoverTest keeps these cases as ignored tests until the fix lands).
