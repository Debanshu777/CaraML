# Task 8 report — documentation, full verification, and connected-device acceptance

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Task start: `5fe9a6e6599f784a9f16eb7b73e6aeefac452306`
Implementation commit: `58e28e9` (`fix(models): complete installed model device acceptance`)
Device: Pixel 9, serial `48221FDAQ003AT`
Package: `com.debanshu777.caraml`

## Outcome

The existing `openbmb/MiniCPM5-2B-GGUF / MiniCPM5-2B-Q4_K_M.gguf` installation was preserved, repaired online through exact evidence, loaded through the exact V2 Llama path, and generated a response. With Wi-Fi and mobile data disabled, a force-stop/relaunch loaded the same installed artifact and generated again without a metadata fetch. Connectivity and the user's original GPU and F16/F16 KV settings were restored afterward.

Device acceptance exposed several product defects. The scoped fixes are included in `58e28e9`; no legacy loading fallback was added.

## Implemented

- Restricted the strict Hugging Face recommendation-detail request to the bounded fields consumed by descriptor construction, avoiding rejection of unrelated mutable response fields.
- Made absent optional `config.json` metadata non-blocking for an exact repository lookup.
- Added a bounded local GGUF metadata inspector (4 MiB maximum scan, bounded entry/array/count/string sizes) that reads compatibility-critical version, architecture, context, and transformer shape from the already verified exact artifact. Conflicting or malformed metadata fails closed.
- Persisted locally enriched complete evidence so subsequent offline selections reuse the exact descriptor.
- Fixed the model-selection navigation mutation so the Navigation3 back stack never becomes transiently empty.
- Fixed resource-snapshot timing by sampling assessment time after the platform resource capture, preventing a legitimate later resource timestamp from being rejected as future/stale.
- Added safe actionable assessment/admission copy and a distinct `NATIVE_PREFLIGHT_INVALID` reason. Native preflight logs only bounded reason enums, never paths, prompts, or model data.
- Updated only the relevant `README.md` and `composeApp/README.md` Recent Changes bullets. `huggingFaceManager/README.md` was intentionally unchanged because no public API contract changed.

## Automated verification

All final commands ran from the isolated worktree.

| Check | Result |
|---|---|
| `git diff --check` | PASS, no whitespace errors |
| Required focused `:composeApp:jvmTest` filters from the brief | PASS, 75/75 |
| Device-fix Compose regressions (`GgufMetadataInspector`, `DeviceSnapshotProvider`, `LoadAdmissionController`, `AppNavigationBackStack`, `HuggingFaceModelMetadataSource`) | PASS, 39/39 |
| `:huggingFaceManager:jvmTest --tests '*BoundedResponseTest'` | PASS, 18/18 |
| `./gradlew verifyProject --no-daemon` | PASS in 32s; 1,001 JVM tests, 0 failures/errors; native CTest 5/5 |
| No-legacy scan for `loadModel(model: LocalModelEntity`, `legacyLoad`, `selectedLoadRequest`, `ModelLoadRouter`, and `RecommendedModelLoadRequestResolver` under commonMain | PASS, no matches |
| Commit secret scan | PASS |

The first sandboxed Gradle invocation could not open the shared Gradle wrapper lock (`Operation not permitted`). The same command was rerun with the required shared-cache permission and passed; this was an environment permission failure, not a product failure.

The initially attempted Compose-module install task did not exist. The repository's actual Android application task was verified and used:

```text
./gradlew :androidApp:installDebug --no-daemon
```

Result: PASS, in-place update installed successfully. No package clear, uninstall, data replacement, model deletion, or database mutation command was used.

## Preserved-data evidence

Before the in-place install, the installed package was present and the Library UI showed one downloaded model:

- repository: `openbmb/MiniCPM5-2B-GGUF`
- artifact: `MiniCPM5-2B-Q4_K_M.gguf`
- displayed size: 1.45 GB
- state: Ready

After the final install and all online/offline acceptance work, `android layout --device=48221FDAQ003AT` again showed:

