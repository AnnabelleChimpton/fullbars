#!/usr/bin/env bash
# The bonding sender (srtla-kt CLI) against real SRTLA receivers, over 3 emulated uplinks shaped with tc netem.
# One run = one scenario; it leaves results/<run id>/ with the raw logs and result.json (see verify.py).
#
#   ./run.sh [belabox|irlserver|openirl|staylive] [scenario] [seconds]
#
# Scenarios:
#   clean        no shaping
#   lossy        random loss and jitter on every link
#   aggregate    2 + 2 + 1.5 Mbit: no single link can carry the stream, bonding must sum them
#   kill-one     up2's network vanishes for 20 s, then comes back with the same address
#   uplink-dead  up2 loses everything it sends for 20 s, but still receives
#   tunnel       every link dead for 15 s; the SRT session must survive
#   stepdown     6 Mbit in total, then 2.5 Mbit from 20 s on (a cell getting crowded)
#   handover     up1 and up2 take turns blacking out for a second and changing delay, like a phone moving between cells
#   gilbert      bursty (Gilbert-Elliott) loss on every link
#   skew         8-10 Mbps over a 30 ms and a 120 ms link only (up3 dead): reordering across very different delays
#
# Environment:
#   SENDER=ours|belabox|irl-classic|irl-enhanced   whose sender (the reference senders are for baselines only)
#   SOURCE=x264|synth     fixed-bitrate ffmpeg source, or the CLI's encoder-shaped source (use with ABR=...)
#   ABR=fast|balanced|rock   bitrate control for SOURCE=synth
#   BITRATE=4M            x264 source bitrate (9M for skew)
#   SYNTH_BPS             SOURCE=synth: with ABR, the controller's ceiling, set above each scenario's shaped capacity
#                         so the controller (not the ceiling) finds the limit: 12000000 for skew, 8000000 for
#                         aggregate and stepdown, else 6000000 (the app's default ceiling). Without ABR, a fixed
#                         rate: 9000000 for skew, else 3000000.
#   SEED=1  SINK=srt|keeper  REORDER=64 (staylive's -reorder)
#   SKIP_BUILD=1          reuse the images already built (nothing is fetched)
#   GRADLE=./gradlew      how to build the sender CLI from srtla-kt/
#
# Topology (plain docker, no compose):
#   source (ffmpeg testsrc -> srt-live-transmit caller) --lan--> sender (srtla-kt CLI)
#   sender --up1/up2/up3 (netem per interface)--> receiver (srtla_rec) --localhost--> sink (SRT listener -> .ts)
#
# The receivers (BELABOX, irlserver, OpenIRL srtla_rec, all GPL/AGPL) are built unmodified from pinned commits inside
# containers and only ever run as separate test counterparts; none of their code is copied here.
set -euo pipefail
cd "$(dirname "$0")"

RECEIVER=${1:-belabox}
SCENARIO=${2:-clean}
DURATION=${3:-60}
SEED=${SEED:-1}
case "$SCENARIO" in skew) BITRATE=${BITRATE:-9M} ;; *) BITRATE=${BITRATE:-4M} ;; esac
if [ -n "${ABR:-}" ]; then
  case "$SCENARIO" in skew) SYNTH_BPS=${SYNTH_BPS:-12000000} ;; aggregate|stepdown) SYNTH_BPS=${SYNTH_BPS:-8000000} ;; *) SYNTH_BPS=${SYNTH_BPS:-6000000} ;; esac
else
  case "$SCENARIO" in skew) SYNTH_BPS=${SYNTH_BPS:-9000000} ;; *) SYNTH_BPS=${SYNTH_BPS:-3000000} ;; esac
fi
# The scenarios with a fixed timeline need it to fit, plus 10 s after the last change to see the recovery.
case "$SCENARIO" in kill-one|uplink-dead) MIN_S=50 ;; tunnel) MIN_S=45 ;; stepdown) MIN_S=30 ;; handover) MIN_S=15 ;; *) MIN_S=1 ;; esac
if ! [ "$DURATION" -ge "$MIN_S" ] 2>/dev/null; then
  echo "$SCENARIO needs at least $MIN_S seconds (got '$DURATION')" >&2
  exit 2
