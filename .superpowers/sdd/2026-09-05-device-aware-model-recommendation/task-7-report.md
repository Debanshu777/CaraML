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

## Fix Round 1 — Cache Containment and Observational Shadowing

### Review Findings Verified

- The clean Task 7 commit was not self-contained: its common tests import `kotlinx.coroutines.test`, while `HEAD:composeApp/build.gradle.kts` contained only `libs.kotlin.test` in `commonTest`. This round owns and commits the exact `implementation(libs.kotlinx.coroutinesTest)` dependency hunk.
- A null cache key previously ran `assessmentComputer` directly on the caller's original descriptor. Malformed or oversized descriptors and calibration-source failures could therefore bypass both normalization and the maximum-128 in-flight registry (CWE-400).
- In `SHADOW`, ordinary v2 or recorder failures previously escaped after legacy had succeeded, changing observable legacy behavior.
- The default shadow recorder used `AppLogger.d`, but the production logger defaults to `INFO`; the real debug Koin path therefore discarded every default shadow record.

### TDD Evidence

The focused regressions were added before the production fixes. The RED command was:

```text
./gradlew :composeApp:jvmTest --tests '*ModelAssessmentRepositoryTest*' --tests '*LegacySuitabilityAdapterTest*'
```

Result: `BUILD FAILED` with 29 tests run and five expected failures. The failures proved that an oversized direct diffusion descriptor reached assessment work, concurrent calibration failures created uncached work, ordinary v2 and recorder exceptions escaped `SHADOW`, and the real debug Koin/default-recorder path emitted no record at the normal `INFO` threshold. The calibration-change, cancellation-identity, and fatal-`Error` characterizations already passed.

After the minimal fixes and the final cancellation regression, the exact focused command completed `BUILD SUCCESSFUL` (31 tests: 19 repository, 11 common adapter, and 1 JVM integration test; zero failures). The complete recommendation-package gate also completed `BUILD SUCCESSFUL`:

```text
./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.recommendation.*'
```

The required platform gate completed `BUILD SUCCESSFUL` (130 actionable tasks):

```text
./gradlew :composeApp:compileKotlinIosSimulatorArm64 :androidApp:assembleDebug
```

Only the existing non-fatal native OpenSSL/OpenGL notices and expect/actual beta warnings were observed.

### Fix Decisions

- Descriptor, workload, and static hardware normalization now succeeds before calibration is consulted. Any invalid direct input returns a fresh, structured unknown assessment without invoking compatibility or estimation work.
- Ordinary calibration-source failure or an invalid calibration state also returns a fresh structured unknown assessment; it cannot create uncapped work. `NoCalibrationSource` retains its stable validated sentinel and still benefits from single-flight/LRU caching.
- Valid assessment work has one path: the bounded maximum-128 single-flight registry, using only normalized immutable inputs retained by the cache key. Cancellation is rethrown by identity before work begins.
- Before publishing a completed value, the repository verifies that engine version and calibration revision are still current. An older in-flight completion cannot replace or be served as the new revision's result.
- `SHADOW` catches only ordinary `Exception` from v2 calculation or recording after legacy succeeds. It returns legacy unchanged, rethrows `CancellationException` by identity, leaves fatal `Error` unsuppressed, and does not log exception messages or payloads.
- The private default recorder writes the already bounded privacy-safe record directly to the platform debug sink. This bypasses the general logger's `INFO` threshold only inside an explicitly selected `SHADOW` execution; release mode remains fail-safe `LEGACY`.
- A JVM integration test resolves the real adapter from the debug Koin configuration, holds `AppLogger` at `INFO`, captures the platform record, and verifies that repository IDs, paths, and metadata detail are absent.

### Fix Round 1 Files

- `composeApp/build.gradle.kts` (`commonTest` coroutines-test dependency hunk only)
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/ModelAssessmentRepository.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapter.kt`
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/ModelAssessmentRepositoryTest.kt`
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapterTest.kt`
- `composeApp/src/jvmTest/kotlin/com/debanshu777/caraml/core/recommendation/LegacySuitabilityAdapterTestJvm.kt`
- `.superpowers/sdd/2026-09-05-device-aware-model-recommendation/task-7-report.md`

### Dependency and Staging Audit

- `git show HEAD:composeApp/build.gradle.kts` confirmed that the clean base omitted `libs.kotlinx.coroutinesTest`; `git show :composeApp/build.gradle.kts` confirmed that the staged version includes it in `commonTest`.
- The seven Fix Round 1 paths/hunks above were staged interactively. The complete cached diff contains no `AppModule.kt`, GeneratedMediaStore change, runtime-containment work, or other unrelated dirty file.
- `git diff --cached --check` completed with no whitespace errors. All unrelated user work remains unstaged.

### Deferred Review Minors

- Backend selection priority is intentionally unchanged in this round.
- Stable-digest padding behavior is intentionally unchanged in this round.

## Fix Round 2 — Version-Catalog Closure

- Verified clean `b5aa8f1` contained `implementation(libs.kotlinx.coroutinesTest)` in `composeApp/build.gradle.kts` but no `kotlinx-coroutinesTest` alias in `gradle/libs.versions.toml`, so the Task 7 test dependency was not resolvable from a clean checkout.
- Added exactly `kotlinx-coroutinesTest = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "kotlinx-coroutines" }` to the staged catalog. The adjacent dirty `kotlinx-coroutinesCore` alias and trailing blank-line change remain unstaged.
- The exact focused Task 7 command and the full `com.debanshu777.caraml.core.recommendation.*` JVM suite both completed `BUILD SUCCESSFUL`.
- HEAD/index inspection confirms the build-script reference and catalog alias are both present in the commit candidate. The cached diff contains only this alias and this report section; `git diff --cached --check` is clean.
