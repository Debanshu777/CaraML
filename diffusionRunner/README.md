# diffusionRunner

Kotlin Multiplatform wrapper for **stable-diffusion.cpp** image and video generation. Provides a common `DiffusionRunner` interface backed by platform-specific JNI (Android/JVM) and cinterop (iOS) bindings.

Mirrors the `:runner` module structure. Native library is built and linked by `:nativeEngine` using the shared GGML graph (see `GgmlUnified.cmake`).

---

## Platforms

| Platform | Binding | Native Library |
|----------|---------|---------------|
| Android | JNI via `System.loadLibrary("diffusion_runner")` | `diffusion_runner.so` |
| JVM Desktop | JNI via `System.loadLibrary("diffusion_runner")` | `libdiffusion_runner.dylib/.so` |
| iOS arm64 | Kotlin/Native cinterop | `libllama_runner_merged.a` (includes diffusion) |
| iOS Simulator | Kotlin/Native cinterop | `libllama_runner_merged.a` (sim build) |

---

## Package Structure

```
com.debanshu777.diffusionrunner/
├── DiffusionRunner.kt               # expect class — core interface
├── DiffusionRunner.android.kt       # actual: JNI wrapper
├── DiffusionRunner.jvm.kt           # actual: JNI wrapper
├── DiffusionRunner.ios.kt           # actual: cinterop wrapper
├── DiffusionModelConfig.kt          # Model paths + device config
├── ImageGenParams.kt                # txt2img parameters
├── VideoGenParams.kt                # txt2vid parameters
├── DiffusionState.kt                # IDLE / LOADING / GENERATING / ERROR
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

---

## Usage

```kotlin
val config = DiffusionModelConfig(
    modelPath = "/path/to/model.safetensors",
    vaePath = "/path/to/vae.safetensors",       // optional
    clipPath = "/path/to/clip_l.safetensors",   // optional
    t5xxlPath = "/path/to/t5xxl.safetensors",   // optional
    threads = 4,
    gpuLayers = -1,   // -1 = all layers on GPU
)

val runner = DiffusionRunner()
runner.load(config)

// Text-to-image
val imageBytes: ByteArray = runner.txt2img(
    ImageGenParams(
        prompt = "a cat sitting on a mountain at sunset",
        negativePrompt = "blurry, low quality",
        width = 512,
        height = 512,
        steps = 20,
        cfgScale = 7.0f,
        seed = -1L,              // -1 = random
        sampleMethod = SampleMethod.EULER_A,
    )
)

runner.unload()
```

---

## DiffusionModelConfig

| Parameter | Type | Description |
|-----------|------|-------------|
| `modelPath` | String | Path to main model file (safetensors/gguf) |
| `vaePath` | String? | VAE file path (optional, overrides embedded VAE) |
| `clipPath` | String? | CLIP-L encoder path |
| `t5xxlPath` | String? | T5-XXL encoder path (FLUX models) |
| `threads` | Int | CPU threads |
| `gpuLayers` | Int | Layers on GPU (-1 = all, 0 = CPU only) |

---

## ImageGenParams

| Parameter | Type | Description |
|-----------|------|-------------|
| `prompt` | String | Positive prompt |
| `negativePrompt` | String | Negative prompt |
| `width` | Int | Output width (multiple of 64) |
| `height` | Int | Output height (multiple of 64) |
| `steps` | Int | Denoising steps (10–50 typical) |
| `cfgScale` | Float | Classifier-free guidance scale |
| `seed` | Long | RNG seed (-1 = random) |
| `sampleMethod` | SampleMethod | Euler, Euler-A, DPM++2M, LCM, etc. |

---

## SampleMethod

```kotlin
enum class SampleMethod {
    EULER_A,
    EULER,
    HEUN,
    DPM2,
    DPMPP2S_A,
    DPMPP2M,
    DPMPP2Mv2,
    IPNDM,
    IPNDM_V,
    LCM
}
```

---

## GGML Sharing with llama.cpp

Both `llama_runner` and `diffusion_runner` link against the **same** GGML build (from `libraries/llama.cpp`). `GgmlUnified.cmake` in `:nativeEngine` builds GGML once, guarded by `if (NOT TARGET ggml)`, and both libraries link it. This avoids duplicate symbol errors and reduces binary size.

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

<!-- Updated at end of each Claude Code session -->

- Initial implementation of diffusion runner (llama/diffusion GGML sharing)
- DiffusionInferenceRepository wired to chat UI (feature/diffusion-chat-window)
- Smart install system tracks component files (VAE, CLIP, T5) via `ComponentRepository`
- `VideoGenParams` added for future txt2vid support
