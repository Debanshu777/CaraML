# CaraML Device-Aware Model Recommendation Design

## Goal

Replace the current heuristic model rating with an explainable, variant-specific recommendation system that answers four separate questions:

1. Can this build of CaraML execute the model?
2. Can the current device run it without unsafe memory or storage pressure?
3. What performance should the user expect for a concrete workload?
4. Does it fit the user's risk tolerance and optimization preference?

The system must make useful pre-download estimates, improve them after native inspection, learn from local observations, and fail conservatively when evidence is incomplete. A user preference may change ranking and acceptable headroom, but it must never override hard incompatibility or a known no-fit result.

## Current-state problems

`ModelSuitabilityCalculator` currently combines a single memory ratio with coarse GPU and CPU adjustments. It assumes `Q4_K_M` when a variant is unknown, uses fixed architecture tables and overheads, and often evaluates the advertised maximum context rather than the workload CaraML will run. Search results can therefore look precise before a concrete file has been selected.

`DeviceHints` compresses device state into core counts, one memory budget, and a GPU Boolean. It does not represent dynamic process headroom, GPU memory topology, thermal state, backend capabilities, evidence freshness, or collection failures.

The native runtime already has stronger information than the UI estimator. llama.cpp uses `common_fit_params`, CaraML caps automatic LLM context at 16,384 tokens, and the vendored stable-diffusion.cpp exposes automatic fitting and backend memory controls. Rating and execution can consequently disagree.

Finally, Hugging Face metadata is external input. Unchecked multiplication and addition involving file sizes, parameters, dimensions, or context can overflow and turn an invalid estimate into a favorable result (CWE-190/CWE-400).

## Scope

This design includes:

- Variant- and workload-specific model descriptors.
- Fresh, platform-specific device snapshots.
- Compatibility, memory, storage, performance, thermal, and confidence assessment.
- Bounded run-plan optimization for LLM and diffusion inference.
- User-selected risk tolerance and optimization priority.
- First-open Models onboarding and editable recommendation settings.
- Client-side personalized ranking over a bounded candidate window.
- Post-download native preflight.
- Local performance calibration and suspected native-load-crash recovery.
- Compatibility migration from the existing suitability UI.

## Non-goals

- Claiming objective model quality from parameter count alone.
- Cloud profiling, fleet telemetry, or transmitting device fingerprints.
- Automatically downloading a different model without user confirmation.
- Silently reducing diffusion resolution or an explicitly selected LLM context.
- Guaranteeing recovery from arbitrary native memory corruption or process signals.
- Replacing Hugging Face popularity, date, and download-count sorts.
- Refactoring unrelated inference or download behavior.

## Product preferences

The first time the user opens the Models tab, CaraML presents a short chooser with Balanced selected. Continuing persists the choice; dismissing uses Balanced and leaves onboarding complete so the dialog does not repeatedly interrupt browsing.

Preferences have two independent axes:

```kotlin
enum class RiskTolerance {
    CONSERVATIVE,
    BALANCED,
    EXPERIMENTAL,
}

enum class OptimizationPriority {
    SPEED_EFFICIENCY,
    BALANCED,
    QUALITY_CONTEXT,
}
```

Risk tolerance controls additional resource reserve, required evidence, and how profile-neutral estimate intervals map to categories. Optimization priority controls performance targets and ordering among models in the same safety category. Both are editable from a Recommendation profile control in the Models tab and from Settings.

The raw model assessment remains independent of these preferences. Changing preferences only reapplies policy and reranks cached assessments.

## Recommendation categories

```kotlin
enum class RecommendationCategory {
    RECOMMENDED,
    USABLE,
    RISKY,
    NOT_SUITABLE,
    INCOMPATIBLE,
    NEEDS_INFORMATION,
}
```

