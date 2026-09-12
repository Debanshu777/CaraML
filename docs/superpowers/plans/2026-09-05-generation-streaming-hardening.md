# Generation and Streaming Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Reject OOM-prone generation requests, preserve cancellation, remove per-token quadratic copying, and prevent redundant Compose work.

**Architecture:** Runner output crosses the module boundary as lossless deltas with explicit resynchronization flags. The use case owns cumulative text and publishes UI snapshots at a bounded cadence, while final output is always flushed. Diffusion inputs share one defensive validator for image and video generation.

**Tech Stack:** Kotlin Multiplatform, kotlinx.coroutines Flow, Compose Multiplatform, Kotlin/Native/JNI runner APIs, kotlin.test.

**Spec:** `docs/superpowers/specs/2026-09-05-reliability-performance-hardening.md`

## Global Constraints

- Do not change generated model text, token accounting, or final Markdown output.
- Keep cancellation as `CancellationException`; never wrap it in `Result.failure` or a generic network/native error.
- Reject dangerous dimensions and numeric values before native allocation.
- Do not add dependencies.
- Every behavior change follows a focused red-green cycle.

---

### Task 1: Diffusion generation limits

**Files:**
- Create: `diffusionRunner/src/commonTest/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidationTest.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerValidation.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunnerExt.kt`
- Modify: platform `DiffusionRunner` actuals.

**Interfaces:**
- Produces: `validateVideoGenParams(VideoGenParams)` and shared prompt/dimension/step/CFG/LoRA validation.

- [ ] **Step 1: Add failing table-driven tests for dimensions above 4096, more than 16 megapixels, steps above 150, non-finite CFG/LoRA strengths, prompts above 16,384 characters, more than 32 LoRAs, and video frames above 256.**
- [ ] **Step 2: Run the focused JVM test and confirm unsafe cases are accepted before the fix.**
- [ ] **Step 3: Implement shared validation using `Long` pixel multiplication and stable generic argument messages.**
- [ ] **Step 4: Invoke video validation from the extension and every platform actual before native calls.**
- [ ] **Step 5: Run the focused test and compile JVM, Android, and iOS simulator targets.**

### Task 2: Lossless delta stream

**Files:**
- Modify: `runner/src/commonMain/kotlin/com/debanshu777/runner/InferenceChunk.kt`
- Modify: `runner/src/commonMain/kotlin/com/debanshu777/runner/LlamaRunnerExt.kt`
- Modify: `runner/src/commonTest/kotlin/com/debanshu777/runner/LlamaRunnerExtTest.kt`

**Interfaces:**
- Produces: `InferenceChunk(reasoningDelta, contentDelta, reasoningResync, contentResync)` once per native token plus a final parser-resync event when needed.

- [ ] **Step 1: Replace cumulative expectations with failing literal delta expectations and add an end-of-generation resync test.**
- [ ] **Step 2: Run the focused runner tests and verify they fail against cumulative snapshots.**
- [ ] **Step 3: Parse the resync sentinel into an explicit flag and emit only delta payloads. On `nextToken() == null`, read and emit one final non-empty/resync delta before stopping.**
- [ ] **Step 4: Run runner tests green and run the runner JVM suite.**

### Task 3: Bounded UI snapshots

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/domain/usecase/InferenceTextAccumulator.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/features/chat/domain/usecase/InferenceTextAccumulatorTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/domain/usecase/GenerateResponseUseCase.kt`

**Interfaces:**
- Produces: accumulator applying append/resync deltas and reporting whether 50 ms has elapsed since the prior UI snapshot.

- [ ] **Step 1: Add failing tests for append, independent reasoning/content resync, immediate first publish, 50 ms throttling, and unconditional final flush.**
- [ ] **Step 2: Implement the minimal accumulator with injected timestamps.**
- [ ] **Step 3: Count every native token event in `TokenTimer`, but call `onToken` only for bounded snapshots and the final flush.**
- [ ] **Step 4: Run focused and full Compose JVM tests.**

### Task 4: Compose streaming render boundary

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/ChatScreen.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatMessageList.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/ChatContextIndicator.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/presentation/components/MessageBubble.kt`

**Interfaces:**
- Consumes: one `StreamingState` value collected at `ChatScreen`.

- [ ] **Step 1: Pass the collected value to the list and context indicator; remove nested `StateFlow` collectors.**
- [ ] **Step 2: Render streaming assistant output with Material `Text`; retain Markdown for finalized assistant messages.**
- [ ] **Step 3: Compile Compose JVM/Android and run Android lint.**

### Task 5: Cancellation and cache correctness

**Files:**
- Modify: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/api/ClientWrapper.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/chat/domain/usecase/ManageContextUseCase.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/DiffusionInferenceRepository.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt`

**Interfaces:** Existing public interfaces unchanged.

- [ ] **Step 1: Add cancellation regression tests around pure wrappers or existing injectable interfaces.**
- [ ] **Step 2: Rethrow cancellation before generic catches and make failed context restoration return failure.**
- [ ] **Step 3: Include context preference, KV types, batch sizes, model size, and model file identity in the params-fit cache key.**
- [ ] **Step 4: Use `Long` multiplication for VAE tiling decisions and remove full model/component paths from diffusion logs.**
- [ ] **Step 5: Run full JVM verification.**

