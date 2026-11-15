# diffusion native bridge

Kotlin Multiplatform wrapper for **stable-diffusion.cpp** image and video generation. Provides a common `DiffusionRunner` interface backed by platform-specific JNI (Android/JVM) and cinterop (iOS) bindings.

Mirrors the `:plugin-llama` module structure. Native library is built and linked by `:nativeEngine` using the shared GGML graph (see `GgmlUnified.cmake`).

---

## Platforms

| Platform | Binding | Native Library |
|----------|---------|---------------|
| Android | JNI via `System.loadLibrary("diffusion_runner")` | `libdiffusion_runner.so` |
| JVM Desktop | JNI via shared `DesktopNativeLibraries` resolver | `libdiffusion_runner.dylib/.so` or `diffusion_runner.dll` |
| iOS arm64 | Kotlin/Native cinterop | `libllama_runner_merged.a` (includes diffusion) |
| iOS Simulator | Kotlin/Native cinterop | `libllama_runner_merged.a` (sim build) |

---

JVM loading resolves `caraml.native.lib.dir` first, then `compose.application.resources.dir`. A configured directory is authoritative; a missing library fails instead of falling back to a build-tree library. With neither directory configured, the resolver uses `System.loadLibrary`. Package the JNI runtime and its shared backends together; the CaraML desktop host uses `:nativeEngine:stageDesktopRuntime`.

## Package Structure

```
com.debanshu777.caraml.sdk.plugin.diffusion.bridge/
├── DiffusionRunner.kt               # expect class — core interface
├── DiffusionRunner.java.kt          # shared Android/JVM actual in src/javaMain
├── DiffusionNativeRuntime.android.kt # Android library loader
├── DiffusionNativeRuntime.jvm.kt    # Desktop library loader and diagnostics
├── DiffusionRunner.ios.kt           # actual: cinterop wrapper
├── DiffusionModelConfig.kt          # Model paths + device config
├── ImageGenParams.kt                # txt2img parameters
├── VideoGenParams.kt                # txt2vid parameters
├── DiffusionState.kt                # Optional host lifecycle/result state types
├── SampleMethod.kt                  # Sampling algorithm enum
├── DiffusionRunnerExt.kt            # Extension functions
└── DiffusionRunnerValidation.kt     # Config validation

src/commonCpp/
├── diffusion_runner_core.h          # C++ class header
├── diffusion_runner_core.cpp        # Core stable-diffusion.cpp wrapper
├── diffusion_runner_jni.cpp         # JNI bridge
└── DiffusionRunnerCommon.cmake      # CMake source list

src/iosMain/cpp/
├── diffusion_runner.h               # C function declarations
├── diffusion_runner.cpp             # C wrapper
└── diffusion_runner.def             # Kotlin cinterop definition
```

Both `androidMain` and `jvmMain` explicitly include `src/javaMain/kotlin`.
Source sharing preserves the runner class package, JNI method signatures and
consumer keep rules; only native-library loading remains target-specific.

---

## Usage

Prefer the owned `DiffusionEngineProvider` session API described in the
[module README](../README.md). Direct bridge callers must serialize native
ownership and run blocking calls off the UI thread:

```kotlin
withContext(Dispatchers.Default) {
    val runner = DiffusionRunner()
    try {
        runner.initialize(nativeLibraryDirectory)
        check(runner.loadModel(DiffusionModelConfig(
            modelPath = "/path/to/model.safetensors",
            vaePath = "/path/to/vae.safetensors",      // optional companion
            clipLPath = "/path/to/clip_l.safetensors", // optional companion
            nThreads = 4,
            runtimeBackend = DiffusionRuntimeBackend.CPU,
        )))
        val imageBytes = checkNotNull(runner.txt2Img(ImageGenParams(
            prompt = "a cat sitting on a mountain at sunset",
            width = 512, height = 512, steps = 20,
            sampleMethod = SampleMethod.EULER_A,
        )))
        println(imageBytes.size)
    } finally {
        runner.release()
    }
}
```

`cancelGeneration()` signals cancellation. Await the active operation before
releasing its native handle. `generateImage()` and `generateVideo()` provide
suspending wrappers; check `supportsVideoGeneration()` before using video.

`DiffusionState` defines optional host state types: `Idle`, `LoadingModel`,
`ModelReady`, `Generating`, `Complete(imageData)`, and `Error(message)`. The runner
does not emit these states automatically.

---

## DiffusionModelConfig

| Parameter | Type | Description |
|-----------|------|-------------|
| `modelPath` | String | Path to main model file (safetensors/gguf) |
| `vaePath` | String | VAE file path; empty when unused |
| `clipLPath`, `clipGPath` | String | Optional CLIP encoder paths |
| `t5xxlPath`, `llmPath` | String | Optional text encoder paths |
| `nThreads` | Int | CPU thread count; `-1` selects the native default |
| `runtimeBackend` | DiffusionRuntimeBackend | Explicit CPU, Metal, Vulkan or CUDA placement |
| `offloadToCpu`, `keepClipOnCpu`, `keepVaeOnCpu` | Boolean | Parameter residency and component placement controls |

---

## ImageGenParams

| Parameter | Type | Description |
|-----------|------|-------------|
| `prompt` | String | Positive prompt |
| `negativePrompt` | String | Negative prompt |
| `width` | Int | Output width within validated dimension/pixel limits; multiple of 8 |
| `height` | Int | Output height within validated dimension/pixel limits; multiple of 8 |
| `steps` | Int | Denoising steps within the runner's validated limit |
| `cfgScale` | Float | Classifier-free guidance scale |
| `seed` | Long | RNG seed (-1 = random) |
| `sampleMethod` | SampleMethod | Euler, Euler-A, DPM++2M, LCM, etc. |

