#!/bin/sh
# Env:
#   SRTLA_PORT   port phones use: UDP for bonding, TCP for the paired app's control API (default 5000)
#   PUBLIC_HOST  this server's public address, for the pairing QR (deploy.sh sets it)
#   SERVER_NAME  the name the app shows for this server (default "Your BRB server")
#   OBS_PORT     UDP port home OBS pulls the stream from in "My OBS" mode (default 5001)
#   STATS_PORT   TCP port of the NOALBS stats (plain HTTP, secret path; default 8181)
#   OUTPUT_URL   optional fallback output (rtmp(s)/srt incl. key) when the app hasn't set one. Unset = record.
#   REC_KEEP     recordings kept in /data (the newest; default 5)
#   SLATE_W/H    default slate size (1920x1080), STALL (1500ms), HOLD (10m)
#   LATENCY      SRT latency the server asks for (default 1s). SRT uses the larger of both sides', so the phone's
#                setting (2 s today) decides; raise this to force more on every sender.
#   REORDER      SRT reorder tolerance floor in packets (default 64). It grows with the bitrate, so a gap waits
#                latency/8 (20-250 ms) for a packet still on a slower link before it is asked for again.
# Output URLs contain stream keys: they are never printed (only scheme + host), and ffmpeg's errors are redacted.
#
# This script is PID 1. If the control API or srtla_rec dies, it exits 1 so Docker's restart policy
# (deploy.sh: --restart unless-stopped) brings the whole server back, instead of a container that looks "Up" with a
# dead part. `docker logs fullbars-brb` shows why.
set -eu
SRTLA_PORT=${SRTLA_PORT:-5000}; W=${SLATE_W:-1920}; H=${SLATE_H:-1080}
mkdir -p /data

# Default BRB slate. The phone sends its own (identical encoder settings, so SPS/PPS match) about 2 s into each
# stream; the keeper keeps that in /data/slate-phone.ts and prefers it. This one only covers a first dropout before then.
if [ ! -s /data/slate.ts ]; then  # -s: a killed earlier run can leave an empty file
  ffmpeg -nostdin -loglevel error \
    -f lavfi -i "color=c=0x0d0d0d:s=${W}x${H}:r=30,drawtext=fontfile=/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf:text='BE RIGHT BACK':fontcolor=0xF4F1EA:fontsize=${H}/9:x=(w-tw)/2:y=(h-th)/2" \
    -f lavfi -i anullsrc=r=48000:cl=stereo -t 4 \
    -c:v libx264 -preset ultrafast -tune zerolatency -threads 1 -profile:v high -b:v 2M -g 60 -pix_fmt yuv420p -c:a aac -b:a 128k -f mpegts /data/slate.tmp.ts && mv /data/slate.tmp.ts /data/slate.ts
fi

# Control API for the paired app (TLS on TCP, same port number as SRTLA's UDP). First start creates the pairing.
brb control -listen ":$SRTLA_PORT" -stats-listen ":${STATS_PORT:-8181}" -data /data -events /data/events.jsonl &
CONTROL_PID=$!
srtla_rec "$SRTLA_PORT" 127.0.0.1 4001 &
SRTLA_PID=$!
KEEPER_PID=""
WATCH_PID=""

stop_all() { kill $SRTLA_PID $CONTROL_PID ${KEEPER_PID:-} ${WATCH_PID:-} 2>/dev/null || true; }
# docker stop: stop the parts and exit now. The keeper gets a moment to end the Twitch stream cleanly; docker no
# longer has to wait out its timeout.
trap 'stop_all; [ -z "$KEEPER_PID" ] || wait "$KEEPER_PID" 2>/dev/null || true; exit 0' INT TERM
# The watchdog's signal: a part died on its own.
trap 'echo "fullbars-brb: the control API or srtla_rec stopped; restarting the server"; stop_all; exit 1' USR1
( while kill -0 $SRTLA_PID 2>/dev/null && kill -0 $CONTROL_PID 2>/dev/null; do sleep 5; done; kill -USR1 $$ ) &
WATCH_PID=$!

echo "fullbars-brb: bonding on udp/$SRTLA_PORT, control on tcp/$SRTLA_PORT, OBS pull on udp/${OBS_PORT:-5001}, NOALBS stats on tcp/${STATS_PORT:-8181}"
echo "  pair the app:  docker exec -it fullbars-brb brb pair      home OBS settings:  docker exec -it fullbars-brb brb obs"

# One loop iteration = one stream session: idle until the phone goes live, BRB through dropouts, end after HOLD on
# slate (or when the app says the streamer ended: POST /v1/end), then wait for the next one. The keeper opens its
# output when the phone goes live: the app's choice (Twitch, or "My OBS": served on OBS_PORT for home OBS to pull),
# else OUTPUT_URL, else a recording in /data. It runs in the background so the traps above fire at once (a shell runs traps only
# between commands, and `wait` is interruptible where a foreground command isn't).
while true; do
  if ! kill -0 $SRTLA_PID 2>/dev/null || ! kill -0 $CONTROL_PID 2>/dev/null; then
    echo "fullbars-brb: the control API or srtla_rec stopped; restarting the server"
    stop_all
    exit 1
  fi
  brb -wait-live -listen :4001 -pull-listen ":${OBS_PORT:-5001}" -data /data -slate /data/slate.ts -phone-slate /data/slate-phone.ts \
    -stall "${STALL:-1500ms}" -hold "${HOLD:-10m}" -latency "${LATENCY:-1s}" -reorder "${REORDER:-64}" -events /data/events.jsonl &
  KEEPER_PID=$!
  wait "$KEEPER_PID" || true
  KEEPER_PID=""
  sleep 1
done
