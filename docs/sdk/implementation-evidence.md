# SDK extraction implementation evidence

Date: 2026-10-14. This is an implementation work record, not release approval.

The acceptance requirements in [the architecture](../../architecture-plan.md) and
[the evaluation checklist](../../evalutaion.md) remain authoritative. No platform,
native engine, persistence format, or compatibility promise is accepted by merely
adding a module or compiling an interface.

## Baseline

The existing checkout uses Gradle 9.8.1, Kotlin 2.4.21, AGP 9.4.1 and a JetBrains
JDK 21 Gradle daemon. The shell's Java is OpenJDK 17; the Gradle daemon toolchain
is selected independently by `gradle/gradle-daemon-jvm.properties`. The version
catalog specifies compileSdk 37, targetSdk 36 and minSdk 28; some existing
wrappers override these values.

Pinned native gitlinks:

- llama.cpp: `f46bc30cb6a7f68a67e34a00061e20a4ad1eff43`
- stable-diffusion.cpp: `c92d73c408515c94beef32161bb5960764fde7a0`

`./gradlew verifyProject --console=plain` was run before the new SDK compilation.
It failed at `:nativeEngine:verifyNativeSubmodulePins`: the existing llama.cpp
origin is not the allowlisted public HTTPS remote. The task's policy and checkout
remote were not changed to conceal this failure. The artifact filesystem Android
root native test passed during this invocation. A failed aggregate gate does not
establish a passing baseline; old test reports alone are not new-run evidence.

The existing app Room schema is version 1. App preferences and database ownership
remain in CaraML. This change does not migrate its files, persisted worker names,
or service component identities. The pre-existing root README and Xcode project
changes are preserved.

## Scope and sequencing

The new portable modules are an experimental Kotlin/KMP prototype. The first
native release gate remains the external Android/Kotlin local-file llama sample
specified in P2. Direct Java or Swift use, optimized Android native packaging,
real-model behavior, and a stable SDK release are not established by JVM tests.

`-PsdkOnly=true` includes `core-api`, `core`, `storage`, `model-discovery` and
the internal `native-runtime` coordination module; it excludes app, runner and
native producer configuration. `verifySdk` runs portable JVM suites and verifies
resolved runtime dependency boundaries. The user explicitly deferred publication;
publication configuration, staging tasks and coordinate-consumer checks were
removed from this implementation scope.

Run portable module checks with:

```sh
./gradlew -PsdkOnly=true verifySdk
```

The test provider/engine are fixtures. They prove extension wiring, not native
inference or model quality. `InMemorySdkStorage` is bounded ephemeral test storage,
not a production durable store or a large-weight file store. Its opaque locations
are not native filesystem paths.

## Implemented module boundaries

| Module | Responsibility | Production project dependencies |
|---|---|---|
| `core-api` | Neutral artifact, engine, resource, discovery and transfer contracts | None |
| `core` | Client/session lifecycle, plan binding and admission arbitration | `core-api` |
| `storage` | In-memory catalog/task store and injectable Okio file artifact store | `core-api` |
| `model-discovery` | Provider-neutral browsing/transfer and Hugging Face adapter | `core-api` |
| `native-runtime` | Internal shared native ownership gate | None |
| `plugin-llama` | Local GGUF text engine adapter and plugin-owned options | `core-api`, `runner`, `native-runtime` |
| `plugin-diffusion` | Bounded single-image adapter and injected output publication | `core-api`, `diffusionRunner`, `native-runtime` |

The core and plugins bind private plans to complete artifact identity, model and
settings. Core tests exercise lifecycle, cancellation, ownership, reservations and
reentrant terminal callbacks. The native gate serializes the two SDK adapters;
it does not coordinate legacy direct-runner callers or independent classloaders.
Failed native teardown permanently retains the session, artifact lease and
process reservation; concurrent/repeated close cannot release that quarantine. Android and JVM resource
factories read OS resources without app DI.

