# Native and Download Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate known native ownership races and ensure only validated, complete model files reach inference engines.

**Architecture:** A common exclusive-operation gate serializes all llama model/context access while cancellation remains lock-free. A shared download policy validates untrusted identifiers and paths; each platform writes a temporary sibling, validates the response and length, closes it, and atomically commits it.

**Tech Stack:** Kotlin Multiplatform 2.4, kotlinx.coroutines, Ktor 3.5, Kotlin/Native cinterop, JNI/C++, kotlin.test, JDK `HttpServer` for JVM integration tests.

**Spec:** `docs/superpowers/specs/2026-09-05-reliability-performance-hardening.md`

## Global Constraints

- Do not add a dependency.
- Preserve the public download and inference interfaces.
- Reject unsafe input before filesystem or network access.
- Rethrow `CancellationException` unchanged.
- Do not log prompts, generated output, full local paths, or raw exception messages.
- Make every behavior change pass a red-green regression cycle; native lifetime-only edits require platform compilation proof.

---

### Task 1: Exclusive native session gate

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/NativeSessionGate.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/data/inference/NativeSessionGateTest.kt`
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/data/inference/LlamaInferenceRepository.kt`

**Interfaces:**
- Produces: `internal class NativeSessionGate` with `suspend fun <T> exclusive(block: suspend () -> T): T`.
- Consumes: `kotlinx.coroutines.sync.Mutex`.

- [ ] **Step 1: Write the failing exclusivity test**

```kotlin
@Test
fun secondOperationWaitsUntilFirstCompletes() = runTest {
    val gate = NativeSessionGate()
    val firstEntered = CompletableDeferred<Unit>()
    val releaseFirst = CompletableDeferred<Unit>()
    var secondEntered = false
    val first = launch { gate.exclusive { firstEntered.complete(Unit); releaseFirst.await() } }
    firstEntered.await()
    val second = launch { gate.exclusive { secondEntered = true } }
    runCurrent()
    assertFalse(secondEntered)
    releaseFirst.complete(Unit)
    joinAll(first, second)
    assertTrue(secondEntered)
}
```

- [ ] **Step 2: Run `./gradlew :composeApp:jvmTest --tests '*NativeSessionGateTest*'` and confirm unresolved `NativeSessionGate` failure.**
- [ ] **Step 3: Implement the gate with explicit `lock()`/`unlock()` in `try/finally`, so a suspending block is supported and cancellation releases ownership.**
- [ ] **Step 4: Add a cancellation regression proving a cancelled holder releases the gate, then run the focused test green.**
- [ ] **Step 5: Route load, unload, generation/finalization, summarization/finalization, and reset operations through one gate. Keep cancellation outside it.**
- [ ] **Step 6: Run the focused tests and `./gradlew :composeApp:jvmTest`.**
- [ ] **Step 7: Commit the independently passing slice with `fix: serialize llama native session access`.**

### Task 2: Diffusion bridge ownership

