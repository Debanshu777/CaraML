#!/usr/bin/env python3
"""Summarize retained benchmark evidence without mixing warmups or profiled runs."""
import argparse
import json
from pathlib import Path
import statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args()
    if (args.directory / "EXCLUDED.txt").exists():
        raise ValueError((args.directory / "EXCLUDED.txt").read_text().strip())
    print("| Configuration | Decode tok/s (median; range) | TTFT ms | Load ms | Peak PSS MiB | After load °C |")
    print("|---|---:|---:|---:|---:|---:|")
    for path in sorted(args.directory.glob("*.jsonl")):
        rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        runs = [r for r in rows if r.get("event") == "generation" and not r["warmup"] and not r.get("profiling")]
        if not runs:
            continue
        if len(runs) != 3:
            raise ValueError(f"{path}: expected three measured repetitions, found {len(runs)}")
        if not all(row.get("foreground_verified") for row in runs) or any(row.get("event") == "error" for row in rows):
            raise ValueError(f"{path}: missing successful foreground verification")
        load = next(r for r in rows if r["event"] == "load")
        rates = [r["decode_tokens_per_second"] for r in runs]
        ttft = statistics.median(r["ttft_ms"] for r in runs)
        memory = max(r["peak_pss_bytes"] for r in runs) / 2**20
        print(f"| {path.stem} | {statistics.median(rates):.2f} ({min(rates):.2f}–{max(rates):.2f}) | {ttft:.1f} | {load['load_ms']:.1f} | {memory:.0f} | {load['battery_temperature_c']:.1f} |")


if __name__ == "__main__":
    main()
