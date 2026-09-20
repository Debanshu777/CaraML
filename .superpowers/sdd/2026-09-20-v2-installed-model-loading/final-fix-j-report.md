# Final fix J report — post-teardown assessment and static CPU fallback

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `e5bdc48df56824c6b7147633144646d9e0fee811`
Implementation commit: the Fix J commit containing this report

## Outcome

Installed-model selection no longer assesses current device headroom while a superseded LLM or diffusion runner is still resident. Resolution is split into immutable evidence/artifact preparation and final resource assessment. Chat prepares evidence first, releases both native runner families once under the existing serialized owner, rechecks that the attempt is still current, captures final resources/settings, assesses and revalidates the exact artifact, and only then enters the selected native loader.

When a GPU-enabled static assessment ends specifically in `MEMORY_NO_FIT` or `NO_RUN_PLAN`, the resolver now runs an independent CPU-only assessment through the same workload, policy, exact-artifact request builder, and final artifact revalidation. A valid CPU result is returned as a typed safe alternative and is never loaded automatically. The user must explicitly choose `Use safer plan`; invalid metadata, unsupported format/engine evidence, stale identity, CPU no-fit, and non-accelerated primary assessments remain blocked.

No legacy loading route or inferred-path fallback was added.

## Ownership and ordering

The final sequence is:

1. Resolve complete immutable descriptor evidence and the exact installed artifact identity.
2. If preparation is terminal, report the typed failure without releasing a resident model.
3. Claim/check the current model-load owner and release text plus diffusion runners once in a non-cancellable native teardown section.
4. Recheck cancellation, generation, selected model, and generation mode after teardown.
5. Capture final device resources and current settings, construct the bounded workload, assess, personalize, and rebuild a strict exact `LoadRequest`.
6. Revalidate the artifact bytes and exact bindings in the existing strict request builder.
7. Enter only the runner selected by the assessed request.

The runner-needed flag is cleared only after both releases succeed and is set again at native-load entry. Confirmation and retry continuations therefore do not repeat an already completed pre-assessment teardown, while a native attempt that may have initialized or loaded state remains eligible for later cleanup.

A successor can change the selected model while a prior release is already inside the non-cancellable native section, but it cannot claim the serialized runner owner or overtake that release. The releasing attempt rechecks its identity immediately afterward and exits through cancellation before snapshot, assessment, or load. The successor then prepares and assesses against the released state.

## Static CPU alternative contract

- Static fallback is considered only when GPU use is enabled, the captured profile actually contains a non-CPU backend, and the primary outcome is `MEMORY_NO_FIT` or `NO_RUN_PLAN`.
- The CPU attempt uses a CPU-only copy of the same final snapshot, preserving host/shared/storage budgets and current workload/settings, including explicit KV-cache choices.
- The alternative must be a separately assessed exact CPU `LoadRequest` with a nonblank matching assessment key, matching artifact identity, no nested backend alternative, and no inherited risk acknowledgement.
- `LoadAdmission.SafeAlternativeAvailable` is a distinct typed admission. Chat renders it as an explicit `Use safer plan` decision and performs the normal fresh native admission/preflight path only after acceptance.
- The existing exact LLM CPU backend alternative for native accelerator-preflight incompatibility remains unchanged.
- Diffusion and LLM requests share the static policy path; no CPU alternative is synthesized from an accelerator plan.

## TDD evidence

The first RED run was `:composeApp:compileTestKotlinJvm`. It failed on the intentionally absent split-preparation API, typed `SafeAlternative` resolution/admission/action, and post-release helper signature. Production changes were written only after that failure.

A later focused RED run added the rule that a CPU-only primary must not be assessed a second time. `InstalledModelLoadRequestResolverTest.notSuitableWithSelectedPlanIsNotAdmissible` failed with two assessments instead of one. The implementation then required an actual accelerator in the captured snapshot before considering static CPU fallback.

Focused command:

```text
./gradlew :composeApp:jvmTest \
  --tests '*InstalledModelLoadingTest*' \
  --tests '*InstalledModelLoadRequestResolverTest*' \
  --tests '*ChatViewModelRetryTest*' \
  --tests '*ChatViewModelQuarantineRetryTest*' \
  --no-daemon
```

