# SRT tooling for the netem harness: srt-live-transmit (SRT caller/listener), ffmpeg (test source, verification).
FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends srt-tools ffmpeg iproute2 procps \
    && rm -rf /var/lib/apt/lists/*
