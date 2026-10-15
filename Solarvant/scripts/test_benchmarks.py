#!/usr/bin/env python3
import importlib.util
import tempfile
from pathlib import Path

location = Path(__file__).with_name("compare-benchmarks.py")
spec = importlib.util.spec_from_file_location("compare_benchmarks", location)
benchmark = importlib.util.module_from_spec(spec)
spec.loader.exec_module(benchmark)

with tempfile.TemporaryDirectory() as directory:
    root = Path(directory)
    def create(name, values):
        path = root / name
        path.write_text("frame,frametime_ms\n" + "".join(f"{i},{v}\n" for i, v in enumerate(values, 1)))
        return path
    bad = create("bad.csv", [16, 16, 16, 5000])
    metrics = benchmark.load_run(bad)
    assert metrics["p99_ms"] > 4000, metrics
    assert metrics["stutters_over_50ms_per_minute"] > 0, metrics
    assert metrics["one_percent_low_fps"] < 1, metrics
    fast = [create(f"fast-{n}.csv", [8] * 1000 + [30] * 10) for n in range(3)]
    slow = [create(f"slow-{n}.csv", [16] * 1000 + [80] * 10) for n in range(3)]
    assert benchmark.evaluate(benchmark.aggregate(slow), benchmark.aggregate(fast))["pass"]
    assert not benchmark.evaluate(benchmark.aggregate(fast), benchmark.aggregate(slow))["pass"]
    try:
        benchmark.load_run(create("invalid.csv", [0]))
        raise AssertionError("Invalid frame accepted")
    except ValueError:
        pass
print("PASS: paired Sodium gate, 1% low, pathological stalls, strict CSV parsing")