- `1 downloaded model`
- the same repository and artifact
- `1.45 GB`
- `Ready for chat`
- storage summary `Models: 1.5 GB`

This proves the package update preserved the existing app/model data at the user-visible contract boundary.

## Online acceptance

Networking was enabled. Logcat was cleared immediately before selection and inspected with bounded filters. Selecting the existing MiniCPM artifact completed exact repair and reached the exact Llama/native route; there was no “needs to be reassessed” or legacy fallback error.

Safe native evidence:

- exact model path supplied to the repository (path value was not logged in this report)
- final runtime plan: context 4096, 4 threads, batch 256, GPU layers 0
- model loaded in 629 ms
- native context ready; vocabulary size 130,560
- Chat UI reached the loaded/ready state

Prompt: `Reply with exactly ONLINE_OK`
Observed response: `ONLINE_OKONLINE_OK`
Generation: 18 tokens, 3.84 tokens/s, 4.42 seconds.

The response duplicated the requested marker, but it was a successful non-empty native generation containing the deterministic marker.

## Offline restart acceptance

After the online repair/load:

1. The current connectivity preferences were recorded.
2. Wi-Fi and mobile data transports were disabled with `svc`; no reset/airplane/destructive mode was used.
3. The app was force-stopped and relaunched without clearing data.
4. The same installed MiniCPM artifact was selected.
5. Exact native loading completed without a metadata/network prompt or fetch, proving the complete repaired evidence was persisted and reusable offline.

Safe native evidence:

- exact model path supplied
- final runtime plan: context 4096, GPU layers 0
- model loaded in 609 ms
- native context and model became ready

Prompt: `Reply with exactly OFFLINE_OK`
Observed response: `OFFLINE_OKOFFLINE_OK`
Generation: 19 tokens, 3.89 tokens/s, 4.62 seconds.

The duplicated marker is noted as above; generation itself succeeded offline.

## Connectivity and settings restoration

Restoration was completed before final verification:

- global Wi-Fi preference: `1`
- global mobile-data preference: `1`
- Wi-Fi service: enabled
- Wi-Fi transport: connected and validated
- VPN over Wi-Fi: connected and validated
- original GPU setting: enabled/restored
- original KV setting: F16/F16 restored

No device connectivity or inference preference remains in the temporary acceptance configuration.

## Product failures found and regression proof

1. Strict full Hugging Face responses rejected newly added fields; fixed with a bounded projection and an 18-test boundary suite.
2. Missing optional repository config blocked repair; fixed and covered by exact-lookup regression tests.
3. Repository tags/config did not supply sufficient compatibility metadata; fixed through bounded, exact local GGUF enrichment with malformed/conflict tests.
4. Search selection could empty the navigation back stack and crash; fixed atomically with two root/nested regressions.
5. Resource capture could appear future-dated relative to an earlier clock sample; reproduced deterministically and fixed with a 19-test provider suite.
6. Native preflight failure was conflated with invalid artifact identity; split into a safe diagnostic category and covered by admission/copy tests.

## Blocked / unverified / concerns

- The original GPU-enabled automatic plan was rejected by native preflight (`NATIVE_PREFLIGHT_INVALID`) for this artifact/device combination. Exact CPU loading and online/offline generation passed, but exact GPU loading/generation remains unverified. This concern was not hidden with a legacy fallback; the original GPU preference was restored.
- Physical-device iOS/Desktop behavior was not part of this Android acceptance. Their JVM/native compilation and tests are covered by `verifyProject`; no claim of physical-device acceptance is made.
- No screenshot was necessary because semantic Android layout output provided all required UI evidence.

## Self-review

- Scope is limited to exact installed-model repair/admission/loading, device-exposed navigation/snapshot defects, their regressions, and relevant Recent Changes documentation.
- All external response/file input remains bounded and validated; malformed or conflicting metadata fails closed.
- No secrets, model paths, prompts, or user data were added to production diagnostic logs.
- No legacy loading contract or compatibility fallback was restored.
- Final connectivity, settings, package data, and model presence were rechecked after acceptance.
