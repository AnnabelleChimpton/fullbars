// SPDX-License-Identifier: Apache-2.0
package main

import "testing"

func TestLossDetectorNeedsSustainedLoss(t *testing.T) {
	var d lossDetector
	// Clean feed, then one bad second, then clean: never weak.
	for i := 0; i < 10; i++ {
		if w, _ := d.add(550, 0); w {
			t.Fatal("weak on a clean feed")
		}
	}
	if w, _ := d.add(550, 40); w {
		t.Fatal("one bad second shouldn't count")
	}
	// A trickle below 0.5 % (or under 3 packets) isn't bad either.
	for i := 0; i < 10; i++ {
		if w, _ := d.add(550, 2); w {
			t.Fatal("2 packets a second is noise")
		}
	}
	for i := 0; i < 10; i++ {
		if w, _ := d.add(5500, 20); w {
			t.Fatal("0.36 % is under the bar")
		}
	}
	// Three bad seconds within five: weak, reported once.
	seq := [][2]uint64{{550, 30}, {550, 0}, {550, 25}, {550, 0}}
	for _, s := range seq {
		if w, _ := d.add(s[0], s[1]); w {
			t.Fatal("only two bad seconds so far")
		}
	}
	w, changed := d.add(550, 10)
	if !w || !changed {
		t.Fatalf("third bad second in the window: weak=%v changed=%v", w, changed)
	}
	if w, changed := d.add(550, 50); !w || changed {
		t.Fatalf("still weak, no new report: weak=%v changed=%v", w, changed)
	}
}

func TestLossDetectorRecoversAfterFiveCleanSeconds(t *testing.T) {
	var d lossDetector
	for i := 0; i < 3; i++ {
		d.add(550, 30)
	}
	if !d.weak {
		t.Fatal("should be weak")
	}
	for i := 0; i < 4; i++ {
		if w, _ := d.add(550, 0); !w {
			t.Fatalf("recovered after only %d clean seconds", i+1)
		}
	}
	d.add(550, 30) // relapse restarts the count
	for i := 0; i < 4; i++ {
		d.add(550, 0)
	}
	if !d.weak {
		t.Fatal("the relapse should have restarted the clean count")
	}
	// Seconds with nothing received (a stall) don't count towards recovery.
	d.add(0, 0)
	d.add(0, 0)
	if !d.weak {
		t.Fatal("a stall isn't a clean feed")
	}
	if w, changed := d.add(550, 0); w || !changed {
		t.Fatalf("fifth clean second: weak=%v changed=%v", w, changed)
	}
}
