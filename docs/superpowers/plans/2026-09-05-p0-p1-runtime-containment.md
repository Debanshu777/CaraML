# P0/P1 Runtime Containment Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Contain audited P0/P1 memory exhaustion, native lifecycle, prompt-overflow, iOS video, media-retention, and stale-memory-budget failures.

**Architecture:** Defensive common validation rejects unsafe work before native entry. Diffusion contexts use reference-counted handles with per-handle operation ownership and asynchronous cancellation, llama prompt commits become failure-atomic, and generated media moves to a bounded session cache loaded off-main.

**Tech Stack:** Kotlin Multiplatform 2.4, kotlinx.coroutines, Compose Multiplatform, Okio, JNI/C++, Kotlin/Native cinterop, stable-diffusion.cpp, llama.cpp, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-09-05-p0-p1-runtime-containment.md`

## Global Constraints

- Preserve existing uncommitted work and public behavior outside the approved P0/P1 scope.
- Add no dependency.
- Rethrow `CancellationException` unchanged.
- Do not log prompts, generated output, full paths, or raw exception messages.
- Preserve JVM `OutOfMemoryError` and pending JNI exceptions.
- Use red-green tests for testable behavior and native platform compilation for ABI/lifetime-only behavior.
- Do not create commits unless the user explicitly requests them.

---

### Task 1: Bound video and output memory

**Files:**
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidation.kt`
- Modify: `diffusionRunner/src/commonTest/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidationTest.kt`
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionMemoryPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionMemoryPolicyTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`

**Interfaces:**
- Produces: `internal const val MAX_VIDEO_FRAME_PIXELS = 16_777_216L` and `internal fun estimateMediaOutputBytes(width: Int, height: Int, frames: Int): Long`.
- Produces: `internal fun fitsDiffusionMemoryBudget(weightsBytes: Long, outputBytes: Long, budgetBytes: Long): Boolean` with saturating arithmetic.

- [x] Add a failing validation test that rejects 4096x4096x2 and accepts the literal 512x512x64 boundary.
- [x] Run `./gradlew :diffusionRunner:jvmTest --tests '*DiffusionRunnerValidationTest*'` and confirm the oversized request is accepted before the fix.
- [x] Add the `Long` frame-pixel check and run the focused test green.
- [x] Add failing literal tests for overflow-safe output-byte estimation and insufficient memory envelopes.
- [x] Run the focused Compose test and confirm the policy symbols are unresolved.
- [x] Implement saturating arithmetic and invoke it before image/video native calls with stable rejection text.
- [x] Run both focused suites green.

### Task 2: Make native diffusion cancellation and JNI conversion safe

**Files:**
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.h`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.cpp`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_jni.cpp`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt`
- Modify: Android/JVM `DiffusionRunner` actuals.
- Modify: `diffusionRunner/src/iosMain/cpp/diffusion_runner.h`
- Modify: `diffusionRunner/src/iosMain/cpp/diffusion_runner.cpp`
- Modify: `diffusionRunner/src/iosMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.ios.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatViewModel.kt`

**Interfaces:**
- Produces: `bool diffusion_runner_core_cancel_generation(int64_t handle)`.
- Produces: `fun DiffusionRunner.cancelGeneration(): Boolean` on every platform.
- Produces: `fun DiffusionInferenceRepository.cancelGeneration()`.

- [x] Replace the native registry value with `shared_ptr<SdHandle>` and add `lookup_handle` that releases the registry mutex before work.
- [x] Serialize generation with `SdHandle.operation_mutex`; make release erase, cancel, wait, and free in that order.
- [x] Export cancellation through JNI and iOS FFI using `SD_CANCEL_ALL` without waiting for `operation_mutex`.
- [x] Add scoped PNG-result cleanup and guard every JNI lookup/allocation/array acquisition before use, preserving pending JVM exceptions.
- [x] Signal diffusion cancellation before cancelling media coroutines during user cancel, mode switch, reload, and teardown.
- [x] Run Android lint/native ABI compilation and iOS simulator compilation as ABI proof.

### Task 3: Disable emulated iOS video safely

**Files:**
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt`
- Modify: all platform `DiffusionRunner` actuals.
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatViewModel.kt`

**Interfaces:**
- Produces: `fun DiffusionRunner.supportsVideoGeneration(): Boolean` and repository forwarding property.

- [x] Return true on Android/JVM and false on iOS.
- [x] Remove the iOS repeated-still loop and return a fast unsupported result.
- [x] Reject iOS video mode before model loading/generation with stable user-facing copy.
- [x] Compile all platform source sets.

### Task 4: Make llama prompt admission failure-atomic

**Files:**
- Modify: `runner/src/commonCpp/llama_runner_core.cpp`
- Create: `runner/src/commonMain/kotlin/com/debanshu777/runner/PromptProcessingResult.kt`
- Create: `runner/src/commonTest/kotlin/com/debanshu777/runner/PromptProcessingResultTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatViewModel.kt`

**Interfaces:**
- Produces: native return code `4` for prompt/context capacity exhaustion.
- Produces: `PromptProcessingResult.fromNativeCode(code: Int)` with `Success`, `ContextFull`, and `Failure`.

- [x] Add failing table-driven tests for native-code mapping.
- [x] Run the focused runner test and confirm the mapping type is unresolved.
- [x] Implement the minimal mapping and run it green.
- [x] Remove suffix truncation; preflight `decodeStart + tokens + reservedGeneration <= contextLimit` before committing chat history.
- [x] Append the user message only after successful decode; on KV-changing decode failure, clear KV and mark a full rebuild while preserving logical history.
- [x] Move repository finalization/snapshot cleanup around every started prompt attempt and surface context exhaustion as a stable actionable failure.
- [x] Run runner and Compose JVM suites plus native platform compilation.

### Task 5: Refresh conservative device memory budgets

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/MemoryBudgetPolicy.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/platform/MemoryBudgetPolicyTest.kt`
- Modify: Android/JVM/iOS `DeviceCapabilities` actuals.
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`

**Interfaces:**
- Produces: `internal fun conservativeMemoryBudget(totalBytes: Long, currentlyAvailableBytes: Long?): Long`.

- [x] Add failing literal tests proving the smaller safe ceiling wins and invalid readings fall back conservatively.
- [x] Implement the common policy and run focused tests green.
- [x] Cache CPU/GPU hints only; refresh Android/JVM available physical memory for every `getDeviceHints()` call.
- [x] Prefer native diffusion `estimatedRamBytes` over disk-size approximation and add component/output headroom with overflow-safe arithmetic.
- [x] Run affected JVM tests and platform compilation.

### Task 6: Move generated media out of message heap and decode off-main

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/media/GeneratedMediaStore.kt`
- Create: platform `GeneratedMediaCachePath` actuals for Android/JVM/iOS.
- Create: `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/media/GeneratedMediaStoreTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/data/ChatMessage.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatViewModel.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatMessageList.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/MessageBubble.kt`

