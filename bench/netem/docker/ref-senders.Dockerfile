# Reference SRTLA senders, built UNMODIFIED from pinned commits, for baseline comparison only.
#   /usr/local/bin/srtla_send_belabox   BELABOX srtla_send (AGPL-3.0)  — classic algorithm
#   /usr/local/bin/srtla_send_irl       irlserver srtla_send (MIT)      — --mode classic | enhanced
# Never linked into or shipped with our code.
FROM debian:bookworm-slim AS belabox
ARG BELABOX_COMMIT=37862da3d0c13b46956efd3f88877053293d97d6
RUN apt-get update && apt-get install -y --no-install-recommends build-essential git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/BELABOX/srtla.git /src && cd /src && git checkout -q $BELABOX_COMMIT && make

FROM rust:1-bookworm AS irl
ARG IRL_COMMIT=8db546cb06f2d28a336e0a422f706ce79f515c95
RUN git clone https://github.com/irlserver/srtla_send.git /src && cd /src && git checkout -q $IRL_COMMIT \
    && cargo build --release --locked --bin srtla_send

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 iptables && rm -rf /var/lib/apt/lists/*
COPY --from=belabox /src/srtla_send /usr/local/bin/srtla_send_belabox
COPY --from=irl /src/target/release/srtla_send /usr/local/bin/srtla_send_irl