- **Recommended:** the preferred workload has a plan that satisfies the selected policy with sufficient evidence.
- **Usable:** the model is expected to run, but needs a disclosed compromise or misses a noncritical preference.
- **Risky:** the estimate straddles a limit or confidence is insufficient for a stronger claim. An Experimental policy may classify a Likely fit as Recommended, but it must retain a visible tight-fit warning.
- **Not suitable:** no allowed plan meets the minimum workload or a resource floor is known to fail.
- **Incompatible:** the model, format, quantization, component graph, or required backend cannot execute in this build.
- **Needs information:** critical compatibility or resource evidence is unavailable and cannot be safely inferred.

Categories are not averaged scores. A positive quality or performance signal cannot compensate for incompatibility, memory no-fit, or storage failure.

## Architecture

The system is divided into independently testable components:

```text
Hugging Face/local metadata -> ModelDescriptorFactory -> ModelDescriptor
Device APIs                -> DeviceCapabilityProvider -> DeviceSnapshot
User/runtime settings      -> WorkloadConfigFactory     -> WorkloadConfig

ModelDescriptor + DeviceSnapshot + WorkloadConfig
    -> CompatibilityChecker
    -> LlmFootprintEstimator or DiffusionFootprintEstimator
    -> PerformanceEstimator
    -> bounded RunPlanGenerator
    -> SuitabilityEngine
    -> objective ModelAssessment with assessed candidate plans

ModelAssessment + RecommendationProfile
    -> RunPlanOptimizer
    -> RecommendationPolicy
    -> PersonalizedRecommendation
    -> UI, sort order, download gate, and load gate

Native preflight and observed runs feed stronger evidence back through
ModelAssessmentRepository and CalibrationRepository.
```

`ModelViewModel` consumes `ModelAssessmentRepository`; it does not calculate memory or profile policy. `LlamaInferenceRepository` and `DiffusionInferenceRepository` consume the selected run plan and perform the final just-in-time gate before native loading.

## Core data model

### ModelDescriptor

A descriptor represents one exact variant or one complete diffusion component graph, never only a repository name.

It contains:

- Stable repository ID, revision, and file identity.
- Model kind, format, architecture, and required features.
- Exact component list and readiness state.
- Validated file sizes and temporary download-space requirement.
- Parameter count and quantization distribution when available.
- Layer count, embedding/head dimensions, KV-head count, recurrent-state metadata, and model context limit.
- Diffusion architecture, native resolution, conditioning, VAE, and control components.
- Per-field provenance and validation warnings.

Repository-level search results do not receive an authoritative category until a concrete runnable variant is known. If file metadata is available, the list may show the best compatible variant and name it explicitly. Otherwise the result is Needs information or “Select a variant.” The implementation must not silently assume `Q4_K_M`.

External numeric values are accepted only when finite, positive where required, and within central sanity limits. Resource arithmetic uses checked or saturating operations. Any overflow produces invalid evidence and cannot become Recommended.

The initial `DescriptorLimits` are deliberately far above supported on-device workloads while keeping arithmetic bounded: 1 PiB per file, 2 PiB per component bundle, 4,096 components, 1 quadrillion parameters, 16,777,216 context tokens, and 65,536 pixels per dimension. These are parser-safety limits, not claims that CaraML can execute inputs near them. Runtime and product limits are applied separately.

### DeviceSnapshot

Static and dynamic facts are separated:

```kotlin
data class HardwareProfile(
    val cpuArchitecture: String,
    val logicalCoreCount: Int,
    val performanceCoreCount: Int?,
    val instructionSets: Set<String>,
    val backends: List<BackendCapability>,
    val memoryTopology: MemoryTopology,
)

data class ResourceSnapshot(
    val additionalAllocatableHostBytes: Long?,
    val additionalAllocatableGpuBytes: Long?,
    val currentProcessBytes: Long?,
    val freeStorageBytes: Long?,
    val lowMemory: Boolean,
    val thermalState: ThermalState,
    val capturedAtEpochMs: Long,
    val evidence: List<Evidence>,
)
```

`HardwareProfile` may be cached until the app or native-engine version changes. `ResourceSnapshot` is refreshed before download admission and again before model loading. Final categories are not persisted as device truth because memory, storage, and thermal state change.

Platform collectors use the strongest available APIs and preserve failures as evidence:

