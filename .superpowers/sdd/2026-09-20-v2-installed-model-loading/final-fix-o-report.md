# Final Fix O Report — exact storage admission and bounded revision replacement

## Scope

Resolved confirmed findings 6–7 only: admission used descriptor-relative paths instead of the final immutable download request set, and successful same-owner revisions could retain old manifest entries until the root's 64-entry cap blocked replacement.

## Implementation

- Language and diffusion flows now construct the final `DownloadArtifactRequest` list before admission and reuse that same list for storage evidence and durable enqueue.
- `DownloadManager.inspectStorage` validates immutable metadata under the shared artifact-root locks, recovers each root manifest, and reports only the canonical `destinationRelativePath`, its real staged checkpoint, target size, and exact publication state. Repository-relative descriptor paths no longer participate in production admission.
- Admission accounts zero bytes for an exact published generation, otherwise counts missing final bytes plus the largest sequential temporary deficit per volume. Invalid, oversized, duplicate, or quarantined observations remain `NeedsInformation`.
- Root manifests retain a bounded 128-entry replacement window while owner bundles remain capped at 64. A durable owner replacement plan records the prior exact bundle before the new bundle is published and survives restart until cleanup is acknowledged.
- Finalization publishes and validates the new bundle, transactionally replaces the Ready catalog, prunes only prior entries with no remaining target or generation-root catalog reference, and then acknowledges the replacement plan. Cleanup failures are retryable and leave the new bundle/catalog current.
- Cleanup is idempotent across a crash after manifest pruning but before byte deletion; shared external components remain untouched while referenced by another installed catalog.

## TDD evidence

RED was observed before production edits when the exact-request estimator and `exactPublished` inventory state did not exist.

Focused coverage includes canonical-path versus repository-relative decoys, complete/shared/partial byte accounting at a one-byte threshold, real manifest checkpoints, a full 64-entry A-to-B replacement, repeated revision pruning without growth, durable replacement-plan restart, shared external references, and cleanup retry after both catalog publication and manifest pruning.

## Verification

Focused JVM/HFM regression gate:

```text
./gradlew :composeApp:jvmTest --tests '*ModelDownloadFinalizerTest' \
  :huggingFaceManager:jvmTest --no-daemon --no-parallel -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL`.

Repository and Android gate:

```text
./gradlew verifyProject :androidApp:assembleDebug \
  --no-daemon --no-parallel -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL` in 1 minute 54 seconds; 1,067 Compose JVM tests, HFM/runner/diffusion JVM tests, native artifact/diffusion tests, and Android debug packaging completed.

iOS compile gate:

```text
./gradlew :composeApp:compileKotlinIosSimulatorArm64 \
  :huggingFaceManager:compileKotlinIosSimulatorArm64 \
  --no-daemon --no-parallel -Pkotlin.incremental=false
```

Result: `BUILD SUCCESSFUL` in 1 minute 18 seconds, including native simulator archives, cinterop, Room KSP, HFM, and Compose Kotlin compilation.

## Preserved behavior

- Owner publication and artifact-root lock ordering remain authoritative.
- Owner bundle size remains capped at 64.
- Current-only Room schemas and destructive stale-schema recreation remain unchanged; no migration or legacy path was added.
- New/current publication is never rolled back because superseded-byte cleanup failed.