fi
HERE=$(pwd)
REPO=$(cd ../.. && pwd)
SENDER=${SENDER:-ours}
SOURCE=${SOURCE:-x264}
RUN_ID=$(date +%Y%m%d-%H%M%S)-$RECEIVER-$SCENARIO-s$SEED$([ "$SENDER" = ours ] || echo "-$SENDER")
OUT="$HERE/results/$RUN_ID"
REL="results/$RUN_ID" # what the logs print: no absolute paths in anything a run leaves behind
mkdir -p "$OUT"
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" | tee -a "$OUT/harness.log"; }
build() { [ -n "${SKIP_BUILD:-}" ] || docker build -q "$@" >/dev/null; }

# Scenario capacity in kbps (sum of the shaped rates), for utilisation; 0 = not shaped.
case "$SCENARIO" in
  aggregate) CAPACITY_KBPS=5500 ;;
  stepdown)  CAPACITY_KBPS=2500 ;; # after the step; utilisation is measured on the last part of the run
  skew)      CAPACITY_KBPS=12000 ;;
  *)         CAPACITY_KBPS=0 ;;
esac

# --- images ------------------------------------------------------------------------------------
if [ "$SENDER" = ours ] && [ -z "${SKIP_BUILD:-}" ]; then
  log "building the sender CLI from source"
  ( cd "$REPO/srtla-kt" && ${GRADLE:-./gradlew} -q :cli:installDist )
  rm -rf docker/srtla-send-kt && cp -R "$REPO/srtla-kt/cli/build/install/srtla-send-kt" docker/srtla-send-kt
fi
build -t netem-tools -f docker/tools.Dockerfile docker
[ "$SENDER" = ours ] && build -t netem-sender -f docker/sender.Dockerfile docker
case "$RECEIVER" in
  belabox|staylive)
             build -t netem-rx-belabox -f docker/belabox.Dockerfile docker
             RX_IMAGE=netem-rx-belabox; RX_ARGS=(5000 127.0.0.1 4001) ;;
  irlserver) build -t netem-rx-irlserver "https://github.com/irlserver/srtla.git#877dc16e36d3fef421f9cffd345b54a526020732"
             RX_IMAGE=netem-rx-irlserver; RX_ARGS=(--srtla_port 5000 --srt_hostname 127.0.0.1 --srt_port 4001) ;;
  openirl)   build -t netem-rx-openirl -f docker/openirl.Dockerfile docker
             RX_IMAGE=netem-rx-openirl; RX_ARGS=(--srtla_port 5000 --srt_hostname 127.0.0.1 --srt_port 4001) ;;
  *) echo "unknown receiver $RECEIVER"; exit 2 ;;
esac
SINK=${SINK:-srt}
[ "$RECEIVER" = staylive ] && SINK=keeper
[ "$SINK" = keeper ] && build -t netem-keeper "$REPO/server/stay-live"

# --- clean slate -------------------------------------------------------------------------------
cleanup() {
  docker rm -f netem-source netem-sender netem-sink netem-receiver >/dev/null 2>&1 || true
  for n in netem-lan netem-up1 netem-up2 netem-up3; do docker network rm "$n" >/dev/null 2>&1 || true; done
}
cleanup
trap cleanup EXIT
docker network create --subnet 10.31.0.0/24 netem-lan >/dev/null
for i in 1 2 3; do docker network create --subnet 10.31.$i.0/24 netem-up$i >/dev/null; done