- Android: `ActivityManager.MemoryInfo` availability, threshold and low-memory state; current process footprint; storage; exact Vulkan/backend registration; GPU heap budget when the backend exposes it.
- iOS: `os_proc_available_memory()` without caching; process thermal state; Metal recommended working-set headroom and current allocation when available; simulator/backend distinction.
- JVM desktop: current OS available memory from supported platform APIs, process footprint, storage, and actual registered native backend memory information. Total physical memory alone is a fallback with low confidence.

Unified-memory devices expose one shared resource pool. Discrete GPU devices expose host and GPU pools separately; the estimator never adds them into a fictitious combined budget.

### WorkloadConfig and RunPlan

`WorkloadConfig` describes what the user intends to do:

- LLM target context, prompt reference size, generation reserve, batch/micro-batch, KV types, and backend preference.
- Diffusion mode, width, height, frame count, batch, steps, VAE mode, control components, and backend preference.

`RunPlan` is an executable configuration plus its compromises and estimate. It is immutable and is passed to the inference repository that will execute it.

## Assessment algorithm

### 1. Hard compatibility

The compatibility checker rejects unsupported model formats, GGUF versions, architectures, quantizations, missing required components, unavailable mandatory backends, and model features absent from the current native build.

Unsupported is Incompatible. Unknown critical compatibility is Needs information. User policy never overrides either state.

### 2. Footprint interval

Every resource estimate is an interval:

```kotlin
data class EstimateRange(
    val lowBytes: Long,
    val likelyBytes: Long,
    val highBytes: Long,
)
```

Construction enforces `0 <= low <= likely <= high`. Invalid or overflowing component estimates produce invalid evidence rather than a reordered or clamped favorable range.

For an LLM:

```text
peak incremental memory =
    resident weights
  + KV cache(target context, batch, K/V types, architecture)
  + recurrent state where applicable
  + graph and compute buffers
  + backend allocations
  + runtime growth
```

For diffusion:

```text
peak incremental memory =
    resident components
  + architecture/resolution/batch activation peak
  + conditioning and VAE buffers
  + backend allocations
  + runtime growth
```

Exact file sizes do not make runtime buffers exact. Unknown components widen only the affected interval. Missing critical architecture data produces Needs information instead of a favorable generic estimate.

The effective budget is:

```text
platformReserve = max(
    OS low-memory or pressure threshold,
    observed app-footprint fluctuation P95,
    platform minimum reserve
)

baseBudget = max(0, currentlyAllocatable - platformReserve)
```

The initial platform minimum reserve is 384 MiB on Android/iOS and 512 MiB on desktop. When the OS provides a stronger low-memory or process-headroom threshold, that value wins. `baseBudget` and the estimate intervals are stored in the profile-neutral assessment.

`RecommendationPolicy` then derives `policyBudget = baseBudget * (1 - profileReserve)`. Initial additional profile reserves are 25% for Conservative, 15% for Balanced, and 5% for Experimental. These values are versioned policy constants and are applied after the platform reserve, not against total physical RAM.

Host, GPU, and shared-memory intervals are compared only with their matching budgets. Storage includes final files, temporary download files, and bundle components. Storage uses a profile-independent reserve of `min(10 GiB, max(512 MiB, 5% of current free space))`; Experimental mode cannot disable filesystem safety. If temporary and final files reside on different filesystems, each filesystem is admitted independently.

### 3. Policy fit band

The objective assessment retains the estimate and base budget. For each candidate plan, `RecommendationPolicy` applies the selected memory reserve and derives:

```text
COMFORTABLE: high <= policy budget
LIKELY:      likely <= policy budget < high
BORDERLINE: low <= policy budget < likely
NO_FIT:     policy budget < low
```

The worst required pool determines the policy fit band. A known storage no-fit blocks download. A runtime no-fit prevents load but may still allow an explicit “download for later” action when storage and compatibility pass.

### 4. Bounded run-plan optimization

