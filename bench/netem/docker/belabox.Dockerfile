# Reference receiver: BELABOX srtla_rec, built UNMODIFIED from a pinned commit (AGPL-3.0).
# Used only as a test counterpart; never linked into or shipped with our code.
FROM debian:bookworm-slim AS build
ARG SRTLA_COMMIT=37862da3d0c13b46956efd3f88877053293d97d6
RUN apt-get update && apt-get install -y --no-install-recommends build-essential git ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN git clone https://github.com/BELABOX/srtla.git /src && cd /src && git checkout -q $SRTLA_COMMIT && make

FROM debian:bookworm-slim
COPY --from=build /src/srtla_rec /usr/local/bin/srtla_rec
ENTRYPOINT ["srtla_rec"]
