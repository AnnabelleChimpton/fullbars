# Fullbars BRB server: SRTLA in -> slate keeper -> RTMP(S) out (or a recording). Pure remux, no transcoding.
# Commands inside the container: `brb pair`, `brb obs` (`stay-live` is the same program, kept as an alias).
# srtla_rec is BELABOX's receiver, built UNMODIFIED from a pinned commit and run as a separate program (AGPL-3.0,
# source: https://github.com/BELABOX/srtla). Everything else here is Apache-2.0.

FROM debian:bookworm-slim AS srtla
ARG SRTLA_COMMIT=37862da3d0c13b46956efd3f88877053293d97d6
RUN apt-get update && apt-get install -y --no-install-recommends build-essential git ca-certificates && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/BELABOX/srtla.git /src && cd /src && git checkout -q $SRTLA_COMMIT && make srtla_rec

FROM golang:1.26-bookworm AS keeper
WORKDIR /src
COPY stay-live/go.mod stay-live/go.sum ./
# gosrt is vendored with a patch (stay-live/third_party/gosrt/PATCHES.md); go.mod points at it.
COPY stay-live/third_party ./third_party
RUN go mod download
COPY stay-live/*.go ./
RUN CGO_ENABLED=0 go build -trimpath -o /stay-live .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg fonts-dejavu-core ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=srtla /src/srtla_rec /usr/local/bin/srtla_rec
COPY --from=keeper /stay-live /usr/local/bin/stay-live
RUN ln -s /usr/local/bin/stay-live /usr/local/bin/brb
COPY brb/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
EXPOSE 5000/udp 5000/tcp
ENTRYPOINT ["/entrypoint.sh"]
