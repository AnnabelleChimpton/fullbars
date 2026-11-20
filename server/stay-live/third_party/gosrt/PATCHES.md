# Fullbars patches to gosrt

This is github.com/datarhei/gosrt **v0.13.0** (MIT, see `LICENSE`), copied from the Go module cache. `server/stay-live/go.mod` points at it with a `replace`. Upstream's `.github`, `Dockerfile` and `.dockerignore` were left out. Everything else is unchanged except the code marked `[fullbars patch]`.

## Reorder tolerance (SRTO_LOSSMAXTTL)

**Why.** The BRB server receives bonded streams (SRTLA): the phone sends packets over several links with different delays, so they arrive out of order all the time. Upstream's live receiver (`congestion/live/receive.go`, `Push`) sends a NAK the moment it sees a gap. Its own comment says `SRTO_LOSSMAXTTL` would prevent that but isn't implemented. In the first real stream 20–25% of all packets were resent while none were lost, and the phone's bitrate control took the resend storms as congestion.

**What.**

- `live.ReceiveConfig` has two new fields. `LossMaxTTL` is the tolerance in packets. `LossMaxWait` is a cap in microseconds. With `LossMaxTTL` 0 the receiver works exactly as upstream does.
- A gap no longer causes an immediate NAK. It is kept as a *fresh loss*. A packet that turns up late fills its place: the gap shrinks or splits, and nothing is asked for.
- On each `Tick`, a fresh loss that is still open is reported once `LossMaxTTL` newer packets have arrived, or once it has waited `LossMaxWait`, whichever comes first. The NAK goes out in that same tick, and the periodic NAK repeats it until the packet arrives. The periodic NAK leaves out gaps that are still within the tolerance.
- `PktLoss` (the receiver's loss counter) counts gaps when they are reported, not when they are first seen.
- In `connection.go` the receiver gets `Config.LossMaxTTL`, an option that already existed but was unused. `LossMaxWait` is an eighth of the receive latency, between 20 and 250 ms, so real losses are still asked for with most of the latency left.

- The packet tolerance follows the receive rate. With `LossMaxWait` set, `LossMaxTTL` is only a floor: once a second the receiver raises the tolerance to the packets that arrive in 1.25 × `LossMaxWait` at the measured rate (`max(LossMaxTTL, pktRate × LossMaxWait × 1.25)`), and until the first measurement only the time cap applies. A fixed 64 packets is about 80 ms at 8 Mbps, less than the delay difference between two cellular links, so at higher bitrates every packet on the slower link was asked for again. Now `LossMaxWait` is the tolerance that applies at every bitrate. `LossTolerance()` on the receiver reports the value in use.

**Tests.** `server/stay-live/reorder_test.go` checks that out-of-order arrival within the tolerance sends no NAK, and that a real loss is NAKed after the tolerance in packets, or after `LossMaxWait` at a low packet rate. It also checks partly filled gaps and that the periodic NAK leaves out fresh gaps. A two-link simulation (40 ms and 220 ms one-way, 8 Mbps) checks that the delay spread alone causes no NAK, and that with 1% loss every lost packet is recovered before its TSBPD time and nothing that was only reordered is asked for. Upstream's own tests still pass: `go test . ./congestion/... ./circular/... ./packet/...` here.

## Read queue overflow counter

**Why.** `connection.go`'s `deliver` drops a packet when the reader (the keeper's demuxer) falls behind and the 1024-packet read queue is full. Those packets were already acknowledged, so SRT never resends them and no statistic counts them: the stream just loses data silently.

**What.** `srtConn` counts them in an atomic, and `ReadQueueDrops()` returns the count. It is not part of the `Conn` interface; the server reads it with a type assertion and shows it in its stats line and stats file.
