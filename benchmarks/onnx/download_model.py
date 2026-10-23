#!/usr/bin/env python3
"""Download one public, revision-pinned HF graph and sidecars, checking remote hashes.

Usage: python3 benchmarks/onnx/download_model.py REPO GRAPH OUTPUT [--revision SHA]
Weights belong under build/ (ignored by Git), never in the source tree.
"""
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import urllib.parse
import urllib.request


def fetch_json(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def digest(path, lfs):
    value = hashlib.sha256() if lfs else hashlib.sha1()
    if not lfs:
        value.update(f"blob {path.stat().st_size}\0".encode())
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(4 * 1024 * 1024), b""):
            value.update(chunk)
    return value.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("repo")
    parser.add_argument("graph")
    parser.add_argument("output", type=Path)
    parser.add_argument("--revision")
    args = parser.parse_args()
    endpoint = "https://huggingface.co/api/models/" + args.repo
    if args.revision:
        endpoint += "/revision/" + args.revision
    detail = fetch_json(endpoint + "?blobs=true")
    revision = detail["sha"]
    assert len(revision) == 40 and all(c in "0123456789abcdef" for c in revision)
    sidecars = {"config.json", "tokenizer.json", "tokenizer_config.json", "generation_config.json", "special_tokens_map.json"}
    files = [file for file in detail["siblings"] if file["rfilename"] == args.graph or
             file["rfilename"].startswith(args.graph + "_data") or file["rfilename"] == args.graph + ".data" or file["rfilename"] in sidecars]
    names = {file["rfilename"] for file in files}
    assert args.graph in names
    if args.graph.endswith(".onnx"):
        assert {"config.json", "tokenizer.json"} <= names
    args.output.mkdir(parents=True, exist_ok=True)
    for file in files:
        name = file["rfilename"]
        relative = PurePosixPath(name)
        assert not relative.is_absolute() and ".." not in relative.parts
        path = args.output / name
        path.parent.mkdir(parents=True, exist_ok=True)
        lfs = file.get("lfs")
        expected = lfs["sha256"] if lfs else file["blobId"]
        if path.is_file() and path.stat().st_size == file["size"] and digest(path, lfs) == expected:
            print("Verified existing", name, flush=True)
            continue
        url = "https://huggingface.co/" + args.repo + "/resolve/" + revision + "/" + urllib.parse.quote(name, safe="/")
        temporary = path.with_name(path.name + ".partial")
        print("Downloading", args.repo, name, file["size"], flush=True)
        with urllib.request.urlopen(url, timeout=120) as response, temporary.open("wb") as target:
            for chunk in iter(lambda: response.read(4 * 1024 * 1024), b""):
                target.write(chunk)
        assert temporary.stat().st_size == file["size"], "Incomplete download"
        assert digest(temporary, lfs) == expected, "Hash mismatch"
        temporary.replace(path)
    manifest = {"repository": args.repo, "revision": revision, "graph": args.graph,
                "downloads_at_selection": detail.get("downloads"), "files": files}
    (args.output / "benchmark-manifest.json").write_text(json.dumps(manifest, indent=2) + "\n")
    print("Bundle verified", args.output, flush=True)


if __name__ == "__main__":
    main()
