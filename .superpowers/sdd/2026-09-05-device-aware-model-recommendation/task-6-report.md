# Task 6 report — constraint-first recommendation policy

Status: complete.

## Implementation

- Added a validated roofline `PerformanceEstimator` with backend profiles, correction keys, immutable per-range provenance/confidence, bounded outputs, honest unknowns, and non-blocking video estimates.
- Added a stateless/profile-neutral `SuitabilityEngine` that checks compatibility first, estimates every bounded candidate, and assembles current resource budgets separately.
- Added `RunPlanOptimizer` with checked profile reserves, independent host/GPU/shared pool fitting, storage gating, exact weighted-geometric utility vectors, confidence/fallback/performance caps, and candidate-order tie-breaking.
- Added `RecommendationPolicy` hard-gate precedence, fresh-snapshot enforcement, ordered reasons, personalization, and lexicographic sort keys.
- Extended objective assessment contracts only with immutable performance/utility evidence and two-phase assessment context; user profile/category ownership remains solely in personalization.

## TDD evidence

Initial RED command:

`./gradlew :composeApp:jvmTest --tests '*PerformanceEstimatorTest*' --tests '*SuitabilityEngineTest*' --tests '*RunPlanOptimizerTest*' --tests '*RecommendationPolicyTest*' --tests '*RecommendationPolicyPropertyTest*'`

Result: expected `compileTestKotlinJvm` failure on unresolved Task 6 production types and methods (`PerformanceEstimator`, `SuitabilityEngine.assessPlans/assemble`, `RunPlanOptimizer`, `RecommendationPolicy`, performance/utility fields, and policy reasons). Production files had not been created.

Self-review RED command:

`./gradlew :composeApp:jvmTest --tests '*RunPlanOptimizerTest*' --tests '*RecommendationPolicyTest*'`

Result: 19 tests executed, 2 expected behavioral failures proving direct optimizer incompatibility and expired/future capture timestamps were not yet enforced.

Per-range provenance/input-validation RED command:

`./gradlew :composeApp:jvmTest --tests '*PerformanceEstimatorTest*'`

Result: expected test compilation failure because `PerformanceRange.evidence` and `PERFORMANCE_ESTIMATED` did not yet exist.

Profile-ownership audit RED command:

`./gradlew :composeApp:jvmTest --tests '*RunPlanOptimizerTest*'`

Result: 8 tests executed, 1 expected behavioral failure proving performance utility still used a profile-neutral placeholder instead of the selected profile's versioned target. The fix moved target normalization exclusively into the optimizer and removed the Balanced target from static assessment.

Final focused GREEN command:

`./gradlew :composeApp:jvmTest --tests '*PerformanceEstimatorTest*' --tests '*SuitabilityEngineTest*' --tests '*RunPlanOptimizerTest*' --tests '*RecommendationPolicyTest*' --tests '*RecommendationPolicyPropertyTest*'`

Result: PASS, 37 tests, `BUILD SUCCESSFUL in 9s`.

Tasks 1–6 regression command:

`./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.recommendation.*'`

Result: PASS, 143 tests across 15 suites, `BUILD SUCCESSFUL in 2s`.

iOS simulator compile command:

`./gradlew :composeApp:compileKotlinIosSimulatorArm64`

Result: PASS, `BUILD SUCCESSFUL in 24s`.

## Files

- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/AssessmentModels.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/PerformanceEstimator.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationPolicy.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RecommendationProfile.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/RunPlanOptimizer.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/SuitabilityEngine.kt`
- Five corresponding Task 6 common test classes.

## Self-review and security

- Verified incompatibility and critical unknowns precede fit/utility/profile logic in both public policy and optimizer entry points.
- Verified static assessment contains no selected-profile target or category; performance utility is normalized only in the optimizer using the selected profile's versioned target.
- Verified current resources are never added across pools; each required host/GPU/shared interval uses its matching checked integer policy budget.
- Verified final recommendation requires both the trusted freshness flag and a timestamp within the versioned 30-second window.
- Verified storage is profile-independent, missing upper bounds fail closed, no-fit results cannot be upgraded, and low performance confidence cannot create a strong claim.
- Validated all public numeric inputs before division/multiplication, rejected non-finite/nonpositive values, bounded serialized results, and returned stable structured reasons without sensitive data.
- All new collection-bearing contracts defensively snapshot caller collections.
- No credentials, paths, prompts, generated content, network work, native work, or dynamic execution were added.

## Staging audit

- Interactively staged exactly the 11 Task 6 recommendation paths listed above; no unrelated P0/P1 path entered the index.
- Inspected the entire cached diff, including all six production files and all five test files.
- `git diff --cached --check` passed with no whitespace errors.
- The pre-commit hard-coded-secret scan passed.
- After commit, the Task 6 paths are clean and the index is empty; unrelated dirty work remains untouched.

## Commit

`8f37b8a0548d01e05d6a54e03ad43cd677632dfb` — `feat(recommendation): classify device fit by policy`

## Concerns

- No blocker. `NoCalibrationSource` intentionally keeps speed unknown until Task 14 supplies validated backend calibration.
- Video performance intentionally remains non-blocking until a comparable calibration bucket exists.

---

# Fix Round 1 — confidence provenance and adversarial policy hardening

Status: complete; ready for scoped commit.

## Implementation

- Added immutable host/GPU/shared/storage confidence to raw resource snapshots and derived device budgets. Unified budgets use the weakest contributing evidence, discrete pools remain independent, missing confidence suppresses the corresponding safe budget, and the JVM total-memory fallback is explicitly Low confidence.
- Made selection confidence candidate-local: only the selected plan's compatibility, memory, storage, matching device-pool budget, and storage-budget confidence participate. Aggregate confidence from unused candidates no longer caps a selected plan.
- Added full assessment-graph validation before fitting: outer/inner identity and compatibility, assessed and candidate topology, every candidate's plan/performance type, and current AVAILABLE backend capability including CPU.
- Reconciled performance wrapper confidence with the exact policy-compared range. Contradictions add structured invalid evidence and use the weaker confidence; Low-confidence hard misses remain Risky with uncertainty rather than becoming confidently Not Suitable.
- Required a bounded validated engine version before calibration-key construction or correction lookup. Calibration keys now partition corrections by engine version; absent versions remain honest Unknown.
- Versioned all utility weights, quality proxy coefficients/maps, storage/parameter normalization targets, and utility clamp bounds in `RecommendationPolicyV1`. Quality is descriptor-derived from exact parsed LLM quantization and validated parameter scale, or typed diffusion architecture/exact quantization; absent/unknown dimensions are omitted and weights renormalized. Evidence explicitly labels the result a non-benchmark proxy.
- Added independent deterministic 1,000-seed monotonic properties for compatibility, selected-plan memory, snapshot resource, and storage confidence, plus generated High/Medium-to-Low hard-slow comparisons.
- Reused one optimizer selection inside each policy call path so recommendation category and sort key cannot cross a freshness boundary.

## TDD evidence

- Resource confidence RED: `./gradlew :composeApp:jvmTest --tests '*DeviceSnapshotProviderTest*' --tests '*RunPlanOptimizerTest*'` failed at test compilation on missing `ResourcePoolConfidence`, `ResourceSnapshot.confidence`, and `DeviceSnapshot.budgetConfidence`. After implementation, 23 focused tests passed.
- Graph/backend RED: `./gradlew :composeApp:jvmTest --tests '*RecommendationPolicyTest*'` failed at test compilation on the missing `ASSESSMENT_GRAPH_INVALID` and `DEVICE_CAPABILITIES_CHANGED` contracts. After graph validation, all then-current 16 policy tests passed.
- Performance type/confidence RED: the two focused policy adversarial tests executed and both failed because mismatched performance types and contradictory confidences were still accepted. Both passed after exact-range/type reconciliation.
- Engine-version RED: `./gradlew :composeApp:jvmTest --tests '*PerformanceEstimatorTest*'` failed at test compilation because the fake's required `engineVersion()` contract did not exist. All 9 estimator tests passed after version-key partitioning.
- Quality RED: the three focused quality tests executed with 2 behavioral failures for missing LLM/diffusion descriptor proxies. A later descriptor-only omission test failed 1/1 because the old plan-only KV proxy still produced quality without descriptor evidence. Both cycles passed after the profile-neutral descriptor proxy implementation.
- Versioned-constant RED: the focused policy-fixture test failed at compilation on missing `UTILITY_METRIC_MIN/MAX`; it passed after moving both remaining clamp constants into V1.
- Single-selection RED: the focused freshness-boundary test executed 1 test with 1 failure because `sortKey` read the clock twice. It passed after sharing one internal selection result.
- Property additions were already GREEN when first run because the preceding resource/performance production fixes supplied the required monotonic behavior; they add four independent randomized 1,000-seed confidence dimensions and the generated hard-slow confidence comparison without further production changes.

Final focused gate:

`./gradlew :composeApp:jvmTest --tests '*PerformanceEstimatorTest*' --tests '*SuitabilityEngineTest*' --tests '*RunPlanOptimizerTest*' --tests '*RecommendationPolicyTest*' --tests '*RecommendationPolicyPropertyTest*'`

Result: PASS, 54 tests, 0 skipped/failures/errors, `BUILD SUCCESSFUL in 2s`.

Recommendation regression:

`./gradlew :composeApp:jvmTest --tests 'com.debanshu777.caraml.core.recommendation.*'`

Result: PASS, 164 tests across 15 suites, 0 skipped/failures/errors, `BUILD SUCCESSFUL in 2s`.

Device snapshot cross-check:

`./gradlew :composeApp:jvmTest --tests '*DeviceSnapshotProviderTest*' --tests '*DeviceSnapshotPolicyTest*' --tests '*DeviceSnapshotJvmPolicyTest*'`

Result: PASS, 24 tests, 0 skipped/failures/errors, `BUILD SUCCESSFUL in 1s`.

iOS simulator compile:

`./gradlew :composeApp:compileKotlinIosSimulatorArm64`

Result: PASS, `BUILD SUCCESSFUL in 22s`.

## Files

- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/platform/DeviceSnapshot.kt`
- `composeApp/src/{androidMain,iosMain,jvmMain}/kotlin/com/debanshu777/caraml/core/platform/DeviceCapabilities.*.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/recommendation/{AssessmentModels,DeviceSnapshotProvider,PerformanceEstimator,RecommendationPolicy,RecommendationProfile,RunPlanOptimizer,SuitabilityEngine}.kt`
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/recommendation/{DeviceSnapshotProviderTest,PerformanceEstimatorTest,RecommendationPolicyTest,RecommendationPolicyPropertyTest,RunPlanOptimizerTest,SuitabilityEngineTest}.kt`
- `.superpowers/sdd/2026-09-05-device-aware-model-recommendation/task-6-report.md`

## Self-review and security

- Confirmed profiles/categories remain outside static `SuitabilityEngine` assessment and are applied only by optimizer/policy personalization.
- Confirmed compatibility/graph/backend/freshness and missing upper-bound gates precede fit or utility, and all candidates are validated before any candidate can be selected.
- Confirmed host/GPU/shared pools are never summed; checked integer reserve arithmetic and worst-pool headroom remain independent. Storage reserve remains profile-independent.
- Confirmed an unused candidate cannot lower selected-plan confidence, while missing matching budget confidence fails closed as Needs information.
- Confirmed performance evidence is plan-kind checked, exact-range confidence is never strengthened by its wrapper, and Low-confidence slowness cannot create a strong Not Suitable claim unless a separate hard safety constraint already does.
- Confirmed engine-version strings are length/character bounded before key creation, corrections are version-partitioned, numeric ranges remain finite/positive/capped, and no core-count speed heuristic was introduced.
- Confirmed public collection-bearing contracts defensively snapshot inputs; no personalized category entered objective assessment/cache state.
- No secrets, network access, external paths, dynamic execution, or user-controlled logging were added.

## Staging audit

- Interactively staged exactly the 17 implementation/test paths above plus this report; unrelated P0/P1 dirty work remains excluded.
- Inspected the entire cached diff in bounded production/test sections; `git diff --cached --check` passed with no whitespace errors.

## Commit

Planned message: `fix(recommendation): harden device fit evidence policy` (this report is part of that commit; SHA returned in the handoff).

## Concerns

- No Task 6 blocker. `NoCalibrationSource` remains intentionally uncalibrated until Task 14 supplies a validated engine version and measurements.
- Video performance remains intentionally non-blocking until comparable video calibration exists.
