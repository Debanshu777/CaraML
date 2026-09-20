# Final fix E report — remove legacy artifact loader

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `7f938e0742883174c0f388b9de52e2dfa6617ff2`
Commit subject: `refactor(models): remove legacy artifact loader`
Review follow-up subject: `test(models): guard removed artifact loader symbols`
Review follow-up 2 subject: `test(models): harden legacy loader syntax guard`
Review follow-up 3 subject: `test(models): make legacy loader guard Kotlin-aware`
Review follow-up 4 subject: `test(models): lex legacy loader string templates`

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

## Review follow-up

The first Final Fix E review approved production and found one Minor gap in the structural regression: the test did not automatically forbid the removed `resolvePersistedHub` alias or every deleted sidecar helper/constant named in the brief. That round expanded the forbidden set without changing production.

A deliberate temporary `resolvePersistedHub` mutation produced the intended RED: the structural suite ran 2 tests and failed `productionArtifactResolutionHasNoLegacyLocalIdentityPath`. The mutation was immediately removed. The post-revert resolver and structural suites then passed 17/17 tests across 2 suites. No production change is part of the follow-up.

The second review found one Minor test-only weakness: the first follow-up's comment-removal regex was not Kotlin string, raw-string, or nested-comment aware. The regex preprocessor and whole-`commonMain` token scan are now gone. The structural guard reads only `LocalArtifactIdentityResolver.kt` and uses declaration/call/type/constant-shaped patterns for every removed API, helper, model, constant, sidecar path literal, `RevisionIdentity.LocalContent`, and the legacy resolving request overload.

Permanent mutation fixtures prove URL, raw-string, and nested-comment prose does not match while every removed syntax shape does. A harmless temporary real-resolver mutation containing those documentation forms passed all 3 structural tests. Replacing it with real `resolvePersistedHub()` and `readSidecar()` declaration/call syntax produced the intended RED: 3 structural tests ran and the production-source assertion failed. Both mutations were removed; the post-revert resolver and structural suites passed 18/18 tests across 2 suites. Production remains untouched.

The third hardening review found one remaining Minor test gap: shaped regexes against unsanitized source could still match code-shaped text inside strings or comments. That round introduced a small Kotlin-aware lexical pass with explicit code, normal-string, raw-string, character, line-comment, and nested-block-comment states. It preserved code plus line positions, blanked non-code regions, honored escapes, and kept unterminated non-code blank through EOF.

The ignored-source fixture placed real-looking legacy declarations, calls, types, constants, URLs, escapes, and the sidecar filename inside normal strings, raw strings, chars, line comments, and nested block comments; it remained clean and proved output length/newline preservation. Unterminated normal/raw/char/comment fixtures proved conservative EOF behavior. The detection fixture covered all prior symbols plus `reuseUnchanged`, `toSidecar`, `readBounded`, `LEGACY_MANIFEST_VERSION`, `MAX_MANIFEST_BYTES`, and `MAX_CHANGE_STAMP_LENGTH`. A temporary real resolver alias/helper/constant chain produced the intended RED: 4 structural tests ran and the production-source assertion failed. After immediate reversion, the resolver and structural suites passed 19/19 tests across 2 suites. Production remained untouched.

The fourth review found one Important test-only false negative: Kotlin string interpolation is executable code, but the third-round lexer blanked all normal/raw string contents. The lexer is now a context stack that restores the containing string after each interpolation and copies executable `$identifier` and balanced `${...}` expressions into the code view. Template expressions support nested braces, normal/raw strings, escaped characters, line comments, nested block comments, and recursively nested string templates. Non-template string/comment prose stays blank, `${'$'}` remains a literal-dollar construction rather than exposing the following prose, and an unterminated template keeps its executable portion visible through EOF. Exact `.caraml-local-identity-v1.json` literals are captured in both normal and triple-quoted raw code values.

Permanent mutation fixtures prove normal and raw `${...}` calls/declarations, nested normal-to-raw and raw-to-normal templates, and shorthand `$resolveLegacy` are detected, while identical code-shaped prose and comments remain clean. A temporary real resolver raw-string `${resolveLegacy()}` mutation, backed by a helper outside the scanned resolver, produced the intended RED: 6 structural tests ran and only the production-source assertion failed with `resolveLegacy code identifier`. The mutation was immediately removed. The post-revert resolver and structural suites passed 21/21 tests across 2 suites. Production remains untouched.

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

The production source scan returned no matches for `allowLegacyFallback`, `resolveLegacy`, `resolvePersistedHub`, `readSidecar`, `writeSidecar`, `deletePart`, `sidecarPath`, `reuseUnchanged`, `toSidecar`, `readBounded`, `RevisionIdentity.LocalContent`, `.caraml-local-identity-v1.json`, `LegacyIdentitySidecar`, `SidecarComponent`, `MANIFEST_FILE_NAME`, `LEGACY_MANIFEST_VERSION`, `MAX_MANIFEST_BYTES`, `MAX_CHANGE_STAMP_LENGTH`, or `suspend fun createLoadRequest(`. A separate scan confirmed `LegacySuitabilityAdapter` remains in its recommendation source and Koin registration. The automated guard lexically isolates code in `LocalArtifactIdentityResolver.kt`, including executable normal/raw string-template expressions, before applying resolver-scoped identifier patterns; generic tokens in non-template strings, comments, tests, or unrelated manifest stores cannot trip it.

`git diff --check` passed.

## Repository gate

```text
./gradlew verifyProject --no-daemon
```

Final clean-state result after review follow-up 4: PASS in 31 seconds. The gate ran 1,063/1,063 JVM tests across 142 suites plus 5/5 native CTests, with zero skipped JVM tests and zero failures/errors. JVM detail: `composeApp` 924 tests/118 suites, `huggingFaceManager` 91/15, `runner` 29/6, and `diffusionRunner` 19/3. Gradle reported 41 actionable tasks: 14 executed and 27 up to date.

## Security and scope review

- Missing or unreadable manifests fail closed; no local bytes can mint a replacement source identity.
- Manifest hashing still runs on the trusted dispatcher, rechecks storage snapshots, enforces aggregate byte bounds, and rethrows cancellation unchanged.
- Absolute/local paths still come only from the trusted storage provider and verified manifest topology; persisted evidence cannot supply them.
- No compatibility shim, deprecated alias, payload/path logging, schema migration, network contract, download behavior, or native runner behavior was added.

## Remaining concerns

- A pre-existing local sidecar file is deliberately ignored, not deleted. The app never reads or trusts it; deletion is unnecessary filesystem mutation and could race an older process during upgrade.
- Physical-device acceptance was not repeated for this structural loader deletion. The prior exact-load device acceptance remains the relevant end-to-end evidence; this fix is covered by behavioral/structural JVM regressions, the full repository JVM gate, and all native CTests.
- Existing expect/actual, generic CMake architecture, missing `ccache`, and deprecated Compose-test warnings remain unchanged.
