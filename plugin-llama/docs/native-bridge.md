# llama native bridge

Kotlin Multiplatform wrapper for **llama.cpp** LLM inference. Provides a common `LlamaRunner` interface backed by platform-specific JNI (Android/JVM) and cinterop (iOS) bindings.

---

## Platforms

| Platform | Binding | Native Library |
|----------|---------|---------------|
| Android | JNI via `System.loadLibrary("llama_runner")` | `libllama_runner.so` (built by `:nativeEngine`) |
| JVM Desktop | JNI via shared `DesktopNativeLibraries` resolver | `libllama_runner.dylib/.so` or `llama_runner.dll` |
| iOS arm64 | Kotlin/Native cinterop | `libllama_runner_merged.a` (merged static) |
| iOS Simulator | Kotlin/Native cinterop | `libllama_runner_merged.a` (sim build) |

---

JVM loading resolves `caraml.native.lib.dir` first, then `compose.application.resources.dir`. A configured directory is authoritative; a missing library fails instead of falling back to a build-tree library. With neither directory configured, the resolver uses `System.loadLibrary`. Package the JNI runtime and its shared backends together; the CaraML desktop host uses `:nativeEngine:stageDesktopRuntime`.

## Package Structure

```
com.debanshu777.caraml.sdk.plugin.llama.bridge/
├── LlamaRunner.kt               # expect class — core interface
├── LlamaRunner.java.kt          # shared Android/JVM actual in src/javaMain
├── LlamaNativeRuntime.android.kt # Android library loader
├── LlamaNativeRuntime.jvm.kt    # Desktop library loader and diagnostics
├── LlamaRunner.ios.kt           # actual: cinterop wrapper
├── NativeRunnerConfig.kt        # Inference parameters (threads, GPU layers, context, KV quant)
├── InferenceState.kt            # Optional host lifecycle state types
├── StopReason.kt                # NONE / EOG / MAX_TOKENS / CONTEXT_FULL / CANCELLED / ERROR
├── InferenceChunk.kt            # Content/reasoning deltas and resynchronization flags
├── LlamaRunnerExt.kt            # Extension functions (convenience wrappers)
└── LlamaRunnerValidation.kt     # Config validation (throws on invalid params)
```

Both `androidMain` and `jvmMain` explicitly include `src/javaMain/kotlin`.
Source sharing preserves the runner class package, JNI method signatures and
consumer keep rules; only native-library loading remains target-specific.

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

Prefer the owned `LlamaPlugin` session API described in the [module README](../README.md).
Direct bridge callers must serialize native ownership and run blocking calls off
the UI thread. For a host-owned runner, a bounded text operation is:

```kotlin
withContext(Dispatchers.Default) {
    val runner = LlamaRunner()
    try {
        runner.initialize(nativeLibraryDirectory)
        check(runner.loadModel("/path/to/model.gguf", NativeRunnerConfig(
            nCtx = 2048, nThreads = 4, nGpuLayers = 0, autoFit = false,
        )))
        try {
            check(PromptProcessingResult.fromNativeCode(
                runner.processUserPrompt("Hello", predictLength = 128),
            ) == PromptProcessingResult.Success)
            val content = StringBuilder()
            runner.generateStructuredChunks().collect { chunk ->
                if (chunk.contentResync) content.clear()
                content.append(chunk.contentDelta)
            }
            println(content)
        } finally {
            runner.finalizeGeneration()
        }
    } finally {
        runner.shutdown()
    }
}
```

`cancelGenerate()` signals cancellation; the host must await generation and
finalization before unloading. `unloadModel()` releases the model while retaining
the initialized backend; `shutdown()` also releases backend state.

---

## NativeRunnerConfig

| Parameter | Type | Description |
|-----------|------|-------------|
| `nCtx` | Int | Requested token context; zero allows automatic selection |
| `nThreads` | Int | CPU threads for inference |
| `nGpuLayers` | Int | Explicit offloaded layer count; `0` is CPU and `-1` requests full offload |
| `typeK`, `typeV` | Int | Validated native KV-cache type identifiers |
| `nBatch`, `nUbatch` | Int | Logical and physical prompt batch sizes |
| `autoFit` | Boolean | Fit the requested configuration to native resource limits |
| `useMmap` | Boolean | Memory-map model file |
| `useMlock` | Boolean | Lock model in RAM (prevent swap) |

`DeviceCapabilities` (in `:app:composeApp`) builds the appropriate config per platform.

---

## Structured Streaming

`generateStructuredChunks()` returns content and reasoning deltas with explicit
resynchronization flags when parsing reclassifies an earlier fragment. Apply a
resynchronization as a replacement of that channel's accumulated text. Final
parser updates are distinguished from generated token events. This bridge does
not expose the removed grammar/schema helpers.

---

## State and Stop Reasons