Final result: PASS, 54/54 tests, zero skipped/failures/errors:

- `InstalledModelLoadRequestResolverTest`: 26/26
- `InstalledModelLoadingTest`: 14/14
- `ChatViewModelQuarantineRetryTest`: 12/12
- `ChatViewModelRetryTest`: 2/2

Coverage includes:

- resident model A making a hypothetical pre-release B assessment fail, followed by one serialized release, B assessment with no resident model, and exact B load;
- exact `prepare -> release -> assess -> load` ordering and no duplicate Chat-owned release;
- model switch during a non-cancellable release, with the stale attempt performing neither assessment nor native load afterward;
- cancellation propagation and the existing predecessor/native ownership barrier;
- GPU memory-no-fit to separately assessed exact CPU fit for both LLM and diffusion;
- GPU no-plan to exact CPU fit;
- GPU and CPU both no-fit;
- invalid metadata, unsupported format, unsupported engine feature, stale manifest, and inconsistent identity failing closed without a CPU offer;
- explicit user acceptance before a static CPU request enters either runner;
- exact artifact, assessment-key, mode, workload, KV-cache, and backend bindings;
- preservation of native-preflight CPU fallback and quarantined retry behavior.

## Repository and platform verification

```text
git diff --check
./gradlew verifyProject --no-daemon
```

`verifyProject` PASS in 45 seconds. JVM: 1,130/1,130 tests across 144 suites with zero skipped/failures/errors (`composeApp` 989/120, `huggingFaceManager` 91/15, `runner` 29/6, `diffusionRunner` 21/3). Native: artifact-root CTest 1/1 and diffusion CTests 5/5. Gradle reported 41 actionable tasks (14 executed, 27 up to date). `git diff --check` passed.

```text
./gradlew :composeApp:compileAndroidMain --no-daemon
```

PASS in 39 seconds; 25 actionable tasks (6 executed, 19 up to date).

```text
./gradlew :composeApp:compileKotlinIosSimulatorArm64 --no-daemon --max-workers=1
```

The task rebuilt/merged the iOS simulator native libraries and reached application Kotlin compilation. It failed in 52 seconds only at the pre-existing out-of-scope `GgufMetadataInspector.kt:20,73` Kotlin/Native `use`/nullable-generic errors already recorded by Fix I. No Fix J source appeared in compiler diagnostics, so app-wide iOS compilation remains independently blocked.

No-legacy scan:

```text
rg -n "loadModel\\(model: LocalModelEntity|legacyLoad|selectedLoadRequest|ModelLoadRouter|RecommendedModelLoadRequestResolver" composeApp/src/commonMain
```

PASS, no matches.

## Documentation

The root and `composeApp` Recent Changes sections now describe the post-teardown assessment ordering and explicit exact CPU alternative. Other module READMEs were intentionally unchanged because Fix J does not change their public contracts.

## Security and failure behavior

- Downloaded descriptors, manifests, component identities, paths, sizes, and hashes remain untrusted and go through the existing bounded exact-artifact validation.
- Static fallback has a reason allowlist. Metadata, format, engine, stale-evidence, and unknown-evidence failures cannot be converted into a runnable CPU request.
- The exact artifact is rehashed/revalidated when each primary or CPU request is constructed; a changed artifact produces typed rejection.
- Runner release failures return fixed generic UI text and do not expose exception details. Cancellation is never converted into an error result.
- No paths, model data, prompts, exception details, tokens, or secrets were added to logs or user-facing messages.

## Remaining verification boundary

- App-wide iOS simulator compilation remains blocked by the unchanged `GgufMetadataInspector.kt` portability errors above.
- No physical Android/iOS model was loaded for Fix J. Post-teardown ordering, exact CPU admission, user confirmation, and cancellation races are covered deterministically in common/JVM tests; Android common-app compilation passed.
- Existing expect/actual, Compose-test deprecation, missing `ccache`, OpenSSL/OpenGL, and native-toolchain warnings are unchanged.
