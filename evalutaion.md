# Generic inference library: SDK evaluation and CaraML migration checks

Date: 2026-10-14

Status: Implementation in progress; portable module and native feasibility checks are being recorded. Full migration acceptance remains pending. Publication is deferred by user instruction.

2026-10-14 corrective implementation: the native bridges now live inside
`:plugin-llama` and `:plugin-diffusion`; the separate runner Gradle projects are
removed. Regression and build evidence is tracked in
[implementation evidence](docs/sdk/implementation-evidence.md#corrective-implementation--2026-10-14).
These fixes do not implicitly complete any pending device, publication or migration gate. The follow-up provider consolidation also retires
`huggingFaceManager`: Hub APIs now live in discovery, with existing native storage,
platform downloads and curated install rules in the app. Provider extension uses
`ModelProvider`/`ArtifactSource`; one facade selects one provider. This preserves
SDK-only isolation and does not imply federated search or completed P7 adoption.

Primary design: [architecture-plan.md](architecture-plan.md)

The filename follows the requested `evalutaion.md` spelling. This document breaks the architecture into reviewable steps and records what evidence is needed to accept each one. Checking a box requires evidence, not just moving files.

**Scope:** SDK acceptance proves that any supported consumer can use the library without CaraML code, data or UI. CaraML migration evidence separately proves preservation of that application's existing installation. Host-only checks never impose CaraML filenames, schemas, preference storage or UI on library consumers. See [the design's consumer migration appendix](architecture-plan.md#appendix-a-caraml-only-migration-requirements).

## 1. Evaluation method

Use these states: **Not started**, **In progress**, **Passed**, **Failed**, **Blocked**, or **Not applicable with reason**. A missing toolchain/device is Blocked, not Passed. Record existing baseline failures separately from new regressions. Contract tests, compilation, native linkage, runtime behavior, and artifact inspection are different evidence types and cannot substitute for one another.

For each phase, record:

```text
Phase / criterion:
Scope: SDK / generic host integration / CaraML migration
Status:
Commit or patch identity:
Platform / architecture / toolchain:
Consumer configuration:
Command or manual procedure:
Discovered / executed / skipped tests (or N/A for non-test evidence):
Expected result:
Actual result:
Evidence path:
Known limitations:
Next action / responsible owner:
```

Keep logs and artifact inventories in a dedicated evaluation output directory chosen during implementation. Do not commit model weights, credentials, or live user databases as evidence. Use purpose-built fixture databases and redact sensitive runtime paths where needed.

### Review finding traceability

The 2026-10-14 architecture review added the following gates. **Addressed in planning does not mean verified in implementation.** See the design's review table for source evidence and rationale.

| Finding | Required evidence | Gate |
|---|---|---|
| R1 — Android runtime/identity migration | Old-app queued work survives upgrade; background-only startup works in a host without Koin | P3/P6/P7 |
| R2 — Native ownership | Two core clients cannot corrupt/cancel each other's session; combined resource reservations cannot oversubscribe | P1/P4/P5 |
| R3 — JNI and R8 | Optimized external APK exercises native field/class lookups and storage JNI | P2/P8/P9 |
| R4 — CaraML-only shared DataStore migration | CaraML retains one owner for its existing file and preserves preference/recovery keys; SDK clients work without that file or any preferences repository | CaraML P3/P7; SDK isolation P1/P9 |
| R5 — Flow/lifecycle semantics | Single execution, slow/absent collectors, operation-scoped cancellation, native completion before disposal | P1/P4/P7 |
| R6 — Native-free planning/identity | Non-Git provider revisions and local identities work; a full explicit diffusion bundle installs without native inference | P1/P6 |
| R7 — Storage guarantees | Store conformance, fenced leases, leased files, import interruption and partial restore checks | P3/P5 |
| R8 — Android native distribution | Supported ABIs, 16 KB checks, AAB-derived installed backends, compatible shared runtime | P2/P8 |
| R9 — Early publication/API evolution | P2 published vertical slice, API maturity/deprecation policy, minimum consumer-toolchain tests, API signatures and compatible binary-consumer test; release maintenance owner | P1/P2/P9/P10 |
| R10 — Build configuration/native selection | API/core JVM checks without native tools/submodules; standalone native AAR selection with production metadata; Android tests actually execute | P1/P2/P8 |
| R11 — Extensible plans/options | Separately compiled fake plugin supplies custom metadata/options through admission and execution without core edits; stale/mismatched plans fail; external authors can run versioned conformance tests | P1/P4/P5/P9 |
| R12 — Recommendation ownership | Neutral metadata/ranking boundaries; cancelled searches and stable pagination preserved; engine-free browsing gives unavailable assessment | P4/P6/P7 |
| R13 — Text-session semantics | No history replay/double templating; settings snapshots and token bounds respected; interrupted summarization cannot expose a damaged ready session | P1/P5/P7 |
| R14 — Platform defaults and pressure | External local consumer uses real resource readings without Koin/provider/storage implementation dependencies; pressure policy works without legacy trim callbacks | P1/P2/P4/P7 |
| R15 — Shared native runtime | Both init orders, overlapping probe/generation attempts, close-one/use-the-other and reopen preserve shared backend and callback ownership | P2/P5/P8 |
| R16 — Media output lifecycle | Peak output/copy memory and disk are accounted for; terminal success requires full publication; cancellation/write failure cannot leak or expose partial results | P1/P3/P5/P7 |
| R17 — Fault boundary and recovery evidence | Process exit is classified without inventing model causality; unknown/user/OS interruptions do not become known instability; a second live client cannot recover another client's marker | P1/P4/P7 |
| R18 — Reproducible native capabilities | Explicit backend/CPU profiles, oldest claimed CPU coverage and clean-consumer execution; optional build-machine tools cannot silently change published capabilities | P0/P2/P8 |
| R19 — Unicode bridge contract | Deterministic non-ASCII/split-byte fixtures through actual JNI with CheckJNI; corresponding JVM/iOS behavior and explicit malformed/NUL handling | P1/P5/P8 |
| R20 — Native coexistence and backend boundary | SDK-owned backend inventory, binary/export/STL policy and an external second-library fixture; foreign candidates are never initialized by SDK discovery | P2/P8/P9 |
| R21 — Host transport policy | Built-in provider and transfers use the configured transport, preserve host network policy, bound total attempts and close only owned requests/resources | P1/P6/P9 |

R10–R21 are follow-up planning findings, not observed migration regressions. Record the implementation decision and test evidence separately; adding these requirements does not pass a phase.

## 2. Phase tracking

Implementation evidence is tracked in [docs/sdk/implementation-evidence.md](docs/sdk/implementation-evidence.md). Prototype work does not satisfy a phase exit gate; the full native and consumer migration criteria remain unchecked.

| Phase | Evaluation question | Status |
|---|---|---|
| P0 | Do we know the current behavior, data formats, dependencies, and native contents? | In progress; see implementation evidence |
| P1 | Are contracts independent of engines, UI, and persistence frameworks? | In progress; see implementation evidence |
| P2 | Can native engine artifacts be selected safely across platforms? | In progress; see implementation evidence |
| P3 | Can generic storage run independently, with CaraML upgrade evidence tracked separately? | In progress; see implementation evidence |
| P4 | Can core work with arbitrary registered implementations? | In progress; see implementation evidence |
| P5 | Do extracted plugins preserve supported inference behavior? | In progress; see implementation evidence |
| P6 | Is discovery genuinely provider-neutral and recoverable? | In progress; see implementation evidence |
| P7 | Does CaraML's consumer migration preserve its UX/data without adding app code to the SDK? | Not started |
| P8 | Are unused engines absent from builds and packages? | Not started |
| P9 | Can another project consume published library artifacts? | Deferred by user; focus on module split |
| P10 | Is the migration ready to release and maintain? | Not started |

## 3. P0 — Baseline and scope

### Actions

- [ ] Capture current Gradle project/dependency graphs for common code and each platform, including debug-only dependencies.
- [ ] Record exact upstream gitlinks, applied patches, supported targets, native build flags, and toolchains.
- [ ] Record actual backend/CPU compiler definitions and optional tool/header detection. Resolve the Android diffusion Vulkan setting/comment conflict from build/runtime evidence; identify every host-tuned binary before considering publication.
- [ ] Derive the toolchain baseline from current Gradle files/catalog rather than older prose: inspected catalog declares AGP 9.4.1, Kotlin 2.4.21, compileSdk 37 and targetSdk 36; record wrapper-specific overrides too.
- [ ] Decide initial consumer languages and target scope before packaging work. If direct Swift consumption is in scope, assign an early framework/API bridging spike; otherwise explicitly advertise Kotlin/KMP consumption only.
- [ ] Record the first release's capability/target/ABI scope and distinguish it from the full migration matrix. Use the external Android/Kotlin local llama consumer as the first proof; Java interoperability, direct Swift and untested platforms/capabilities are not implied by artifact availability.
- [ ] Run existing JVM/native gates and classify existing failures.
- [ ] Record native files in a representative Android package, desktop distribution, and iOS archive/link output.
- [ ] Capture candidate generic library records/formats separately from CaraML's legacy schemas, database paths, preference keys and migration fixtures.
- [ ] Define representative text, image, and supported video fixtures and usage procedures.
- [ ] Record baseline memory, load time, streaming behavior, and generation performance under repeatable conditions.
- [ ] For media, measure native output, encoding/bridge copies, retained Kotlin frames and file publication separately; record result frame order/rate and existing cache limits. Preserve the measured memory peak when evaluating a temporary all-frames adapter.
- [ ] Agree acceptable regression thresholds before collecting post-migration measurements.
- [ ] CaraML migration: record its Android worker/service identities, work names, extras, stop markers, notification actions, manifest entries, backup rules and initialization order.
- [ ] CaraML migration: capture fixture data from every supported prior app release; define its upgrade window and unsupported-version behavior.
- [ ] CaraML migration: record baseline rotation, navigation, backgrounding, multi-window, memory-pressure and process-death behavior; distinguish known limitations from intended guarantees.
- [ ] Record current unfinished-load marker/quarantine behavior and approve the R17 policy as an intentional behavioral change. Baseline parity must not require misclassifying a user/OS interruption as confirmed model instability.
- [ ] Define a repeatable measurement protocol: model hash, prompt/seed, execution plan, device/OS, thermal state, cold/warm cache, repetitions and summary statistics. Include peak process/native memory, time to first token, cancellation latency, startup cost, and package size alongside throughput.

### Existing entry points

```bash
./gradlew verifyProject
./gradlew :androidApp:assembleDebug
./gradlew :nativeEngine:compileLlamaRunnerDesktop
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64
```

These are inspected current task names, not commands executed by this documentation change. `verifyProject` is not a complete platform gate. Discover available hardening/native parity tasks before using them and record fixture/network requirements explicitly. For iOS, archive creation must be followed by application/framework linkage and runtime checks where hardware is available.

### Pass condition

The team can distinguish a migration regression from a pre-existing issue and has reproducible data/performance fixtures. Stop downstream acceptance if the baseline is unknown.

## 4. P1 — Neutral contracts

- [ ] `:core-api` compiles for the declared target matrix without Compose, Room, DataStore, Ktor DTOs, runners, or inference native libraries.
- [ ] Public model/load types contain no `LocalModelEntity`, native handles, or engine-specific event types.
- [ ] SDK artifacts contain no CaraML `AppSettings`, `ChatMessage`, presentation/onboarding/theme types, UI resources or legacy app schema definitions. Dependencies point from consumers to the SDK, never back to `:composeApp` or `:androidApp`.
- [ ] Inference accepts explicit typed session/request options and requires no preferences repository or `app.preferences_pb`. Host preference changes do not mutate an admitted operation.
- [ ] Prototype builder-based inference and discovery clients independently. Minimal text inference requires no download/media/preference services; browsing works without download stores. Required dependencies are validated for selected capabilities only.
- [ ] Builders snapshot configuration, reject duplicates/invalid wiring, and perform no eager I/O/native probing. Explicit borrowed-versus-owned resource semantics preserve shared caller stores when one client closes.
- [ ] Streaming events preserve content/reasoning distinction and explicit terminal reasons.
- [ ] Text, image, and video capabilities are separate; text context features are not mandatory for every engine.
- [ ] Engine/provider IDs allow third-party implementations without editing a central implementation enum.
- [ ] Storage/provider contracts are small enough to supply only the services used by the enabled workflow.
- [ ] Compatibility adapters let the existing app compile without an immediate wholesale rewrite.
- [ ] Document generation Flow start/collection/replay/backpressure/terminal semantics, cancellation ownership, context after cancellation, and idempotent suspending close.
- [ ] Plugin capacity reflects actual native state; llama declares one process-wide loaded session and returns a typed busy result for a competing owner.
- [ ] Store contracts specify atomic commit/removal, compare-and-set/fencing, lease expiry, and unknown-state handling; custom implementations have a reusable conformance suite.
- [ ] Artifact leases and local/provider identities do not assume a path is a URI or every revision is a Git hash; expected and locally computed digests remain distinct.
- [ ] Public API signatures are checked; define host versus implementer compatibility and record provisional platform/compiler minimums.
- [ ] Mark host API, implementation contracts and internal bridges deliberately. Define alpha/experimental/stable guarantees and a deprecation path; do not freeze the entire contract inventory before the external consumer and extension prototypes work.
- [ ] Separately compile a fake plugin with unfamiliar metadata and a typed option. Exercise selection, preflight, admission, load and generation without adding cases to core's descriptor/workload/plan types or using public untyped option maps.
- [ ] Define plan binding to runtime/session, engine build, artifact, effective options and request bounds; invalid/foreign/stale plans fail before native allocation. A fallback engine cannot silently reuse another engine's options.
- [ ] Define one default arbitration domain across client facades/plugins and how a second runtime joins or rejects competing ownership. Custom arbitration does not bypass native ownership guards.
- [ ] Specify new-turn versus full-history input, system instruction/template ownership, settings snapshot timing, token-budget handling and exclusive context repair after interrupted summarization.
- [ ] API/core JVM checks configure and execute without native tools or inference submodules in an isolated checkout/environment. Record any remaining Android SDK requirement separately; never remove the developer's installed tools to simulate this.
- [ ] Define explicit platform resource factories in core platform source sets, with injected application context, storage metrics and logging. Neither defaults nor common orchestration require Koin, Hugging Face or host download scheduling.
- [ ] Discovery exposes neutral task creation/execution/pause/cancel/observation operations without Android scheduler types. A host can run a task in-process or schedule its durable ID; no scheduler is mandatory for basic downloads.
- [ ] Choose a transport seam for built-in provider metadata and artifact transfers without requiring provider replacement or HTTP types in core-api. Specify owned/borrowed clients, request/body lifetime, timeouts and total retry accounting; reject incompatible transport configuration explicitly.
- [ ] Define media output-sink staging/commit/abort and result-lease ownership, bounded buffering, metadata, cancellation-versus-commit ordering and distinct publication failures. Pure text consumers need no output sink.
- [ ] Document in-process native fault limits: typed errors cover recoverable failures; fatal signals/OS kills promise neither cleanup nor terminal delivery. Separate live-backend quarantine from interrupted-load caution and evidence-based configuration quarantine.
- [ ] Define Unicode conversion for prompts, paths, metadata and generated deltas, including split sequences, malformed bytes, unpaired surrogates, embedded NUL and incomplete final output. Token accounting never substitutes string length for tokenization.

**Evaluation:** Build the API module alone; inspect its dependency/public signature surface; compile minimal fake implementations in a separate test module. Test cancellation and typed failure mapping at the boundary.

**Reject if:** Moving types simply relocates runner/Room coupling into a module named core-api.

## 5. P2 — Native feasibility before large extraction

- [ ] Produce experimental llama-only, diffusion-only, and combined configurations.
- [ ] Verify which GGML source/build is shared and how its version is coordinated.
- [ ] Verify diffusion-only does not compile/package llama inference, even if it uses GGML from the llama checkout.
- [ ] Link minimal Android, desktop, iOS device, and iOS simulator consumers for the proposed artifact strategy.
- [ ] Record duplicate symbol, backend lookup, Metal/resource, and ABI findings.
- [ ] Decide artifact ownership and variant/publication strategy from evidence.
- [ ] Keep Android JNI `externalNativeBuild` in standalone Android library producers; prove selected native dependencies through the single-variant KMP wrapper. Inspect staged production metadata for debug/default fallbacks and accidental combined artifacts.
- [ ] If direct Swift is selected in P0, build an early external framework sample that can call the advertised API and consume/cancel a stream. Otherwise record the narrower KMP scope explicitly.
- [ ] Stage candidate artifacts and build an external Android llama/local-file consumer without project dependencies, Koin, Room, or discovery. Run load/generate/cancel/close with R8 enabled before broad extraction.
- [ ] Prove the proposed native bridge identity/consumer-rule strategy, extracted backend loading requirements, and initial shared-runtime version conflict handling.
- [ ] The external Android consumer uses real resource readings through the proposed factory; unavailable readings are explicit, not a fabricated safe memory budget. Its merged manifest contains no download components.
- [ ] Audit process-global GGML backend registration, callback mutation, caches and teardown. Demonstrate one shared native coordination domain with both engine init orders and close-one/use-the-other, not just successful combined linkage.
- [ ] Choose explicit backend/CPU profiles for candidate artifacts; record instruction baselines and dispatched variants. Demonstrate loading/inference on the oldest claimed CPU capability and admitted CPU fallback when an optional GPU backend is unavailable. Mark missing hardware evidence Blocked.
- [ ] Decide SDK-specific native filenames/SONAMEs, required exports and C++ runtime ownership using the real multi-library graph. Test a second native SDK fixture and both load orders; do not assume the host's STL or GGML build matches ours.
- [ ] Prototype loading only declared packaged backends. A foreign similarly named backend in the search directory must not be opened or initialized; production ignores working-directory/environment backend injection. Checking compatibility after loading does not pass this gate.

The early consumer is a thin feasibility slice using temporary adapters. It does not pass later storage, plugin parity, or publication-matrix gates by itself.

**Evaluation:** Inspect CMake targets, Gradle task execution, native dependencies, archive members, and final link outputs. Load both plugins in one minimal process where runnable.

**Reject if:** Independent Kotlin modules still require the existing unconditional combined archive, or standalone tests pass but combined linkage fails.

## 6. P3 — Generic storage independence and compatibility

- [ ] Room entities and DAOs are internal to storage; core receives neutral models.
- [ ] Artifact filesystem configuration/build succeeds without inference-engine configuration.
- [ ] Default library schemas/locations describe neutral operational records, with no implicit opening of CaraML files or import of its entities/preferences. Hosts supply storage location or their own implementation.
- [ ] Library schema/format changes use verified migrations and synthetic SDK fixtures; destructive fallback is not an accepted upgrade strategy.
- [ ] Generic installed-model relationships, operational recovery/calibration records and resumable tasks survive supported library-format upgrades independently of CaraML data.
- [ ] Filesystem publication before catalog commit is repaired after restart.
- [ ] A stale catalog reference to missing files becomes unavailable/repaired rather than loadable.
- [ ] Removing one model preserves shared components used by another.
- [ ] Caller-owned imported files are not deleted as if they were managed artifacts.
- [ ] Existing path containment, symlink/reparse protections, append/resume, and durability behavior remain covered.
- [ ] Custom stores pass atomic bundle commit/removal and competing-worker tests; expired/stale owners cannot write checkpoints or finalize after another owner takes over.
- [ ] Deletion/model replacement during load and generation cannot remove leased/mapped/lazily read files. Cleanup eventually occurs after all owners release them.
- [ ] Managed imports handle document URI access, revoked grants, disk-full conditions, source mutation, and interrupted copy without publishing a partial artifact.
- [ ] Backup/device restore with catalog entries but missing bytes invalidates readiness and stale device calibration/leases. No restored job resumes solely because an old lease was present.
- [ ] Default and consumer-supplied media sinks pass staged-output interruption, disk-full, cancellation/commit-race and multi-frame failure tests. Active result leases survive other stores' cleanup; abandoned output is eventually reclaimed under the documented retention policy.

**Evaluation:** Upgrade fixture databases and files, inject interruptions at publication boundaries, run filesystem regression tests, and reopen stores after process-style restart. Test using a custom/in-memory persistence implementation to establish that the default storage module is optional.

**Reject if:** Storage requires CaraML schema/preferences or silently resets records instead of applying a supported migration.

### Separate CaraML-only migration evidence

- [ ] Host adapters retain existing database paths/formats or explicitly migrate/import data into chosen library stores. Installed models, shared components, saved preferences, recovery/calibration state and paused downloads survive the app upgrade.
- [ ] CaraML retains sole ownership of its existing DataStore; theme, inference preferences and recovery adapters share that instance while using the same file. Concurrent writes preserve all keys. Other consumers have no shared-preferences requirement.
- [ ] Queued CaraML WorkManager/UIDT work survives through unchanged component identities or host compatibility mappings; new library code contains no CaraML-specific entry points.
- [ ] Any migration to default stores cuts over to one authoritative writer, with verified records and documented rollback. Recreating empty stores/redownloading files does not count as successful migration.

## 7. P4 — Core orchestration

- [ ] Core compiles without storage implementations, providers, concrete plugins, and Compose.
- [ ] A fake engine supports load/generate/cancel/unload through the public contracts.
- [ ] Duplicate engine IDs are rejected; ambiguous compatible engines have deterministic selection behavior.
- [ ] No registered engine yields a useful typed error for inference, while applicable non-inference services work.
- [ ] Startup/capability queries do not eagerly require every plugin's native library.
- [ ] Admission covers ready, blocked, temporary, confirmation, and alternative-plan outcomes.
- [ ] Partial load failure releases resources and updates recovery state.
- [ ] Cancellation, unload, and model replacement cannot free an in-use native session.
- [ ] Observation/recovery keys distinguish engines, versions, artifacts, device/backend, and relevant plans.
- [ ] Two core/client instances contend for the same physical engine safely; closing or cancelling one cannot alter the other's operation.
- [ ] Competing load/generation/calibration requests reserve resources atomically; cancelling queued work and failing loads release only their own reservations.
- [ ] Two independent client facades/runtimes using different plugins cannot admit against separate copies of the same process budget. Test resident versus pending/transient accounting, unified memory and refreshed snapshots without double-counting reservations.
- [ ] Changing request dimensions, frame count, context/output limits or effective options after assessment requires re-admission; foreign engine/session and stale artifact plans are rejected.
- [ ] Repeat collection cannot regenerate a prompt; slow collectors do not drop text/reasoning or grow unbounded buffers, and UI collection restart does not start new work.
- [ ] Collector cancellation propagates cancellation without a fabricated success event. Engine failure emits the defined terminal result; subsequent generation follows the documented context policy.
- [ ] Cancellation can signal while generation holds its session gate; close waits for native acknowledgement. A timed-out native operation remains quarantined and is never freed while still executing.
- [ ] Injected dispatchers/clocks allow deterministic race and expiry tests; platform UI-thread calls perform no blocking file/network/native work.
- [ ] Reusable recommendation ranking accepts neutral assessments and has no provider DTO, UI/Paging type or discovery implementation dependency. Preserve deterministic ordering and invalidate stale workload/engine/evidence cache entries.
- [ ] Resource-source failure stays unknown and follows an explicit admission policy. Low-memory/thermal inputs, cooldown and recovery are tested without any trim callback; monitoring stops and listeners are unregistered when their owning runtime closes.
- [ ] Native completion releases only native-work reservations; retained output/copy/publication allocations retain their accounting until release or explicit transfer. Success is recorded only after readable output is committed; collector disappearance does not orphan the result.
- [ ] Recovery fixtures distinguish cancellation/user stop, package update, low-memory exit, unknown interruption and correlated native failure. Repeated unknown interruptions never become `KNOWN_UNSTABLE`; resource pressure triggers reassessment, and explicit retry/expiry follows the documented policy.
- [ ] Recovery consumes an interrupted marker once, retains its stage/run/build identity and cannot consume another live client's marker. Missing API 30+ exit evidence, API 28–29, mismatched exit records and restored legacy markers remain unconfirmed. Process-level crash evidence alone does not identify a model as its cause.

**Evaluation:** Deterministic fake-engine contract tests with injected resource changes, delays, cancellation, failure, and restart evidence. These prove architecture and lifecycle logic, not real inference support.

**Reject if:** Adding a second fake engine requires editing core switches on llama/diffusion identities.

## 8. P5 — Real plugin behavior

### Llama

- [ ] Load a compatible local model with no discovery module present.
- [ ] Compare content/reasoning streaming, stop reasons, token metrics, context accounting, and finalization with baseline.
- [ ] Verify cancellation, context exhaustion, reset/summarization, failed load cleanup, and reload.
- [ ] Verify metadata/preflight and resource estimates remain engine-owned.
- [ ] Verify typed engine options map to validated native settings.
- [ ] Exercise process-wide singleton ownership with two independent clients, including a stale cancel after the next operation begins and attempted replacement of a loaded model.
- [ ] Append multiple turns without replaying prior history or applying templates twice; reset/new-session behavior and system instruction handling match the documented contract.
- [ ] Change settings during a queued/running operation: that operation keeps its admitted configuration; subsequent operations apply the documented new settings or require reload/reset. Input/output token overflow has an explicit outcome.
- [ ] Cancel/fail summarization between native context clearing and replacement. Competing turns cannot interleave; ordinary generation stays unavailable until context is repaired/reset. Do not claim rollback if native history was destroyed.

### Diffusion

- [ ] Resolve required components without depending on Hugging Face client classes.
- [ ] Verify image generation, progress, cancellation, native preflight, and generated artifact handling.
- [ ] Verify video only on explicitly supported model/platform combinations; record unsupported combinations.
- [ ] Preserve component placement and supported CPU/GPU behavior.
- [ ] Verify failure-atomic context publication and cleanup after partial loads.
- [ ] Validate image/video output descriptors, frame count/order/dimensions/format/effective FPS, and bounded transfer to the output sink. Do not label a PNG frame sequence as an encoded video.
- [ ] Measure peak native-plus-Kotlin memory through encoding, copying and publication at the supported workload limit. Inject output write failure or cancellation after native success and ensure no partial result is reported as success.

### Combined

- [ ] Switch between engine capabilities without stale handles, inconsistent recovery state, or backend conflicts.
- [ ] Enforce resource policy for overlapping requests rather than assuming safe concurrent native inference.
- [ ] Compare baseline load time, memory, and generation throughput with agreed thresholds.
- [ ] Verify calibration/preflight exclusion, cancellation latency, repeated load/unload memory behavior, and thermal/memory-pressure transitions on real backends.
- [ ] Verify actual native completion before releasing file/resource leases, including a slow or non-responsive native-call fixture where feasible.
- [ ] Exercise overlapping cross-engine probes/generation, both initialization orders, close-one/use-the-other and reopen under the shared native guard. Verify logger restoration/redaction and no global teardown while another participant remains active. Cancel must still signal while the shared operation guard is held.
- [ ] Deterministic fixtures exercise both bridges with supplementary emoji, Indic/CJK text, combining characters, non-ASCII model paths, split UTF-8 bytes, NUL and malformed input. CheckJNI reports no conversion/reference errors; supported JVM/iOS paths follow the same documented text policy. Do not rely on model sampling to generate the edge cases.

**Reject if:** Only compilation passes, native regressions are bypassed, or engine-specific failures are silently translated into generic success.

## 9. P6 — Generic model discovery and transfer

- [ ] Hugging Face DTOs, authentication, search, and repository resolution remain in an implementation package inside `:model-discovery`. Neutral public operations accept injected providers; constructing discovery with a mock provider does not initialize or contact Hugging Face.
- [ ] A mock non-Hugging-Face provider can browse/resolve artifacts through the same workflow.
- [ ] Generic transfer supports expected progress, pause, cancellation, retries, and resume behavior.
- [ ] Range/validator mismatch, truncated content, corrupt files, insufficient space, and verification failures produce correct outcomes.
- [ ] Restart reconciliation does not duplicate publication or lose completed artifacts.
- [ ] Platform lifecycle/background completion handling remains functional where supported.
- [ ] Diffusion setup rules do not force every discovery consumer to depend on diffusion.
- [ ] Discovery, with built-in Hugging Face support, plus storage can install models without an inference plugin or OS scheduler. Provider replacement does not claim to remove bundled Hugging Face/HTTP dependencies from the artifact.
- [ ] Browse/metadata APIs accept neutral task filters rather than presentation modes. A second provider returns equivalent candidate/variant data without Hugging Face DTOs.
- [ ] Superseded searches cancel bounded enrichment without overwriting the newer query; provider/revision cache keys prevent cross-provider collisions. Engine absence yields unavailable assessment while browsing/downloads remain usable.
- [ ] Provider secrets are absent from persisted descriptors and evaluation logs.
- [ ] A non-Hugging-Face provider with non-hex immutable revisions works without weakening Hugging Face validation or changing legacy bundle/storage IDs.
- [ ] An explicit complete diffusion bundle (or native-free planner) installs without an inference plugin; missing planner/engine produces distinct completeness/compatibility/loadability outcomes.
- [ ] Resume refreshes an expired signed URL via provider identity; redirect/auth policy does not forward credentials to an unrelated origin. Authentication failure does not become an infinite retry loop.
- [ ] Concurrent downloads account for staging/finalization storage, and disk exhaustion cannot publish a ready bundle.
- [ ] Discovery's Android artifact has no WorkManager dependency, auto-initializer, worker/service component or notification permission. Host application code owns scheduling, its factory/configuration, notifications and permissions.
- [ ] A sample host schedules by durable task ID and delegates to the library's task workflow; it works without Koin. Test process death between task persistence and scheduling, rejected scheduling, and competing in-process/OS execution without duplicate ownership.
- [ ] Library bounded retries and typed permanent failures are preserved by host rescheduling. A system stop yields a resumable task; explicit user pause/cancel/stop is not silently converted to an automatic retry.
- [ ] Android fallback keeps one owner per batch; notification denial, ineligible UIDT scheduling, API 36 job quota, reboot, system kill and user stop retain correct durable outcomes.
- [ ] Kill the process without invoking stop/cleanup callbacks; restart does not fabricate completion, revive explicitly stopped work, or allow stale callbacks to overwrite a new generation.
- [ ] A recording transport observes every built-in provider metadata request, redirect, artifact transfer, resumed request and credential refresh. No hidden fallback client bypasses host policy; substituted transport preserves validation and bounded response parsing.
- [ ] Cancel/close while bodies stream: discovery's requests stop and release bodies, while unrelated requests using a borrowed client continue. Repeated client construction/disposal closes owned clients/engines without closing borrowed resources.
- [ ] Bound underlying HTTP attempts across injected retry plugins, provider retries and host rescheduling. Test rate limits/`Retry-After`, auth-refresh exhaustion, metadata deadline, artifact idle timeout and cancellation during backoff.
- [ ] A host-configured test endpoint works through the intended transport; denied cleartext and untrusted TLS remain denied. The library supplies no application-wide network-security override or process-global trust/proxy mutation.

**Reject if:** Renaming `huggingFaceManager` leaves generic APIs dependent on provider-specific types or requires inference native configuration for downloads.

## 10. P7 — CaraML consumer integration and UI preservation

This section accepts the CaraML application migration. Its screens, ViewModels, stored preferences and UI behavior remain consumer concerns; they are not shipped SDK components or prerequisites for another host.

- [ ] Application Koin wiring selects concrete implementations; libraries remain constructible without Koin.
- [ ] ViewModels consume core/capability contracts without runner/native imports.
- [ ] Model browsing, installed model selection, downloads, settings, text chat, images, and supported videos retain their behavior.
- [ ] Preserve recommendation rollout behavior, manual selection, stable pagination/ranking and query windows after the service split. Application composition mediates discovery and assessment without introducing a core/discovery cycle.
- [ ] Available generation modes reflect registered capabilities; absent plugins do not crash startup.
- [ ] Appearance/navigation/UI utilities stay app-owned.
- [ ] Android debug/release, desktop launch/distribution, and iOS framework integration use the new wiring.
- [ ] Existing data is visible after upgrading the application.
- [ ] Application startup can initialize workers/services without `MainActivity`, Compose, or global Koin inside library code; no Activity context is retained.
- [ ] Rotation, recomposition, screen stop/start, navigation away/back, and multi-window do not duplicate generation or unexpectedly replace a session. One operation producer feeds lifecycle-aware UI state.
- [ ] Process death interrupts ephemeral inference honestly; restored selections do not claim a native session or conversation was restored.
- [ ] Permission prompts stay app-owned; trim/thermal callbacks invoke defined core policy without freeing active native memory or blocking the main thread.
- [ ] Shutdown disposes owned resources and preserves caller-owned scopes; repeated runtime construction/disposal does not leak stores, clients, threads, or native sessions.
- [ ] On API 34+, resource policy works when no running-low/critical trim or `onLowMemory` callback arrives; on API 28 unavailable thermal status is handled honestly. Verify pressure blocking, critical cancellation, safe idle eviction and cooldown against recorded P0 behavior.
- [ ] The ViewModel renders committed media references while core owns generation/publication; navigating away at commit cannot leave an ownerless output or a falsely successful message.
- [ ] Kill the process during a recorded load, then restart with known and unavailable exit evidence. The host presents interruption/uncertainty honestly, does not silently replay generation or label a valid model faulty, and preserves explicit retry. Run intentional native-crash fixtures only in disposable test processes, separate from fake-engine exception tests.

**Evaluation:** Run existing presentation tests and targeted manual end-to-end flows on baseline platforms. Inspect actual rendered behavior for progress, cancel/retry, and admission messages; backend unit tests cannot establish UI equivalence.

## 11. P8 — Artifact isolation matrix

Fill every applicable cell with Passed, Failed, or Blocked plus evidence. None are passed by this plan.

| Configuration | Android | Desktop | iOS device/simulator | Exclusion requirement |
|---|---|---|---|---|
| Core-only | Pending | Pending | Pending | No inference or default storage libraries |
| Storage-only | Pending | Pending | Pending | No llama/diffusion inference build |
| Discovery including Hugging Face + storage | Pending | Pending | Pending | No inference artifacts or library-owned Android scheduling components |
| Android host scheduling + discovery + storage | Pending | N/A: Android only | N/A: Android only | Background components remain host-owned; no library dependency on app DI |
| Core + llama + local files | Pending | Pending | Pending | No diffusion inference artifacts |
| Core + diffusion + local files | Pending | Pending | Pending | No llama inference artifacts |
| Core + both plugins | Pending | Pending | Pending | No conflicting shared native ownership |
| Full application | Pending | Pending | Pending | Only intended modules/resources |

For each configuration:

- [ ] Inspect resolved dependency metadata and tasks actually executed.
- [ ] Inspect packaged native files and linked/archive symbols, not only Gradle declarations.
- [ ] Run from a clean output directory so cached combined binaries cannot mask missing dependencies.
- [ ] Exercise configuration without native tools for API/core-only configurations. Record actual Android host/device test counts; enable needed KMP test source sets and flag unexpected zero-test runs.
- [ ] Confirm dynamically loaded native backends/resources are present when required.
- [ ] Run the installed artifact outside the repository's working directory.
- [ ] Record size and native dependency differences against the baseline.
- [ ] Inspect merged Android manifests: core/local-inference consumers have no download services, auto-initializers, or notification permissions added by these libraries.
- [ ] Run an R8 full-mode/resource-shrunk external consumer exercising JNI configuration, metadata, and artifact filesystem access; packaged consumer rules are sufficient without broad keep-all rules.
- [ ] Inspect every native dependency for supported ABI and 16 KB ELF alignment; verify ZIP alignment where applicable and run in a 16 KB environment without relying on compatibility fallback.
- [ ] Install APK splits generated from the AAB and exercise dynamic backend discovery. Confirm any extraction/packaging requirement is explicit in consumer setup.
- [ ] Inspect shared GGML/backend/C++ runtime versions and duplicate library names; incompatible combinations fail clearly and are never resolved by arbitrary `pickFirst` packaging.
- [ ] Compare declared native profiles with compile commands, runtime capability reports and dependency inventories. Adding/removing unrelated optional build tools cannot silently change capabilities; missing requested prerequisites fail clearly. Exercise the clean consumer without the producer's SDK installations or library search paths.
- [ ] Run Unicode bridge fixtures with CheckJNI in an Android test build and representative Unicode load/generation in the optimized external consumer. R8 validation and string correctness are separate gates.
- [ ] Repeat native coexistence tests in the installed optimized consumer. Inspect exports, SONAME/needed dependencies, backend inventory and C++ runtime selection; a foreign candidate is not initialized, and either library's shutdown leaves the other intact. Known incompatible combinations fail clearly without `pickFirst` workarounds.
- [ ] Inspect network permissions and application-level network settings per artifact: core/local inference adds no `INTERNET` or automatic network activity; discovery documents its network requirement and leaves host security configuration intact.

### Minimum Android scenario coverage

This is a risk-based matrix, not a requirement to benchmark every Cartesian combination. Cover each applicable behavior on supported OS levels and each shipped ABI; link-only and device evidence remain separate.

| Environment / trigger | Required result |
|---|---|
| Declared minimum Android API (current native floor: 28) | Local inference/storage start and legacy download scheduling remain supported |
| API 33, notification permission denied | Clear download status and valid scheduling behavior without a permission/startup crash |
| API 34+, user download and ineligible background scheduling | UIDT eligibility/fallback and single-owner behavior |
| API 35/36, foreground restrictions/quota and user stop | Safe checkpoints, no assumed unlimited runtime, no automatic reversal of user stop |
| 16 KB device/emulator plus supported 4 KB environment | All selected native dependencies load and perform representative work |
| arm64-v8a device, x86_64 emulator | Shipped ABI/backend selection verified; emulator results do not establish device GPU behavior |
| Oldest claimed CPU features; optional GPU missing/unsupported | Baseline bridge/dispatch loads safely; unsupported acceleration is explicit and fallback is re-admitted |
| Old APK with pending work → same-identity upgraded APK | Data and work survive without uninstalling, clearing data, or recreating the queue |
| System kill, force-stop, reboot, and explicit in-app cancel | Distinct documented recovery behavior; never rely on `onDestroy`/`onStopJob` running |

**Reject if:** An unused engine is merely hidden behind `implementation`, compiled anyway, or accidentally copied into the final package.

## 12. P9 — Library publication and external consumption

- [ ] Choose artifact coordinates, version policy, supported target matrix, and plugin/core compatibility rules.
- [ ] Publish candidates to an isolated local Maven repository; remote publication is a separate release action.
- [ ] Build sample consumers using artifact coordinates rather than Gradle project dependencies/composite builds.
- [ ] An independent Android host constructs inference through the builder with no CaraML source/resources, Compose, Koin, app database or preferences file. Exercise explicit configuration and load/generate/cancel/close. A separate discovery sample proves its builder and host scheduling integration.
- [ ] Inspect published classes/resources and dependency metadata for accidental host code or schema leakage; app-specific adapters remain in the consumer. Include direct-call/headless usage so a chat UI cannot hide required dependencies.
- [ ] Confirm public API dependencies appear correctly in KMP metadata and native runtime dependencies are delivered.
- [ ] Prove the consumer needs neither repository-relative paths nor unpublished build outputs.
- [ ] Run minimal local inference with custom storage, discovery-only, and combined-engine consumers.
- [ ] Verify the iOS distribution/consumer-language scope selected in P0/P2, including direct Swift only if explicitly supported in that scope.
- [ ] Include required source/native licenses and notices.
- [ ] Document setup, local model loading, plugin registration, capability errors, storage replacement, and platform limitations.
- [ ] Discovery is published with its built-in Hugging Face implementation and accepted bundled dependencies. Document provider injection; include host-owned Android scheduling as sample app code, not a required library artifact.
- [ ] Publish artifact-specific minimum Android API, Kotlin/AGP/build-JDK/bytecode requirements, iOS deployment targets, and native ABIs; resolve the existing wrapper API 24 versus native API 28 mismatch in advertised support.
- [ ] Check public API changes and run an older compiled consumer/plugin against a claimed-compatible newer release, including implementer-facing interfaces. Source recompilation alone is insufficient.
- [ ] Verify coordinated plugin/core/native versions, diagnostics for unsupported combinations, and consumer setup without app-specific packaging/DI assumptions.
- [ ] An external engine/custom-store implementation runs version-matched conformance support against staged coordinates without internal imports, repository paths, model weights or production test dependencies. Compile documented quick-start/extension examples and expose a fake engine suitable for host tests.
- [ ] Test the declared minimum consumer Kotlin/AGP/JDK combination separately from the producer toolchain and exercise representative host coroutine/HTTP dependency versions. Preserve resolved-graph evidence; forced dependency versions are not compatibility evidence.
- [ ] Public documentation states API maturity, units/ranges, lifecycle/collection/ownership semantics and error recovery. Verify JVM binary compatibility separately from the promised KMP/Native compilation and linkage scope.
- [ ] Published discovery preserves injected transport behavior and borrowed-resource ownership in an external host; native consumers pass the declared coexistence policy, not only an otherwise empty application.

**Reject if:** Samples work only inside the CaraML checkout or require consumers to initialize its native submodules.

## 13. P10 — Final acceptance and release readiness

- [ ] SDK evidence is complete for every advertised target. Track CaraML migration acceptance separately; its app-specific checks cannot be satisfied by adding app data/UI to the SDK.
- [ ] Temporary adapters and old dependencies are removed or have explicit supported deprecation plans.
- [ ] `verifyProject` includes the appropriate extracted-module suites and native gates.
- [ ] CI covers contract isolation, data upgrades, selected-engine packaging, and external artifact consumption.
- [ ] Relevant module READMEs and root Recent Changes describe shipped behavior accurately.
- [ ] Upgrade and rollback procedures document data compatibility limits.
- [ ] Existing failures, unsupported targets, and blocked device checks are visible in release notes.
- [ ] ONNX/TensorFlow and chat persistence remain clearly marked as future work.
- [ ] Every phase has a responsible owner, reviewed evidence, rollback boundary, and temporary-adapter removal condition; old/new pipelines never compete as duplicate writers.
- [ ] R1–R21 are linked to passing evidence or explicitly excluded release scope. No unresolved P1 finding is waived by a JVM-only pass.
- [ ] Decision records exist for consumer scope, host/plugin contracts, arbitration/text semantics, native artifact ownership, store compatibility and recommendation ownership before their dependent phase is accepted.
- [ ] Public diagnostics expose useful IDs/timings/typed failures without prompts, generated text, secrets, or credential-bearing URLs; native crash symbols/build IDs are retained.
- [ ] A named maintenance owner, supported-version window, issue/security reporting route and patch-release process exist. Dependency/native build inventory and notices accompany the release; upstream updates rerun affected hardening, ABI, coexistence and external-consumer gates.

## 14. Architecture acceptance scorecard

These are mandatory gates, not points to average. A critical failure cannot be offset by passes elsewhere.

| Property | Evidence needed |
|---|---|
| Engine independence | Core/public API dependency inspection plus alternate fake-engine integration |
| Optional discovery | Real local inference without discovery/provider dependencies |
| Optional default persistence | Consumer-owned stores and artifact access |
| Generic discovery | Mock alternative provider and isolated transfer/reconciliation tests |
| Native modularity | Selected configuration task graph, artifact inventory, symbols, and runtime linkage |
| Generic storage continuity | Synthetic library-format upgrades retain artifact relationships, operational evidence and resumable state without app preferences |
| Lifecycle correctness | Cancellation/load/unload/failure tests and real native regressions |
| Consumer independence | Builder/direct-call consumers work without CaraML classes, files, UI or DI |
| Distribution readiness | External consumers of staged published artifacts |
| Android integration | Background-only startup, persisted work upgrade, manifest isolation, optimized/AAB-derived installs and 16 KB execution |
| API evolution | Checked signatures, documented target minimums, older binary consumer/plugin compatibility |
| Extension completeness | Third-party options/metadata/plans pass admission and execution without core changes |
| Build independence | API/core configuration succeeds without native tools; production native artifacts selected explicitly |
| Generic recommendations | Provider-neutral enrichment, engine-optional assessment and neutral caller-supplied ranking policy inputs |
| Text state correctness | Single history authority, configuration snapshots and exclusive context repair |
| Platform independence | Real default OS readings without app DI/provider coupling; explicit unknown/pressure behavior |
| Shared native lifecycle | Coordinated initialization, callbacks and teardown across both plugins and all clients |
| Media completion | Bounded output memory, atomic publication, usable result metadata and explicit retention |
| Recovery attribution | Unknown/user/OS interruption distinguished from correlated native failure; once-only recovery and explicit in-process fault limits |
| Native portability | Explicit backend/CPU profiles, baseline-safe dispatch, declared dependency closure and reproducible capability inventory |
| Text fidelity | Real bridge Unicode/byte-boundary fixtures, CheckJNI and supported cross-platform parity |
| Native ecosystem coexistence | Declared binary/export/STL boundary; allowlisted packaged backends; no foreign initialization in a second-library fixture |
| Host network control | Built-in discovery and transfer honor injected transport, bounded attempts, host security policy and borrowed-client lifetime |
| External extension usability | Versioned conformance support, compilable examples and explicit host/implementer API maturity |
| Release maintainability | Tested consumer-toolchain range, maintained native dependency inventory, support ownership and a repeatable patch-release gate |

CaraML-only acceptance separately covers existing data/preferences, scheduled-work upgrade, UI behavior and recommendation rollout/query behavior under P3/P7. Those checks preserve the consumer; they do not expand the SDK surface.

## 15. Evaluation record for this documentation change

- Architecture and migration plan written; phase and acceptance criteria defined.
- Source/build files inspected to identify current coupling and storage/native constraints.
- Senior Android/KMP follow-up review retained R1–R9, added R10–R13, and clarified cross-client arbitration and early consumer-language decisions. Sources inspected include native/Gradle configuration, recommendation and plan types, and text/context orchestration; official Android KMP/modularization and Kotlin compatibility guidance is linked in the design.
- Additional source review added R14–R16: platform resource ownership, shared GGML runtime state, and media completion/accounting. Inspected Android device/snapshot code, both native bridges and pinned logger/backend implementations, diffusion results, ViewModel publication and the existing media store. Verified Android KMP, DataStore, UIDT, trim-callback and thermal-listener guidance against official documentation. Added runtime, rollout and media-lifecycle diagrams to the design.
- Simplified distribution boundaries: Hugging Face ships inside discovery behind neutral interfaces; Android scheduling/components stay in the host app. Updated diagrams, ownership/relocation tables, consumer matrix and scheduler-free/host-integration gates. No standalone provider or Android scheduling library is planned for the initial release.
- Clarified generic SDK scope: CaraML is one consumer; preferences/UI/legacy schemas stay host-owned. Moved its DataStore requirement into consumer migration evidence, removed a required library preferences store, and added builder construction/configuration/resource-ownership gates plus an independent host with no CaraML data or UI.
- A previous senior Android/KMP review added R17–R19 after inspecting load recovery, JNI string conversion, platform CMake and pinned GGML defaults. Added explicit interruption attribution/in-process fault limits, reproducible backend/CPU profiles and Unicode bridge gates; consolidated the review summary without adding modules or phases. Official Android exit-reason, JNI and CPU-feature references are linked in the design. Findings are source-based risks, not reproduced runtime failures.
- Independent-SDK follow-up added R20–R21 after inspecting native binary names/backend scanning and provider/download HTTP client construction. Added external native coexistence and backend allowlisting, host transport/lifetime/retry gates, and strengthened R9/R11 with API maturity, consumer-toolchain tests, external conformance support and release maintenance ownership. Official Android middleware/export/network-policy, Kotlin compatibility and Ktor lifecycle guidance is linked in the design. No new production module or engine is proposed.
- Documentation checks passed: 42 local links/anchors resolve, both review tables contain unique R1–R21 entries, all 11 phase statuses remain Not started, fences are balanced and whitespace checks pass. The five existing Mermaid blocks are unchanged; a parser/render check was not run. These checks do not establish runtime correctness.
- No implementation migration, native build, inference benchmark, or platform test has been performed as part of writing these documents.
- The original documentation-only record above predates implementation. Current phase statuses and implementation evidence are tracked in section 2 and the linked work record; no full phase is marked passed.