**Files:**
- Modify: `diffusionRunner/src/iosMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.ios.kt`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_jni.cpp`

**Interfaces:**
- Produces: JNI-local owning configuration whose `DiffusionModelConfig` pointers remain valid through `diffusion_runner_core_load_model`.
- Consumes: existing `DiffusionModelConfig` and `DiffusionModelConfigFFI` ABI unchanged.

- [ ] **Step 1: Replace function-static JNI path strings with a stack-owned structure containing all seven `std::string` fields and a `DiffusionModelConfig view()` method.**
- [ ] **Step 2: Extract Java fields into that owner and invoke core load before the owner leaves scope. Check pending JNI exceptions after class/field/string extraction and return `0` on failure.**
- [ ] **Step 3: Move the iOS load call into the existing `memScoped`, returning its handle from the block.**
- [ ] **Step 4: Run `./gradlew :nativeEngine:buildCMakeRelease[arm64-v8a] :nativeEngine:buildCMakeRelease[x86_64]` through the Android lint gate and compile the iOS simulator Kotlin target.**
- [ ] **Step 5: Commit the independently compiling slice with `fix: preserve diffusion bridge string lifetimes`.**

### Task 3: Shared download request policy and progress coalescing

**Files:**
- Create: `huggingFaceManager/src/commonMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadPolicy.kt`
- Create: `huggingFaceManager/src/commonTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadPolicyTest.kt`

**Interfaces:**
- Produces: `ValidatedDownloadRequest`, `validateDownloadRequest(modelId, path)`, and `DownloadProgressTracker.next(bytesReceived)`.
- Consumes: `DownloadProgressDTO`.

- [ ] **Step 1: Write table-driven tests that reject `../x`, `a/../../x`, absolute paths, backslashes, empty segments, control characters, and unsafe model IDs while accepting `org/model` plus nested Hugging Face paths.**
- [ ] **Step 2: Run `./gradlew :huggingFaceManager:jvmTest --tests '*DownloadPolicyTest*'` and confirm unresolved policy failure.**
- [ ] **Step 3: Implement validation with literal segment checks and length bounds; do not normalize unsafe input into acceptance.**
- [ ] **Step 4: Add tests proving known-size progress emits at most once per integer percentage and unknown-size progress emits per MiB, with a separate unconditional completion event.**
- [ ] **Step 5: Implement the minimal tracker and run focused tests green.**
- [ ] **Step 6: Commit the independently passing slice with `fix: validate model download targets`.**

### Task 4: JVM and Android transactional downloads

**Files:**
- Modify: `huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.jvm.kt`
- Modify: `huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.android.kt`
- Create: `huggingFaceManager/src/jvmTest/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManagerJvmTest.kt`

**Interfaces:**
- Consumes: `validateDownloadRequest` and `DownloadProgressTracker`.
- Produces: final local path only after validated commit; sibling `<filename>.part` during transfer.

- [ ] **Step 1: Start a loopback `HttpServer` in the test and write a failing 404 test asserting neither final nor `.part` exists.**
- [ ] **Step 2: Add a failing truncated-body test asserting cleanup and no final path.**
- [ ] **Step 3: Add a failing success test asserting exact bytes, final completion path, and no `.part`.**
- [ ] **Step 4: Implement response-status validation, encoded URL components, temporary streaming, content-length verification, close-before-rename, and temporary-only cleanup on error/cancellation.**
- [ ] **Step 5: Mirror the tested JVM transaction in Android using the same shared policy and tracker.**
- [ ] **Step 6: Run the JVM integration test, the whole manager JVM suite, and Android lint/native compilation.**
- [ ] **Step 7: Commit the independently passing slice with `fix: commit model downloads atomically`.**

### Task 5: iOS transactional downloads and path containment

**Files:**
- Modify: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/DownloadManager.ios.kt`
- Modify: `huggingFaceManager/src/iosMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.ios.kt`
- Modify: `huggingFaceManager/src/androidMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.android.kt`
- Modify: `huggingFaceManager/src/jvmMain/kotlin/com/debanshu777/huggingfacemanager/download/StoragePathProvider.jvm.kt`

**Interfaces:**
- Consumes: shared validated model IDs/paths and progress tracker.
- Produces: off-main iOS writes with a required file handle and standardized containment checks.

- [ ] **Step 1: Validate model IDs in every storage provider before constructing a root path.**
- [ ] **Step 2: Standardize and resolve iOS root/target paths before containment checks.**
- [ ] **Step 3: Implement the iOS `.part` transaction, reject non-2xx, fail on null file handle, close before rename, and add `flowOn(Dispatchers.Default)`.**
- [ ] **Step 4: Compile all iOS simulator source sets and run the manager JVM tests as shared-policy regression proof.**
- [ ] **Step 5: Commit the independently compiling slice with `fix: harden iOS model file handling`.**

### Task 6: Verification and documentation

**Files:**
- Modify: `README.md`
- Modify: `composeApp/README.md`
- Modify: `huggingFaceManager/README.md`
- Modify: `diffusionRunner/README.md`
- Modify: `runner/README.md`
- Modify: `nativeEngine/README.md` only if native build orchestration changes.

**Interfaces:** None.

- [ ] **Step 1: Run focused tests for the session gate, policy, and JVM transaction.**
- [ ] **Step 2: Run `./gradlew verifyProject`.**
- [ ] **Step 3: Run `./gradlew :androidApp:lintDebug` to compile Android Kotlin and both configured native ABIs.**
- [ ] **Step 4: Compile/link the relevant iOS simulator targets; record any environment-only limitation separately from a code failure.**
- [ ] **Step 5: Update only relevant `Recent Changes` sections with concise, non-stale bullets.**
- [ ] **Step 6: Run `git diff --check` and review the complete diff for unrelated changes, secret exposure, and unsafe error text.**
- [ ] **Step 7: Commit the verified documentation with `docs: record native and download hardening`.**

