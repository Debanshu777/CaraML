# Final fix F report — bound evidence payload decoding

Date: 2026-09-20
Branch: `codex/v2-installed-model-loading`
Base: `40dc449d720ac5a46bb02db4b79a0103f64bba90`
Commit subject: `fix(models): bound evidence payload decoding`

## Outcome

Persisted model evidence now enforces its 262,144-byte UTF-8 limit without first allocating a byte array proportional to untrusted payload text. Decode rejects oversized or malformed UTF-16 before hashing or JSON parsing; encode applies the same preflight to its serialized output before hashing. Valid canonical payloads and SHA-256 values remain unchanged.

The old `payload.encodeToByteArray().size` boundary was avoidable resource consumption on attacker-controlled/corrupt persisted input (CWE-400). The secure fix is a common allocation-free capped scanner rather than a platform encoder or an unbounded intermediate buffer.

## Implementation

- Added `cappedUtf8ByteCount`, a common `String` scanner whose contract is a non-negative within-limit byte count or one of two rejection sentinels.
- Counted ASCII, 2-byte BMP, 3-byte BMP, and valid supplementary pairs as 1, 2, 3, and 4 UTF-8 bytes respectively.
- Rejected unpaired high/low surrogates instead of accepting platform-dependent replacement encoding.
- Used `byteCount > limit - width` before addition, so neither accumulation nor the boundary check can overflow.
- Returned immediately at the first malformed pair or byte that would exceed the cap; no payload-sized collection, encoded byte array, or buffer is created by preflight.
- Preserved `Evidence payload is too large` for size rejection and `Invalid evidence payload` for malformed UTF-16 without including payload contents.
- Updated root and `composeApp` Recent Changes. The `huggingFaceManager` public contract did not change, so its README was not modified.

## TDD evidence

Seven boundary/security tests were written before production code. The focused RED command was:

```text
./gradlew :composeApp:jvmTest --tests '*PersistedModelEvidenceTest' --no-daemon
```

The first sandboxed attempt stopped at the shared Gradle wrapper lock and was rerun with approved shared-cache access; that was an environment boundary, not a product result. The approved run failed compilation exactly because `cappedUtf8ByteCount`, `UTF8_BYTE_COUNT_LIMIT_EXCEEDED`, and `UTF8_BYTE_COUNT_MALFORMED` did not yet exist. No unrelated compile or test failure appeared.

After the minimal implementation, the same command passed 22/22 tests in `PersistedModelEvidenceTest`, including:

- exact-boundary and boundary-plus-one ASCII behavior;
- 2-/3-byte BMP and 4-byte supplementary accounting;
- isolated/mispaired high and low surrogate rejection;
- decode error ordering before digest work;
- very large hostile ASCII and multibyte inputs through the capped helper contract;
- all pre-existing round-trip, digest, tamper, unknown-field, descriptor, and invariant tests.

## Impacted GREEN evidence

```text
./gradlew :composeApp:jvmTest \
  --tests '*PersistedModelEvidenceTest' \
  --tests '*DownloadEvidenceFactoryTest' \
  --tests '*DownloadModelsTest' \
  --tests '*DownloadDatabaseTest' \
  --tests '*ModelDownloadFinalizerTest' \
  --tests '*AppDatabaseMigrationTest' \
  --tests '*InstalledModelEvidenceRepairerTest' \
  --tests '*InstalledModelLoadRequestResolverTest' \
  --tests '*InstalledModelLoadingTest' \
  --no-daemon
```

Result: PASS, 106/106 tests across 9 suites, with zero skipped/failures/errors.

`git diff --check` passed. A production-source scan found no remaining payload-wide `encodeToByteArray` size check in `PersistedModelEvidence.kt`.

## Repository gate

```text
./gradlew verifyProject --no-daemon
```

Result: PASS in 1 minute 20 seconds. The gate ran 1,070/1,070 JVM tests across 142 suites plus 5/5 native CTests, with zero failures/errors. JVM detail: `composeApp` 931 tests/118 suites, `huggingFaceManager` 91/15, `runner` 29/6, and `diffusionRunner` 19/3. Gradle reported 41 actionable tasks: 17 executed and 24 up to date.

## Security and scope review

- Persisted payload text remains untrusted and still must pass schema, lowercase SHA-256, canonical digest, unknown-field, descriptor, identity, path, and collection validation.
- The scanner prevents the prior proportional secondary byte-array allocation. The database/serialization layer necessarily supplies the original Kotlin `String`; this change does not claim to eliminate that source allocation.
- Digest construction is still bounded to at most 256 KiB and valid payload bytes/digests are unchanged.
- No payload, path, model identity, digest input, or exception detail is logged.
- No schema, migration, download protocol, inference contract, native runner, or platform-specific behavior changed.

## Remaining concerns

- Physical-device acceptance was not repeated for this pure common codec hardening. The changed boundary is covered by direct common/JVM tests, all durable evidence consumers, the full repository JVM gate, and all native CTests.
- Existing expect/actual, generic CMake architecture, missing `ccache`, and deprecated Compose-test warnings remain unchanged.

## Review follow-up

The Final Fix F production implementation was approved with one Minor test gap: hostile-input tests proved the rejection result but did not distinguish immediate limit exit from a scanner that continued into a malformed suffix.

The test-only follow-up adds literal ASCII and multibyte cases where the prefix already exceeds 64 bytes and an unpaired high surrogate follows. Both must return `UTF8_BYTE_COUNT_LIMIT_EXCEEDED`, proving the suffix is never inspected after the cap is crossed.

A temporary mutation changed the scanner to remember the exceeded state and continue through the remaining string. The new single-test run failed at the first assertion because the malformed suffix won. The mutation was immediately removed; `git diff` confirmed production returned byte-for-byte to the committed implementation. Post-revert results:

- evidence codec: 23/23 tests;
- impacted evidence/download/migration/finalizer/repair/load matrix: 107/107 tests across 9 suites;
- `verifyProject`: 1,071/1,071 JVM tests across 142 suites plus 5/5 native CTests, with zero failures/errors; JVM detail is `composeApp` 932/118 suites, `huggingFaceManager` 91/15, `runner` 29/6, and `diffusionRunner` 19/3;
- full gate time: 47 seconds; 41 actionable tasks, 14 executed and 27 up to date.

No production, README, schema, API, or runtime behavior changed in this follow-up.
