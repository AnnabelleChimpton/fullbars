# A/V sync rig

Measures how far the stream's sound sits from its picture, end to end, and how often a frame is shown twice.

1. `make_clip.sh avsync-clip.mp4 600 60` makes a 10-minute clip: black, with one white frame and a short click at
   the start of every second.
2. Play it full screen on a monitor (sound up) and film it with the phone while streaming to a server that records.
3. `python3 analyze.py recording.ts` reports the A/V offset per flash (mean, standard deviation, p95; positive means
   the sound is late) and the share of repeated frames. Run the repeat count on a recording of real motion.

Both scripts need ffmpeg; `FFMPEG` can name another command, for example one that runs ffmpeg in a container. The
device protocol and pass thresholds are in [docs/BENCHMARKS.md](../../docs/BENCHMARKS.md).
