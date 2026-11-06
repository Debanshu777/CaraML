#!/usr/bin/env python3
"""Compare the patched CPU repacker byte-for-byte with the pinned implementation.

Applies only patch 0007 to a temporary source copy, extracts the actual four
functions, and compiles both versions against upstream layout declarations. No
model, native engine build, Gradle invocation or submodule mutation is needed.
"""
import argparse
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[4]


def extract(source, name):
    pattern = r"(?:template <[^\n]+>\n)?static [^\n]+\b" + re.escape(name) + r"\([^\n]+\) \{"
    matches = list(re.finditer(pattern, source))
    if len(matches) != 1:
        raise RuntimeError(f"Expected one function {name}, found {len(matches)}")
    start = matches[0].start()
    cursor = matches[0].end()
    depth = 1
    while depth:
        if cursor >= len(source):
            raise RuntimeError(f"Unterminated function {name}")
        depth += (source[cursor] == "{") - (source[cursor] == "}")
        cursor += 1
    return source[start:cursor]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--compiler", default="c++")
    parser.add_argument("--git", default="git")
    parser.add_argument("--sanitize", action="store_true")
    parser.add_argument("--keep-generated", type=Path)
    args = parser.parse_args()
    pinned = ROOT / "libraries/llama.cpp"
    relative = Path("ggml/src/ggml-cpu/repack.cpp")
    patch = ROOT / "libraries/patches/llama.cpp/0007-cpu-repack-constant-interleave.patch"
    harness = ROOT / "nativeEngine/src/nativeTest/cpp/cpu_repack_equivalence_test.cpp"
    original = (pinned / relative).read_text()
    with tempfile.TemporaryDirectory(prefix="caraml-repack-") as temp:
        temp = Path(temp)
        target = temp / relative
        target.parent.mkdir(parents=True)
        target.write_text(original)
        subprocess.run([args.git, "apply", "--check", str(patch)], cwd=temp, check=True)
        subprocess.run([args.git, "apply", str(patch)], cwd=temp, check=True)
        candidate = target.read_text()
        names = ["make_block_q4_Kx8", "make_block_q6_Kx8", "repack_q4_K_to_q4_K_8_bl", "repack_q6_K_to_q6_K_8_bl"]
        translation = '''#include "repack.h"
#include <cassert>
#include <cstring>
#undef GGML_ASSERT
#define GGML_ASSERT(expression) assert(expression)
extern "C" int64_t ggml_nrows(const ggml_tensor * tensor) {
    return tensor->ne[1] * tensor->ne[2] * tensor->ne[3];
}
'''
        for namespace, source in (("reference", original), ("candidate", candidate)):
            translation += f"namespace {namespace} {{\n" + "\n".join(extract(source, name) for name in names) + "\n}\n"
        translation += harness.read_text()
        generated = temp / "equivalence.cpp"
        generated.write_text(translation)
        if args.keep_generated:
            args.keep_generated.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(generated, args.keep_generated)
        binary = temp / ("equivalence.exe" if os.name == "nt" else "equivalence")
        command = [args.compiler, "-std=c++17", "-O2", "-Wall", "-Wextra", "-Werror",
                   "-isystem", str(pinned / "ggml/src/ggml-cpu"), "-isystem", str(pinned / "ggml/src"),
                   "-isystem", str(pinned / "ggml/include"), str(generated), "-o", str(binary)]
        if args.sanitize:
            command += ["-fsanitize=address,undefined", "-fno-omit-frame-pointer"]
        subprocess.run(command, check=True)
        subprocess.run([str(binary)], check=True)


if __name__ == "__main__":
    main()
