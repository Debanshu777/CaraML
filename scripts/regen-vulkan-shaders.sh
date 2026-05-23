#!/usr/bin/env bash
# Recompiles all ggml-vulkan GLSL shaders to SPIR-V.
# Requires: glslc (Vulkan SDK >= 1.3), run from repo root.
# Re-run when libraries/llama.cpp submodule is updated.
set -euo pipefail

SHADER_SRC="libraries/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders"
OUT_DIR="nativeEngine/src/androidMain/cpp/precompiled-spirv"
mkdir -p "$OUT_DIR"
MANIFEST="$OUT_DIR/MANIFEST.sha256"
echo "llama.cpp submodule: $(git -C libraries/llama.cpp rev-parse HEAD)" > "$MANIFEST"
echo "Generated: $(date -u +%Y-%m-%dT%H:%M:%SZ)" >> "$MANIFEST"
find "$SHADER_SRC" -name "*.comp" | sort | while read -r shader; do
    name=$(basename "$shader" .comp)
    out="$OUT_DIR/$name.spv"
    glslc --target-env=vulkan1.2 -O -fshader-stage=compute "$shader" -o "$out"
    echo "$(sha256sum "$out" | cut -d' ' -f1)  $name.spv" >> "$MANIFEST"
done
echo "Done. Regenerated $(find "$OUT_DIR" -name '*.spv' | wc -l) shaders."
