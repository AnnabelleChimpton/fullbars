#!/usr/bin/env python3
"""A/V offset and repeated frames in a recording of the flash-and-click clip (make_clip.sh).

    python3 tools/avsync/analyze.py recording.ts [--limit-ms 25] [--json out.json]

Record the server's output (or the VOD) while the phone films the clip, then run this on the file. For every flash
it finds the video onset (the first frame whose mean brightness is over halfway from dark to bright, refined by how
bright that frame is) and the audio onset (the end of the silence before the click), pairs each flash with
the nearest click within 400 ms, and reports the offset: positive means the sound arrives late.

Also counts repeated frames (a decoded frame identical to the one before it), which on a moving scene means the
pipeline showed the same picture twice. Run the repeat count on a recording of real motion, not on the clip.

Needs ffmpeg; set FFMPEG to another command (e.g. "docker run --rm -v $PWD:/w -w /w <image> ffmpeg") to run it
elsewhere. Standard library only.
"""
import argparse
import json
import os
import re
import shlex
import statistics
import subprocess
import sys

PAIR_WINDOW_S = 0.4


def ffmpeg(args):
    cmd = shlex.split(os.environ.get("FFMPEG", "ffmpeg")) + ["-nostdin", "-hide_banner"] + args
    p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, universal_newlines=True)
    if p.returncode != 0:
        sys.exit("ffmpeg failed:\n" + p.stderr[-2000:])
    return p.stdout, p.stderr


def luma(path):
    """(pts seconds, mean luma, luma difference from the previous frame) per decoded frame."""
    out, _ = ffmpeg(["-i", path, "-map", "0:v:0", "-vf",
                     "signalstats,metadata=print:file=-",
                     "-f", "null", "-"])
    frames, t, y = [], None, None
    for line in out.splitlines():
        m = re.search(r"pts_time:([-\d.]+)", line)
        if m:
            t, y = float(m.group(1)), None
            continue
        m = re.match(r"lavfi\.signalstats\.(YAVG|YDIF)=([-\d.]+)", line.strip())
        if m and t is not None:
            if m.group(1) == "YAVG":
                y = float(m.group(2))
            else:
                frames.append((t, y if y is not None else 0.0, float(m.group(2))))
    return frames


def video_onsets(frames):
    """
    Flash onsets: the first frame at or over halfway between the dark and bright levels. A frame only partly bright
    caught the flash part-way through its exposure, so the onset is moved later by the dark share of one frame
    (taking a frame's timestamp as the start of its exposure, and exposure as about one frame long).
    """
    if len(frames) < 2:
        return []
    ys = sorted(f[1] for f in frames)
    lo = ys[len(ys) // 10]
    hi = ys[-1 - len(ys) // 200] if len(ys) > 200 else ys[-1]
    if hi - lo < 20:  # no flashes in this recording
        return []
    thr = (lo + hi) / 2
    onsets = []
    for (t0, y0, _), (t1, y1, _) in zip(frames, frames[1:]):
        if y0 < thr <= y1:
            bright = min(1.0, max(0.0, (y1 - lo) / (hi - lo)))
            onsets.append(t1 + (1.0 - bright) * (t1 - t0))
    return onsets


def audio_onsets(path, noise_db):
    """Click onsets: where ffmpeg's silencedetect says a silence of at least 0.2 s ended."""
    _, err = ffmpeg(["-i", path, "-map", "0:a:0", "-af", f"silencedetect=noise={noise_db}dB:d=0.2", "-f", "null", "-"])
    return [float(m.group(1)) for m in re.finditer(r"silence_end: ([-\d.]+)", err)]


def pair(video, audio):
    offsets = []
    j = 0
    for v in video:
        while j + 1 < len(audio) and abs(audio[j + 1] - v) <= abs(audio[j] - v):
            j += 1
        if audio and abs(audio[j] - v) <= PAIR_WINDOW_S:
            offsets.append(audio[j] - v)
    return offsets


def summarize(offsets_s, frames):
    ms = [o * 1000 for o in offsets_s]
    res = {"pairs": len(ms)}
    if ms:
        a = sorted(abs(x) for x in ms)
        res.update(mean_ms=round(statistics.mean(ms), 1),
                   sd_ms=round(statistics.pstdev(ms), 1),
                   p95_abs_ms=round(a[min(len(a) - 1, int(0.95 * (len(a) - 1) + 0.5))], 1),
                   min_ms=round(min(ms), 1), max_ms=round(max(ms), 1))
    moving = frames[1:]
    if moving:
        repeats = sum(1 for f in moving if f[2] == 0.0)
        res.update(frames=len(frames), repeated_frames=repeats, repeated_ratio=round(repeats / len(moving), 5))
    return res


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("recording")
    ap.add_argument("--noise-db", type=float, default=-30, help="silence threshold for the click detector (dB)")
    ap.add_argument("--limit-ms", type=float, default=25, help="pass if |mean| and p95 |offset| are within this")
    ap.add_argument("--repeat-limit", type=float, default=0.001, help="pass if repeated frames are under this share")
    ap.add_argument("--json", help="also write the result here")
    a = ap.parse_args()

    frames = luma(a.recording)
    res = summarize(pair(video_onsets(frames), audio_onsets(a.recording, a.noise_db)), frames)
    checks = []
    if res["pairs"]:
        checks.append(abs(res["mean_ms"]) <= a.limit_ms and res["p95_abs_ms"] <= a.limit_ms)
    if "repeated_ratio" in res:
        res["repeats_pass"] = res["repeated_ratio"] < a.repeat_limit
    res["sync_pass"] = all(checks) if checks else None
    print(json.dumps(res, indent=1))
    if a.json:
        with open(a.json, "w") as f:
            json.dump(res, f, indent=1)


if __name__ == "__main__":
    main()
