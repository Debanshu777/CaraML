# Final fix A report — preserve multi-repository manifests

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `57a0280c6c932c31663ee2ac425550009b3bfe71`
Commit subject: `fix(models): preserve multi-repository manifests`

## Outcome

`AppModule` now supplies `LocalArtifactIdentityResolver` with the validated owner bundle as the authoritative complete manifest. It no longer appends Room-linked external components that are already present in that published filesystem bundle, so multi-repository image and video installations retain one entry per role and repository/path and resolve successfully.

Null, cancellation, and failure behavior is unchanged. `ArtifactManifest.create` remains the only manifest construction boundary and its duplicate/conflict checks were not weakened.

## Implementation

- Added the small internal `installedModelManifestSource` factory used directly by production Koin wiring.
- The factory delegates only to `DownloadManager.validatedBundle(ownerModelId)` and returns that exact validated manifest.
- Removed production calls that re-read Room links and external per-repository manifests during installed-model resolution.
- Added production-wiring integration coverage for owner-primary plus external image artifacts and owner-primary plus external video artifacts.
- The integration tests assert exact component coverage, unique bundle-role and repository/local-path keys, resolver success, and identity preservation of the authoritative manifest.
- Added boundary coverage proving duplicate entries remain rejected, plus null/cancellation/failure propagation coverage.
- Updated the relevant root and `composeApp` Recent Changes bullets. `huggingFaceManager/README.md` was intentionally unchanged because the download manifest format and public API did not change.

## RED evidence

Command:

```text
./gradlew :composeApp:jvmTest --tests '*AppModuleManifestSourceTest' --no-daemon
```

The initial sandboxed invocation could not open the shared Gradle wrapper lock (`Operation not permitted`). It was rerun with shared-cache permission; this was an environment gate, not a product result.

The corrected RED run failed compilation only because `installedModelManifestSource` did not exist. The compiler reported the unresolved production seam at every test use. No production code had been changed at that point.

## GREEN evidence

Focused production-wiring regression:

```text
./gradlew :composeApp:jvmTest --tests '*AppModuleManifestSourceTest' --no-daemon
```

Result: PASS, 6/6 tests, zero skipped/failures/errors.

Impacted manifest, resolver, repair, and diffusion suites:

```text
./gradlew :composeApp:jvmTest \
  --tests '*AppModuleManifestSourceTest' \
  --tests '*LocalArtifactIdentityResolverTest' \
  --tests '*InstalledModelLoadRequestResolverTest' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --tests '*DiffusionArtifactReadinessTest' \
  --tests '*DiffusionBundleProjectionTest' \
  :huggingFaceManager:jvmTest \
  --tests '*ArtifactBundleManifestStoreTest' \
  --no-daemon
```

Result: PASS, 63/63 tests across seven suites, zero skipped/failures/errors.

Repository gate:

```text
git diff --check
./gradlew verifyProject --no-daemon
```

Result: PASS in 42 seconds. JVM: 1,016/1,016 tests across 138 suites, zero skipped/failures/errors. Native CTest: 5/5 passed. Gradle reported 41 actionable tasks.

## Self-review

- Re-read the final-fix brief and checked the diff against each required behavior.
- Confirmed production uses the same internal factory exercised by the integration tests.
- Confirmed the validated owner bundle is returned directly without reconstruction, deduplication, or Room component reads.
- Confirmed valid image/video fixtures contain external repositories and resolve every manifest entry exactly once.
- Confirmed duplicate manifests still fail at `ArtifactManifest.create` and no manifest validation code changed.
- Confirmed cancellation and arbitrary failures are not caught or converted by the factory; the resolver retains its existing cancellation/rejection handling.
- Confirmed no download format, database schema, dependency, secret, telemetry, or user-visible error behavior changed.
- Confirmed `git diff --check` passes.

## Concerns and verification boundary

- No product concern remains for fix A.
- The gate emits existing expect/actual and deprecated Compose test API warnings; there are no new warnings attributable to this change.
- Physical-device acceptance was not repeated because this wiring fix is covered at the real manifest/resolver filesystem boundary and the required JVM/native repository gate passed.
