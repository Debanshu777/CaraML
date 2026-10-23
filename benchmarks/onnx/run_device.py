#!/usr/bin/env python3
"""Run the debug-only harness on an explicitly selected device; retain raw output and JSONL.

Models must already be in the app's external benchmarks directory. Never clears app data.
Example: python3 benchmarks/onnx/run_device.py --serial SERIAL --mode onnx --threads 1
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import re
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--mode", choices=["onnx", "gguf"], required=True)
    parser.add_argument("--provider", choices=["CPU", "XNNPACK", "NNAPI", "WEBGPU", "QNN_GPU", "QNN_HTP"], default="CPU")
    parser.add_argument("--threads", type=int, default=1)
    parser.add_argument("--gpu", type=int, default=0)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--tokens", type=int, default=64)
    parser.add_argument("--model", help="Optional single model directory prefix")
    parser.add_argument("--exclude", action="append", default=[], help="Exclude an exact model directory name")
    parser.add_argument("--max-temperature", type=float, default=35.0, help="Maximum battery temperature at run start (C)")
    parser.add_argument("--device-prefix", default="benchmarks/")
    parser.add_argument("--models", type=Path, default=Path("build/benchmarks"))
    parser.add_argument("--output", type=Path, default=Path("build/benchmark-results"))
    parser.add_argument("--profile", action="store_true")
    parser.add_argument("--portable", action="store_true")
    parser.add_argument("--basic", action="store_true")
    args = parser.parse_args()
    adb = [args.adb, "-s", args.serial]
    args.output.mkdir(parents=True, exist_ok=True)
    selected = 0
    for directory in sorted(args.models.glob("*-" + args.mode)):
        if args.model and not directory.name.startswith(args.model):
            continue
        if directory.name in args.exclude:
            continue
        selected += 1
        manifest = json.loads((directory / "benchmark-manifest.json").read_text())
        run_id = f"{directory.name}-{args.provider}-t{args.threads}-gpu{args.gpu}" + ("-profile" if args.profile else "")
        if args.portable:
            run_id += "-portable"
        if args.basic:
            run_id += "-basic"
        # Thermal state is evidence, not an invitation to keep heating a throttled device.
        subprocess.run(adb + ["shell", "input", "keyevent", "223"], check=True)  # sleep display while cooling
        for attempt in range(180):
            thermal = subprocess.check_output(adb + ["shell", "dumpsys", "thermalservice"], text=True)
            status = re.search(r"Thermal Status: (\d+)", thermal)
            battery = subprocess.check_output(adb + ["shell", "dumpsys", "battery"], text=True)
            temperature = re.search(r"temperature: (\d+)", battery)
            if status and int(status[1]) == 0 and temperature and int(temperature[1]) / 10 <= args.max_temperature:
                break
            if attempt % 12 == 0:
                print("Cooling before", run_id, "battery C:", int(temperature[1]) / 10 if temperature else "unknown", flush=True)
            time.sleep(5)
        else:
            raise RuntimeError("Device did not reach the requested temperature and nominal thermal state")
        started = datetime.datetime.now(datetime.timezone.utc).isoformat()
        subprocess.run(adb + ["shell", "input", "keyevent", "224"], check=True)
        command = adb + ["shell", "am", "instrument", "-w", "-e", "model", args.device_prefix + directory.name + "/" + manifest["graph"],
                         "-e", "threads", str(args.threads), "-e", "gpu", str(args.gpu), "-e", "provider", args.provider,
                         "-e", "tokens", str(args.tokens), "-e", "runs", str(args.runs)]
        if args.profile:
            command += ["-e", "profile", "1"]
        if args.portable:
            command += ["-e", "portable", "1"]
        if args.basic:
            command += ["-e", "basic", "1"]
        command += ["com.debanshu777.caraml/com.debanshu777.caraml.benchmark.OnnxGgufBenchmark"]
        print("Starting", run_id, flush=True)
        try:
            result = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=900)
            output = result.stdout
        except subprocess.TimeoutExpired as failure:
            output = (failure.stdout or b"").decode() + "\nHARNESS_TIMEOUT\n"
            subprocess.run(adb + ["shell", "am", "force-stop", "com.debanshu777.caraml"], check=True)
        (args.output / (run_id + ".log")).write_text(output)
        records = []
        for line in output.splitlines():
            if "CARAML_BENCH " in line:
                record = json.loads(line.split("CARAML_BENCH ", 1)[1])
                record.update(run_id=run_id, started_utc=started, repository=manifest["repository"], revision=manifest["revision"],
                              provider_requested=args.provider, threads=args.threads, gpu_requested=args.gpu, profiling=args.profile,
                              gate_temperature_c=int(temperature[1]) / 10, portable_kernels=args.portable, basic_optimizations=args.basic)
                records.append(record)
        (args.output / (run_id + ".jsonl")).write_text("".join(json.dumps(row) + "\n" for row in records))
        generations = [r for r in records if r["event"] == "generation" and not r["warmup"]]
        environment = next((r for r in records if r["event"] == "environment"), {})
        success = ("INSTRUMENTATION_CODE: -1" in output and len(generations) == args.runs
                   and all(row.get("foreground_verified") for row in generations)
                   and all(environment.get(key) for key in ["foreground_resumed", "foreground_focused", "display_interactive"]))
        print("Finished", run_id, "RUN_OK" if success else "FAILED", [round(r["decode_tokens_per_second"], 2) for r in generations], flush=True)
        if not success:
            print(output[-1800:], flush=True)
            raise SystemExit(1)
    if not selected:
        parser.error("No model manifests matched the selection")


if __name__ == "__main__":
    main()
