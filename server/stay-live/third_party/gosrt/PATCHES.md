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

**Tests.** `server/stay-live/reorder_test.go` checks that out-of-order arrival within the tolerance sends no NAK, and that a real loss is NAKed after the tolerance in packets, or after `LossMaxWait` at a low packet rate. It also checks partly filled gaps and that the periodic NAK leaves out fresh gaps. Upstream's own tests still pass: `go test . ./congestion/... ./circular/... ./packet/...` here.
