#!/usr/bin/env python3
"""One command for CaraML benchmark setup, measurements, diagnostics and reports.

Run from the repository root. Use --help at any level; it never accesses a device.
"""
import argparse
import os
from pathlib import Path
import runpy
import subprocess
import sys

ROOT = Path(__file__).resolve().parent

# Imports are lazy: downloading or viewing help does not require ORT, tokenizers,
# Android tools or Apple's tools. Each leaf keeps its workload's existing flags.
COMMANDS = {
    "diffusion": {
        "download": ("diffusion/download_models.py", "main", "Fetch five pinned image-model bundles"),
        "android": ("diffusion/run_android.py", "main", "Foreground image generation through production JNI"),
        "matrix": ("diffusion/run_matrix.py", "main", "Serial image CPU/Vulkan comparisons with thermal records"),
        "summary": ("diffusion/summary.py", "main", "Export compact image-generation results"),
    },
    "android": {
        "ui": ("android/ui.py", "main", "Startup, scrolling and Baseline Profile benchmarks"),
        "load": ("android/model_load.py", "main", "Fresh-process GGUF engine loads"),
        "journey": ("android/journey_matrix.py", "main", "Matched CPU/Vulkan installed-model journeys"),
        "perfetto": ("android/profiling.py", "perfetto_main", "Capture or validate a native Perfetto diagnostic"),
        "simpleperf": ("android/profiling.py", "simpleperf_main", "Capture or summarize native sampled stacks"),
        "analyze": ("android/analysis.py", "traces_main", "Validate and summarize archived UI traces"),
        "vulkan": ("android/analysis.py", "vulkan_main", "Summarize Vulkan operation timing logs"),
    },
    "onnx": {
        "performance": ("onnx/performance_pass.py", "main", "Installed ONNX loading, reuse and generation"),
        "engine": ("onnx/device.py", "engine_main", "Android ONNX/GGUF provider and thread measurements"),
        "matrix": ("onnx/device.py", "matrix_main", "Paired Android ONNX/GGUF configurations"),
        "validate": ("onnx/device.py", "validate_main", "Functional app download/load/chat checks"),
        "iphone": ("onnx/run_iphone.py", "main", "Physical iPhone engine measurements"),
        "summary": {
            "android": ("onnx/summary.py", "android_main", "Android results with PSS memory"),
            "iphone": ("onnx/summary.py", "iphone_main", "iPhone results with RSS memory"),
        },
        "report": ("onnx/analysis/comparison.py", "main", "Regenerate the measured ONNX comparison"),
    },
    "models": {
        "download": ("onnx/models.py", "download_main", "Download and verify one pinned model bundle"),
        "restore": ("onnx/models.py", "restore_main", "Restore selected bundles from saved manifests"),
        "references": ("onnx/models.py", "references_main", "Generate independent tokenizer/ORT references"),
    },
    "test": (None, None, "Run all offline Python benchmark tests"),
}


def run_tests(argv):
    parser = argparse.ArgumentParser(description="Run offline checks without a device or model downloads.")
    parser.add_argument("-v", "--verbose", action="store_true")
    args = parser.parse_args(argv)
    env = dict(os.environ, PYTHONDONTWRITEBYTECODE="1")
    for directory in (ROOT, ROOT / "android", ROOT / "onnx", ROOT / "onnx/analysis", ROOT / "diffusion"):
        command = [sys.executable, "-m", "unittest", "discover", "-s", str(directory), "-p", "test_*.py"]
        if args.verbose:
            command.append("-v")
        result = subprocess.run(command, cwd=ROOT.parent, env=env)
        if result.returncode:
            return result.returncode
    return 0


def main(argv=None):
    argv = list(sys.argv[1:] if argv is None else argv)
    routes = COMMANDS
    prog = "python3 benchmarks/run.py"
    while isinstance(routes, dict):
        descriptions = [f"  {name:12} {value[2] if isinstance(value, tuple) else 'Related commands; use ' + name + ' --help'}"
                        for name, value in routes.items()]
        parser = argparse.ArgumentParser(prog=prog, description=__doc__,
                                         epilog="\n".join(descriptions),
                                         formatter_class=argparse.RawDescriptionHelpFormatter)
        parser.add_argument("command", choices=routes)
        if not argv:
            parser.print_help()
            return 2
        # Parse only the command, forwarding every remaining argument verbatim.
        # In particular, the -- separating a profiler's host workload is retained.
        args = parser.parse_args(argv[:1])
        routes = routes[args.command]
        prog += " " + args.command
        argv = argv[1:]
    script, function, _ = routes
    old_argv, old_path = sys.argv, sys.path[:]
    try:
        sys.argv = [prog, *argv]
        if script is None:
            return run_tests(argv)
        path = ROOT / script
        sys.path.insert(0, str(path.parent))
        namespace = runpy.run_path(str(path))
        return namespace[function](argv)
    finally:
        sys.argv = old_argv
        sys.path[:] = old_path


if __name__ == "__main__":
    raise SystemExit(main())