**Interfaces:**
- Produces: `GeneratedMediaStore.saveImage`, `saveVideo`, `read`, and `clear` with session-root containment.
- Produces: `ChatMessage.imagePath` and `videoFramePaths`; byte fields remain only for existing preview compatibility.

- [x] Add failing JVM tests for atomic image persistence, transactional video cleanup, root containment, read size limiting, and session cleanup.
- [x] Implement the store with Okio and run focused tests green.
- [x] Persist successful native results before updating message state; do not store the prompt in media metadata.
- [x] Load only visible paths and decode bytes inside `produceState` on `Dispatchers.Default`.
- [x] Clear the session cache from `onCleared()` and compile Compose previews/source sets.

### Task 7: Verify and document

**Files:**
- Modify: `README.md`
- Modify: `composeApp/README.md`
- Modify: `runner/README.md`
- Modify: `diffusionRunner/README.md`
- Modify: `nativeEngine/README.md` only if orchestration changes.

- [x] Run every focused regression suite from Tasks 1, 4, 5, and 6.
- [x] Run `./gradlew verifyProject --rerun-tasks`.
- [x] Run `./gradlew :androidApp:lintDebug`.
- [x] Run `./gradlew :composeApp:compileKotlinIosSimulatorArm64`.
- [x] Update only relevant `Recent Changes` bullets.
- [x] Run `git diff --check` and inspect the scoped diff for unrelated changes, secrets, unsafe messages, and missed P0/P1 requirements.