`RunPlanGenerator` creates a small, profile-neutral ordered candidate set instead of a Cartesian product. `SuitabilityEngine` estimates every candidate. `RunPlanOptimizer` belongs to the policy layer and selects from those assessed candidates using the user's risk and optimization preferences.

For LLMs, generation begins with the user workload, clamps it to the model and current engine limits, and includes decreasing context buckets down to the minimum supported workload. KV-cache variants are generated only when the user selected Auto or permitted fallback. GPU layer assignment comes from native auto-fit where possible; batch values use a short predefined safe sequence. Pruning is allowed only for axes the estimator explicitly declares monotonic, such as decreasing context with an otherwise identical plan.

For diffusion, it evaluates normal execution, VAE tiling, supported backend memory limits, and layer streaming. A lower resolution is returned only as a disclosed fallback proposal; it is never silently selected.

The policy layer selects the highest-utility configuration that passes policy while preserving candidate order as a stable tie-breaker. If only a permitted degraded configuration fits, the model can be at most Usable. If no candidate meets the minimum workload, it is Not suitable.

### 5. Performance estimate

Static core count is not treated as a speed measurement. The initial predictor uses a roofline-style bound for the actual backend:

```text
estimated time = max(
    estimated bytes moved / sustained backend bandwidth,
    estimated operations / sustained backend compute
) * architecture/backend correction
```

LLM output includes prompt processing rate, decode tokens per second, time to first token, and load-time ranges. Diffusion image output includes seconds per step and total-time range for the stated resolution and step count. Video plans additionally include seconds per frame and total-time ranges. Video performance remains non-blocking until comparable calibration exists; compatibility and memory gates still apply.

Initial minimum LLM decode targets are 8 tokens/second for Speed and efficiency, 4 for Balanced, and 2 for Quality and context. Initial diffusion targets are 30, 90, and 180 seconds respectively for a displayed 512x512, 20-step reference workload. They are product experience targets, not scientific quality claims, and live in one versioned policy definition.

When performance evidence is low-confidence, the UI says “Speed not verified.” A safe memory estimate may still produce an estimated recommendation, but the confidence label and reason must remain visible.

### 6. Confidence

Confidence is derived per dimension from evidence provenance and freshness, not from the category:

```kotlin
data class AssessmentConfidence(
    val compatibility: Confidence,
    val memory: Confidence,
    val storage: Confidence,
    val performance: Confidence,
)
```

- High: verified local file plus successful native preflight and a fresh device snapshot.
- Medium: exact remote variant metadata with a recognized analytical estimator, or local metadata without native preflight.
- Low: inferred quantization/shape, stale device fallback, or no comparable performance calibration.

Critical unknowns do not average into a numeric confidence score. Unknown compatibility or a hard resource bound produces Needs information. Low performance confidence alone produces “Speed not verified” and cannot invalidate an otherwise sufficiently evidenced memory result.

### 7. Policy mapping

When compatibility passes, the preferred plan meets its performance target, and evidence is sufficient:

| Policy fit band | Conservative | Balanced | Experimental |
|---|---|---|---|
| Comfortable | Recommended | Recommended | Recommended |
| Likely | Risky | Usable | Recommended with a tight-fit warning |
| Borderline | Not suitable | Risky | Risky |
| No fit | Not suitable | Not suitable | Not suitable |

A fallback plan is capped at Usable. Missing critical evidence produces Needs information. Missing a noncritical performance target downgrades one level; performance below the minimum acceptable experience for the selected priority produces Not suitable only when prediction confidence is at least Medium. Otherwise it remains Risky with “Performance uncertain.”

Severe current thermal or memory pressure is a transient execution gate, not a permanent model property. The UI shows “Try again when the device cools” or “Close other apps and retry” without rewriting stored model evidence.

## Personalized ranking

`Recommended for me` sorts lexicographically:

```text
category
-> confidence
-> preference utility
-> remaining headroom
-> stable repository/file identifier
```

Safety category always precedes utility. Each available dimension is normalized to `[0.05, 1.0]` using the versioned experience targets. Missing dimensions are omitted and the remaining weights are renormalized; confidence is already an earlier sort key.

