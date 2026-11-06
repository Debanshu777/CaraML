#!/usr/bin/env python3
"""Repeat fresh-process GGUF loads using CaraML's debug-only native harness.

Install :app:androidApp:assembleDebug first. Supply a bundle produced by
benchmarks/run.py models download. Device files stay in the app's benchmark area.
This is an engine benchmark, not release UI timing or cold-storage latency.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import time
from analysis import inspect_trace

ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.debanshu777.caraml"


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--bundle", type=Path, required=True)
    parser.add_argument("--trials", type=int, default=3)
    parser.add_argument("--runs", type=int, default=3)
    parser.add_argument("--threads", type=int, default=4)
    parser.add_argument("--gpu", type=int, default=0)
    parser.add_argument("--max-start-temperature", type=float, default=35.0,
                        help="Starting battery temperature ceiling; use the same value for comparisons")
    parser.add_argument("--trace", action="store_true", help="Capture diagnostic Perfetto traces; keep separate from untraced timing comparisons")
    parser.add_argument("--trace-processor", type=Path, default=ROOT / "build/tools/trace_processor")
    args = parser.parse_args(argv)
    if not (1 <= args.trials <= 10 and 1 <= args.runs <= 10 and 1 <= args.threads <= 8 and -1 <= args.gpu <= 99):
        parser.error("Invalid trials, runs, threads or GPU layer count")
    if not 20 <= args.max_start_temperature <= 40:
        parser.error("Starting temperature ceiling must be between 20 and 40 C")
    processor = args.trace_processor.resolve()
    if args.trace and not processor.is_file():
        parser.error("--trace requires a local official trace_processor for quality validation")
    bundle = args.bundle.resolve()
    manifest = json.loads((bundle / "benchmark-manifest.json").read_text())
    graph = manifest["graph"]
    if Path(graph).name != graph or not graph.endswith(".gguf") or not re.fullmatch(r"[A-Za-z0-9_.-]+", graph):
        parser.error("Use a single GGUF filename")
    entry = next(f for f in manifest["files"] if f["rfilename"] == graph)
    expected = entry["lfs"]["sha256"]
    model = bundle / graph
    with model.open("rb") as source:
        actual = hashlib.file_digest(source, "sha256").hexdigest()
    if model.stat().st_size != entry["size"] or actual != expected:
        parser.error("Model size or SHA-256 does not match the pinned manifest")
    adb = ["adb", "-s", args.serial]

    def shell(*command):
        return subprocess.check_output(adb + ["shell", *command], text=True).strip()

    stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    output = ROOT / "build/benchmark-results/model-load" / f"{stamp}-gpu{args.gpu}-t{args.threads}"
    output.mkdir(parents=True)
    (output / "model-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    metadata = dict(serial=args.serial, trials=args.trials, runs=args.runs, threads=args.threads,
                    gpu=args.gpu, cacheState="OS_CACHE_UNCONTROLLED; verification reads weights before testing",
                    managedBuild="DEBUG", nativeBuild="RELEASE", contextTokens=2048, tokens=64,
                    diagnosticTracing=args.trace,
                    maxStartTemperatureC=args.max_start_temperature,
                    device=shell("getprop", "ro.build.fingerprint"), allTrialsValid=False)
    apk_paths = shell("pm", "path", PACKAGE).splitlines()
    if len(apk_paths) != 1 or not apk_paths[0].startswith("package:"):
        parser.error("Install the single debug APK before running")
    metadata["installedApkSha256"] = shell("sha256sum", apk_paths[0].removeprefix("package:")).split()[0]
    metadata["gitRevision"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip()
    print(output, flush=True)
    # A content-addressed directory avoids replacing unrelated models or app catalog data.
    relative = f"load-{expected[:16]}/{graph}"
    device_path = f"/sdcard/Android/data/{PACKAGE}/files/benchmarks/{relative}"
    before_timeout = shell("settings", "get", "system", "screen_off_timeout")

    def conditions():
        thermal = shell("dumpsys", "thermalservice")
        battery = shell("dumpsys", "battery")
        status = re.search(r"^Thermal Status: (\d+)$", thermal, re.MULTILINE)
        temperature = re.search(r"temperature: (\d+)", battery)
        return {"thermal": int(status[1]) if status else None,
                "batteryTemperatureC": int(temperature[1]) / 10 if temperature else None}

    try:
        shell("settings", "put", "system", "screen_off_timeout", "1800000")
        shell("mkdir", "-p", str(Path(device_path).parent))
        current = subprocess.run(adb + ["shell", "sha256sum", device_path], text=True, capture_output=True)
        if current.returncode != 0 or current.stdout.split()[0] != expected:
            subprocess.run(adb + ["push", str(model), device_path], check=True)
        if shell("sha256sum", device_path).split()[0] != expected:
            raise RuntimeError("Device model hash mismatch")
        valid = []
        for trial in range(1, args.trials + 1):
            deadline = time.monotonic() + 900
            while True:
                before = conditions()
                if before["thermal"] == 0 and before["batteryTemperatureC"] is not None and before["batteryTemperatureC"] <= args.max_start_temperature:
                    break
                if time.monotonic() >= deadline:
                    raise RuntimeError("Device did not cool within 15 minutes")
                print("Cooling:", before, flush=True)
                time.sleep(15)
            shell("am", "force-stop", PACKAGE)
            trace_path = f"/data/misc/perfetto-traces/caraml-load-{stamp}-{trial}.perfetto-trace"
            trace_pid = None
            trace_quality = None
            if args.trace:
                trace_pid = shell("perfetto", "-D", "-t", "300s", "-b", "64mb", "-o", trace_path,
                                  "-a", PACKAGE, "sched", "freq", "idle", "am", "wm", "gfx", "view", "dalvik")
                if not trace_pid.isdigit():
                    raise RuntimeError(f"Unexpected Perfetto PID: {trace_pid}")
            command = adb + ["shell", "am", "instrument", "-w", "-r", "-e", "model", relative,
                             "-e", "threads", str(args.threads), "-e", "gpu", str(args.gpu),
                             "-e", "tokens", "64", "-e", "runs", str(args.runs),
                             f"{PACKAGE}/{PACKAGE}.benchmark.OnnxGgufBenchmark"]
            print(f"Trial {trial}/{args.trials}: fresh-process load + one warmup + {args.runs} measured generations", flush=True)
            log = output / f"trial-{trial}.txt"
            with log.open("w") as stream:
                try:
                    result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=900)
                except subprocess.TimeoutExpired:
                    shell("am", "force-stop", PACKAGE)
                    raise
                finally:
                    if trace_pid:
                        subprocess.run(adb + ["shell", "kill", "-TERM", trace_pid], check=False)
                        # The shell process may disappear before the trace file is flushed.
                        # Require a nonempty, stable file before pulling or removing it.
                        previous_size = 0
                        stable = 0
                        for _ in range(100):
                            size = int(shell("stat", "-c", "%s", trace_path))
                            stable = stable + 1 if size > 0 and size == previous_size else 0
                            previous_size = size
                            if stable >= 5:
                                break
                            time.sleep(0.1)
                        else:
                            raise RuntimeError("Perfetto did not flush a stable nonempty trace")
                        local_trace = output / f"trial-{trial}.perfetto-trace"
                        subprocess.run(adb + ["pull", trace_path, str(local_trace)], check=True)
                        if local_trace.stat().st_size != previous_size:
                            raise RuntimeError("Incomplete Perfetto trace pull")
                        shell("rm", trace_path)
                        trace_quality = inspect_trace(processor, local_trace)
                        (output / f"trial-{trial}.quality.json").write_text(json.dumps(trace_quality, indent=2) + "\n")
            raw = log.read_text()
            native_log = subprocess.check_output(adb + ["logcat", "-d", "-t", "6000", "-s", "LlamaRunner"], text=True)
            (output / f"trial-{trial}-native-logcat.txt").write_text(native_log)
            events = [json.loads(line.split("CARAML_BENCH ", 1)[1]) for line in raw.splitlines() if "CARAML_BENCH " in line]
            generations = [e for e in events if e.get("event") == "generation"]
            loads = [e for e in events if e.get("event") == "load"]
            placement = re.search(r"gpuLayers=(\d+)", loads[0].get("runtime", "")) if len(loads) == 1 else None
            backend_matches = placement is not None and ((int(placement[1]) > 0) == (args.gpu != 0))
            after = conditions()
            passed = (result.returncode == 0 and "INSTRUMENTATION_CODE: -1" in raw
                      and len(generations) == args.runs + 1
                      and len(loads) == 1 and backend_matches
                      and all(e.get("foreground_verified") for e in generations)
                      and not any(e.get("event") == "error" for e in events))
            timing_valid = (passed and after["thermal"] == 0
                           and loads[0]["thermal"] == 0
                           and all(e["thermal_start"] == 0 and e["thermal_end"] == 0 for e in generations))
            trace_valid = trace_quality["valid"] if trace_quality is not None else not args.trace
            trial_valid = timing_valid and trace_valid
            record = {"trial": trial, "before": before, "after": after, "passed": passed,
                      "timingValid": timing_valid, "traceQualityValid": trace_valid if args.trace else None,
                      "measurementValid": trial_valid, "events": events, "command": command}
            (output / f"trial-{trial}.json").write_text(json.dumps(record, indent=2) + "\n")
            print(json.dumps({k: v for k, v in record.items() if k not in {"events", "command"}}), flush=True)
            valid.append(trial_valid)
            if not passed:
                raise RuntimeError(f"Model harness failed; inspect {log}")
            if not trial_valid:
                raise RuntimeError(f"Model measurement failed thermal/trace-quality gates; inspect trial-{trial}.json")
        metadata["allTrialsValid"] = all(valid)
    except BaseException as error:
        metadata["allTrialsValid"] = False
        metadata["error"] = str(error)
        raise
    finally:
        cleanup_error = None
        try:
            if before_timeout == "null":
                shell("settings", "delete", "system", "screen_off_timeout")
            else:
                shell("settings", "put", "system", "screen_off_timeout", before_timeout)
            if shell("settings", "get", "system", "screen_off_timeout") != before_timeout:
                raise RuntimeError("Restored screen timeout did not match its original value")
            metadata["screenTimeoutRestored"] = True
        except Exception as error:
            cleanup_error = error
            metadata["screenTimeoutRestored"] = False
            metadata["screenTimeoutRestoreError"] = str(error)
            metadata["allTrialsValid"] = False
        (output / "run.json").write_text(json.dumps(metadata, indent=2) + "\n")
        if cleanup_error is not None and sys.exc_info()[0] is None:
            raise RuntimeError("Screen timeout restoration failed") from cleanup_error


if __name__ == "__main__":
    main()
