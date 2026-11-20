#!/usr/bin/env python3
"""Turns one netem run's raw logs into result.json (and a short human summary on stdout).

    python3 verify.py results/<run id>

Reads what run.sh leaves in the run directory: meta.json, harness-events.jsonl (when links were cut or restored, in
the sender's time base), sender-stats.jsonl (our sender's per-second stats), sink-srt-stats.jsonl (srt-live-transmit)
or keeper-events.jsonl/sink.log (Stay-live), video-pts.txt and probe.txt. Standard library only.

Every number is about what a viewer gets or what the bond wastes:
  srt_dropped_unrecoverable  packets the SRT receiver gave up on (too late): visible glitches
  srt_lost / srt_retransmitted / retransmit_excess_ratio   loss detected, resends received, and the share of resends
                             beyond the losses (resends of packets that were only reordered or already there)
  video_gaps                 holes over 0.5 s in the received video's timestamps
  time_to_divert_s           per link-down event: seconds until the dead link carries (almost) nothing
  time_to_recover_s          per link-up event: seconds until it carries a real share again
  utilisation                ABR target (or delivered rate) over the scenario's shaped capacity, after warm-up; read
                             it against synth_bps in meta.json (the controller's ceiling), which run.sh keeps above
                             the capacity so the ceiling never caps it
  reports_below_0_5mbps      srt-live-transmit stats reports (one per 1000 packets) with the receive rate under
                             0.5 Mbps: stalls, counted in reports rather than seconds
"""
import json
import pathlib
import re
import sys

WARMUP_S = 20  # ABR startup ramp; utilisation and queue numbers start after it


def read_jsonl(path):
    rows = []
    if not path.exists():
        return rows
    for line in path.read_text().splitlines():
        line = line.strip().rstrip(",")
        if not line.startswith("{"):
            continue
        try:
            rows.append(json.loads(line))
        except ValueError:
            pass
    return rows


def percentile(values, p):
    if not values:
        return None
    v = sorted(values)
    return v[min(len(v) - 1, max(0, int(round(p / 100.0 * (len(v) - 1)))))]


def link_deltas(rows, label):
    """(t, packets sent that second) for one link, from the cumulative per-link counters."""
    out, prev = [], None
    for r in rows:
        link = next((l for l in r.get("links", []) if l.get("label") == label), None)
        if link is None:
            continue
        pkts = link.get("pkts", 0)
        if prev is not None:
            out.append((r["t"], max(0, pkts - prev)))
        prev = pkts
    return out


def divert_times(rows, events):
    """Seconds from each link-down to the first second the link carried under 5% of its earlier rate."""
    results = []
    for e in events:
        if e.get("event") != "down" or e.get("link") in (None, "all"):
            continue
        d = link_deltas(rows, e["link"])
        before = [n for t, n in d if e["t"] - 10 <= t < e["t"]]
        if not before:
            continue
        base = sum(before) / len(before)
        limit = max(5, base * 0.05)
        after = [t for t, n in d if t >= e["t"] and n <= limit]
        results.append({"link": e["link"], "at_s": e["t"], "pps_before": round(base),
                        "time_to_divert_s": round(after[0] - e["t"], 1) if after else None})
    return results


def recover_times(rows, events):
    """Seconds from each link-up to the first second the link carried a quarter of its pre-outage rate."""
    results = []
    downs = {}
    for e in events:
        link = e.get("link")
        if link in (None, "all"):
            continue
        if e.get("event") == "down":
            downs[link] = e["t"]
        elif e.get("event") == "up" and link in downs:
            d = link_deltas(rows, link)
            before = [n for t, n in d if downs[link] - 10 <= t < downs[link]]
            if not before:
                continue
            base = sum(before) / len(before)
            back = [t for t, n in d if t >= e["t"] and n >= base * 0.25]
            results.append({"link": link, "at_s": e["t"], "time_to_recover_s": round(back[0] - e["t"], 1) if back else None})
    return results


def srt_live_transmit_stats(path):
    lost = dropped = retrans = 0
    rates = []
    for r in read_jsonl(path):
        rv = r.get("recv", {})
        lost += rv.get("packetsLost", 0)
        dropped += rv.get("packetsDropped", 0)
        retrans += rv.get("packetsRetransmitted", 0)
        rates.append(rv.get("mbitRate", 0))
    return lost, dropped, retrans, rates


# Stay-live writes the same cumulative counters in its 30 s report ("SRT session N stats: received ...") and once
# more when the session ends ("SRT session N ended (why); skipped damaged K; received ..."), which has the final totals.
STAYLIVE = re.compile(r"SRT session (\d+)(?: stats:| ended \(.*?\); skipped damaged \d+;) received (\d+) \(unique \d+\), "
                      r"reordered but in time \d+, resent (\d+), asked for again \d+, lost (\d+), dropped (\d+)")


def staylive_stats(out):
    """Final counters per SRT session, summed over sessions: the session-end line, or the last 30 s report for a
    session still open when the run stopped. The counters only grow, so the line with the most packets received is
    the latest whichever file it came from."""
    last = {}
    for src in (out / "keeper-events.jsonl", out / "sink.log"):
        if src.exists():
            for m in STAYLIVE.finditer(src.read_text(errors="replace")):
                v = (int(m.group(2)), int(m.group(4)), int(m.group(5)), int(m.group(3)))  # received, lost, dropped, resent
                if m.group(1) not in last or v[0] >= last[m.group(1)][0]:
                    last[m.group(1)] = v
    if not last:
        return None
    return tuple(sum(v[i] for v in last.values()) for i in (1, 2, 3))


