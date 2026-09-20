# Final fix H1 report — immutable revision-scoped artifact lifetime

Date: 2026-09-21
Branch: `codex/v2-installed-model-loading`
Base: `088d7a7`
Implementation commits: `2c2b6bc`, `51ac405`, plus the round-two review follow-up commit containing this report

## Outcome

Two installed owners can now retain different immutable revisions of the same repository-relative artifact without sharing or overwriting a byte path. Every new durable batch derives storage below:

```text
<validated repository root>/.caraml-artifacts/<64-char lowercase bundle digest>/<native layout path>
```

The bundle digest is derived from the complete exact artifact identity set. The native layout remains a separate projection used only after the exact manifest `localRelativePath` and generation root have been validated. Callers cannot choose an arbitrary generation, relocate an artifact, escape the repository root, or admit an unscoped destination into a new durable batch.

Single-file models store the exact scoped file in `LocalModelEntity.localPath`. Native directory bundles store the exact immutable generation root, with every required native path proven to be below that same root. External diffusion components retain their own repository roots and exact scoped paths.

The review follow-up closes every mutation seam around legacy destinations. Download DB v3 quarantines unfinished pre-H1 rows, batch claim/resume and finalization reject them, and Android, iOS, and JVM platform entry points refuse download, publication, discard, or import writes outside an immutable generation. A completed legacy install can still be read only when its exact installed manifest independently binds the bytes; it cannot authorize a new write or deduplication candidate.

The round-two closure also makes restart state exact. A pending pre-H1 commit journal is untrusted mutation state and is rolled back rather than completed; only canonical immutable journals may recover. Recoverable database batches are reconstructed and validated from their complete Kotlin request model one batch at a time, so a malformed row is terminally quarantined without aborting reconciliation of valid batches. Model Hub adopts progress/completion only when the full current artifact request multiset agrees, and iOS imports a background payload only when its descriptor still equals the canonical ID recomputed from the current persisted request.

## Storage, manifest, and catalog contract

- `immutableArtifactStorageLocation` is the single derivation point for new storage. It validates the canonical lowercase bundle digest, normalized remote/native path, full relative-path bound, repository-relative containment, and path segments.
- `ArtifactManifestEntry` persists both `localRelativePath` and `layoutRelativePath`. Only the former locates bytes. The latter is accepted solely as the native-loader layout after it re-derives from the exact identity and local storage binding.
- Repository manifests can retain multiple revisions of one remote repository/path because their local paths are generation-scoped. Bundle manifests copy the exact installed local path instead of reconstructing it.
- `DownloadBatchRequest` recomputes the bundle digest from every exact identity, rejects a caller-forged digest, rejects destination collisions, and rejects legacy/unscoped destinations for new work.
- `downloaded_component` now records nullable `immutable_revision`, `remote_object_id`, `bundle_id`, and `content_sha256`. New Ready publication requires the exact identity fields and deduplicates only by exact immutable storage identity and path.
- Same exact component generation may safely be linked by more than one owner. Different revisions or different bundle generations coexist as distinct rows/paths.
- Owner snapshots project the role from `model_component_link`, not the shared component row, so two owners may bind identical bytes under different native roles without corrupting resolution or removal.
- The finalizer locks the owner and all exact storage paths through manifest validation and the Room Ready transaction, preventing concurrent publication/removal from observing a half-published generation.

## Load and legacy boundary

`LocalArtifactIdentityResolver` locates bytes only from a validated owner bundle manifest and its exact `localRelativePath`. Catalog paths, revision/object/bundle identity, byte count, digest, and the post-hash storage snapshot must agree. Ready/partial UI projection applies the same exact-generation check; matching identity and native layout cannot make a stale or different generation Ready. A directory target is admitted only when `LocalModelEntity.localPath` equals the exact validated generation root and every required stable-diffusion native path resolves below it.

