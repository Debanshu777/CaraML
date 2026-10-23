#!/usr/bin/env python3
"""Exercise discovery, verified downloads, admission and chat in the installed debug app.

Uses public fixtures and preserves app data and existing transfer pause/cancel intent.
This is a functional check, not a timing benchmark.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--adb", default=os.path.expanduser("~/Library/Android/sdk/platform-tools/adb"))
    parser.add_argument("--output", type=Path, default=Path("build/benchmark-results/app-validation"))
    parser.add_argument("--model", choices=["smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b", "llama32-1b"])
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    failed = []
    models = [args.model] if args.model else ["smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b", "llama32-1b"]
    for model in models:
        print("Starting production-path validation:", model, flush=True)
        command = [args.adb, "-s", args.serial, "shell", "am", "instrument", "-w", "-e", "case", model,
                   "com.debanshu777.caraml/com.debanshu777.caraml.benchmark.OnnxBundleValidation"]
        with (args.output / (model + ".log")).open("w") as stream:
            try:
                result = subprocess.run(command, stdout=stream, stderr=subprocess.STDOUT, timeout=1250)
                process_ok = result.returncode == 0
            except subprocess.TimeoutExpired:
                stream.write("\nHARNESS_TIMEOUT\n")
                process_ok = False
                subprocess.run([args.adb, "-s", args.serial, "shell", "am", "force-stop", "com.debanshu777.caraml"], check=True)
        output = (args.output / (model + ".log")).read_text()
        rows = [json.loads(line.split("CARAML_ONNX_VALIDATION ", 1)[1])
                for line in output.splitlines() if "CARAML_ONNX_VALIDATION " in line]
        success = (process_ok and "INSTRUMENTATION_CODE: -1" in output
                   and any(row["stage"] == "complete" for row in rows)
                   and not any(row["stage"] == "error" for row in rows))
        print(model, "PASS" if success else "FAILED", flush=True)
        if not success:
            failed.append(model)
            print(output[-2400:], flush=True)
    if failed:
        raise SystemExit("Failed models: " + ", ".join(failed))


if __name__ == "__main__":
    main()
