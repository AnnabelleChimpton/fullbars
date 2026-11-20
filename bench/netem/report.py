#!/usr/bin/env python3
"""Medians over repeated netem runs, next to the checked-in baseline. Report only: it never fails.

    python3 report.py [--baseline ../baseline.json] [--json medians.json] results/*/result.json

Runs are grouped by receiver, scenario, sender, source and ABR preset. For each metric it prints the median, the
spread (largest distance of any run from the median, in percent, so run-to-run noise is visible before anyone
trusts a difference), and the baseline value with the change against it. Markdown, for a CI job summary.
"""
import argparse
import json
import pathlib
import statistics

METRICS = [
    # (key, lower is better)
    ("srt_dropped_unrecoverable", True),
    ("srt_lost", True),
    ("srt_retransmitted", True),
    ("retransmit_excess_ratio", True),
    ("time_to_divert_s", True),
    ("utilisation", False),
    ("abr_target_mean_kbps", False),
    ("abr_queue_p95_ms", True),
    ("overcap_total", True),
    ("video_gap_count", True),
]


def group_key(r):
    return "/".join(str(r.get(k) or "-") for k in ("receiver", "scenario", "sender", "source", "abr"))


def fmt(v):
    if v is None:
        return "–"
    if isinstance(v, float):
        return f"{v:.0f}" if abs(v) >= 100 else f"{v:.3g}"
    return str(v)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--baseline", type=pathlib.Path)
    ap.add_argument("--json", type=pathlib.Path, help="also write the medians here")
    ap.add_argument("results", nargs="+", type=pathlib.Path)
    a = ap.parse_args()

    groups = {}
    for p in a.results:
        try:
            r = json.loads(p.read_text())
        except (OSError, ValueError):
            continue
        r["video_gap_count"] = len(r["video_gaps"]) if isinstance(r.get("video_gaps"), list) else None
        groups.setdefault(group_key(r), []).append(r)

    baseline = {}
    if a.baseline and a.baseline.exists():
        baseline = json.loads(a.baseline.read_text()).get("groups", {})

    medians = {}
    print("## Bonding netem bench\n")
    print("Medians over repeated runs. Spread is how far the furthest run sits from the median. "
          "Report only: differences inside the spread are noise.\n")
    for key in sorted(groups):
        runs = groups[key]
        commits = sorted({str(r.get("commit")) for r in runs})
        print(f"### {key}\n\n{len(runs)} run(s), commit {', '.join(commits)}\n")
        print("| metric | median | spread | baseline | change |\n|---|---|---|---|---|")
        medians[key] = {}
        for metric, lower_better in METRICS:
            vals = [r[metric] for r in runs if isinstance(r.get(metric), (int, float))]
            if not vals:
                continue
            m = statistics.median(vals)
            spread = max(abs(v - m) for v in vals) / abs(m) * 100 if m else 0.0
            medians[key][metric] = m
            b = baseline.get(key, {}).get(metric)
            change = ""
            if isinstance(b, (int, float)) and b:
                pct = (m - b) / abs(b) * 100
                better = pct < 0 if lower_better else pct > 0
                change = f"{pct:+.0f}% ({'better' if better else 'worse'})" if abs(pct) >= 1 else "same"
            print(f"| {metric} | {fmt(m)} | ±{spread:.0f}% | {fmt(b)} | {change} |")
        print()
    if a.json:
        a.json.write_text(json.dumps({"groups": medians}, indent=1, sort_keys=True) + "\n")


if __name__ == "__main__":
    main()
