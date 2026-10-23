"""Summarize Android PSS or iPhone RSS evidence separately; exclude invalid runs."""
import argparse
import json
from pathlib import Path
import statistics


def android_main(argv=None):
    """Summarize retained benchmark evidence without mixing warmups or profiled runs."""
    parser = argparse.ArgumentParser(description=android_main.__doc__)
    parser.add_argument("directory", type=Path)
    args = parser.parse_args(argv)
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


def iphone_main(argv=None):
    """Summarize physical iPhone runs; never combine their RSS with Android PSS."""
    parser = argparse.ArgumentParser(description=iphone_main.__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args(argv)
    print('| Configuration | Decode tok/s (median; range) | TTFT ms | Load ms | Peak RSS MiB | Thermal states | GPU layers |')
    print('|---|---:|---:|---:|---:|---|---:|')
    for path in sorted(args.directory.glob('*.jsonl')):
        rows = [json.loads(line) for line in path.read_text().splitlines() if line.strip()]
        runs = [row for row in rows if row.get('event') == 'RUN' and row['iteration'] > 0]
        if not (any(row.get('event') == 'COMPLETE' for row in rows) and
                any(row.get('event') == 'HOST_RESULT' and row.get('success') for row in rows)):
            print(f'| {path.stem} | Incomplete; see raw log | — | — | — | — | — |')
            continue
        if len(runs) != 3:
            raise ValueError(f'{path}: expected three measured repetitions')
        load = next(row for row in rows if row['event'] == 'LOAD')
        rates = [row['decodeTokensPerSecond'] for row in runs]
        ttft = statistics.median(row['ttftMs'] for row in runs)
        memory = max(row['peakResidentBytes'] for row in runs) / 2**20
        thermal = ', '.join(str(state) for state in sorted({row['thermalState'] for row in runs}))
        layers = load.get('actualGpuLayers', '—')
        print(f'| {path.stem} | {statistics.median(rates):.2f} ({min(rates):.2f}–{max(rates):.2f}) | {ttft:.1f} | {load["loadMs"]:.1f} | {memory:.0f} | {thermal} | {layers} |')
