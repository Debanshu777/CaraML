#!/usr/bin/env python3
"""Summarize physical iPhone runs; never combine their RSS with Android PSS."""
import argparse
import json
from pathlib import Path
import statistics


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    args = parser.parse_args()
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


if __name__ == '__main__':
    main()
