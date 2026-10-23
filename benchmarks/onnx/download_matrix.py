#!/usr/bin/env python3
"""Restore the exact benchmark models from the retained revision manifests."""
import argparse
import json
from pathlib import Path
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/benchmarks"))
    parser.add_argument("--only", action="append", help="Optional exact manifest name, without .json")
    args = parser.parse_args()
    directory = Path(__file__).resolve().parent
    paths = sorted((directory / "results/manifests").glob("*.json"))
    if args.only:
        missing = set(args.only) - {p.stem for p in paths}
        if missing:
            parser.error("Unknown manifests: " + ", ".join(sorted(missing)))
        paths = [p for p in paths if p.stem in args.only]
    for path in paths:
        manifest = json.loads(path.read_text())
        subprocess.run([sys.executable, str(directory / "download_model.py"), manifest["repository"],
                        manifest["graph"], str(args.output / path.stem), "--revision", manifest["revision"]], check=True)


if __name__ == "__main__":
    main()