# --- receiver + sink (sink shares the receiver's network namespace) ----------------------------
docker create --name netem-receiver --network netem-up1 --ip 10.31.1.10 "$RX_IMAGE" "${RX_ARGS[@]}" >/dev/null
docker network connect --ip 10.31.2.10 netem-up2 netem-receiver
docker network connect --ip 10.31.3.10 netem-up3 netem-receiver
docker start netem-receiver >/dev/null
if [ "$SINK" = keeper ]; then
  # The product's receive path: srtla_rec -> Stay-live (its patched gosrt waits for -reorder newer packets before
  # asking for a missing one, and covers dropouts with a slate). The slate is encoded like the source so the splice
  # is valid.
  docker run --rm -v "$OUT:/out" --entrypoint sh netem-tools -c "ffmpeg -nostdin -loglevel error \
    -f lavfi -i color=c=0x1d1f3a:s=1920x1080:r=30 -f lavfi -i anullsrc=r=48000:cl=mono -t 4 \
    -c:v libx264 -preset veryfast -tune zerolatency -b:v $BITRATE -maxrate $BITRATE -bufsize 2M -g 60 -pix_fmt yuv420p \
    -c:a aac -b:a 128k -f mpegts /out/slate.ts"
  docker run -d --name netem-sink --network container:netem-receiver -v "$OUT:/out" --entrypoint sh netem-keeper -c \
    "stay-live -listen :4001 -latency 2s -reorder ${REORDER:-64} -slate /out/slate.ts -events /out/keeper-events.jsonl > /out/received.ts 2>/out/sink.log" >/dev/null
else
  docker run -d --name netem-sink --network container:netem-receiver -v "$OUT:/out" --entrypoint sh netem-tools -c \
    "srt-live-transmit -s:1000 -pf:json -statsout:/out/sink-srt-stats.jsonl \
     'srt://:4001?mode=listener&latency=2000&lossmaxttl=40&peeridletimeo=30000' file://con > /out/received.ts 2>/out/sink.log" >/dev/null
fi

# --- sender ------------------------------------------------------------------------------------
if [ "$SENDER" = ours ]; then
  docker create --name netem-sender --cap-add NET_ADMIN --network netem-lan --ip 10.31.0.20 -v "$OUT:/out" netem-sender \
    --listen 0.0.0.0:6000 \
    --link up1=10.31.1.20@10.31.1.10:5000 \
    --link up2=10.31.2.20@10.31.2.10:5000 \
    --link up3=10.31.3.20@10.31.3.10:5000 \
    --stats /out/sender-stats.jsonl ${SENDER_ARGS:-} \
    $( [ "$SOURCE" = synth ] && echo "--synth 10.31.0.30:1234 --bitrate $SYNTH_BPS ${ABR:+--abr $ABR}" ) >/dev/null
else
  # Reference senders take one receiver address and a list of source IPs. A per-source DNAT sends each uplink to
  # the receiver's address on that uplink's own network, so netem on that interface applies.
  case "$SENDER" in
    belabox)      REF_CMD="srtla_send_belabox 6000 10.31.1.10 5000 /tmp/ips" ;;
    irl-classic)  REF_CMD="srtla_send_irl --mode classic 6000 10.31.1.10 5000 /tmp/ips" ;;
    irl-enhanced) REF_CMD="srtla_send_irl --mode enhanced 6000 10.31.1.10 5000 /tmp/ips" ;;
    *) echo "unknown SENDER $SENDER"; exit 2 ;;
  esac
  build -t netem-refsend -f docker/ref-senders.Dockerfile docker
  docker create --name netem-sender --cap-add NET_ADMIN --network netem-lan --ip 10.31.0.20 -v "$OUT:/out" \
    --entrypoint sh netem-refsend -c "
      for n in 2 3; do iptables -t nat -A OUTPUT -p udp -s 10.31.\$n.20 -d 10.31.1.10 -j DNAT --to-destination 10.31.\$n.10; done
      printf '10.31.1.20\n10.31.2.20\n10.31.3.20\n' > /tmp/ips
      RUST_LOG=info exec $REF_CMD" >/dev/null
fi
for i in 1 2 3; do docker network connect --ip 10.31.$i.20 netem-up$i netem-sender; done
docker start netem-sender >/dev/null
T0=$(date +%s) # the sender's stats count seconds from about here
event() { # event <name> [link]: machine-readable timeline for verify.py, in the sender's time base
  printf '{"t":%d,"event":"%s"%s}\n' $(( $(date +%s) - T0 )) "$1" "${2:+,\"link\":\"$2\"}" >> "$OUT/harness-events.jsonl"
}
sleep 3

