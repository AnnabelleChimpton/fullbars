# srtla-kt

A permissively licensed (Apache-2.0) **SRTLA sender** for the JVM and Android. It bonds one SRT stream across several network links (cellular, Wi‑Fi, Ethernet, helper phones), and it is wire-compatible with `srtla_rec` and the hosted SRTLA relays.

```
libsrt (SRT caller) ──UDP──▶ SrtlaSender (localhost) ──▶ link A ─┐
                                                      ──▶ link B ─┼──▶ srtla_rec ──▶ SRT listener
                                                      ──▶ link C ─┘
```

## Design
- **`SrtlaCore`: an I/O-free state machine with no clock of its own.** It handles registration (REG1/REG2/REG3, NGP/ERR/NAK), per-link congestion windows, scheduling (`window / (inFlight + 1)`), SRT ACK/NAK bookkeeping, keepalive RTT, and de-duplication of receiver control packets. All of it is deterministic and unit-tested.
- **`SrtlaSender`: a thin NIO layer**, with one selector thread and thread-safe `addLink` / `removeLink` / `stats`.
  - On Android, pass a `channelFactory` that calls `network.bindSocket(channel.socket())` so each link really leaves through its own `Network`.
- **Liveness is proof of delivery.** A link stays alive only while the receiver confirms it hears that link's uplink: a keepalive echo, an SRTLA ACK for a packet this link sent, or REG3. Plain inbound traffic doesn't count. Receivers send SRT ACKs down every link, so a link whose uplink is dead but whose downlink still works would otherwise look healthy forever.
- **Extended keepalives** (38 bytes) carry per-link telemetry. Receivers that don't understand them still read the standard 10-byte prefix.

## Usage
```kotlin
val sender = SrtlaSender(InetSocketAddress("127.0.0.1", 0)).start()
sender.addLink("cellular", relay) { DatagramChannel.open().also { cellNetwork.bindSocket(it.socket()) } }
sender.addLink("wifi", relay) { DatagramChannel.open().also { wifiNetwork.bindSocket(it.socket()) } }
// point libsrt's caller at 127.0.0.1:${sender.localPort}
val stats = sender.stats // per-link phase, window, in-flight, RTT, kbps, NAKs
```

## Build and test
```bash
./gradlew test               # unit tests + real-UDP loopback test with an in-process fake receiver
./gradlew :cli:installDist   # desktop CLI for testing against a real srtla_rec
```

## Provenance (read before contributing)
This library is Apache-2.0 and must stay free of GPL/AGPL code.
- **Protocol constants and behaviour** were taken from the public protocol description and cross-checked against **irlserver/srtla_send** (MIT, © 2025 Thomas Lekanger). See [NOTICE](NOTICE).
- **No code from BELABOX srtla, OpenIRL, irlserver/srtla (receiver), CERALIVE, Brix, LifeStreamer or Bond Bunny** (all GPL/AGPL) may be copied here. Those projects may only be run, unmodified, as test counterparts.
