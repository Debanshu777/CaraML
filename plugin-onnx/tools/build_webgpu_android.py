#!/usr/bin/env python3
"""Build a pinned ORT WebGPU plugin for Android arm64; no vendor SDK is needed."""
import argparse
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tarfile
from prepare_android_gpu_discovery import prepare

REVISION = "402979ac1bef89dcd55add41eaa7277c407599eb"  # ONNX Runtime 1.31.0
SOURCE_SHA256 = "bb558325d1a91878eb808412dfc9d8f17ef480b839e5b3ad3c31371734aff6a9"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sdk", required=True, type=Path)
    parser.add_argument("--ndk", required=True, type=Path)
    parser.add_argument("--cmake", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    project = Path(__file__).resolve().parents[2]
    root = project / "build/onnx-webgpu"
    root.mkdir(parents=True, exist_ok=True)
    archive = root / "source.tar.gz"
    if not archive.exists():
        temporary = archive.with_suffix(".partial")
        subprocess.run(["curl", "--fail", "--location", "--retry", "2",
                        f"https://codeload.github.com/microsoft/onnxruntime/tar.gz/{REVISION}", "-o", str(temporary)], check=True)
        temporary.replace(archive)
    digest = hashlib.file_digest(archive.open("rb"), "sha256").hexdigest()
    assert digest == SOURCE_SHA256, "ORT source hash mismatch"
    source = root / f"onnxruntime-{REVISION}"
    if not source.exists():
        with tarfile.open(archive) as contents:
            contents.extractall(root, filter="data")
    prepare(source, archive)
    env = dict(os.environ)
    env["PATH"] = str(args.cmake.parent) + os.pathsep + env["PATH"]
    build = root / "android"
    subprocess.run([sys.executable, str(source / "tools/ci_build/build.py"), "--build_dir", str(build),
                    "--config", "Release", "--android", "--android_sdk_path", str(args.sdk),
                    "--android_ndk_path", str(args.ndk), "--android_abi", "arm64-v8a", "--android_api", "28",
                    "--cmake_generator", "Ninja", "--use_webgpu", "shared_lib", "--build_shared_lib",
                    "--skip_tests", "--skip_submodule_sync", "--compile_no_warning_as_error", "--update", "--cmake_path", str(args.cmake),
                    "--cmake_extra_defines", "onnxruntime_BUILD_UNIT_TESTS=OFF"], check=True, env=env)
    subprocess.run([str(args.cmake), "--build", str(build / "Release"), "--target", "onnxruntime_providers_webgpu",
                    "--parallel", "6"], check=True, env=env)
    binaries = list((build / "Release").glob("*onnxruntime_providers_webgpu.so"))
    assert len(binaries) == 1, "Missing WebGPU provider output"
    destination = args.output / "arm64-v8a"
    destination.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(binaries[0], destination / "libonnxruntime_providers_webgpu.so")


if __name__ == "__main__":
    main()