iface() { docker exec netem-sender sh -c "ip -o -4 addr show | awk '/10.31.$1.20\//{print \$2}'"; }
UP1=$(iface 1); UP2=$(iface 2); UP3=$(iface 3)
netem() { # netem <iface> <args...> | netem <iface> off
  local dev=$1; shift
  docker exec netem-sender tc qdisc del dev "$dev" root 2>/dev/null || true
  [ "$1" = off ] || docker exec netem-sender tc qdisc add dev "$dev" root netem "$@"
  log "netem $dev: $*"
}

# --- source ------------------------------------------------------------------------------------
if [ "$SOURCE" = synth ]; then
docker run -d --name netem-source --network netem-lan --ip 10.31.0.30 --entrypoint sh netem-tools -c "
  exec srt-live-transmit -q 'udp://:1234' 'srt://10.31.0.20:6000?mode=caller&latency=2000&peeridletimeo=30000'
" >/dev/null
else
docker run -d --name netem-source --network netem-lan --ip 10.31.0.30 --entrypoint sh netem-tools -c "
  ffmpeg -nostdin -loglevel error -re -f lavfi -i testsrc2=size=1920x1080:rate=30 -f lavfi -i sine=frequency=440:sample_rate=48000 \
    -c:v libx264 -preset veryfast -tune zerolatency -b:v $BITRATE -maxrate $BITRATE -bufsize 2M -g 60 -pix_fmt yuv420p \
    -c:a aac -b:a 128k -f mpegts 'udp://127.0.0.1:1234?pkt_size=1316' &
  exec srt-live-transmit -q 'udp://:1234' 'srt://10.31.0.20:6000?mode=caller&latency=2000&peeridletimeo=30000'
" >/dev/null
fi
COMMIT=$(git -C "$REPO" rev-parse --short HEAD 2>/dev/null || echo unknown)
git -C "$REPO" diff --quiet HEAD 2>/dev/null || COMMIT="$COMMIT-dirty"
cat > "$OUT/meta.json" <<JSON
{"run":"$RUN_ID","commit":"$COMMIT","receiver":"$RECEIVER","scenario":"$SCENARIO","seed":$SEED,"duration_s":$DURATION,
 "bitrate":"$BITRATE","sender":"$SENDER","source":"$SOURCE","abr":"${ABR:-}","sink":"$SINK","capacity_kbps":$CAPACITY_KBPS,
 "synth_bps":$( [ "$SOURCE" = synth ] && echo "$SYNTH_BPS" || echo null ),"synth_bps_is_ceiling":$( [ -n "${ABR:-}" ] && echo true || echo false )}
JSON
log "commit=$COMMIT source=$SOURCE${ABR:+ abr=$ABR} receiver=$RECEIVER scenario=$SCENARIO seed=$SEED duration=${DURATION}s bitrate=$BITRATE$( [ "$SOURCE" = synth ] && echo " synth_bps=$SYNTH_BPS" ) ifaces=$UP1/$UP2/$UP3"

