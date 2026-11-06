#!/usr/bin/env python3
"""Run the opt-in signed iOS harness on an explicitly selected physical iPhone.

Install the benchmark build first. Copies only fixture directories under this
app's Documents/benchmarks and retains console logs even when a run fails.
"""
import argparse
import datetime
import json
from pathlib import Path
import subprocess


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--device', required=True)
    parser.add_argument('--bundle-id', default='com.debanshu777.caraml.CaraML')
    parser.add_argument('--models', type=Path, default=Path('build/benchmarks'))
    parser.add_argument('--output', type=Path, default=Path('build/benchmark-results/iphone13'))
    parser.add_argument('--model', choices=['smollm2-135m', 'smollm2-360m', 'qwen25-05b', 'qwen3-06b', 'llama32-1b'])
    parser.add_argument('--mode', choices=['onnx', 'gguf'])
    parser.add_argument('--provider', choices=['CPU', 'COREML'], default='CPU')
    parser.add_argument('--threads', type=int, choices=[1, 2, 4])
    parser.add_argument('--gpu', type=int, choices=[0, 99])
    parser.add_argument('--skip-copy', action='store_true')
    parser.add_argument('--low-memory', action='store_true')
    args = parser.parse_args(argv)
    args.output.mkdir(parents=True, exist_ok=True)
    models = [args.model] if args.model else ['smollm2-135m', 'smollm2-360m', 'qwen25-05b', 'qwen3-06b', 'llama32-1b']
    failed = []
    for model in models:
        for mode in ([args.mode] if args.mode else ['onnx', 'gguf']):
            suffix = '-q4' if model == 'llama32-1b' and mode == 'onnx' else ''
            directory = args.models / (model + suffix + '-' + mode)
            manifest = json.loads((directory / 'benchmark-manifest.json').read_text())
            if not args.skip_copy:
                print('Copying', directory.name, flush=True)
                subprocess.run(['xcrun', 'devicectl', 'device', 'copy', 'to', '--device', args.device,
                                '--source', str(directory), '--destination', 'Documents/benchmarks/' + directory.name,
                                '--domain-type', 'appDataContainer', '--domain-identifier', args.bundle_id], check=True)
            configs = [{'threads': 1, 'gpu': 0}, {'threads': 2, 'gpu': 0}] if mode == 'onnx' else [{'threads': 2, 'gpu': 0}, {'threads': 2, 'gpu': 99}]
            if args.threads:
                configs = [dict(threads=args.threads, gpu=gpu) for gpu in ([0] if mode == 'onnx' else [0, 99])]
            if args.gpu is not None:
                configs = [config for config in configs if config['gpu'] == args.gpu]
            for config in configs:
                spec = dict(model=model, mode=mode, provider=args.provider, lowMemory=args.low_memory, **config)
                run_id = f'{model}-{mode}-{args.provider}-t{config["threads"]}-gpu{config["gpu"]}'
                if args.low_memory:
                    run_id += '-low-memory'
                print('Starting', run_id, flush=True)
                start = datetime.datetime.now(datetime.timezone.utc).isoformat()
                command = ['xcrun', 'devicectl', 'device', 'process', 'launch', '--device', args.device,
                           '--terminate-existing', '--console', '--environment-variables', json.dumps({'CARAML_BENCHMARK': json.dumps(spec)}),
                           args.bundle_id]
                log_path = args.output / (run_id + '.log')
                with log_path.open('w') as stream:
                    process_ok = False
                    try:
                        process = subprocess.run(command, text=True, stdout=stream, stderr=subprocess.STDOUT, timeout=1200)
                        process_ok = process.returncode == 0
                    except subprocess.TimeoutExpired:
                        stream.write('\nHARNESS_TIMEOUT\n')
                        # Next launch terminates only this app; no device/user-data reset.
                output = log_path.read_text()
                if 'profile has not been explicitly trusted' in output:
                    raise SystemExit('iOS blocked app launch: trust the signed developer profile on the phone, then rerun with --skip-copy for staged fixtures.')
                rows = []
                for line in output.splitlines():
                    if 'CARAML_APPLE_DEVICE ' in line:
                        row = json.loads(line.split('CARAML_APPLE_DEVICE ', 1)[1])
                        row.update(started_utc=start, repository=manifest['repository'], revision=manifest['revision'], run_id=run_id)
                        rows.append(row)
                complete = (process_ok and any(row['event'] == 'COMPLETE' for row in rows)
                            and 'The app terminated with the exit code 0.' in output)
                rows.append(dict(event='HOST_RESULT', success=complete, run_id=run_id,
                                 termination=[line for line in output.splitlines() if 'terminated' in line][-1:]))
                (args.output / (run_id + '.jsonl')).write_text(''.join(json.dumps(row) + '\n' for row in rows))
                print('Finished', run_id, 'PASS' if complete else 'FAILED',
                      [round(row['decodeTokensPerSecond'], 2) for row in rows if row['event'] == 'RUN' and row['iteration'] > 0], flush=True)
                if not complete:
                    print(output[-2400:], flush=True)
                    failed.append(run_id)
    if failed:
        raise SystemExit('Failed configurations: ' + ', '.join(failed))


if __name__ == '__main__':
    main()
