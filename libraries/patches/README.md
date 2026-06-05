# Native library patches

Local fixes applied at build time to the vendored submodules under `libraries/`.
Patches stay in the main repo so the submodules themselves remain pinned cleanly to upstream commits — `git submodule status` should always be clean.

## Layout

Each subdirectory mirrors the path of the git working tree it applies to, relative to `libraries/`:

```
libraries/
  patches/
    llama.cpp/                                          → libraries/llama.cpp
      0001-metal-pin-shading-language-version.patch
    stable-diffusion.cpp/
      ggml/                                             → libraries/stable-diffusion.cpp/ggml
        0001-metal-pin-shading-language-version.patch
```

`stable-diffusion.cpp` vendors `ggml` as a nested submodule, so its patches live under `libraries/patches/stable-diffusion.cpp/ggml/`.

## How patches apply

The `applyNativePatches` Gradle task runs automatically before any native compile (Desktop, iOS, Android). It is idempotent — running it twice in a row is a no-op the second time. Implementation: for each `.patch` it checks `git apply --check -R` first; if that succeeds the patch is already in place and is skipped.

## Bumping a submodule

```sh
./gradlew revertNativePatches                  # roll back patches first
git submodule update --remote libraries/llama.cpp
# build / test
./gradlew applyNativePatches                   # if still applies cleanly: done
```

If `applyNativePatches` fails after a bump, the upstream change touched the patched region:

```sh
# Apply by hand, fix conflicts, then regenerate the patch
git -C libraries/llama.cpp apply --3way libraries/patches/llama.cpp/0001-...patch
# (resolve conflicts, edit the file)
git -C libraries/llama.cpp diff -- ggml/src/ggml-metal/ggml-metal-device.m \
    > libraries/patches/llama.cpp/0001-metal-pin-shading-language-version.patch
git -C libraries/llama.cpp checkout -- ggml/src/ggml-metal/ggml-metal-device.m
./gradlew applyNativePatches                   # confirm the new patch applies cleanly
```

## Current patches

### `0001-metal-pin-shading-language-version.patch` (both submodules)

**What:** Pins `MTLCompileOptions.languageVersion` to Metal 3.1 / 3.2 in `ggml-metal-device.m`.

**Why:** The bf16 kernels in `ggml-metal.metal` are gated on `__METAL_VERSION__ >= 310`. Some hosts (notably JetBrains Runtime) default `MTLCompileOptions` to a lower language version, so the kernels are stripped from the compiled library — but ggml's C-side `has_bfloat` flag remains true based on the GPU family check. Bf16 ops then dispatch to a non-existent Metal pipeline and segfault inside `ggml_metal_encoder_set_pipeline`.

**Affects:** macOS desktop (Metal), iOS (Metal). Android (Vulkan / CPU) is unaffected.
