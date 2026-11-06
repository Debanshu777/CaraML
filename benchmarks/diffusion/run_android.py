#!/usr/bin/env python3
"""Run one foreground JNI diffusion trial and preserve timings, conditions, logs, and PNGs."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
from datetime import datetime, timezone

PACKAGE = "com.debanshu777.caraml"
INSTRUMENTATION = PACKAGE + "/" + PACKAGE + ".benchmark.DiffusionGenerationBenchmark"


def adb(*args, **kwargs):
    return subprocess.run(["adb", *args], check=True, **kwargs)


def text(*args):
    return adb(*args, capture_output=True, text=True).stdout


def process_exit_reason(exit_info, pid):
    for block in re.split(r"ApplicationExitInfo #\d+:", exit_info)[1:]:
        if re.search(rf"\bpid={re.escape(str(pid))}\b", block):
            reason = re.search(r"\breason=(\d+) \(([^)]+)\)", block)
            if reason:
                return {"code": int(reason.group(1)), "name": reason.group(2)}
    return None


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--model", required=True, choices=["sd15", "sd-turbo", "sdxl-base", "sdxl-turbo", "z-image-turbo"])
    parser.add_argument("--tag", required=True)
    parser.add_argument("--output", type=Path, default=Path("build/diffusion-benchmark/results"))
    parser.add_argument("--models", type=Path, default=Path("build/diffusion-benchmark/models"))
    parser.add_argument("--transfer", action="store_true")
    parser.add_argument("--transfer-only", action="store_true")
    parser.add_argument("--backend", choices=["CPU", "VULKAN"], default="CPU")
    parser.add_argument("--steps", type=int)
    parser.add_argument("--threads", type=int, default=-1)
    parser.add_argument("--size", type=int, default=512)
    parser.add_argument("--runs", type=int, default=1)
    parser.add_argument("--flash", type=int, choices=[0, 1], default=1)
    parser.add_argument("--mmap", type=int, choices=[0, 1], default=0)
    parser.add_argument("--conv", type=int, choices=[0, 1], default=0)
    parser.add_argument("--tiling", type=int, choices=[0, 1], default=0)
    parser.add_argument("--offload", type=int, choices=[0, 1], default=1)
    parser.add_argument("--vae-cpu", type=int, choices=[0, 1], default=1)
    parser.add_argument("--max-vram", type=float, help="Managed GPU-memory budget in GiB")
    parser.add_argument("--segmented", type=int, choices=[0, 1], default=0)
    parser.add_argument("--prefetch", type=int, choices=[0, 1], default=0)
    parser.add_argument("--quantized", type=int, choices=[0, 1], default=0,
                        help="Use the locally converted SDXL-Turbo Q4_0 bundle")
    parser.add_argument("--preview", type=int, choices=[0, 1], default=0,
                        help="SD 1/2 only: use the approximate TAESD preview decoder")
    parser.add_argument("--wtype", type=int, default=-1)
    parser.add_argument("--sampler", type=int)
    parser.add_argument("--prediction", type=int)
    parser.add_argument("--scheduler", type=int, choices=[-1, 0, 5, 6])
    parser.add_argument("--cfg", type=float)
    parser.add_argument("--timeout", type=int, default=900)
    args = parser.parse_args(argv)
    if args.quantized and args.model != "sdxl-turbo":
        parser.error("--quantized is only defined for SDXL-Turbo")
    if args.preview and args.model not in ["sd15", "sd-turbo"]:
        parser.error("This preview decoder supports SD 1/2 only")
    if args.max_vram is not None and not 0 < args.max_vram <= 32:
        parser.error("--max-vram must be within (0, 32] GiB")
    if args.prefetch and not args.segmented:
        parser.error("--prefetch requires --segmented")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", args.tag):
        parser.error("Invalid tag")
    destination = args.output / args.tag
    destination.mkdir(parents=True, exist_ok=False)
    manifest = json.loads(Path(__file__).with_name("models.json").read_text())
    entries = [e for e in manifest["files"] if e["model"] == args.model]
    if args.quantized:
        entries = [manifest["derived_files"]["sdxl-turbo-q4_0"]]
    if args.preview:
        entries += [manifest["optional_files"]["preview-decoder"]]
    if args.transfer or args.transfer_only:
        adb("shell", "run-as", PACKAGE, "mkdir", "-p", "files/diffusion-benchmark")
        for entry in entries:
            source = args.models / entry["local"]
            if source.stat().st_size != entry["size"]:
                raise ValueError(f"Wrong file size: {source}")
            with source.open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != entry["sha256"]:
                    raise ValueError(f"Wrong hash: {source}")
            temporary = "/data/local/tmp/caraml-" + source.name
            adb("push", str(source), temporary)
            adb("shell", "run-as", PACKAGE, "cp", temporary, "files/diffusion-benchmark/" + source.name)
            adb("shell", "rm", temporary)
            verified = text("shell", "run-as", PACKAGE, "sha256sum", "files/diffusion-benchmark/" + source.name).split()[0]
            if verified != entry["sha256"]:
                raise ValueError(f"Device checksum mismatch: {source.name}")
    if args.transfer_only:
        (destination / "transfer.json").write_text(json.dumps(entries, indent=2) + "\n")
        return

    adb("shell", "am", "force-stop", PACKAGE)
    apk_hashes = [text("shell", "sha256sum", line.removeprefix("package:")).split()[0]
                  for line in text("shell", "pm", "path", PACKAGE).splitlines() if line.startswith("package:")]
    snapshots = {}
    def snapshot(label):
        snapshots[label] = {key: text("shell", "dumpsys", service) for key, service in
                            [("thermal", "thermalservice"), ("battery", "battery")]}
        snapshots[label]["memory"] = text("shell", "dumpsys", "meminfo", PACKAGE)
        snapshots[label]["system_memory"] = text("shell", "cat", "/proc/meminfo")
    snapshot("before")
    command = ["shell", "am", "instrument", "-w", "-r"]
    for key in ["model", "tag", "backend", "steps", "threads", "size", "runs", "flash", "mmap", "conv",
                "tiling", "offload", "wtype", "sampler", "cfg", "timeout", "prediction", "scheduler", "vae_cpu", "quantized", "preview",
                "max_vram", "segmented", "prefetch"]:
        value = getattr(args, key)
        if value is not None:
            command += ["-e", key, str(value)]
    command.append(INSTRUMENTATION)
    started = time.monotonic()
    started_utc = datetime.now(timezone.utc).isoformat()
    host_timeout = False
    with (destination / "instrumentation.txt").open("w") as log:
        process = subprocess.Popen(["adb", *command], stdout=log, stderr=subprocess.STDOUT)
        telemetry = []
        app_pid = None
        while process.poll() is None:
            if app_pid is None:
                found = subprocess.run(["adb", "shell", "pidof", PACKAGE], capture_output=True, text=True)
                candidate = found.stdout.strip()
                if candidate.isdecimal():
                    app_pid = candidate
            # Clock telemetry is optional: other phones expose different sysfs paths.
            frequency = subprocess.run(["adb", "shell", "cat", "/sys/devices/platform/1f000000.mali/cur_freq"],
                                       capture_output=True, text=True)
            telemetry.append({"elapsed_s": time.monotonic() - started,
                              "gpu_khz": frequency.stdout.strip() if frequency.returncode == 0 else None})
            if time.monotonic() - started > args.timeout * args.runs + 180:
                host_timeout = True
                adb("shell", "am", "force-stop", PACKAGE)
                process.terminate()
                process.wait(timeout=15)
                break
            time.sleep(2)
    snapshot("after")
    log = (destination / "instrumentation.txt").read_text()
    events = [json.loads(line.split("CARAML_DIFFUSION ", 1)[1]) for line in log.splitlines() if "CARAML_DIFFUSION " in line]
    # Isolate this process so old generation counts cannot be attributed to a new trial.
    native_log = text("logcat", "-d", "--pid=" + app_pid, "-s", "DiffusionRunner") if app_pid else ""
    (destination / "logcat.txt").write_text(native_log)
    completed = [e for e in events if e.get("event") == "generation"]
    for event in completed:
        with (destination / event["output"]).open("wb") as stream:
            adb("exec-out", "run-as", PACKAGE, "cat", "files/diffusion-benchmark/" + event["output"], stdout=stream)
    record = {"events": events, "conditions": snapshots, "gpu_frequency_samples": telemetry,
              "files": entries, "command": command, "exit_code": process.returncode,
              "apk_sha256": apk_hashes,
              "started_utc": started_utc,
              "device": {key: text("shell", "getprop", key).strip() for key in
                         ["ro.product.model", "ro.soc.model", "ro.build.fingerprint"]},
              "success": len(completed) == args.runs and "INSTRUMENTATION_CODE: -1" in log,
              "host_timeout": host_timeout,
              "git_commit": subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()}
    if not record["success"]:
        exit_info = text("shell", "dumpsys", "activity", "exit-info", PACKAGE)
        (destination / "exit-info.txt").write_text(exit_info)
        record["process_exit"] = process_exit_reason(exit_info, app_pid) if app_pid else None
    (destination / "result.json").write_text(json.dumps(record, indent=2) + "\n")
    print(json.dumps({"path": str(destination), "success": record["success"], "events": events}), flush=True)
    if not record["success"]:
        raise SystemExit(1)


if __name__ == "__main__":
    main()
