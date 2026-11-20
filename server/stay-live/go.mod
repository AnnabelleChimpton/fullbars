module staylive

go 1.26.0

require (
	github.com/asticode/go-astits v1.17.0
	github.com/datarhei/gosrt v0.13.0
	rsc.io/qr v0.2.0
)

require (
	github.com/asticode/go-astikit v0.30.0 // indirect
	github.com/benburkert/openpgp v0.0.0-20160410205803-c2471f86866c // indirect
	golang.org/x/net v0.61.0 // indirect
	golang.org/x/sys v0.49.0 // indirect
)

// gosrt with a reorder-tolerance patch for bonded streams: see third_party/gosrt/PATCHES.md.
replace github.com/datarhei/gosrt => ./third_party/gosrt