Within a category, utility is the weighted geometric mean `exp(sum(weight * ln(metric)))`:

| Priority | Performance | Energy | Quality proxy | Context | Storage efficiency |
|---|---:|---:|---:|---:|---:|
| Speed and efficiency | 60% | 25% | 5% | 0% | 10% |
| Balanced | 30% | 10% | 30% | 20% | 10% |
| Quality and context | 10% | 5% | 55% | 25% | 5% |

Quality uses explicit benchmark evidence when available and parameter/quantization/context values only as labeled proxies. The numeric utility remains internal; the UI shows category, confidence, expected behavior, and reasons.

Hugging Face cannot globally sort by a local device profile. Personalized browsing therefore uses two-stage retrieval:

1. Apply coarse server-side task, format, and parameter filters.
2. Fetch an initial candidate window of 48 models.
3. Enrich variant metadata with bounded concurrency and caching.
4. Rank that window locally with a stable tie-breaker.
5. Add 24 candidates per explicit or scroll-triggered load, with at most 96 enriched candidates retained per query.

Existing popularity and date sorts remain server-backed. The UI makes clear that personalized order covers the candidates CaraML evaluated rather than claiming a global Hugging Face ranking.

## Native preflight

After download, the runner modules expose structured preflight APIs whose ordinary failures do not throw:

```kotlin
sealed interface NativePreflightResult {
    data class Fit(val report: NativeFitReport) : NativePreflightResult
    data class Unsupported(val reason: NativeReason) : NativePreflightResult
    data class InvalidModel(val reason: NativeReason) : NativePreflightResult
    data class Unavailable(val reason: NativeReason) : NativePreflightResult
}
```

The llama runner uses the same `common_fit_params` assumptions as actual loading and reports selected context, GPU layers, KV/cache and compute-buffer estimates, and backend allocation. Stable diffusion uses the vendored auto-fit, maximum-backend-memory, and streaming capabilities to return an equivalent plan. Preflight does not publish a globally loaded handle; transient native resources are released before returning.

No C++ exception crosses JNI or a C ABI. Allocation and parse failures become structured results. Coroutine cancellation remains exceptional and is rethrown unchanged. Native preflight runs away from the UI thread and is serialized per runner.

## Local calibration

CaraML may offer a transparent, cancellable, approximately three-second backend calibration after the first Models profile selection. It is skipped when thermal or battery pressure is active. Declining it leaves conservative cold-start estimates in place.

Actual inference stores only numeric observations:

- Engine and estimator version.
- Backend, architecture family, and quantization family.
- Context/resolution workload bucket.
- Predicted and observed performance.
- Predicted and observed peak memory when the platform can measure it reliably.
- Success, allocation failure, or suspected load-crash outcome.
- Timestamp.

It never stores prompts, generated content, repository credentials, raw private paths, or a hardware serial number.

For comparable observations:

```text
ratio_i = observed_i / analytical_prediction_i
weight_i = configuration_similarity_i * exp(-age_i / 30 days)

likely correction = weighted median(ratio)
high correction = max(1.0, weighted P90(ratio))
```

At least five comparable observations are required before local corrections replace built-in likely bounds. The high-memory correction cannot fall below 1.0. Corrections are keyed by engine version, backend, architecture family, quantization family, and workload bucket. Engine or estimator version changes invalidate affected entries.

Calibration data lives in a separate Room `recommendation_cache.db`, capped at 500 observations and 90 days. It contains derived, disposable data and may be reset if corrupt without touching the existing `AppDatabase` or downloaded-model records. Profile choices and onboarding state remain in DataStore.

## Download and load admission

Before download, CaraML refreshes exact storage, evaluates the selected variant, and suggests a safer variant when possible. Insufficient storage blocks the operation. Incompatible content does not use the normal install action. A runtime no-fit result may expose an explicit “download for later” action after warning that CaraML does not expect it to run on this device.

