# runner

Kotlin Multiplatform wrapper for **llama.cpp** LLM inference. Provides a common `LlamaRunner` interface backed by platform-specific JNI (Android/JVM) and cinterop (iOS) bindings.

---

## Platforms

| Platform | Binding | Native Library |
|----------|---------|---------------|
| Android | JNI via `System.loadLibrary("llama_runner")` | `llama_runner.so` (built by `:nativeEngine`) |
| JVM Desktop | JNI via `System.loadLibrary("llama_runner")` | `libllama_runner.dylib/.so` |
| iOS arm64 | Kotlin/Native cinterop | `libllama_runner_merged.a` (merged static) |
| iOS Simulator | Kotlin/Native cinterop | `libllama_runner_merged.a` (sim build) |

---

## Package Structure

```
com.debanshu777.runner/
├── LlamaRunner.kt               # expect class — core interface
├── LlamaRunner.android.kt       # actual: JNI wrapper
├── LlamaRunner.jvm.kt           # actual: JNI wrapper (same JNI, different lib path)
├── LlamaRunner.ios.kt           # actual: cinterop wrapper
├── NativeRunnerConfig.kt        # Inference parameters (threads, GPU layers, context, KV quant)
├── InferenceState.kt            # IDLE / LOADING / GENERATING / ERROR
├── StopReason.kt                # EOS / LENGTH / STOP_TOKEN / CANCELLED / ERROR
├── StructuredOutputGrammar.kt   # GBNF grammar definitions for constrained output
├── StructuredOutputParser.kt    # Parse grammar from JSON Schema
├── LlamaRunnerExt.kt            # Extension functions (convenience wrappers)
└── LlamaRunnerValidation.kt     # Config validation (throws on invalid params)
```

**C++ Sources:**
```
src/commonCpp/
├── llama_runner_core.h          # C++ class header
├── llama_runner_core.cpp        # Core llama.cpp wrapper (model load, generate, stop)
├── llama_runner_jni.cpp         # JNI bridge (Android + JVM)
└── LlamaRunnerCommon.cmake      # CMake source list (included by :nativeEngine)

src/iosMain/cpp/
├── llama_runner.h               # C function declarations (for cinterop)
├── llama_runner.cpp             # C wrapper over llama_runner_core
└── llama_runner.def             # Kotlin cinterop definition file
```

---

## Usage

```kotlin
val config = NativeRunnerConfig(
    modelPath = "/path/to/model.gguf",
    contextSize = 4096,
    threads = 4,
    gpuLayers = 32,          // 0 = CPU only
    kvCacheType = "q8_0",    // KV cache quantization
)

val runner = LlamaRunner()

// Load model
runner.load(config)

// Streaming generation
runner.generate(
    prompt = "<|user|>\nHello<|end|>\n<|assistant|>\n",
    config = config
).collect { token ->
    print(token)  // Flow<String>
}

// Stop generation
runner.stop()

// Unload (free VRAM/RAM)
runner.unload()
```

---

## NativeRunnerConfig

| Parameter | Type | Description |
|-----------|------|-------------|
| `modelPath` | String | Absolute path to `.gguf` file |
| `contextSize` | Int | Token context window (max sequence length) |
| `threads` | Int | CPU threads for inference |
| `gpuLayers` | Int | Layers offloaded to GPU (0 = CPU only) |
| `kvCacheType` | String | KV cache quant: `"f16"`, `"q8_0"`, `"q4_0"` |
| `batchSize` | Int | Prompt processing batch size |
| `useMmap` | Boolean | Memory-map model file |
| `useMlock` | Boolean | Lock model in RAM (prevent swap) |

`DeviceCapabilities` (in `:composeApp`) builds the appropriate config per platform.

---

## Structured Output

`StructuredOutputGrammar` provides GBNF grammar strings for constrained decoding (JSON, boolean, number, etc.). `StructuredOutputParser` converts JSON Schema to GBNF for arbitrary structured output.

---

## Inference State Machine

```
IDLE → LOADING → GENERATING → IDLE
                      ↓
                   ERROR / CANCELLED
```

`StopReason` indicates why generation ended: `EOS` (natural end), `LENGTH` (context limit), `STOP_TOKEN` (stop string match), `CANCELLED` (user stopped), `ERROR`.

---

## Building Native Library

The native `.so`/`.a`/`.dylib` is built by `:nativeEngine`, not this module. This module only contains the JNI/cinterop glue that calls into those libraries.

```bash
# Desktop
./gradlew :nativeEngine:compileLlamaRunnerDesktop

# iOS arm64
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64
```

---

## Recent Changes

<!-- Updated at end of each Claude Code session -->

- GPU layer offloading via `NativeRunnerConfig.gpuLayers`
- KV cache quantization support (`kvCacheType`)
- Structured output / GBNF grammar support
- Improved chat template handling in core.cpp
- Patch system integration (applied pre-build by `:nativeEngine`)
