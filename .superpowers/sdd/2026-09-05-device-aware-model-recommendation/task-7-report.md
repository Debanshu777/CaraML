# Task 7 Report — Assessment Repository and Shadow Comparison

## Outcome

Implemented a profile-neutral assessment repository that caches only immutable `AssessedPlans`, assembles every `ModelAssessment` from the caller's current snapshot, and keeps personalization outside the cache. Equal work is single-flight with bounded LRU storage, reference-counted consumers, final-consumer cancellation, safe invalidation, and exact propagation of computation failures and cancellations.

Added privacy-safe `LEGACY`, `SHADOW`, and `V2` rollout coordination. Debug Android/iOS/desktop-run integrations select `SHADOW`; release and all missing/invalid build signals fail safe to `LEGACY` until Task 15. Shadow records contain only a validated stable content digest (or `unavailable`), enum names, bounded reason codes, and a bounded estimator version.

## TDD Evidence

### RED

The complete focused repository and adapter tests were written before their production types.

Command:

```text
./gradlew :composeApp:jvmTest --tests '*ModelAssessmentRepositoryTest*' --tests '*LegacySuitabilityAdapterTest*'
```

Result: `BUILD FAILED` at `:composeApp:compileTestKotlinJvm`, with unresolved references to the not-yet-created `ModelAssessmentRepository`, `assessmentCacheKey`, `LegacySuitabilityAdapter`, rollout-mode, and shadow-recording types.

Additional focused regressions were observed RED before their fixes:

- true LRU hit recency and repository-driven in-flight invalidation;
- rejection of unsafe direct descriptor identities from cache keys;
- isolation of first-caller descriptor/hardware evidence from equal-key cache hits;
- rejection of unverified content-ID prefixes before shadow digesting;
- Koin rollout binding, which failed with `NoDefinitionFoundException` before the mode source and adapter were registered.

### GREEN

Final focused command:

```text
./gradlew :composeApp:jvmTest --tests '*ModelAssessmentRepositoryTest*' --tests '*LegacySuitabilityAdapterTest*'
```

Result: `BUILD SUCCESSFUL` (21 tests: 14 repository and 7 adapter tests, zero failures).

Full recommendation command:

```text
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.recommendation.*'
```

Result: `BUILD SUCCESSFUL`.

KMP/platform command:

```text
./gradlew :composeApp:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug
```

Result: `BUILD SUCCESSFUL` (130 actionable tasks). Existing native configuration warnings about unavailable OpenSSL/OpenGL and existing expect/actual beta warnings remained non-fatal.

The plan's `:composeApp:assembleDebug` task is not available for the Android multiplatform library. The supported application assembly used here is `:androidApp:assembleDebug`.

## Decisions

- Cache identity uses a normalized descriptor, every workload field that can affect planning, a static hardware fingerprint, validated engine version, estimator version, and calibration revision.
- Direct public descriptors are normalized and checked against the factory's repository/revision/path/object-ID bounds before becoming keys. Evidence and dynamic resource/headroom fields are never retained in keys.
- Shared computation receives only the exact normalized descriptor/workload/static hardware represented by its key, preventing arbitrary first-caller evidence from entering cached results.
- Repository state uses immutable, maximum-128 maps/lists coordinated with common-Kotlin `AtomicReference` compare-and-set. This avoids a contended platform-thread mutex spin while keeping `invalidate` synchronous.
- Completed entries use remove/reinsert access ordering for a true maximum-128 LRU. Unique in-flight work is independently capped at 128, so caller-controlled keys cannot grow an unbounded registry.
- A shared deferred is cancelled only after its final consumer leaves. Invalidation atomically removes matching completed and in-flight entries before cancelling detached work. Failed and cancelled results are never cached.
- Computations return an internal success/failure value so the original `Throwable`, including `CancellationException`, is rethrown without coroutine stack-recovery replacement.
- Shadow logs never use repository IDs, paths, prompt/user content, metadata detail, or raw repository identity.
- Koin temporarily binds exactly one `UnknownEngineCapabilitySource`, `NoCalibrationSource`, `CompatibilityChecker`, `SuitabilityEngine`, `RecommendationPolicy`, repository, rollout source, and legacy adapter.

## Task 7 Files

- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelAssessmentRepository.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapter.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationRolloutMode.kt`
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/ModelAssessmentRepositoryTest.kt`
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapterTest.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/di/AppModule.kt` (Task 7 hunks only)
- `composeApp/src/androidMain/kotlin/com/debanshu777/caraml/core/di/AppModule.android.kt`
- `composeApp/src/iosMain/kotlin/com/debanshu777/caraml/core/di/AppModule.ios.kt`
- `composeApp/src/jvmMain/kotlin/com/debanshu777/caraml/core/di/AppModule.jvm.kt`
- `composeApp/build.gradle.kts` (desktop-run debug-mode hunks only)
- `README.md` (Task 7 Recent Changes bullet only)
- `composeApp/README.md` (Task 7 Recent Changes bullet only)
- `.superpowers/sdd/2026-09-05-device-aware-model-recommendation/task-7-report.md`

## Staging Audit

The Task 7 files and only Task 7 hunks in overlapping files were staged. In particular, the pre-existing `GeneratedMediaStore` import/factory/`ChatViewModel` argument in `AppModule.kt`, the pre-existing coroutines-test dependency in `composeApp/build.gradle.kts`, and all other working-tree changes remain unstaged. The cached diff and `git diff --cached --check` were inspected before commit.

## Remaining Concerns

- Production capability and calibration sources deliberately remain conservative placeholders; Task 11 replaces the capability binding and a later task supplies accepted calibration data.
- Release remains `LEGACY` until Task 15 explicitly promotes the rollout.
- `SHADOW` performs both computations by design and should remain debug-only until its performance and parity gates pass.
