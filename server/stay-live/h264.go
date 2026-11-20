// SPDX-License-Identifier: Apache-2.0
package main

import "fmt"

// spsSize reads the picture size from an H.264 SPS NAL unit (with or without its start code). The keeper uses it to
// tell a slate in the wrong orientation or resolution from one that only differs in encoder details: switching size
// at a splice makes Twitch's player stall or rescale, so a wrong-size slate is worse than an inexact one.
func spsSize(nal []byte) (w, h int, err error) {
	for len(nal) >= 3 && nal[0] == 0 && nal[1] == 0 && (nal[2] == 1 || (nal[2] == 0 && len(nal) >= 4 && nal[3] == 1)) {
		if nal[2] == 1 {
			nal = nal[3:]
		} else {
			nal = nal[4:]
		}
	}
	if len(nal) < 4 || nal[0]&0x1f != 7 {
		return 0, 0, fmt.Errorf("not an SPS")
	}
	r := bitReader{b: unescapeRBSP(nal[1:])}
	profile := r.bits(8)
	r.bits(16) // constraint flags, level
	r.ue()     // seq_parameter_set_id
	chroma := 1
	switch profile {
	case 100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135:
		chroma = r.ue()
		if chroma == 3 {
			r.bits(1) // separate_colour_plane_flag
		}
		r.ue()    // bit_depth_luma_minus8
		r.ue()    // bit_depth_chroma_minus8
		r.bits(1) // qpprime_y_zero_transform_bypass_flag
		// seq_scaling_matrix_present_flag, then the scaling lists (skipped)
		if r.bits(1) == 1 {
			n := 8
			if chroma == 3 {
				n = 12
			}
			for i := 0; i < n; i++ {
				if r.bits(1) == 1 {
					size := 16
					if i >= 6 {
						size = 64
					}
					last, next := 8, 8
					for j := 0; j < size; j++ {
						if next != 0 {
							next = (last + r.se() + 256) % 256
						}
						if next != 0 {
							last = next
						}
					}
				}
			}
		}
	}
	r.ue() // log2_max_frame_num_minus4
	// pic_order_cnt_type
	switch r.ue() {
	case 0:
		r.ue()
	case 1:
		r.bits(1)
		r.se()
		r.se()
		for n := r.ue(); n > 0 && !r.bad; n-- {
			r.se()
		}
	}
	r.ue()    // max_num_ref_frames
	r.bits(1) // gaps_in_frame_num_value_allowed_flag
	mbW := r.ue() + 1
	mapH := r.ue() + 1
	frameMbsOnly := r.bits(1)
	if frameMbsOnly == 0 {
		r.bits(1) // mb_adaptive_frame_field_flag
	}
	r.bits(1) // direct_8x8_inference_flag
	w, h = mbW*16, mapH*16*(2-frameMbsOnly)
	if r.bits(1) == 1 { // frame_cropping_flag
		cx, cy := 1, 2-frameMbsOnly
		switch chroma {
		case 1:
			cx, cy = 2, 2*(2-frameMbsOnly)
		case 2:
			cx = 2
		}
		left, right, top, bottom := r.ue(), r.ue(), r.ue(), r.ue()
		w -= (left + right) * cx
		h -= (top + bottom) * cy
	}
	if r.bad || w <= 0 || h <= 0 || w > 16384 || h > 16384 {
		return 0, 0, fmt.Errorf("SPS cut short or damaged")
	}
	return w, h, nil
}

// paramSize is spsSize for paramSets' output (SPS first); "" if it can't be read.
func paramSize(params []byte) string {
	if w, h, err := spsSize(params); err == nil {
		return fmt.Sprintf("%dx%d", w, h)
	}
	return ""
}

// unescapeRBSP removes the emulation prevention bytes (00 00 03 -> 00 00).
func unescapeRBSP(b []byte) []byte {
	out := make([]byte, 0, len(b))
	zeros := 0
	for _, c := range b {
		if zeros >= 2 && c == 3 {
			zeros = 0
			continue
		}
		if c == 0 {
			zeros++
		} else {
			zeros = 0
		}
		out = append(out, c)
	}
	return out
}

type bitReader struct {
	b   []byte
	pos int
	bad bool // read past the end
}

func (r *bitReader) bits(n int) int {
	v := 0
	for i := 0; i < n; i++ {
		if r.pos >= len(r.b)*8 {
			r.bad = true
			return 0
		}
		v = v<<1 | int(r.b[r.pos/8]>>(7-r.pos%8))&1
		r.pos++
	}
	return v
}

func (r *bitReader) ue() int {
	zeros := 0
	for r.bits(1) == 0 {
		if r.bad || zeros > 31 {
			r.bad = true
			return 0
		}
		zeros++
	}
	return 1<<zeros - 1 + r.bits(zeros)
}

func (r *bitReader) se() int {
	v := r.ue()
	if v%2 == 1 {
		return (v + 1) / 2
	}
	return -v / 2
}