---

## SampleMethod

```kotlin
val sampler = SampleMethod.fromName("euler_a")
val nativeValue = sampler.value
```

Sampler IDs are explicit native ABI values, not ordinal positions. The current
choices are defined by `SampleMethod.kt`; unknown names fall back to `EULER_A`.

---

## GGML Sharing with llama.cpp

Both runners use the pinned GGML source from `libraries/llama.cpp`.
`GgmlUnified.cmake` configures that source first and lets stable-diffusion.cpp
reuse its targets instead of configuring a second GGML tree. Static/shared
linkage and runtime backend libraries depend on the selected platform profile.

---

## Building Native Library

```bash
# Desktop
./gradlew :nativeEngine:compileLlamaRunnerDesktop

# iOS arm64
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64
```

The merged iOS `.a` includes both `llama_runner` and `diffusion_runner` objects.

---

## Recent Changes

- Android/JVM now share `DiffusionRunner.java.kt` and use small platform library loaders. JNI signatures and handle ownership are unchanged; usage and configuration examples reflect the current bridge API.
- Bridge implementation moved from `diffusionRunner` into `plugin-diffusion`; platform package names and native symbols remain compatible. Consumer rules preserve JNI descriptor classes.


- JNI now converts Kotlin UTF-16 to standard UTF-8 and native metadata to UTF-16 without modified-UTF-8 APIs; Android consumer rules preserve JNI-reflected config/metadata names and the wrapper floor matches the native engine at API 28.

<!-- Updated at end of each Claude Code session -->

- Preflight exception paths return fresh bounded results without assigning a large aggregate, avoiding the GCC 13 `gimple_add_tmp_var` compiler crash seen on Ubuntu CI; segmented-compute verification uses a host-neutral wildcard max-VRAM budget
- The runner now targets stable-diffusion.cpp `c92d73c` against llama.cpp's single shared GGML, with segmented execution and prefetch represented and validated independently
- JNI and iOS now expose bounded engine/model identity and effective video FPS; native load applies the exact admitted placement and max-VRAM plan with upstream auto-fit disabled
- Opt-in native parity verifies bounded corrupt safetensors rejection and repeat-cleanup via `CARAML_NATIVE_PARITY=true ./gradlew :plugin-diffusion:jvmTest`; successful diffusion load remains a physical-device gate because no small redistribution-safe fixture is pinned
- `DiffusionModelConfig` now carries explicit CPU, Metal, Vulkan, or CUDA runtime placement independently of parameter residency; JNI and iOS use stable explicit integer values rather than enum ordinals
- Preflight initializes and reports only devices selected by the resolved runtime/parameter plan; a failing unused registry device no longer invalidates a valid plan
- Added fixed-layout Android/JVM/iOS preflight, backend-registry and feature-probe APIs; model load and preflight share the pinned engine's bounded max-VRAM/auto-fit resolution
- Preflight now reports lossless configured source role/ordinal evidence separately from bundle-internal tensor subdivisions, emits external TAESD as its own source with VAE placement, preserves pinned max-VRAM and streaming semantics, and identifies backend devices canonically across platform ABIs
- Native contexts now use reference-counted handles, per-handle operation locking, asynchronous cancellation, and scoped image/PNG cleanup across JNI and iOS FFI
- Video output is capped by combined frame-pixels; iOS reports video unsupported until a native video ABI replaces the removed repeated-still emulation
- Added bounded validation for prompts, dimensions/pixel area, steps, CFG, video frames, and LoRA inputs before native allocation
- JNI model configuration now owns string storage per invocation; iOS string pointers remain inside their allocation scope; JNI/iOS C boundaries return controlled failures for native exceptions
- Missing native libraries degrade to a recoverable load error, cancellation is rethrown, and sensitive prompts/model paths are no longer included in app-owned diagnostics
- iOS framework/cinterop tasks now depend explicitly on the matching native static-library merge task
- Vulkan runtime plans keep CLIP and VAE on CPU for unsupported mobile F16 pipelines; auto-fit applies that safety only to components actually resolved to Vulkan, never ambient devices
- DiffusionModelConfig extended: `taesdPath`, `vaeTiling`, `freeParamsImmediately` fields; `flowShift` now actually plumbed into `sd_sample_params_t.flow_shift` via `SdHandle` cache (per-gen overrides default)
- SampleMethod gained `fromName(String)` resolver so registry string samplers ("euler", "euler_a", "lcm", etc.) map to the right enum
- JNI + iOS FFI (`.def` / `.h` / `.cpp`) updated in lockstep with new model-config fields
- Initial implementation of diffusion runner (llama/diffusion GGML sharing)
- DiffusionInferenceRepository wired to chat UI (feature/diffusion-chat-window)
- Added `DiffusionModelMetadata` data class (architecture string, dominantQuantType, estimatedRamBytes)
- `DiffusionRunner` expect class gained `getDiffusionModelMetadata(modelPath)` — Android/JVM actuals use JNI `nativeGetDiffusionModelMetadata`, iOS actual uses cinterop `diffusion_runner_ios_get_metadata` with `CValue<DiffusionMetadataResultFFI>.useContents {}`
- C++ layer: `diffusion_runner_core_get_metadata()` uses stable-diffusion.cpp `ModelLoader` to read GGUF headers without loading weights; JNI wrapper constructs `DiffusionModelMetadata` via `NewObject`; iOS FFI wrapper exposes `DiffusionMetadataResultFFI` struct via `.def`
- Smart install system tracks component files (VAE, CLIP, T5) via `ComponentRepository`
- `VideoGenParams` added for future txt2vid support
