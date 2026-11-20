#!/usr/bin/env bash
# Makes the A/V sync test clip: black, with one white frame and a 5 ms 1 kHz click at the start of every second.
# Play it full screen on a monitor or a second phone, with the sound up, in front of the camera under test.
#
#   tools/avsync/make_clip.sh [out.mp4] [seconds] [fps]
#
# Needs ffmpeg. FFMPEG can be set to another command, e.g. one that runs it in a container.
set -euo pipefail
OUT=${1:-avsync-clip.mp4}
SECONDS_LONG=${2:-600}
FPS=${3:-60}
# One frame of white per second: at 60 fps the flash lasts 16.7 ms, short enough to mark one camera frame.
${FFMPEG:-ffmpeg} -nostdin -y -loglevel error \
  -f lavfi -i "color=c=black:s=1920x1080:r=$FPS:d=$SECONDS_LONG,drawbox=x=0:y=0:w=iw:h=ih:color=white:t=fill:enable='lt(mod(n\,$FPS)\,1)'" \
  -f lavfi -i "aevalsrc='if(lt(mod(t\,1)\,0.005)\,0.8*sin(2*PI*1000*t)\,0)':s=48000:d=$SECONDS_LONG" \
  -c:v libx264 -preset veryfast -crf 18 -pix_fmt yuv420p -g "$FPS" \
  -c:a aac -b:a 192k -shortest -movflags +faststart "$OUT"
echo "wrote $OUT (${SECONDS_LONG}s, flash and click every second)"