def main(out):
    meta = json.loads((out / "meta.json").read_text()) if (out / "meta.json").exists() else {}
    res = dict(meta)
    events = read_jsonl(out / "harness-events.jsonl")
    rows = read_jsonl(out / "sender-stats.jsonl")

    # Receiver-side SRT truth.
    st = staylive_stats(out)
    if st is not None:
        lost, dropped, retrans = st
        rates = []
        res["srt_stats_source"] = "stay-live (session-end totals, or the last 30 s report for an open session)"
    else:
        lost, dropped, retrans, rates = srt_live_transmit_stats(out / "sink-srt-stats.jsonl")
        res["srt_stats_source"] = "srt-live-transmit"
    res.update(srt_lost=lost, srt_dropped_unrecoverable=dropped, srt_retransmitted=retrans,
               retransmit_excess_ratio=round(max(0, retrans - lost) / retrans, 3) if retrans else 0.0,
               # srt-live-transmit reports every 1000 packets (-s counts packets, not ms): about every 3.5 s at
               # 3 Mbps, so this counts reports, not seconds.
               reports_below_0_5mbps=sum(1 for x in rates[1:-1] if x < 0.5) if rates else None)

    # What reached the end: video holes and decode errors.
    pts_file = out / "video-pts.txt"
    if pts_file.exists():
        v = []
        for line in pts_file.read_text().splitlines():
            f = line.strip().split(",")[0]
            try:
                v.append(float(f))
            except ValueError:
                pass
        v.sort()
        res["video_gaps"] = [[round(a, 1), round(b - a, 1)] for a, b in zip(v, v[1:]) if b - a > 0.5]
        res["video_span_s"] = round(v[-1] - v[0], 1) if v else 0
    probe = out / "probe.txt"
    if probe.exists():
        for line in probe.read_text().splitlines():
            k, _, val = line.partition("=")
            try:
                res[k.strip()] = float(val) if "." in val else int(val)
            except ValueError:
                res[k.strip()] = None

    # Our sender's view.
    if rows:
        last = rows[-1]
        res["sender_samples"] = len(rows)
        res["dropped_no_link"] = last.get("dropped", 0)
        res["send_errors"] = last.get("sendErrors", 0)
        res["overcap_total"] = last.get("overCap", 0)
        res["links"] = {l["label"]: {"pkts": l.get("pkts", 0), "naks": l.get("naks", 0), "phase": l.get("phase")}
                        for l in last.get("links", [])}
        pkts = sum(l.get("pkts", 0) for l in last.get("links", []))
        res["nak_rate"] = round(sum(l.get("naks", 0) for l in last.get("links", [])) / pkts, 4) if pkts else None
        n = max((r.get("connected", 0) for r in rows), default=0)
        res["seconds_with_fewer_links"] = sum(1 for r in rows if r.get("connected", 0) < n)
        steady = [r for r in rows if r["t"] >= WARMUP_S]
        tail = steady[len(steady) // 2:] if meta.get("scenario") == "stepdown" else steady  # after the step
        if any(r.get("targetKbps") for r in rows):
            tk = [r["targetKbps"] for r in steady]
            res["abr_target_mean_kbps"] = round(sum(tk) / len(tk)) if tk else None
            res["abr_target_min_kbps"] = min(tk) if tk else None
            res["abr_target_max_kbps"] = max(tk) if tk else None
            res["abr_queue_p95_ms"] = percentile([r.get("queueMs", 0) for r in steady], 95)
            ceiling = meta.get("synth_bps") if meta.get("synth_bps_is_ceiling") else None
            if ceiling:
                # Seconds the controller sat at its ceiling: if this is high, the ceiling (not the links) set the
                # bitrate and utilisation says nothing about the bond.
                res["abr_seconds_at_ceiling"] = sum(1 for r in steady if r["targetKbps"] >= ceiling // 1000 - 1)
            used = [r["targetKbps"] for r in tail]
        else:
            used = [r.get("deliveredKbps", 0) for r in tail]
        cap = meta.get("capacity_kbps") or 0
        res["utilisation"] = round(sum(used) / len(used) / cap, 3) if cap and used else None
        res["divert"] = divert_times(rows, events)
        res["recover"] = recover_times(rows, events)
        diverts = [d["time_to_divert_s"] for d in res["divert"] if d["time_to_divert_s"] is not None]
        res["time_to_divert_s"] = max(diverts) if diverts else None
    res["sender_events"] = []
    log = out / "sender.log"
    if log.exists():
        res["sender_events"] = [l.split(" ", 1)[-1] for l in log.read_text(errors="replace").splitlines() if "EVENT" in l][:200]

    (out / "result.json").write_text(json.dumps(res, indent=1, sort_keys=True) + "\n")
    keys = ["receiver", "scenario", "seed", "commit", "srt_dropped_unrecoverable", "srt_lost", "srt_retransmitted",
            "retransmit_excess_ratio", "time_to_divert_s", "utilisation", "abr_target_mean_kbps", "abr_queue_p95_ms",
            "overcap_total", "video_gaps", "decode_error_lines"]
    for k in keys:
        if k in res:
            print(f"{k}={res[k]}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(pathlib.Path(sys.argv[1]))