# --- scenario ----------------------------------------------------------------------------------
# A seeded schedule for the scenarios that move things around, so a seed replays the same timeline (netem's own
# randomness isn't seeded, which is why the nightly job reports medians over several runs).
jitter() { python3 -c "import random,sys; random.seed(int(sys.argv[1])); print(' '.join(str(random.randint(0, 999)) for _ in range(64)))" "$SEED"; }
case "$SCENARIO" in
  clean) sleep "$DURATION" ;;
  lossy)
    netem "$UP1" delay 60ms 20ms loss 3%
    netem "$UP2" delay 120ms 40ms loss 1%
    netem "$UP3" delay 40ms 10ms
    sleep "$DURATION" ;;
  aggregate)
    netem "$UP1" delay 50ms rate 2mbit
    netem "$UP2" delay 80ms rate 2mbit
    netem "$UP3" delay 30ms rate 1500kbit
    sleep "$DURATION" ;;
  kill-one)
    sleep 20; docker network disconnect netem-up2 netem-sender; event down up2; log "up2 disconnected"
    sleep 20; docker network connect --ip 10.31.2.20 netem-up2 netem-sender; event up up2; log "up2 reconnected"
    sleep $((DURATION - 40)) ;;
  uplink-dead)
    sleep 20; netem "$UP2" loss 100%; event down up2
    sleep 20; netem "$UP2" off; event up up2
    sleep $((DURATION - 40)) ;;
  tunnel)
    sleep 20; for d in "$UP1" "$UP2" "$UP3"; do netem "$d" loss 100%; done; event down all
    sleep 15; for d in "$UP1" "$UP2" "$UP3"; do netem "$d" off; done; event up all
    sleep $((DURATION - 35)) ;;
  stepdown)
    netem "$UP1" delay 40ms rate 2400kbit
    netem "$UP2" delay 60ms rate 2mbit
    netem "$UP3" delay 30ms rate 1600kbit
    sleep 20
    netem "$UP1" delay 40ms rate 1mbit
    netem "$UP2" delay 60ms rate 800kbit
    netem "$UP3" delay 30ms rate 700kbit
    event step all
    sleep $((DURATION - 20)) ;;
  handover)
    netem "$UP1" delay 40ms; netem "$UP2" delay 70ms; netem "$UP3" delay 50ms
    read -r -a J <<< "$(jitter)"
    end=$(( $(date +%s) + DURATION )); n=0
    while [ "$(date +%s)" -lt $((end - 8)) ]; do
      sleep "5.$(printf '%03d' "${J[$((n % 64))]}")" # 5-6 s between handovers
      if [ $((n % 2)) -eq 0 ]; then dev=$UP1; link=up1; else dev=$UP2; link=up2; fi
      netem "$dev" loss 100%; event down $link
      sleep 1
      netem "$dev" delay $(( 30 + (${J[$(((n + 7) % 64))]} % 100) ))ms; event up $link
      n=$((n + 1))
    done
    sleep $(( end > $(date +%s) ? end - $(date +%s) : 0 )) ;;
  gilbert)
    # p (good->bad) r (bad->good) 1-h (loss in bad) 1-k (loss in good): ~1% of time in bursts of ~3 packets.
    netem "$UP1" delay 50ms 10ms loss gemodel 1% 30% 70% 0.1%
    netem "$UP2" delay 80ms 15ms loss gemodel 0.5% 25% 80% 0.1%
    netem "$UP3" delay 30ms 5ms loss gemodel 1.5% 35% 60% 0.1%
    sleep "$DURATION" ;;
  skew)
    netem "$UP1" delay 30ms 5ms rate 6mbit
    netem "$UP2" delay 120ms 20ms rate 6mbit
    netem "$UP3" loss 100%; event down up3
    sleep "$DURATION" ;;
  *) echo "unknown scenario $SCENARIO"; exit 2 ;;
esac

# --- collect -----------------------------------------------------------------------------------
docker logs netem-sender > "$OUT/sender.log" 2>&1 || true
docker logs netem-receiver > "$OUT/receiver.log" 2>&1 || true
docker logs netem-source > "$OUT/source.log" 2>&1 || true
docker rm -f netem-source >/dev/null; sleep 3; docker stop -t 3 netem-sink >/dev/null 2>&1 || true; docker rm -f netem-sink >/dev/null
if [ "$SOURCE" != synth ]; then
  # Video timestamps for the gap check, and decode errors; verify.py does the arithmetic.
  docker run --rm -v "$OUT:/out" --entrypoint sh netem-tools -c '
    ffprobe -v error -select_streams v:0 -show_entries packet=pts_time -of csv=p=0 /out/received.ts > /out/video-pts.txt 2>/dev/null || true
    dur=$(ffprobe -v error -show_entries format=duration -of csv=p=0 /out/received.ts 2>/dev/null || echo 0)
    errs=$(ffmpeg -nostdin -v error -i /out/received.ts -f null - 2>&1 | wc -l)
    size=$(stat -c %s /out/received.ts 2>/dev/null || echo 0)
    printf "received_duration_s=%s\ndecode_error_lines=%s\nreceived_bytes=%s\n" "$dur" "$errs" "$size" > /out/probe.txt'
fi
python3 "$HERE/verify.py" "$OUT" | tee "$OUT/verify.txt"
log "results in $REL"
