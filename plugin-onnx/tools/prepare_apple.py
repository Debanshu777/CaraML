#!/usr/bin/env python3
"""Fetch the pinned official Apple runtime and merge the small CaraML C bridge."""
import argparse
import hashlib
from pathlib import Path
import shutil
import subprocess
import zipfile

VERSION = "1.30.0"
SHA256 = "e6f1670c14406fd9f082bb400ab197a9b0a9646058ca6366e440642e2b54a2ea"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--target", choices=["iosArm64", "iosSimulatorArm64"], required=True)
    args = parser.parse_args()
    module = Path(__file__).resolve().parents[1]
    root = module.parent / "build/onnx-apple-runtime"
    root.mkdir(parents=True, exist_ok=True)
    archive = root / f"onnxruntime-ios-{VERSION}.zip"
    if not archive.exists():
        temporary = archive.with_suffix(".partial")
        subprocess.run(["curl", "--fail", "--location", "--retry", "2",
                        f"https://download.onnxruntime.ai/pod-archive-onnxruntime-c-{VERSION}.zip", "-o", str(temporary)], check=True)
        assert hashlib.sha256(temporary.read_bytes()).hexdigest() == SHA256, "Apple runtime hash mismatch"
        temporary.replace(archive)
    assert hashlib.sha256(archive.read_bytes()).hexdigest() == SHA256, "Apple runtime hash mismatch"
    unpacked = root / "unpacked"
    if not (unpacked / ".verified").exists():
        with zipfile.ZipFile(archive) as source:
            source.extractall(unpacked)
        (unpacked / ".verified").write_text(SHA256)
    simulator = args.target == "iosSimulatorArm64"
    sdk = "iphonesimulator" if simulator else "iphoneos"
    slice_name = "ios-arm64_x86_64-simulator" if simulator else "ios-arm64"
    target = "arm64-apple-ios17.2" + ("-simulator" if simulator else "")
    sdk_path = subprocess.check_output(["xcrun", "--sdk", sdk, "--show-sdk-path"], text=True).strip()
    output = module / "build/apple" / args.target
    output.mkdir(parents=True, exist_ok=True)
    subprocess.run(["xcrun", "--sdk", sdk, "clang++", "-std=c++17", "-O2", "-fvisibility=hidden",
                    "-target", target, "-isysroot", sdk_path, "-I" + str(unpacked / "Headers"),
                    "-c", str(module / "src/iosMain/cpp/onnx_bridge.cpp"), "-o", str(output / "bridge.o")], check=True)
    binary = unpacked / "onnxruntime.xcframework" / slice_name / "onnxruntime.framework/onnxruntime"
    runtime = output / "runtime.a"
    archs = subprocess.check_output(["xcrun", "lipo", "-archs", str(binary)], text=True).split()
    if len(archs) > 1:
        subprocess.run(["xcrun", "lipo", str(binary), "-thin", "arm64", "-output", str(runtime)], check=True)
    else:
        shutil.copyfile(binary, runtime)
    subprocess.run(["xcrun", "libtool", "-static", "-o", str(output / "libcaraml_onnx_apple.a"),
                    str(output / "bridge.o"), str(runtime)], check=True)


if __name__ == "__main__":
    main()
