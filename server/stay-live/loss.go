// SPDX-License-Identifier: Apache-2.0
package main

import (
	"time"

	srt "github.com/datarhei/gosrt"
)

// The server's Weak signal screen. Only the server can see packets the phone sent but that never arrived in
// time: SRT counts them as lost for good (PktRecvLost), and viewers would see them as smearing and freezes. When that
// keeps happening the keeper shows the phone's Weak signal slate instead, and goes back to live at the next live
// keyframe once the feed has been clean for a while. This is NOALBS's low-bitrate scene, without OBS.

const (
	lossWindow      = 5 // seconds looked at
	lossBadNeeded   = 3 // bad seconds within the window to call it weak
	lossCleanNeeded = 5 // consecutive clean seconds to call it recovered
	lossMinPackets  = 3 // a bad second loses at least this many packets...
	lossMinPermille = 5 // ...and at least 0.5% of what arrived
)

// lossDetector turns one-second samples of SRT's receive counters into weak / recovered decisions. Pure.
type lossDetector struct {
	window []bool
	clean  int
	weak   bool
}

// add takes one second's packets received and packets lost for good, and says whether the feed is weak now and
// whether that just changed. A second with nothing received (a stall: the BRB slate's job) counts as neither.
func (d *lossDetector) add(recv, lost uint64) (weak, changed bool) {
	if recv == 0 && lost == 0 {
		return d.weak, false
	}
	bad := lost >= lossMinPackets && lost*1000 >= recv*lossMinPermille
	d.window = append(d.window, bad)
	if len(d.window) > lossWindow {
		d.window = d.window[1:]
	}
	if bad {
		d.clean = 0
	} else {
		d.clean++
	}
	if !d.weak {
		n := 0
		for _, b := range d.window {
			if b {
				n++
			}
		}
		if n >= lossBadNeeded {
			d.weak = true
			return true, true
		}
	} else if d.clean >= lossCleanNeeded {
		d.weak = false
		d.window = nil
		return false, true
	}
	return d.weak, false
}

type qualityEvent struct {
	session int
	weak    bool
	lost    uint64 // packets lost in the second that tipped it, for the log
}

// watchLoss samples a live session's SRT counters once a second and tells the keeper when its feed turns weak or
// recovers. Stops quietly when the session ends.
func watchLoss(conn srt.Conn, session int, quality chan<- qualityEvent, stop <-chan struct{}) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	var d lossDetector
	var lastRecv, lastLost uint64
	for {
		select {
		case <-stop:
			// No "recovered" on the way out: the session goroutine closes stop before it sends the ended event, so
			// one sent here would reach the keeper first, clear its Weak signal hold and keep that slate on air.
			// The keeper clears the hold itself when the session ends or a new one starts.
			return
		case <-t.C:
			var st srt.Statistics
			conn.Stats(&st)
			recv, lost := st.Accumulated.PktRecvUnique, st.Accumulated.PktRecvLost
			weak, changed := d.add(recv-lastRecv, lost-lastLost)
			if changed {
				// Never dropped: a lost "recovered" would leave the Weak signal slate on air for good. The detector
				// only changes after several seconds, so waiting for the keeper here costs nothing.
				select {
				case quality <- qualityEvent{session: session, weak: weak, lost: lost - lastLost}:
				case <-stop:
					return
				}
			}
			lastRecv, lastLost = recv, lost
		}
	}
}
