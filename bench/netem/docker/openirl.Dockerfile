# OpenIRL srtla_rec, built UNMODIFIED from a pinned commit (AGPL-3.0). Test counterpart only.
FROM alpine:3.22 AS build
ARG SRTLA_COMMIT=2b19d400d88f2a35ad225d2ea048c04335e69803
RUN apk add --no-cache build-base cmake git linux-headers spdlog-dev
RUN git clone --recurse-submodules https://github.com/OpenIRL/srtla.git /src && cd /src \
    && git checkout -q $SRTLA_COMMIT && git submodule update --init --recursive \
    && mkdir build && cd build && cmake .. && make srtla_rec

FROM alpine:3.22
RUN apk add --no-cache libstdc++ spdlog fmt
COPY --from=build /src/build/srtla_rec /usr/local/bin/srtla_rec
ENTRYPOINT ["srtla_rec"]
