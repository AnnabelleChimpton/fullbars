# Bonding netem bench

The sender (`srtla-kt` CLI, built from this repo) against real SRTLA receivers over three links shaped with
`tc netem`, all in local Docker containers. One run is one scenario:

```sh
bench/netem/run.sh belabox aggregate 60                         # fixed-bitrate x264 source
SOURCE=synth ABR=balanced SEED=2 bench/netem/run.sh staylive stepdown 90   # the app's closed loop
python3 bench/netem/report.py results/*/result.json             # medians over several runs
```

Each run leaves `results/<run id>/` (git-ignored) with the raw logs, `verify.txt` and `result.json`; `verify.py`
explains every number. `run.sh` lists the scenarios and settings at the top. The on-demand GitHub job
(`.github/workflows/netem.yml`) runs every scenario with several seeds and compares the medians with
`bench/baseline.json`; see [docs/BENCHMARKS.md](../../docs/BENCHMARKS.md).

The receivers (BELABOX, irlserver and OpenIRL `srtla_rec`, GPL/AGPL) and the reference senders are built unmodified
from pinned commits inside containers and only ever run as separate test counterparts; none of their code is in
this repository.