The v4 -> v5 migration leaves all new identity columns null for existing rows. It never fabricates a revision, object ID, bundle, or digest. Such legacy-null rows are never exact dedup/reuse candidates. They remain readable only when an independently validated manifest binds the same repository path and exact local path; partial identity is rejected. Persisted manifest entries may retain their pre-H1 unscoped local path, but every future durable batch uses a scoped path, so a conflicting revision cannot overwrite the legacy file.

## Reference-aware deletion

Ready removal now follows one conservative sequence:

1. Read the validated owner bundle manifest.
2. Lock the owner and every exact artifact storage key.
3. Re-read the manifest and exact Ready catalog snapshot; reject or retry if either changed.
4. Remove only that matching owner snapshot in one Room transaction, retaining shared component rows while another owner link exists.
5. Count remaining catalog references by exact local path.
6. Under all affected repository-root locks, revalidate the repository manifests, durably prune only the unreferenced entries, and only then delete their exact derived files.

Manifest pruning uses `.caraml-artifact-v1.prune-journal` plus the preserved previous manifest. Recovery completes a valid staged prune or restores the previous manifest. A crash or filesystem deletion failure can conservatively leak unreferenced bytes, but it cannot leave a retained manifest entry pointing at bytes this cleanup removed. Cleanup never recursively removes a caller-supplied directory and never deletes a generation still referenced by another Ready owner.

The deleted owner's aggregate bundle manifest is allowed to become invalid after its catalog is removed; it cannot resolve as Ready, and a later exact publication replaces it. No remaining owner's repository manifest is invalidated.

## Database migration boundary

- Application catalog schema version: 5.
- `APP_MIGRATION_4_5` adds the four nullable exact-identity columns, replaces repository/path uniqueness with exact immutable storage identity uniqueness, and adds a local-path reference index. Existing rows and links are preserved byte-for-byte, with null exact identity.
- `APP_MIGRATION_5_4` explicitly rebuilds the v4 table and link foreign key/indexes. It is lossless only when the v5 rows satisfy v4 repository/path uniqueness. If multiple exact revisions of one repository/path exist, v4 cannot represent them; the unique-index creation fails transactionally instead of conflating or deleting data.
- Reopen tests cover v4 -> v5 preservation, v5 -> v4 compatible downgrade, exact revision coexistence after close/reopen, link retention, and snapshot compare-and-remove behavior.
- Download database schema version: 3. `DOWNLOAD_MIGRATION_2_3` is a one-way data migration with no table-shape change. It terminally quarantines every queued, paused, downloading, or verifying pre-H1 artifact whose destination is not structurally scoped to its canonical immutable bundle, clears its platform task/lease, and terminally closes its nonterminal batch. Canonically scoped work remains resumable.
- SQL migration is a conservative structural first pass. Every recoverable batch is then reconstructed from all persisted request fields and checked against its canonical batch ID, artifact ID, bundle digest, exact destination, staging token, progress bounds, and complete artifact set. Decode or validation failure quarantines only that batch and cannot terminate global reconciliation.
- Completed unscoped rows are preserved only for exact, read-only installed-manifest compatibility. There is intentionally no v3 -> v2 migration: an older binary must never resume work under the weaker v2 write-path contract. Migration tests create a real v2 database, migrate/reopen twice, verify quarantine and preservation, and prove a raw-mutated unscoped row cannot be claimed after upgrade.

## TDD evidence

Tests were written before each production slice. The intended RED states included:

- missing immutable storage derivation, generation/local-layout manifest fields, and scoped destination behavior;
- schema/query failures before v5 exact identity columns and exact lookup existed;
- resolver failures for nested single-file paths, revision mismatch, exact directory generation roots, and external diffusion components;
- missing reference-aware removal/CAS APIs and shared-link cleanup behavior;
- concurrent same-repository/path revisions colliding under the old unscoped destination;
- missing manifest-prune API, followed by a restart regression proving that deleting bytes without pruning invalidated the retained revision;
- crash-injected prune phases before recovery support existed;
- a mutation run with the new-batch scoped-path guard removed, where a direct legacy destination was incorrectly accepted.
- pre-H1 queued/verifying rows remaining claimable after upgrade and public platform writes accepting an unscoped destination;
- aggregate and interrupted-recovery projections treating a wrong generation as Ready;
- shared exact bytes losing one owner's link role, and ambiguous duplicate bundle coordinates producing order-dependent identity.
- pre-H1 journals becoming visible after restart at any commit phase, stale external-component batches projecting current progress/completion, same-size iOS descriptors binding a changed request, and one malformed database row aborting recovery of an otherwise valid batch.