`FileArtifactStore` streams bounded stages to an explicitly injected filesystem,
validates size/digest, atomically publishes immutable files, and leases local paths.
Its root must be exclusively host-controlled. Ownership is per instance; there is
no cross-process coordination, orphan-stage recovery, or atomic transaction with
an independently supplied task store. It is not a durable download database.

Discovery streams bytes through an injected Ktor transport, checks ranges and
revision identity, bounds retries, and strips hub credentials on signed redirects.
It has no app dependency or OS scheduler. Restart recovery, persistent validator
continuity, provider pagination and installation reconciliation remain incomplete.

Native builds now have explicit engine/backend profiles, profile-isolated desktop
outputs and cache mismatch checks. JNI conversion has shared Unicode fixtures.
These changes do not establish independent production Android packaging or native
coexistence with other SDKs. The app still uses its existing repositories, workers,
Room database and inference paths; no P7 cutover has occurred.

## Verification record

- `./gradlew -PsdkOnly=true verifySdk --console=plain`: **passed** on the
  final storage fixes. Runs JVM tests, common metadata compilation and resolved
  portable dependency checks with app/native producer projects excluded.
- JVM reports: core 12, storage 11, discovery 7 and native-runtime 1 passing tests;
  plugins add llama 3 and diffusion 4 passing tests with fake native bridges.
- The existing suites passed in the full run: composeApp 1,244,
  huggingFaceManager 140, runner 36 and diffusionRunner 23 tests.
- The aggregate `verifyProject` is **not green**: the final `./gradlew verifyProject :core:compileAndroidMain --continue`
  run failed **only** at the existing native remote allowlist check. All 1,481
  JVM tests passed (1,443 existing tests plus 38 SDK/plugin tests), along with
  dependency checks and native regression gates. Intermediate compilation,
  cancellation-fixture and shared-test-budget failures were fixed and rerun.
- Android core compilation and both plugins' common metadata compilation passed.
  Both iOS arm64/simulator static archive merges and runner C interop bindings
  built. The combined desktop native build compiled both JNI bridges.
- Native CTest gates passed: JNI Unicode conversion 1/1, Android-root artifact
  filesystem 1/1, diffusion regressions 5/5. Compilation and fake-engine tests
  are not installed-device or real-model inference evidence.
- Fresh CPU-profile CMake configuration and target inventories passed for
  llama-only and diffusion-only in `/private/tmp/caraml-final-llama-cpu` and
  `/private/tmp/caraml-final-diffusion-cpu`. Each inventory excluded the other
  engine and retained shared GGML. Reusing the llama-only cache for diffusion-only
  failed with the intended profile-lock diagnostic. These probes did not compile
  the selected profiles or establish final package contents.

## Pending acceptance evidence

- P0: reproducible physical-device performance, UI and process-death baselines;
  historical installed-app upgrade fixtures; representative package inventories.
- P1/P4: complete typed capability contracts, runtime ownership across all host entry points,
  extension conformance distribution and API evolution checks.
- P2/P8: separate production Android artifacts, combined-runtime ownership,
  external R8/AAB consumers, 4/16 KB loading, exported-symbol/backend coexistence,
  and installed iOS/desktop evidence.
- P3: durable transactional catalog/task storage, cross-store publication, restore and
  crash reconciliation, host-format upgrade tests.
- P5: real-model llama/diffusion plugin parity, text-context repair, diffusion bundles/
  video, measured media budgets, Unicode inference and combined-runtime evidence.
- P6: durable downloads with validator/revision continuity, install reconciliation,
  complete provider pagination and the host scheduling upgrade matrix.
- P7: host adapters and application migration, followed by UI/data regression
  tests. Broad migration must not precede the native feasibility gates.
- P9/P10: all-target external consumption, compatibility baseline/older binary
  tests, licenses/inventory, maintenance owner and final release acceptance.

