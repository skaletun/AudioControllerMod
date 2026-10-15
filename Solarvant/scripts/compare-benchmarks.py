#!/usr/bin/env python3
"""Reproducible paired Solarvant-vs-Sodium frame-time gate.

Usage:
  python3 scripts/compare-benchmarks.py --sodium sodium-1.csv sodium-2.csv sodium-3.csv \
      --solarvant solarvant-1.csv solarvant-2.csv solarvant-3.csv --json report.json

All captures must use the same world, scene, hardware, resolution, render distance,
resource packs and graphics settings. A PASS is evidence only for these captures,
not for every Minecraft scene, modpack or GPU.
"""
import argparse
import csv
import json
import math
import statistics
from pathlib import Path


def percentile(sorted_values, fraction):
    index = (len(sorted_values) - 1) * fraction
    low = math.floor(index)
    high = min(len(sorted_values) - 1, low + 1)
    return sorted_values[low] + (sorted_values[high] - sorted_values[low]) * (index - low)


def load_run(path):
    with open(path, newline="", encoding="utf-8-sig") as stream:
        rows = csv.DictReader(stream)
        if rows.fieldnames != ["frame", "frametime_ms"]:
            raise ValueError(f"{path}: unexpected CSV columns {rows.fieldnames}")
        times = []
        for number, row in enumerate(rows, 1):
            frame = int(row["frame"])
            value = float(row["frametime_ms"])
            if frame != number or not math.isfinite(value) or value <= 0:
                raise ValueError(f"{path}: invalid frame {number}")
            times.append(value)
    if not times:
        raise ValueError(f"{path}: empty recording")
    ordered = sorted(times)
    n = max(1, math.ceil(len(times) * .01))
    return {
        "frames": len(times),
        "duration_seconds": round(sum(times) / 1000.0, 3),
        "average_fps": 1000.0 * len(times) / sum(times),
        "one_percent_low_fps": statistics.mean(1000.0 / v for v in ordered[-n:]),
        "p95_ms": percentile(ordered, .95),
        "p99_ms": percentile(ordered, .99),
        "stutters_over_50ms_per_minute": sum(v > 50 for v in times) / (sum(times) / 60000.0),
    }


def aggregate(paths):
    metrics = [load_run(p) for p in paths]
    if len(metrics) < 3:
        raise ValueError("At least three runs per renderer are required")
    return {
        "runs": [{"file": str(p), **data} for p, data in zip(paths, metrics)],
        "median": {key: statistics.median(r[key] for r in metrics)
                   for key in ("average_fps", "one_percent_low_fps", "p95_ms",
                               "p99_ms", "stutters_over_50ms_per_minute")},
    }


def evaluate(sodium, solarvant):
    a, b = sodium["median"], solarvant["median"]
    checks = {
        "average_fps_strictly_faster": b["average_fps"] > a["average_fps"],
        "one_percent_low_fps_strictly_faster": b["one_percent_low_fps"] > a["one_percent_low_fps"],
        "p95_frame_time_no_worse": b["p95_ms"] <= a["p95_ms"],
        "p99_frame_time_no_worse": b["p99_ms"] <= a["p99_ms"],
        "long_stutters_no_worse": b["stutters_over_50ms_per_minute"] <= a["stutters_over_50ms_per_minute"],
    }
    return {"pass": all(checks.values()), "checks": checks}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sodium", type=Path, nargs="+", required=True)
    parser.add_argument("--solarvant", type=Path, nargs="+", required=True)
    parser.add_argument("--json", type=Path)
    args = parser.parse_args()
    sodium = aggregate(args.sodium)
    solarvant = aggregate(args.solarvant)
    gate = evaluate(sodium, solarvant)
    report = {"sodium": sodium, "solarvant": solarvant, "gate": gate,
              "scope": "Measured scene only; does not prove all-scene superiority"}
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if args.json:
        args.json.parent.mkdir(parents=True, exist_ok=True)
        args.json.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    return 0 if gate["pass"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
