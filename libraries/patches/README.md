# Native library patches

Local fixes applied at build time to CaraML's build-owned llama.cpp source copy.
Patches stay in the main repo so the pinned upstream submodule remains immutable.

## Layout

The active patch directory mirrors the top-level llama.cpp submodule:

```
libraries/
  patches/
    llama.cpp/                                          → libraries/llama.cpp
      0001-metal-pin-shading-language-version.patch
    stable-diffusion.cpp/                               → historical nested-GGML references
```

CaraML compiles stable-diffusion.cpp with `SD_USE_SYSTEM_GGML=ON`, so its nested GGML patch files are retained only as historical references and are not part of the active build graph.

## How patches apply

The `:nativeEngine:preparePatchedLlamaSource` Gradle task runs automatically before native compilation. It recreates a Gradle-owned source tree, checks each numbered patch, and applies it there. The pinned submodule is never modified.

## Bumping a submodule

```sh
git submodule update --remote libraries/llama.cpp
./gradlew :nativeEngine:preparePatchedLlamaSource
```

If `preparePatchedLlamaSource` fails after a bump, the upstream change touched the patched region. Rebase the numbered patch against the new source and rerun:

```sh
./gradlew :nativeEngine:preparePatchedLlamaSource
```

## Current patches

### `0001-metal-pin-shading-language-version.patch` (active llama.cpp patch)

**What:** Pins `MTLCompileOptions.languageVersion` to Metal 3.1 / 3.2 in `ggml-metal-device.m`.

**Why:** The bf16 kernels in `ggml-metal.metal` are gated on `__METAL_VERSION__ >= 310`. Some hosts (notably JetBrains Runtime) default `MTLCompileOptions` to a lower language version, so the kernels are stripped from the compiled library — but ggml's C-side `has_bfloat` flag remains true based on the GPU family check. Bf16 ops then dispatch to a non-existent Metal pipeline and segfault inside `ggml_metal_encoder_set_pipeline`.

**Affects:** macOS desktop (Metal), iOS (Metal). Android (Vulkan / CPU) is unaffected.

### `0002-vulkan-norm-require-f32.patch`

**What:** Requires contiguous F32 inputs and F32 outputs for Vulkan norm and timestep-embedding operations.

**Why:** Prevents unsupported tensor layouts or types from being admitted to pipelines that only implement F32, allowing a safe CPU fallback instead of a driver failure.

### `0003-fit-memory-probe-raii.patch`

**What:** Makes llama.cpp memory-fit probes restore the global logger and release transient model/context handles through RAII on every return and exception path.

**Why:** CaraML invokes fit probing as part of bounded preflight, so probe failure must not leak native state or leave process-global logging redirected.

### `0004-vulkan-gate-xe-flash-attention-shaders.patch`

**What:** Builds and references the Intel Xe cooperative-matrix flash-attention decode shaders only when the selected `glslc` supports `GL_KHR_cooperative_matrix`.

**Why:** Android NDK shader toolchains can legitimately omit that extension. The pinned upstream generator detected the missing capability but still compiled the two extension-only shaders, which prevented Vulkan Android packaging instead of retaining the scalar flash-attention path.