No unchecked evaluation criterion is implicitly waived. A Pixel 9 is connected. The installed iOS 26.5 simulator runtime has no configured
device. Prior APK/data fixtures and real models must still be identified for the
required runtime evidence. Build-only evidence must remain separate from runtime
evidence.

## Independent review — 2026-10-14

**Original review disposition: changes required before integration.** The eight findings below were corrected in the follow-up implementation; see [corrective evidence](#corrective-implementation--2026-10-14). The portable dependency
boundaries are useful, but the current patch does not yet satisfy P1/P2/P4/P6
acceptance. Deferred publication, absent P7 migration, and documented prototype
limits are not treated as newly discovered defects. The following issues were
found in the implemented behavior.

| Priority | Finding and evidence | Required correction / evaluation gate |
|---|---|---|
| P1 | `nativeEngine/build.gradle.kts` selects only runner CMake targets. The current arm64 debug merged native output contains six libraries, omitting `libartifact_fs.so` and every dynamically loaded `libggml-cpu-*` backend. The CPU backends exist in the CMake build tree but are absent from the merge output. Existing Android artifact storage loads `artifact_fs` explicitly. | Preserve the complete runtime inventory when filtering targets; verify packaged libraries as well as compilation. R8/R10, P2/P8. |
| P1 | `HuggingFaceProvider.prepare()` calls the no-argument `HttpStatement.execute()`. The resolved Ktor 3.6.0 source buffers the complete body for that overload. A response with an unfinished body prevents `open()` from returning. Metadata size checks also occur after this buffering. | Keep the streaming response lifetime open through body consumption and enforce bounds before buffering. R21, P6. |
| P1 | Runner consumer rules preserve enum members but not enum class names used in JNI field descriptors. A small R8 fixture using the actual llama rules renames `LlamaLazyMode` and changes the field descriptor. The diffusion backend enum has the same rule pattern. | Preserve descriptor classes/names and verify both bridges in an optimized external consumer. R3, P2/P8. |
| P1 | On a partial llama load followed by failed teardown, the plugin retains its native gate but throws without returning a session. Core consequently releases the artifact lease and admission reservation. A fake native bridge reproduced this after system-prompt failure. Diffusion's failed-load cleanup has the same ownership gap. | Represent unresolved partial native ownership explicitly so artifact and resource ownership survive until teardown is confirmed. R2/R7, P4/P5. |
| P1 | A host Ktor client with default bearer authorization forwards that header to a signed CDN redirect. `includeToken = false` only suppresses the provider's own header addition; it does not remove inherited authentication. MockEngine reproduced the forwarded header. | Enforce origin-bound authentication after inherited configuration, or reject incompatible client auth configuration. R21, P6. |
| P2 | `FileArtifactStore.abort()` ignores a stage after `close()` because its ownership check also requires `!closed`. Discovery closes failed/paused stages, leaving their temporary files and writer reservations impossible to abort through the original handle. The in-memory store behaves differently. | Separate ownership validity from write-open state and allow fenced cleanup of a closed stage. R7, P3/P6. |
| P2 | `InferenceClient.close()` marks the client closed before teardown. A second concurrent close returns while the first still awaits native unload. A suspended-unload fixture reproduced the early return. `ModelDiscovery.close()` uses the same early-return pattern. | Share a shutdown completion result; every close caller must await it. R5, P1/P4. |
| P2 | The new `jni_utf8_test` puts all conversion calls/checks inside `assert`, while its Release compile flags include `-DNDEBUG`. Its CTest pass therefore does not execute the asserted conversions. | Use checks active in Release or enable assertions for this target. An independent `-UNDEBUG` build passed the current fixtures; actual JNI/CheckJNI coverage remains pending. R19, P5/P8. |

### Review verification

- Portable `verifySdk` passed; its initial JVM test tasks were up to date.
- Fresh standard JVM execution used a temporary Gradle init script to disable
  test up-to-date reuse: 1,481 tests, zero failures, zero skipped. `verifyProject
  --continue` failed only at the existing native submodule remote allowlist check.
- Five additional contract tests failed as expected for the defects above:
  streaming-open behavior, inherited redirect authorization, closed-stage abort,
  concurrent close completion, and partial-load artifact ownership. Review test
  sources were injected from `/tmp/caraml-architect-review` and were not added to
  production or repository test sources. The later standard run excluded them.
- `:nativeEngine:mergeDebugNativeLibs -Pandroid.injected.build.abi=arm64-v8a`
  succeeded; inspection of its output established the missing-library finding.
  This is packaging-stage evidence, not an installed APK/device execution test.
- R8 evidence uses a small Java fixture and the actual llama consumer rules; it
  does not replace the full optimized Android consumer gate.
- Review scripts, reproduction logs, R8 mapping, and the assertions-enabled
  native fixture are in `/tmp/caraml-architect-review`. Temporary files are local
  review evidence, not committed acceptance fixtures.

### Simplification recommendations

- Complete one local-file llama consumer with correct cancellation, shutdown,
  resource readings and Android packaging before extending persistence, media,
  recommendations or recovery APIs. Publication can remain deferred. This follows
  the architecture's first-deliverable sequencing and reduces simultaneous design
  decisions.
- Give shutdown and operation finalization one shared implementation pattern.
  Core and both plugins repeat single-use flags, operation-ID sets, mutexes and
  cleanup paths. Keep the low-level native ownership guard, but make each layer's
  responsibility explicit and reuse proven lifecycle code.
- Separate session configuration from per-operation request data. Diffusion binds
  its loaded session to the original operation ID, seed and sampler, forcing a
  reload for a subsequent image. Reuse a loaded model while reassessing changed
  workload bounds.
- Defer general durable-download claims until a coordinated store and restart
  protocol exist. For the current prototype, an explicit transfer executor is a
  smaller contract. Durable observation should be a separate read-only flow;
  collecting `download()` currently claims and executes work.
- Centralize native profile parsing/output paths shared by nativeEngine and both
  runners. Remove unused discovery content-negotiation/serialization adapters and
  platform transport implementations if the host-injected-client API remains the
  sole supported construction path.
- Batch download checkpoints and retain a buffered staged writer where feasible.
  The current path performs a file stat/open/append/close plus task update for
  every 64 KiB chunk. Measure the change with a representative model-sized fixture
  before claiming download-performance parity.


## Corrective implementation — 2026-10-14

The follow-up addresses all eight review findings and removes two Gradle projects.
`plugin-llama` now owns the former `runner` sources, tests, keep rules and iOS
bindings; `plugin-diffusion` owns the former `diffusionRunner` equivalents.
JNI package/class names and native library names remain unchanged. App dependencies,
CMake source paths, CI parity commands and architecture documentation use the plugins.
The complete checkout now has eleven Gradle projects (thirteen before consolidation).

| Review finding | Correction | Regression evidence |
|---|---|---|
| Android omitted runtime libraries | Remove the runner-only AGP target filter; CMake still controls engine selection. | Android packaging inventory recorded below. |
| Buffered HTTP bodies | `ArtifactSource.withTransfer` scopes body consumption to Ktor's streaming `execute { ... }`; metadata bounds run inside that scope. | An unfinished response delivers its first chunk; oversized declared metadata is rejected before EOF. |
| Renamed JNI descriptor enums | `includedescriptorclasses` protects the classes named in retained configuration fields. | R8 Java fixture using both actual rule files preserves `LlamaLazyMode` and `DiffusionRuntimeBackend` names. Full optimized Android consumer remains a release gate. |
| Lost partial-load ownership | Plugins throw `UnreleasedEngineLoad` with a strong native owner on failed teardown; core quarantines owner, artifact lease and reservation, then rethrows the original failure/cancellation. | Plugin fake teardown failures retain the native gate; core tests retain artifact/capacity and preserve cancellation. |
| Inherited CDN authorization | A per-send Ktor hook strips Authorization from signed-transfer requests after inherited request/auth configuration. | Borrowed-client default bearer reaches Hub, is absent on CDN, and remains intact on subsequent host requests. |
| Unabortable closed stage | Separate current owner/generation identity from writable state. | Closed partial files are removed and writer ownership is freed; stale handles cannot delete resumed work. |
| Early concurrent close | Serialize shutdown through a dedicated mutex and retain its failure outcome for client, session and discovery callers. | Concurrent calls wait for native/transfer cleanup; repeated failed shutdowns report failure without releasing quarantined ownership. |
| Vacuous Unicode Release test | Replace `assert` with always-active checks. | Release CTest executes conversion fixtures with `-DNDEBUG` still enabled. End-to-end Unicode inference through JNI/CheckJNI remains a release gate. |

The injected-client discovery implementation no longer brings unused transport
engines, content-negotiation adapters or a serialization compiler plugin. The shared
native producer and native gate remain separate because both engines use one GGML
runtime; optional storage/discovery and the engine-neutral API/core split remain
useful consumer boundaries. Folder nesting alone would not reduce build modules.

Validation results:

- `./gradlew verifyProject --continue` with test up-to-date reuse disabled ran
  1,491 JVM tests: composeApp 1,244; huggingFaceManager 140; core 14; storage 13;
  model-discovery 11; native-runtime 1; plugin-llama 40; plugin-diffusion 28.
  Zero failures, errors or skipped tests. This includes ten new regression tests.
- Release native CTests passed: artifact filesystem 1/1, diffusion 5/5 and Unicode
  conversion 1/1. The Unicode check uses always-active calls, including under NDEBUG.
- The full gate failed only at the existing `verifyNativeSubmodulePins` check:
  the local llama.cpp origin does not match the allowlisted public HTTPS remote.
  The check and remote configuration were not changed.
- Both R8 fixture enum class mappings preserve their original names. This is a
  targeted classfile obfuscation probe, not a full optimized APK execution test.

- Android arm64 merged native inventory now includes 15 native-engine libraries,
  including `libartifact_fs.so`, all seven `libggml-cpu-android_*` variants and
  `libomp.so`. The app merge contains the same required libraries. Before the
  correction, the native merge contained six libraries.

- Android arm64 debug APK assembly passed. Inspection of
  `androidApp/build/intermediates/apk/debug/androidApp-debug.apk` confirms 18
  native libraries, including artifact filesystem, all seven GGML CPU variants
  and OpenMP. This run does not establish x86_64/device execution or optimized
  external-consumer acceptance.
- Both plugins passed `compileKotlinIosArm64` and
  `compileKotlinIosSimulatorArm64`, including their moved cinterop headers,
  rebuilt native archives and archive merges. No installed iOS inference run is
  implied by compilation.

- `CARAML_NATIVE_PARITY=true ./gradlew :plugin-llama:jvmTest
  :plugin-diffusion:jvmTest` passed with `-Xcheck:jni` injected into both test JVMs.
  All 68 plugin/bridge tests passed with the opt-in cases enabled: the digest-pinned
  TinyStories GGUF preflighted, loaded and unloaded, and corrupt diffusion input
  was rejected on repeated probes. This exercises actual desktop JNI but does not
  establish successful diffusion generation, Unicode inference or device parity.
- `git diff --check` passed. All 82 tracked paths under the former bridge modules
  have destinations in the corresponding plugins, including the moved bridge
  documentation and build configuration.

Temporary logs are `/tmp/caraml-fix-verify.log`,
`/tmp/caraml-fix-platforms.log`, `/tmp/caraml-fix-native-parity.log` and
`/tmp/caraml-fixed-apk-inventory.txt`. The R8 mapping is
`/tmp/caraml-architect-review/r8/fixed-mapping.txt`. The ten regression tests live
in the repository; temporary logs and fixtures are local verification evidence. The earlier
review results above describe the pre-fix state and are retained for traceability.


## Provider consolidation — 2026-10-14

The standalone `huggingFaceManager` Gradle project is removed, reducing the checkout
from eleven to ten projects. Its responsibilities now have these owners:

| Source group | Owner | Reason |
|---|---|---|
| Hub API, metadata DTOs/projections, HTTP factories, repositories/use cases and API tests | `model-discovery` | Provider implementation alongside neutral browse/transfer contracts |
| Existing platform downloads, manifests, native secure filesystem calls, recovery tests and desktop packaged-image smoke | `composeApp` | Existing host integration with persisted identities and native packaging |
| Curated diffusion install catalog/setup/checker and Diffusers filename normalization | `composeApp` | Existing application installation policy, outside generic discovery |

Thirty-nine source/test files moved to discovery and fifty-seven to the app;
all ninety-six match their original indexed contents byte for byte.
Existing Kotlin package names and artifact-filesystem JNI names remain unchanged.
The old module's empty Android manifest was removed; the app continues to own its
permissions and background components. The compatibility API reference is retained
in `model-discovery/docs/huggingface-compatibility.md`.

`ModelProvider` and `ArtifactSource` remain the provider extension contracts. A
facade selects one provider; consumers may construct multiple facades over shared
stores. `ProviderIsolationTest` uses two non-Hub providers with identical remote
artifact IDs and opaque non-Git revisions, verifies browse/resolve/download, rejects
cross-provider candidate resolution and checks separate stored bytes after closing
one client. It requires neither an HTTP client nor native runtime. Federated search
and a provider registry are not added without a concrete consumer need.

The app still uses its existing compatibility API and download workflow; this move
does not complete P7 migration or replace persisted data formats. Discovery now
includes the transports and serialization adapters actually used by the retained
compatibility factory. SDK callers can still inject their own transport/provider;
custom providers do not instantiate the built-in factory. The SDK-only dependency
boundary continues to exclude the app and native engine producers.

Validation after consolidation:

- `./gradlew verifySdk -PsdkOnly=true` passed with no app or native producer
  configuration.
- The full `verifyProject --continue` run with test up-to-date reuse disabled
  passed 1,492 JVM tests: composeApp 1,346; model-discovery 50; core 14; storage 13;
  native-runtime 1; plugin-llama 40; plugin-diffusion 28. No failures, errors or
  skipped tests. The same seven native CTests passed.
- The full gate still fails only at the existing submodule remote allowlist
  check; the llama.cpp origin and guard remain unchanged.

- Android arm64 debug APK assembly passed. Its arm64 native inventory includes
  artifact filesystem, all seven GGML CPU variants and OpenMP. This checks
  packaging, not execution on a device.
- `model-discovery` and `composeApp` passed `compileKotlinIosArm64` and
  `compileKotlinIosSimulatorArm64`, including the moved platform implementations
  and rebuilt plugin native archives. No installed iOS execution is implied.
- `:composeApp:artifactFsInstalledImageSmoke` passed against a freshly generated
  desktop application image, exercising filesystem create/write/sync/read through
  the relocated JNI caller and packaged native library.
- All retired root folders are absent, active build/CI references use the new
  owners, and `git diff --check HEAD` passed.

Local logs: `/tmp/caraml-hub-sdk.log`, `/tmp/caraml-hub-verify.log`,
`/tmp/caraml-hub-platforms.log` and `/tmp/caraml-hub-packaging.log`.

The earlier corrective results above describe the preceding module layout and
are retained as historical evidence.

The root `samples/` folder contained only generated Gradle state/reports and empty
source directories; it has been removed. The root `scripts/` folder is also
removed, with its useful SVG icon generator relocated to
`composeApp/tools/generate_app_icons.py`. Running it from outside the repository
produces identical icons apart from the updated provenance comment. Documentation
now uses the new path; the stale reference to a nonexistent shader-generation
script was replaced with the actual CMake/NDK build mechanism. The native module's
own pin-check script remains in place and wired into verification.