Immediately before native load, CaraML refreshes memory and thermal state and repeats native preflight when cached evidence is stale. Recommended and Usable plans proceed. Risky plans require explicit confirmation and display the reason. A no-fit preferred plan may offer one known-safe fallback plan and proceeds only after the user accepts it. There is no unbounded retry loop, and user-selected context or diffusion resolution is not silently changed.

If an allocation failure is returned, the runner releases partial state before the repository offers the next safer plan. Generic user-visible messages do not expose native stack traces or filesystem paths.

## Suspected native-crash recovery

Kotlin cannot catch every native abort, signal, Jetsam termination, or process kill. Before native loading, CaraML persists a small marker containing a SHA-256 model-identity digest produced by a platform standard library, configuration digest, engine version, phase, and timestamp. Raw paths are not hash input. It clears the marker immediately after successful load.

On a later launch, an uncleared marker is treated as a suspected load crash, not proof. The exact configuration is temporarily quarantined and the user is offered a safer plan or an explicit retry. A single suspected event does not blacklist the model. Two repeated suspected failures for the same identity, configuration, and engine version mark that configuration Known unstable until the engine/configuration changes or the user explicitly retries. Markers older than seven days expire.

No raw path or model content is stored. Recovery state is bounded and is cleared after successful validation.

## UI behavior

Model cards show one compact category chip. The details view provides:

- Confidence and evidence source.
- Selected variant and assumed workload.
- Memory/storage interval and remaining headroom.
- Expected speed range or “Speed not verified.”
- Preferred or fallback run plan.
- Ordered reason codes translated into concise explanations.

The first visible model list does not wait for enrichment. Cards can progress from “Checking” or Needs information to a stable category. Accessibility content includes category, confidence, and the primary reason without relying only on color.

Changing the profile reruns policy and sorting from cached objective assessments. It does not trigger network calls, native inspection, or calibration.

## Error, security, and concurrency behavior

- All remote metadata is validated before arithmetic, filesystem access, or native parsing (CWE-20/CWE-190/CWE-400).
- Model and revision paths continue through the existing path-containment and download validation policy (CWE-22).
- Checked/saturating arithmetic converts overflow to invalid evidence.
- Unknown and failed collectors reduce confidence; they do not fabricate zero usage or unlimited capacity.
- Coroutine cancellation is rethrown unchanged.
- Assessment work is deduplicated by stable key and cancelled when no consumer remains.
- Native preflight is serialized per runner and never blocks the main thread.
- Logs contain stable reason codes, estimator version, and bounded numeric values, never prompts, generated content, credentials, or private paths.
- Final download/load decisions always use a fresh resource snapshot, preventing stale-cache admission races.

## Caching

- Remote descriptor cache key: repository, immutable revision, and filename/component identity.
- Native preflight key: verified file identity, workload/run-plan fields, backend, engine version, and estimator version.
- Calibration key: backend, architecture family, quantization family, workload bucket, engine version, and estimator version.
- Objective metadata and preflight evidence may be cached.
- Dynamic device state and final personalized categories are recomputed.

File identity uses verified download metadata when available. Raw filename alone is not sufficient. Cache corruption or decode failure discards the entry and returns to conservative estimation.

## Performance budgets

- No network, file parsing, benchmarking, or native work on the UI thread.
- Cached policy reclassification and reranking of 100 candidates: p95 under 100 ms.
- Pure analytical assessment: p95 under 2 ms per variant on the JVM reference environment.
- Resource snapshot collection: p95 under 100 ms, excluding unavailable platform calls that immediately fall back.
- Metadata enrichment uses bounded concurrency of four and supports cancellation.
- Native preflight allows one active operation per runner.
- Optional initial calibration targets three seconds and remains cancellable.
- The first model results render without waiting for assessment enrichment.

## Persistence and compatibility

New DataStore keys default to Balanced risk, Balanced optimization, and onboarding incomplete. Unknown enum strings fall back to Balanced. Existing users see the chooser on their first Models visit after upgrade; no existing inference setting is overwritten.

The existing `SuitabilityRating` remains behind an adapter during staged rollout so current chips and detail components can migrate without a big-bang UI change. The old `ModelSuitabilityCalculator` is removed only after the new release matrix passes.

