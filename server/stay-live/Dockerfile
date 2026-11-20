# The bare slate keeper on its own, run as `stay-live`, for testing it in isolation. The real BRB server is server/brb/Dockerfile, which also bundles srtla_rec and the `brb` command.
# Final image carries ffmpeg only for the remux to RTMP; no transcoding.
FROM golang:1.26-bookworm AS build
WORKDIR /src
COPY go.mod go.sum ./
COPY third_party ./third_party
RUN go mod download
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -o /stay-live .

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y --no-install-recommends ffmpeg ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=build /stay-live /usr/local/bin/stay-live
ENTRYPOINT ["stay-live"]
