#!/usr/bin/env python3
"""Issue #2: aggregate the raw run files written by tools/bench-issue2.sh into
the markdown tables used in docs/benchmark-report.md. Every number in the
report's issue-2 section is reproducible with:

    python tools/bench-issue2-summary.py docs/benchmarks/issue-2/raw

Stdlib only. Per experiment (label without the -rN suffix) it prints:
  - throughput: n, median / min / max / range (and range as % of median)
  - phases: median across runs of each run's p50, plus H_commit p99
  - busy: tps x mean(H) (app) and chain-lock busy share (pg sampler)
  - wait_event mix and commit-flush overlap from the pg sampler
  - pgbench tps for the commit-control runs
"""
import json
import re
import statistics
import sys
from collections import defaultdict
from pathlib import Path

PHASES = ["W_queue", "W_conn", "W_row", "W_lock", "H_work", "H_commit", "H"]


def parse(path):
    text = path.read_text(encoding="utf-8", errors="replace")
    run = {"label": path.stem, "file": path.name}
    m = re.search(r"^config: (.*)$", text, re.M)
    run["config"] = m.group(1) if m else ""
    m = re.search(r"synchronous_commit before=(\S+) after=(\S+)", text)
    run["sync"] = (m.group(1), m.group(2)) if m else (None, None)
    m = re.search(r"^git: (\S+)", text, re.M)
    run["git"] = m.group(1) if m else None
    m = re.search(r"^clients=\d+ window=[\d.]+s committed=\d+ tps=([\d.]+) non201=(\d+)", text, re.M)
    if m:
        run["tps"] = float(m.group(1))
        run["non201"] = int(m.group(2))
    m = re.search(r"^tps = ([\d.]+) \(without initial connection time\)", text, re.M)
    if m:
        run["tps"] = float(m.group(1))
        run["non201"] = 0
    m = re.search(r"^number of failed transactions: (\d+)", text, re.M)
    if m:
        run["non201"] = int(m.group(1))
    m = re.search(r"^TIMINGS (\{.*\})$", text, re.M)
    if m:
        run["timings"] = json.loads(m.group(1))
    m = re.search(r"autovacuum inside the measure window.*?\n((?:\S+\s+autovacuum.*\n?)+)", text)
    if m:
        v = sum(int(x) for x in re.findall(r"autovacuum \+(\d+)", m.group(1)))
        a = sum(int(x) for x in re.findall(r"autoanalyze \+(\d+)", m.group(1)))
        run["autovac"] = (v, a)
    # sampler section 1: busy share
    m = re.search(r"chain-lock busy share ==\n.*\n.*\n\s*(\d+)\s*\|\s*(\d+)\s*\|\s*([\d.]+)", text)
    if m:
        run["pg_busy"] = float(m.group(3))
        run["pg_samples"] = int(m.group(1))
    # sampler section 2: wait distribution rows "activity | state | observations | pct"
    sec = re.search(r"wait distribution of in-transaction backends ==\n(.*?)\n\n", text, re.S)
    if sec:
        waits = defaultdict(int)
        for line in sec.group(1).splitlines()[2:]:
            cols = [c.strip() for c in line.split("|")]
            if len(cols) == 4 and cols[2].isdigit():
                waits[cols[0]] += int(cols[2])
        run["waits"] = dict(waits)
    # sampler section 3: flush overlap
    m = re.search(r"commit-flush overlap.*?\n.*\n.*\n\s*(\d+)\s*\|\s*([\d.]+)\s*\|\s*(\d+)\s*\|\s*(\d+)\s*\|\s*([\d.]+)", text)
    if m:
        run["flush_max"] = int(m.group(1))
        run["flush_ge2"] = int(m.group(3))
        run["in_tx_avg"] = float(m.group(5))
    return run


def group_key(label):
    return re.sub(r"-r\d+$", "", label)


def med(xs):
    return statistics.median(xs) if xs else float("nan")


def wait_bucket(activity):
    if activity in ("Lock:transactionid", "Lock:tuple"):
        return "row lock"
    if activity == "Lock:advisory":
        return "chain lock"
    if activity.startswith("IO:Wal") or activity == "LWLock:WALWrite":
        return "WAL flush"
    if activity.startswith("Client:"):
        return "client round trip"
    if activity == "CPU/running":
        return "running"
    return "other"