The main `AppDatabase` schema is not changed by calibration. `recommendation_cache.db` is disposable and independently versioned. This isolates recommendation evolution from downloaded-model ownership records and avoids relying on the main database's current destructive migration fallback.

## Testing

### Common domain tests

Table-driven tests cover every category/profile boundary, exact-byte equality, confidence cap, performance downgrade, fallback-plan cap, and reason-code ordering.

Property tests enforce:

- Increasing required memory never improves a category.
- Increasing available memory never worsens a category.
- Lower confidence never improves a category.
- Experimental is never stricter than Conservative for identical facts.
- Incompatible remains Incompatible under every preference.
- Quality cannot override a hard resource result.
- Candidate search terminates and respects its finite bound.
- Malformed, negative, non-finite, and near-maximum inputs never throw or wrap.

### Estimator fixtures

Versioned fixtures contain a descriptor, device snapshot, workload, predicted interval, observed peak, observed speed, and actual outcome. The corpus covers dense LLMs, MoE/hybrid/recurrent models, representative GGUF quantizations, SD1.x, SDXL, Flux/DiT families, component bundles, unified memory, and discrete GPU memory.

### Platform and integration tests

Injectable platform adapters simulate low memory, missing APIs, stale state, backend registration failure, thermal pressure, and storage changes. UI tests cover onboarding persistence, profile changes, progressive category rendering, ranking stability, warnings, and accessibility semantics.

A separate native CI job compares preflight with actual loads and upstream fitting behavior using license-safe tiny/golden fixtures. It covers corrupt input, unsupported formats, cancellation, allocation failure, and cleanup. The normal `verifyProject` task remains the fast JVM/KMP gate.

Crash-recovery tests terminate a test process during native loading, verify marker detection, confirm only the exact configuration is quarantined, and prove a successful safer load clears recovery state.

## Staged rollout

1. **Foundation:** validated descriptors, snapshots, estimators, policy types, reason codes, and legacy adapter with no visible behavior change.
2. **Shadow mode:** old and new algorithms run side-by-side in debug builds; differences remain local and inspectable.
3. **Details and admission:** new variant details, download storage gate, post-download native preflight, and load admission.
4. **Personalized Models tab:** first-open preferences, Recommended for me, and editable profile UI.
5. **Calibration and recovery:** local correction factors, initial optional benchmark, and suspected native-crash handling.
6. **Legacy removal:** remove the old calculator only after all release criteria pass.

No remote feature-flag or analytics service is required.

## Acceptance criteria

- No Recommended plan OOMs in the representative release matrix across repeated cold loads under normal device conditions.
- With at least twenty comparable samples in a calibration bucket, the high estimate covers at least 95% of observed memory peaks.
- With at least five comparable samples, median absolute performance error is at most 25% for LLM and 30% for diffusion reference workloads.
- Every recommendation exposes at least one stable human-readable reason and its confidence.
- Malformed metadata and arithmetic extremes return structured invalid/unknown results without exceptions.
- Profile changes rerank cached assessments without metadata refetch or native work.
- Dynamic storage, memory, and thermal state are refreshed at admission boundaries.
- Existing settings and downloaded-model records survive the update unchanged.
- A suspected native-load crash produces a recoverable next launch and a safer-plan option.
- Recommendation work does not block first paint or the main thread.
- Existing download and inference behavior remains green under `verifyProject` and platform build gates.

## Verification commands

```bash
./gradlew verifyProject
./gradlew :composeApp:allTests
./gradlew :composeApp:assembleDebug
```

Native preflight parity jobs additionally rebuild the desktop runner and platform native bridges appropriate to the CI host.

## Stop condition

Implementation is complete when the staged v2 path is the sole source of displayed recommendation categories, the legacy calculator is removed, the release fixture/device matrix satisfies the acceptance criteria, and all supported platform gates pass. Cloud telemetry, automatic model downloading, unrelated database refactors, and general inference tuning remain out of scope.
