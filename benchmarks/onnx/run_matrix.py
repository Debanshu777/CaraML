#!/usr/bin/env python3
"""Run paired, temperature-gated ONNX and GGUF measurements on one selected device."""
import argparse
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--max-temperature", type=float, default=35.0)
    args = parser.parse_args()
    for model in ["smollm2-135m", "smollm2-360m", "qwen25-05b", "qwen3-06b", "llama32-1b"]:
        for mode, threads, gpu in [("onnx", 1, 0), ("onnx", 2, 0), ("gguf", 4, 0), ("gguf", 4, 99)]:
            subprocess.run([sys.executable, str(Path(__file__).with_name("run_device.py")),
                            "--serial", args.serial, "--output", str(args.output), "--model", model,
                            "--mode", mode, "--threads", str(threads), "--gpu", str(gpu),
                            "--exclude", "llama32-1b-onnx", "--exclude", "smollm2-135m-q4-onnx",
                            "--max-temperature", str(args.max_temperature)], check=True)


if __name__ == "__main__":
    main()
