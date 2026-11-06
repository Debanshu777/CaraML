#!/usr/bin/env python3
"""Fetch only the pinned benchmark files; resume partial downloads and verify SHA-256."""
import argparse
import concurrent.futures
import hashlib
import json
from pathlib import Path
import subprocess
import urllib.parse


def download(entry, root):
    path = root / entry["local"]
    partial = path.with_suffix(path.suffix + ".partial")
    if not path.exists():
        url = "https://huggingface.co/{}/resolve/{}/{}".format(
            entry["repo"], entry["revision"], urllib.parse.quote(entry["file"], safe="/")
        )
        subprocess.run(["curl", "--fail", "--location", "--retry", "5", "--retry-all-errors",
                        "--continue-at", "-", "--silent", "--show-error", "--output", str(partial), url], check=True)
        candidate = partial
    else:
        candidate = path
    if candidate.stat().st_size != entry["size"]:
        raise ValueError(f"Wrong byte count: {candidate.name}")
    with candidate.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != entry["sha256"]:
        raise ValueError(f"Checksum mismatch: {candidate.name}; remove it before retrying")
    if candidate == partial:
        partial.replace(path)
    print(f"Verified {path.name}: {entry['size']} bytes", flush=True)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/diffusion-benchmark/models"))
    parser.add_argument("--model", help="Download just one model from the manifest")
    parser.add_argument("--workers", type=int, choices=range(1, 5), default=2)
    args = parser.parse_args(argv)
    manifest = json.loads(Path(__file__).with_name("models.json").read_text())
    entries = [e for e in manifest["files"] if args.model is None or e["model"] == args.model]
    if args.model in manifest["optional_files"]:
        entries = [manifest["optional_files"][args.model]]
    if not entries:
        parser.error("Unknown model")
    args.output.mkdir(parents=True, exist_ok=True)
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.workers) as pool:
        list(pool.map(lambda e: download(e, args.output), entries))


if __name__ == "__main__":
    main()
