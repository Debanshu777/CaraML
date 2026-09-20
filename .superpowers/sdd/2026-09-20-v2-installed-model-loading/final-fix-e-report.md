# Final fix E report — remove legacy artifact loader

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `7f938e0742883174c0f388b9de52e2dfa6617ff2`
Commit subject: `refactor(models): remove legacy artifact loader`

## Outcome

Installed artifact resolution is now immutable-Hub-manifest-only. `LocalArtifactIdentityResolver.resolve` is the sole public resolution entrypoint and rejects an absent, unreadable, or invalid authoritative manifest as `STALE_MANIFEST`. Repair retains one internal overload that accepts the validated manifest captured under the owner publication lock.

The dormant local-content fallback was an authenticity downgrade (CWE-345): it could hash local bytes, mint a non-Hub `LocalContent` identity, and persist a private sidecar even when no authoritative download manifest existed. The secure fix deletes that path rather than disabling it behind a flag.

## Implementation

- Deleted `allowLegacyFallback`, `resolveLegacy`, sidecar read/write/delete/cache helpers, sidecar DTOs/constants, and `.part` cleanup behavior.
- Deleted `RevisionIdentity.LocalContent`; `ResolvedLocalArtifact.revisionIdentity` is statically `RevisionIdentity.HubCommit`.
- Renamed the former strict public API to canonical `resolve(model, components)` and kept only the internal `resolve(model, components, manifest)` overload for a caller that already captured a validated manifest.
- Deleted the resolving `createLoadRequest(model, components, ...)` overload. `createLoadRequestFromVerifiedArtifact` is the only request constructor at this layer.
- Simplified revalidation and aggregate identity construction to require immutable revisions for every component while preserving exact repository, revision, path, object-ID, byte-count, content-hash, and typed load-target checks.
- Migrated evidence repair, installed load resolution, manifest wiring tests, and fixtures to the strict API.
- Removed legacy-only behavior tests and added missing-manifest/no-sidecar behavior plus a production-source structural regression.
- Updated root and `composeApp` Recent Changes. The unrelated recommendation `LegacySuitabilityAdapter` remains registered and tested.

## RED evidence

The new regressions were run before production changes:

```text
./gradlew :composeApp:jvmTest \
  --tests '*LocalArtifactIdentityResolverTest.missingHubManifestDoesNotReadOrRewriteLegacySidecar' \
  --tests '*LocalArtifactIdentityResolverStructureTest' \
  --no-daemon
```

Result: 2 tests ran and both failed for the intended reasons. The behavior test observed the permissive local resolution path instead of `STALE_MANIFEST`; the structural test found the legacy symbols and resolving request overload in production. The initial sandboxed attempt stopped at the shared Gradle wrapper lock and was immediately rerun with the approved shared-cache permission; that first stop was an environment boundary, not a product result.

## GREEN evidence

The resolver and structural suite passed 16/16 tests after the implementation:

```text
./gradlew :composeApp:jvmTest \
  --tests '*LocalArtifactIdentityResolverTest' \
  --tests '*LocalArtifactIdentityResolverStructureTest' \
  --no-daemon
```

The wider impacted application suites passed 85/85 tests across resolver, evidence repair, installed request reconstruction, Chat exact loading, Llama admission, diffusion execution/readiness, manifest DI, and recommendation compatibility:

```text
./gradlew :composeApp:jvmTest \
  --tests '*LocalArtifactIdentityResolver*' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --tests '*InstalledModelLoadRequestResolverTest' \
  --tests '*InstalledModelLoadingTest' \
  --tests '*LlamaInferenceRepositoryTest' \
  --tests '*AdmittedDiffusionExecutionStateTest' \
  --tests '*DiffusionArtifactReadinessTest' \
  --tests '*AppModuleManifestSourceTest' \
  --tests '*LegacySuitabilityAdapterTest*' \
  --no-daemon
```

The underlying manifest-store suites passed 20/20 tests:

```text
./gradlew :huggingFaceManager:jvmTest \
  --tests '*ArtifactManifestStoreTest' \
  --tests '*ArtifactBundleManifestStoreTest' \
  --no-daemon
```

## Structural proof

The production source scan returned no matches for `allowLegacyFallback`, `resolveLegacy`, `RevisionIdentity.LocalContent`, `.caraml-local-identity-v1.json`, `LegacyIdentitySidecar`, `SidecarComponent`, `resolvePersistedHub`, or `suspend fun createLoadRequest(`. A separate scan confirmed `LegacySuitabilityAdapter` remains in its recommendation source and Koin registration.

`git diff --check` passed.

## Repository gate

```text
./gradlew verifyProject --no-daemon
```

Result: PASS in 44 seconds. The gate ran 1,058/1,058 JVM tests across 142 suites plus 5/5 native CTests, with zero skipped JVM tests and zero failures/errors. JVM detail: `composeApp` 919 tests/118 suites, `huggingFaceManager` 91/15, `runner` 29/6, and `diffusionRunner` 19/3. Gradle reported 41 actionable tasks: 15 executed and 26 up to date.

## Security and scope review

- Missing or unreadable manifests fail closed; no local bytes can mint a replacement source identity.
- Manifest hashing still runs on the trusted dispatcher, rechecks storage snapshots, enforces aggregate byte bounds, and rethrows cancellation unchanged.
- Absolute/local paths still come only from the trusted storage provider and verified manifest topology; persisted evidence cannot supply them.
- No compatibility shim, deprecated alias, payload/path logging, schema migration, network contract, download behavior, or native runner behavior was added.

## Remaining concerns

- A pre-existing local sidecar file is deliberately ignored, not deleted. The app never reads or trusts it; deletion is unnecessary filesystem mutation and could race an older process during upgrade.
- Physical-device acceptance was not repeated for this structural loader deletion. The prior exact-load device acceptance remains the relevant end-to-end evidence; this fix is covered by behavioral/structural JVM regressions, the full repository JVM gate, and all native CTests.
- Existing expect/actual, generic CMake architecture, missing `ccache`, and deprecated Compose-test warnings remain unchanged.
