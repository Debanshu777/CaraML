# Task 8 report — documentation, full verification, and connected-device acceptance

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Task start: `5fe9a6e6599f784a9f16eb7b73e6aeefac452306`
Implementation commits:

- `58e28e9` (`fix(models): complete installed model device acceptance`)
- `eea1472` (`fix(models): harden installed model acceptance`)

Device: Pixel 9, serial `48221FDAQ003AT`
Package: `com.debanshu777.caraml`

## Outcome

The existing `openbmb/MiniCPM5-2B-GGUF / MiniCPM5-2B-Q4_K_M.gguf` installation was preserved, repaired online through exact evidence, loaded through the exact V2 Llama path, and generated a response. With Wi-Fi and mobile data disabled, a force-stop/relaunch loaded the same installed artifact and generated again without a metadata fetch. Connectivity and the user's original GPU and F16/F16 KV settings were restored afterward.

Device acceptance and review exposed several product defects. The scoped fixes are included in `58e28e9` and `eea1472`; no legacy loading fallback was added.

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

## Review fix round 1 — independently auditable evidence

### Fail-closed GGUF repair

The bounded GGUF reader now scans the entire declared metadata table before producing a result. Recognized numeric fields are collected independently of `general.architecture` ordering. It strictly rejects duplicate architecture or relevant numeric keys, wrong relevant value types, malformed/truncated/oversized entries anywhere in the declared table, non-divisible embedding/head shapes, and explicit head dimensions that disagree with the derived value. Enrichment is still invoked only after exact artifact resolution and never seeks or allocates beyond the existing limits (4 MiB scan, 16,384 metadata entries, bounded strings/arrays/counts).

Regression coverage added exact cases for numeric-before-architecture ordering, equal duplicate rejection, explicit/derived head mismatch, malformed trailing entries, and late duplicates. The initial focused RED run failed all five new parser regressions; the final parser class passed 8/8.

### Typed accelerator incompatibility

The bounded native evidence for the exact GPU-enabled request was:

```text
preflight: invalid (INVALID_MODEL)
```

The original GPU assessment contains only accelerator plans, so the first review implementation—which tried to derive a CPU fallback from the general recommendation—correctly found no CPU plan and the device still displayed `The native engine rejected this model before loading.` That failed hypothesis was removed.

The final fix assesses a CPU-only snapshot in the installed-model resolver while preserving the user's GPU preference. It runs the same shared policy, creates a separately keyed exact `LoadRequest`, revalidates the same artifact, then carries that request as a bounded backend alternative. Only the exact sequence “non-CPU native `Invalid` + bound CPU policy plan + CPU native `Fit`” produces `NATIVE_BACKEND_INCOMPATIBLE`. An absent, mismatched, malformed, or CPU-invalid alternative still returns the original `NATIVE_PREFLIGHT_INVALID`; unrelated `Invalid` was not converted into fallback.

The final UI under GPU enabled + F16/F16 showed:

```text
Safer configuration available
A lower-resource configuration is available for this device.
Use safer plan
```

Accepting it passed the full exact-request path. The safe native excerpt was:

```text
device: cores=4/8, memMB=2211, gpu=true
buildRunnerConfig: arch='llama', family=DENSE
load: model path supplied=1
load: Final params - n_ctx=4096, n_threads=4, n_threads_batch=4, n_batch=256, n_gpu_layers=0
load: Model loaded in 604 ms
load: Context ready, n_ctx=4096
load: Model ready (vocab_size=130560)
```

### Exact automated commands and results

```text
./gradlew :composeApp:jvmTest \
  --tests '*PersistedModelEvidenceTest' \
  --tests '*DownloadDatabaseTest' \
  --tests '*ModelDownloadFinalizerTest' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --tests '*InstalledModelLoadRequestResolverTest' \
  --tests '*InstalledModelLoadingTest' \
  --tests '*GgufMetadataInspectorTest' \
  --tests '*LoadAdmissionControllerTest' \
  --no-daemon
```

Result: PASS in 42 seconds, 95/95 tests, 0 failures/errors. This includes `GgufMetadataInspectorTest` 8/8, `LoadAdmissionControllerTest` 11/11, and `InstalledModelLoadRequestResolverTest` 21/21.

```text
./gradlew verifyProject --no-daemon
```

Result: PASS in 40 seconds; 1,009 JVM tests, 0 failures/errors; native CTest 5/5.

```text
./gradlew :androidApp:installDebug --no-daemon
```

Result: PASS in 50 seconds, `Installed on 1 device.` This was an in-place package update. No `pm clear`, uninstall, data deletion, database replacement, or model replacement was performed.

```text
rg -n "loadModel\\(model: LocalModelEntity|legacyLoad|selectedLoadRequest|ModelLoadRouter|RecommendedModelLoadRequestResolver" composeApp/src/commonMain
git diff --check
```

