#!/usr/bin/env bash
# Deploy the BRB server to a VPS over SSH (builds the image there; nothing else on the host is touched).
#   server/brb/deploy.sh root@HOST        # or a host alias from ~/.ssh/config
#   PUBLIC_HOST=203.0.113.10 server/brb/deploy.sh myvps   # when phones must use a different address than SSH does
# Settings live on the server, in /opt/fullbars-brb/env (KEY=value lines): SRTLA_PORT, OBS_PORT, STATS_PORT,
# SERVER_NAME, HOLD, REC_KEEP, OUTPUT_URL. Don't pass OUTPUT_URL here; the app sends your Twitch details itself.
# Run deploy.sh again after changing a port there.
set -euo pipefail
TARGET=${1:?usage: deploy.sh user@host}

# The address phones use, for the pairing QR. The SSH target can be an alias, so ask ssh where it really connects.
if [ -z "${PUBLIC_HOST:-}" ]; then
  PUBLIC_HOST=$(ssh -G "$TARGET" 2>/dev/null | awk '$1 == "hostname" { print $2; exit }' || true)
  PUBLIC_HOST=${PUBLIC_HOST:-${TARGET#*@}}
fi
case $PUBLIC_HOST in
  '' | *[!A-Za-z0-9.:-]*) echo "deploy.sh: '$PUBLIC_HOST' doesn't look like a host name or address; set PUBLIC_HOST" >&2; exit 1 ;;
  localhost | 127.* | 10.* | 192.168.* | 172.1[6-9].* | 172.2[0-9].* | 172.3[01].* | 100.6[4-9].* | 100.[7-9][0-9].* | 100.1[01][0-9].* | 100.12[0-7].*)
    echo "deploy.sh: warning: $PUBLIC_HOST is a private address. Phones on mobile data can't reach it."
    echo "           Set PUBLIC_HOST to the server's public address and run this again, or pairing won't work away from home." ;;
esac

HERE=$(cd "$(dirname "$0")" && pwd); SERVER=$(cd "$HERE/.." && pwd)

# Runs on the server. Ports come from the env file (the container listens on the same numbers). They're read, not
# sourced: OUTPUT_URL can hold characters a shell would act on.
IFS= read -r -d '' REMOTE <<'EOF' || true
set -e
mkdir -p /opt/fullbars-brb/src /opt/fullbars-brb/data && cd /opt/fullbars-brb/src && rm -rf ./* && tar -xzf -
touch /opt/fullbars-brb/env && chmod 600 /opt/fullbars-brb/env
port() { v=$(sed -n "s/^$1=//p" /opt/fullbars-brb/env | tail -n 1 | tr -dc 0-9); echo "${v:-$2}"; }
SRTLA=$(port SRTLA_PORT 5000); OBS=$(port OBS_PORT 5001); STATS=$(port STATS_PORT 8181)
echo "Building… the first build takes a few minutes."
docker build -q -t fullbars-brb -f brb/Dockerfile . >/dev/null
docker rm -f fullbars-brb >/dev/null 2>&1 || true
docker run -d --name fullbars-brb --restart unless-stopped --env-file /opt/fullbars-brb/env -e PUBLIC_HOST="$PUBLIC_HOST" \
  -p "$SRTLA:$SRTLA/udp" -p "$SRTLA:$SRTLA/tcp" -p "$OBS:$OBS/udp" -p "$STATS:$STATS/tcp" \
  -v /opt/fullbars-brb/data:/data --memory 256m fullbars-brb >/dev/null
sleep 3; docker logs fullbars-brb 2>&1 | tail -4; docker ps --filter name=fullbars-brb --format "{{.Names}} {{.Status}} {{.Ports}}"
echo
echo "Allow these in your provider's firewall (on Oracle Cloud: in the instance's subnet security list too):"
echo "  your phone (required):  UDP $SRTLA and TCP $SRTLA"
echo "  only for My OBS:        UDP $OBS"
echo "  only for NOALBS stats:  TCP $STATS"
EOF

(cd "$SERVER" && COPYFILE_DISABLE=1 tar --no-xattrs -czf - brb stay-live/go.mod stay-live/go.sum stay-live/*.go stay-live/third_party) |
  ssh "$TARGET" "PUBLIC_HOST='$PUBLIC_HOST'; $REMOTE"
echo
echo "It restarts on its own after a reboot or a crash. If something's wrong: ssh $TARGET docker logs fullbars-brb"
echo "On the server:  docker exec -it fullbars-brb brb pair   (pair the app)"
echo "                docker exec -it fullbars-brb brb obs    (home OBS + NOALBS settings)"