`InferenceState` defines optional host state types: `Uninitialized`, `Initializing`,
`LoadingModel`, `ModelReady`, `ProcessingPrompt`, `Generating`, `Cancelling`, and
`Error(message)`. The runner does not emit these states automatically.

`StopReason` distinguishes `EOG`, `MAX_TOKENS`, `CONTEXT_FULL`, `CANCELLED`, and
`ERROR`, with `NONE` before a terminal condition.

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

- Android/JVM now share `LlamaRunner.java.kt` and use small platform library loaders. JNI signatures and native ownership are unchanged; usage and configuration examples reflect the current bridge API.
- Bridge implementation moved from `runner` into `plugin-llama`; Kotlin packages, JNI symbols and cinterop bindings now use `com.debanshu777.caraml.sdk.plugin.llama.bridge`. Rebuild Kotlin and native binaries together. Consumer rules preserve JNI descriptor classes.


- JNI now converts Kotlin UTF-16 to standard UTF-8, validates malformed input/NULs, and decodes streamed output across split UTF-8 bytes; the Android consumer rules preserve JNI-reflected config fields and the wrapper floor matches the native engine at API 28. Native `const char*` token output remains NUL-terminated, so embedded NUL bytes cannot be represented as token text.

- Preflight error paths return fresh, empty results without the aggregate assignment that crashes GCC 13; native regression checks cover cleared plan and pool evidence.

- Generation profiling separates sampling, decode submission, synchronization and parsing. Dense repetition penalties preserve pinned sampler selection and history semantics with upstream fallback for unsupported candidate layouts.

- Native loading/inference diagnostics now record scoped stage outcomes, monotonic timings and bounded upstream reason codes; raw parser/upstream exception text is excluded.

- Templated generation shares exact prompt/parser parameters and uses llama.cpp reasoning budgets with model delimiters to reserve answer space; context exhaustion takes precedence over the clamped output allowance.

- Native preflight identifies Gemma assistant models that require a target context; chat reports the actual admission reason instead of labeling every temporary failure as memory/thermal pressure.

<!-- Updated at end of each Claude Code session -->

- Parameter audit validates cache types/CPU affinity and load paths, honors partial GPU requests, bounds single-sequence logits, and preserves multi-turn KV and final stream state.
- Automatic fitting preserves llama.cpp's `-1` full-offload sentinel, fits the requested context, and reports resolved nonnegative GPU layer counts across preflight/JNI/FFI; Pixel 9 debug chat verified 43 GPU layers and 10.4 tok/s through 1,024 tokens
- The runner now targets llama.cpp `f46bc30`, supports bounded lazy tensor loading and automatic integrated-GPU load mode, and exposes a sanitized bounded engine version across JNI and iOS
- Preflight and load use the same exact admitted budget and placement; rebased ownership, bounds, logger, and Vulkan shader-capability patches are applied only to the build-owned source copy
- Opt-in native parity verifies a digest-pinned tiny GGUF can preflight successfully and a bounded corrupt GGUF fails safely; run `CARAML_NATIVE_PARITY=true ./gradlew :plugin-llama:jvmTest`, while ordinary JVM tests remain fixture- and native-build-free
- Calibration now uses typed memory-copy/GEMM windows, pre-reserved exact-token cancellation, bounded caller timeouts, and permanent fail-closed operation quarantine if an in-process native probe becomes unresponsive; LLM chunks expose native decode-only timing
- Bounded llama.cpp preflight now shares a thread-independent stream session lease across prompt, token, and finalization calls, keeps cancellation lock-free, recognizes only exact pinned quantization labels, and releases transient native state on every exit
- Backend capability records now carry bounded canonical device identity and type so cross-engine aggregation cannot combine different devices merely because their backend kinds match
- User prompts are admitted before KV/history mutation, reserve response capacity without tail truncation, and return a dedicated context-full result with failure cleanup
- Native operations now fail through controlled fallbacks when the shared library is unavailable; JNI and iOS C boundaries catch allocation/native exceptions instead of allowing C++ unwinding to terminate the process
- Structured streaming emits token deltas plus explicit resync flags and distinguishes parser-finalization events from generated tokens for accurate metrics
- Coroutines use the shared 1.11.0 dependency catalog version across modules
- Added native delta accessors `getReasoningDelta`/`getContentDelta` (with `\x01` resync sentinel); `structuredChunkFlow` now accumulates O(n) deltas in Kotlin instead of copying full native accumulators per token
- Added native reasoning/content accessors + `supportsThinking`; new `InferenceChunk` + `generateStructuredChunks`; removed `StructuredOutputGrammar`/`StructuredOutputParser`; `processUserPrompt` no longer takes a grammar
- GPU layer offloading via `NativeRunnerConfig.nGpuLayers`
- KV cache quantization support (`typeK` and `typeV`)
- Improved chat template handling in core.cpp
- Numbered llama.cpp patches are applied to a build-owned source copy by `:nativeEngine`; the pinned submodule remains immutable