def main(raw_dir):
    sys.stdout.reconfigure(encoding="utf-8")  # µ and → on a Windows console
    runs =[parse(p) for p in sorted(Path(raw_dir).glob("*.txt"))
            if re.search(r"-r\d+$", p.stem)]
    groups = defaultdict(list)
    for r in runs:
        groups[group_key(r["label"])].append(r)

    print("## Provenance\n")
    print("| experiment | runs | git | config | synchronous_commit before/after | non-OK |")
    print("|---|---|---|---|---|---|")
    for g, rs in sorted(groups.items()):
        gits = sorted({r["git"] for r in rs if r["git"]}) or ["-"]
        syncs = sorted({"/".join(map(str, r["sync"])) for r in rs})
        print(f"| {g} | {len(rs)} | {', '.join(gits)} | {rs[0]['config']} | {', '.join(syncs)} | "
              f"{sum(r.get('non201', 0) for r in rs)} |")

    print("\n## Throughput (tps)\n")
    print("| experiment | runs (in order) | median | min | max | range | range % of median |")
    print("|---|---|---|---|---|---|---|")
    for g, rs in sorted(groups.items()):
        t = [r["tps"] for r in rs if "tps" in r]
        if not t:
            continue
        rng = max(t) - min(t)
        print(f"| {g} | {', '.join(f'{x:.1f}' for x in t)} | **{med(t):.1f}** | {min(t):.1f} | "
              f"{max(t):.1f} | {rng:.1f} | {100 * rng / med(t):.0f}% |")

    print("\n## Phases (µs): median across runs of each run's p50; H_commit p99 per run\n")
    print("| experiment | " + " | ".join(f"{p} p50" for p in PHASES)
          + " | H_commit p99 (runs) | H_commit p99 median | H_commit/H (mean) |")
    print("|---|" + "---|" * (len(PHASES) + 3))
    for g, rs in sorted(groups.items()):
        ts = [r["timings"]["phases"] for r in rs if "timings" in r]
        if not ts:
            continue
        cells = [f"{med([t[p]['p50_us'] for t in ts]):.0f}" for p in PHASES]
        p99 = [t["H_commit"]["p99_us"] for t in ts]
        share = med([t["H_commit"]["mean_us"] / t["H"]["mean_us"] for t in ts if t["H"]["mean_us"]])
        print(f"| {g} | " + " | ".join(cells)
              + f" | {', '.join(f'{x / 1000:.1f} ms' for x in p99)} | {med(p99) / 1000:.1f} ms | {share:.2f} |")

    print("\n## Busy: app (tps x mean H) vs pg sampler (share of samples with the chain lock held)\n")
    print("| experiment | app busy (median) | 1/mean(H) tps (median) | measured tps (median) | pg busy share (median) |")
    print("|---|---|---|---|---|")
    for g, rs in sorted(groups.items()):
        app = [r["tps"] * r["timings"]["phases"]["H"]["mean_us"] / 1e6 for r in rs if "timings" in r]
        inv = [1e6 / r["timings"]["phases"]["H"]["mean_us"] for r in rs if "timings" in r]
        pg = [r["pg_busy"] for r in rs if "pg_busy" in r]
        if not app and not pg:
            continue
        pg_cell = f"{med(pg):.3f}" if pg else "-"
        app_cell = f"{med(app):.3f}" if app else "-"
        inv_cell = f"{med(inv):.1f}" if inv else "-"
        print(f"| {g} | {app_cell} | {inv_cell} | {med([r['tps'] for r in rs if 'tps' in r]):.1f} | "
              f"{pg_cell} |")

    print("\n## Where in-transaction backends spend time (pg sampler, all runs pooled, % of observations)\n")
    buckets = ["row lock", "chain lock", "WAL flush", "client round trip", "running", "other"]
    print("| experiment | " + " | ".join(buckets) + " | max backends in WAL flush | samples with >=2 flushing | avg in-tx backends |")
    print("|---|" + "---|" * (len(buckets) + 3))
    for g, rs in sorted(groups.items()):
        pooled = defaultdict(int)
        for r in rs:
            for act, n in r.get("waits", {}).items():
                pooled[wait_bucket(act)] += n
        total = sum(pooled.values())
        if not total:
            continue
        cells = [f"{100 * pooled[b] / total:.1f}" for b in buckets]
        fmax = max((r.get("flush_max", 0) for r in rs), default=0)
        ge2 = sum(r.get("flush_ge2", 0) for r in rs)
        smp = sum(r.get("pg_samples", 0) for r in rs)
        intx = med([r["in_tx_avg"] for r in rs if "in_tx_avg" in r])
        print(f"| {g} | " + " | ".join(cells) + f" | {fmax} | {ge2} / {smp} | {intx:.1f} |")

    print("\n## Autovacuum inside the measure window (vacuum/analyze per run)\n")
    print("| experiment | per run |")
    print("|---|---|")
    for g, rs in sorted(groups.items()):
        av = [r["autovac"] for r in rs if "autovac" in r]
        if av:
            print(f"| {g} | {', '.join(f'+{v}/+{a}' for v, a in av)} |")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/benchmarks/issue-2/raw")
