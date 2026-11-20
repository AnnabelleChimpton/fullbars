# Our sender (srtla-kt CLI) + iproute2 so the harness can apply tc netem per uplink.
FROM eclipse-temurin:21-jre
RUN apt-get update && apt-get install -y --no-install-recommends iproute2 && rm -rf /var/lib/apt/lists/*
COPY srtla-send-kt /opt/srtla-send-kt
ENTRYPOINT ["/opt/srtla-send-kt/bin/srtla-send-kt"]