Result: both PASS; no legacy matches and no whitespace errors. The implementation commit hook also reported `Orca Security: Searching for hard-coded secrets...[PASSED]`.

### Exact final device sequence

Primary UI inspection used semantic layout output throughout:

```text
android layout --device=48221FDAQ003AT -p
adb -s 48221FDAQ003AT shell input tap <layout-derived-x> <layout-derived-y>
adb -s 48221FDAQ003AT shell input text 'Reply%swith%sexactly%sFINAL_ONLINE_OK'
```

Online final result: layout showed `FINAL_ONLINE_OKFINAL_ONLINE_OK`, 25 tokens, 4.01 tokens/s, 5.98 seconds. The repeated marker is model behavior; native generation succeeded.

The offline sequence was:

```text
adb -s 48221FDAQ003AT shell svc wifi disable
adb -s 48221FDAQ003AT shell svc data disable
adb -s 48221FDAQ003AT shell dumpsys connectivity | rg -m 1 'Active default network'
adb -s 48221FDAQ003AT shell am force-stop com.debanshu777.caraml
adb -s 48221FDAQ003AT logcat -c
adb -s 48221FDAQ003AT shell monkey -p com.debanshu777.caraml 1
android layout --device=48221FDAQ003AT -p
adb -s 48221FDAQ003AT shell input tap <layout-derived-x> <layout-derived-y>
adb -s 48221FDAQ003AT shell input text 'Reply%swith%sexactly%sFINAL_OFFLINE_OK'
adb -s 48221FDAQ003AT logcat -d -v time -t 3000 -s LlamaRunner:I Inference:I '*:S'
```

Before relaunch, `Active default network: none`; Wi-Fi was disabled and cellular `mDataConnectionState=-1`. After the offline force-stop/relaunch, layout again showed the typed safer-plan action without any metadata/network prompt, then `FINAL_OFFLINE_OKFINAL_OFFLINE_OK`, 23 tokens, 3.1 tokens/s, 7.07 seconds. The bounded log excerpt above proves exact CPU native load and generation; it contains no model path, prompt text, or user data.

Restoration ran immediately after log capture even if the filtered log command had produced no match:

```text
adb -s 48221FDAQ003AT shell svc wifi enable
adb -s 48221FDAQ003AT shell svc data enable
adb -s 48221FDAQ003AT shell settings get global wifi_on
adb -s 48221FDAQ003AT shell settings get global mobile_data
adb -s 48221FDAQ003AT shell dumpsys wifi | rg -m 1 'Wi-Fi is'
adb -s 48221FDAQ003AT shell dumpsys connectivity | rg -m 4 'Active default network|NetworkAgentInfo.*WIFI.*VALIDATED'
```

Final result: Wi-Fi `1`, mobile data preference `1`, `Wi-Fi is enabled`, active default network present, and Wi-Fi plus VPN validated.

Final semantic Library evidence after in-place installation remained `1 downloaded model`, `MiniCPM5-2B-Q4_K_M.gguf`, `openbmb/MiniCPM5-2B-GGUF`, `1.45 GB`, and `Ready for chat`. Final Settings layout showed `GPU acceleration (Vulkan)` checked and `KV cache F16/F16, selected` / `Current: F16/F16`.

### Why GPU was toggled in the earlier attempt

During the original Task 8 run, the GPU-enabled automatic plan returned `NATIVE_PREFLIGHT_INVALID`. GPU was temporarily toggled off solely to complete the initial CPU load/generation diagnosis, then restored. The review reacceptance described above did not toggle the preference: both final online and offline selections began with GPU enabled and F16/F16 selected, surfaced the typed policy-approved CPU alternative, and generated successfully after accepting it.

## Blocked / unverified / concerns

- Blocked: none.
- The accelerator plan itself remains a known rejected configuration for this exact artifact/device (`INVALID_MODEL`); GPU-native generation is therefore not claimed. Product usability with the user's original GPU preference is verified through the exact assessed CPU alternative, not a generic fallback.
- Physical-device iOS/Desktop behavior was not part of this Android acceptance. Their JVM/native compilation and tests are covered by `verifyProject`; no claim of physical-device acceptance is made.
- The model repeated each requested marker. This is a model-output quality observation, not a loading/generation failure.
- No screenshot was necessary because semantic Android layout output provided all required UI evidence.

## Self-review

- Scope is limited to exact installed-model repair/admission/loading, device-exposed navigation/snapshot defects, their regressions, and relevant Recent Changes documentation.
- All external response/file input remains bounded and validated; malformed or conflicting metadata fails closed.
- No secrets, model paths, prompts, or user data were added to production diagnostic logs.
- No legacy loading contract or compatibility fallback was restored.
- Final connectivity, settings, package data, and model presence were rechecked after acceptance.