The round-two impacted Compose aggregate passed 93/93 tests. The complete `huggingFaceManager` JVM suite passed 105/105, including public mutation-seam rejection, completed legacy read-only compatibility, concurrent revision downloads, restart/reopen validation, deletion of one revision while retaining another, deterministic bundle identity, restart after every prune journal phase, and fail-closed recovery at every unscoped commit-journal phase.

Coverage also includes path traversal, forged/noncanonical bundle IDs, full-path and component-count bounds, destination collisions, duplicate repository/revision/path rejection, same-revision reference retention with distinct owner roles, different-revision coexistence, wrong-generation recovery rejection, stale manifest/catalog rejection, exact directory-root publication, and concurrent owner publication.

Round-two focused coverage additionally exercises every unscoped journal crash phase, valid scoped journal recovery, full-request projection when an external component revision changes, wrong-generation interrupted recovery, exact and mismatched git-OID iOS bindings, arbitrary/case/length/suffix destination corruption, per-batch decode isolation, and repeated database reopen.

## Repository and platform verification

```text
git diff --check
./gradlew verifyProject --no-daemon
./gradlew :composeApp:compileAndroidMain \
  :huggingFaceManager:compileKotlinIosSimulatorArm64 --no-daemon
```

All three passed after the round-two H1 review follow-up.

- `verifyProject`: BUILD SUCCESSFUL in 55 seconds; 41 actionable tasks (14 executed, 27 up to date).
- JVM XML: 1,190/1,190 tests — `composeApp` 1,035, `huggingFaceManager` 105, `runner` 29, `diffusionRunner` 21.
- Native: artifact-root CTest 1/1 and diffusion CTests 5/5.
- Combined automated count: 1,196/1,196.
- Android common/application compile plus Hugging Face Manager iOS simulator compile: BUILD SUCCESSFUL in 48 seconds; 27 actionable tasks (8 executed, 19 up to date).

A full `composeApp` iOS simulator compile was freshly attempted after the round-two follow-up. It built the native libraries, compiled the directly changed Hugging Face Manager iOS surface, and reached application Kotlin/Native compilation. The first attempt exposed and fixed one nullable failure-code handoff in the changed scheduler. The fresh rerun failed only at unchanged `GgufMetadataInspector.kt:20,73` `use`/nullable-generic diagnostics; no Fix H1 file appeared in the final diagnostics.

## Security and failure behavior

- Repository IDs, revisions, remote paths, roles, digests, local paths, counts, and lengths remain bounded and validated before filesystem, database, or coordination use.
- No manifest layout value or catalog filename can independently select a byte path.
- New generations are derived from exact immutable identities; legacy-null rows never authorize deduplication or a write destination.
- Root containment is checked after normalization. Traversal, absolute paths, control characters, unsafe separators, noncanonical digest scopes, and case-forged bundle scopes fail closed.
- Publication/removal use a bounded fixed-stripe coordinator with deterministic lock ordering; repository-root locks are also acquired in stable order.
- Removal revalidates both manifest and catalog evidence before unlinking and revalidates repository manifests again before deletion.
- Cancellation is rethrown. Other cleanup/storage failures produce the existing generic removal failure state without exposing paths or internals.

## Remaining verification boundary

No new physical-device download/load sequence was run for Fix H1. The path, manifest, catalog, resolver, restart, concurrency, migration, and deletion contracts are covered deterministically on JVM, with Android compilation, Hugging Face Manager iOS compilation, and native repository gates passing. Full application iOS compilation remains blocked by the unchanged source noted above.
