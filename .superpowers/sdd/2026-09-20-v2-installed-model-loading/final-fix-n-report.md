# Final Fix N Report — iOS completion provenance and import ownership

## Scope

Resolved the two confirmed iOS review findings without a Room schema change, legacy migration, or UI redesign.

## Root causes

- `didFinishDownloading` moved the URLSession temporary file before validating the completed task's actual response URL and status. Relaunch recovery retained only the payload, then fabricated a Hugging Face URL and HTTP 200 for import.
- A captured payload and its asynchronous importer were invisible to `isActive()`. Startup reconciliation could therefore downgrade the durable `RUNNING` row and enqueue replacement work while the delegate callback was importing or finalizing the same exact artifact.
- The iOS relaunch bridge started common reconciliation before registering the background-session handoff.

## Implementation

- The callback synchronously validates the actual final response as HTTPS, port 443, a bounded Hugging Face allowlisted host, and a 2xx status before moving the temporary file. Only the normalized origin and actual status survive; signed query data is not persisted.
- Capture atomically pairs the exact-size payload with a bounded, strict JSON completion envelope containing the canonical active task description, batch/artifact IDs, platform task ID, expected/completed bytes, derived capture path, and validated response provenance. Recovery rejects unknown, malformed, oversized, mismatched, or orphaned metadata and never supplies response defaults.
- The exact `(batch, artifact, platform task)` completion key owns one in-process import flight. Both the delegate and startup recovery join that flight. The envelope remains durable and counts as active until the current Room row transitions to verification, so common reconciliation cannot create a replacement owner.
- Import re-reads and rebinds the envelope to the current-only Room row before mutation and again before publishing its state. The final Room transition is one compare-and-set on `RUNNING`, exact platform task ID, and durable `RUN` intent, so a concurrent pause/cancel or replaced platform task cannot finalize stale work.
- Batch finalization is serialized and re-reads durable state under the lock; only a batch with at least one `VERIFYING` artifact and no non-ready artifact is finalized.
- The background relaunch bridge registers the URLSession handoff before starting common reconciliation, and scheduler enqueue/activity decisions wait for restoration to finish.

## TDD evidence

RED was observed before production edits: focused JVM test compilation failed because the typed response provenance, validated completion envelope/codec, and exact-key completion single-flight did not exist.

Focused tests cover:

- allowlisted actual redirects, status retention, signed-query stripping, and rejection of HTTP, alternate ports, attacker hosts, malformed URLs, and non-2xx responses;
- strict completion-envelope round trip across simulated process death and rejection of corrupted identity, byte count, response provenance, and oversized metadata;
- concurrent callback/reconciliation attempts executing one exact import flight.
- a stale iOS platform generation failing to mutate the replacement generation, followed by one exact completion transition that records full bytes, enters verification, releases its lease, and clears its task ID.

## Verification

Focused JVM/common and real-Room command:

```text
./gradlew :huggingFaceManager:jvmTest \
  --tests '*DownloadResponseProvenanceTest' \
  :composeApp:jvmTest \
  --tests '*IosDownloadResponseBoundTest' \
  --tests '*DownloadDatabaseTest.completionTransitionsOnlyTheExactBoundPlatformGeneration' \
  --no-daemon
```

Result: `BUILD SUCCESSFUL`.

Repository and Android gate:

```text
./gradlew verifyProject :androidApp:assembleDebug --no-daemon
```

Result: `BUILD SUCCESSFUL` in 5 minutes; JVM/native verification and Android debug packaging completed.

iOS compile:

```text
./gradlew :composeApp:compileKotlinIosSimulatorArm64 \
  :huggingFaceManager:compileKotlinIosSimulatorArm64 --no-daemon
```

Result: `BUILD SUCCESSFUL` after the native simulator archives, cinterops, Room KSP, and both Kotlin targets compiled.

Focused iOS importer test:

```text
./gradlew :huggingFaceManager:iosSimulatorArm64Test \
  --tests '*IosCompletedDownloadImporterTest' --no-daemon
```

The iOS test source compiled and its debug test binary linked. Execution was environment-blocked because the configured Xcode reported no supported `ios_simulator_arm64` test device/SDK runtime; Gradle failed while evaluating the test task's `device` property, before running the binary.

## Preserved behavior

- Background relaunch and completion-handler draining remain intact.
- Active, rejected, and stopped task dispositions retain their existing response-bound behavior.
- Pause, cancellation, and current-only durable row policy remain authoritative.
- No legacy state, fallback URL/status, database state, or migration was added.
