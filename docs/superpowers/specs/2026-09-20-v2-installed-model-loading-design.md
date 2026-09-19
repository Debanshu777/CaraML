# V2 Installed Model Loading Design

## Status

Approved in chat on 2026-09-20.

## Problem

A model can be fully downloaded, validated, stored as `ready`, and displayed in the Library, but still fail before native loading. The current chat flow sometimes has only a `LocalModelEntity`; the V2 inference path requires an exact `LoadRequest` containing verified artifact identity, a current device assessment, and the selected run plan. That request currently exists only in transient Model Hub recommendation state and is lost after navigation or process restart.

The load router still contains a legacy entity-only path. Falling back to it would make the model usable, but it would bypass the exact artifact and assessed-plan contract. This migration must instead make the V2 request reconstructible for every installed model and remove the entity-only inference path.

## Goals

- Load every installed model through a verified, freshly assessed `LoadRequest`.
- Preserve enough immutable descriptor evidence to reconstruct that request after navigation, restart, and offline use.
- Repair existing `ready` installations once without redownloading valid model files.
- Revalidate installed artifacts and recalculate the run plan for current device conditions before each native load.
- Remove runtime legacy loading and its `LocalModelEntity`-only inference APIs.
- Keep `Needs information` informational in Model Hub; it must not disable downloading.

## Non-goals

- Redesigning model discovery, ranking, or recommendation presentation.
- Persisting a selected run plan or device assessment as authoritative state.
- Weakening manifest, path-containment, integrity, admission, or native-preflight checks.
- Automatically loading descriptors that cannot be matched exactly to installed artifacts.
- Removing legacy recommendation presentation code that is unrelated to installed-model loading.

## Chosen approach

Persist immutable descriptor evidence at download enqueue time, transfer it into the installed-model catalog only after bundle verification, and recompute mutable assessment data at load time.

This is preferable to persisting the entire `LoadRequest`, because device memory, settings, engine capabilities, and user profile can change. It is also preferable to fetching Hugging Face metadata on every load, because a previously verified installation must remain loadable offline.

## Architecture

### Persisted descriptor snapshot

Introduce a versioned `PersistedModelEvidence` envelope. It always contains the exact artifact identities selected for download and may contain a complete LLM or diffusion descriptor when Model Hub has enough verified metadata. A `REQUIRES_ENRICHMENT` state represents an allowed download whose descriptor was still `Needs information`; it is evidence of what was downloaded, not a claim that the model is runnable. The envelope contains only bounded primitive values, enums, evidence records, and exact `ModelFileIdentity` data. It does not contain local absolute paths, arbitrary URLs, a selected plan, an assessment, or a recommendation.

`PersistedModelEvidenceCodec` converts complete descriptor payloads to and from `ModelDescriptor`. Decoding is strict:

- schema version must be exactly supported;
- unknown fields are rejected;
- payload size, strings, lists, component counts, dimensions, context, parameter counts, sizes, revisions, and object identities use the existing descriptor limits;
- repository IDs and relative paths are validated by the same domain constructors used by live metadata;
- the canonical SHA-256 digest stored beside the payload must match before decoding.

The encoded descriptor payload is capped at 256 KiB. The cap is deliberately much larger than normal model metadata but small enough to prevent unbounded database or memory use.

### Download durability

`DownloadBatchRequest` receives the evidence envelope that corresponds to its exact artifact set. The batch ID incorporates the evidence digest so that two batches with the same files but different evidence cannot alias.

`DownloadDatabase` persists the schema version, canonical payload, and digest with the batch. This is required because Android/iOS background downloads may finalize after the UI process that created the recommendation has gone away.

Model Hub may enqueue a download when its descriptor state is `Needs information`; downloading remains allowed. Such a request persists `REQUIRES_ENRICHMENT` plus the exact selected artifact identities. It does not claim that the model is runnable. If enrichment cannot later produce an admissible plan, the installed model remains present and the user receives the assessment reason.

### Catalog publication

Bundle publication remains ordered as follows:

1. publish downloaded files;
2. validate the complete artifact manifest;
3. decode and validate the persisted evidence envelope;
4. verify that all envelope artifact identities exactly match the validated manifest and, when a complete descriptor is present, verify its identities too;
5. publish the local model, components, and descriptor evidence in one application-database transaction;
6. mark the catalog model `ready` only when all records are committed.

The application database adds `installed_model_evidence`, keyed by repository model ID because the current catalog intentionally keeps one installed variant per model ID. It stores evidence state, schema version, payload, digest, and publication time. App database version 3 migrates to version 4 by creating this table without deleting existing model rows. The current destructive fallback is not used for this migration. Download database version 1 migrates to version 2 by adding nullable evidence columns so in-flight pre-migration batches remain inspectable and repairable.

If catalog publication fails, finalization is retryable and must not expose a new `ready` row without evidence.

### Existing-installation repair

Existing installations have no evidence row, and permitted `Needs information` downloads can have `REQUIRES_ENRICHMENT` evidence. `InstalledModelEvidenceRepairer` handles both states before load:

1. resolve and revalidate the current local manifest;
2. fetch metadata for the exact repository and immutable revisions in that manifest;
3. restrict descriptor construction to the exact installed artifact paths;
4. require every descriptor identity to match a resolved manifest component;
5. atomically replace missing or incomplete evidence with the validated complete descriptor envelope;
6. continue with normal V2 assessment and loading.

Repair never rewrites or redownloads valid model weights. It is idempotent and concurrency-safe so automatic selection and an explicit user tap cannot create conflicting records.

When repair needs network access, the UI reports that the model needs a one-time metadata verification. Network, authentication, and server failures remain retryable. Invalid or mismatched metadata is terminal for that attempt and never triggers entity-only loading.

### Installed load-request resolver

Add a single `InstalledModelLoadRequestResolver` used by Chat regardless of whether selection came from Model Hub, Library, automatic selection, or restored state.

Its input is a `LocalModelEntity` and expected generation mode. Its result is one of:

- `Ready(LoadRequest)`;
- `RepairRequired` or `Repairing`;
- `NeedsNetwork`;
- `NotAdmissible(AssessmentReason)`;
- `Rejected(ArtifactIdentityRejection)`;
- `Failed` with non-sensitive user copy.

Resolution performs these operations in order:

1. read the model's component links and descriptor evidence;
2. repair missing evidence when necessary;
3. strictly decode the complete descriptor, repairing missing or incomplete evidence first;
4. resolve and hash/revalidate local artifacts;
5. require exact descriptor-to-artifact identity matching;
6. capture the current device snapshot, engine capabilities, settings, and recommendation profile;
7. run the existing assessment and recommendation policy;
8. require exactly one selected admissible plan for the requested generation mode;
9. create and return the exact `LoadRequest` through `LocalArtifactIdentityResolver`.

No selected plan or assessment is reused across loads. Assessment caches may optimize repeated work only when their existing keys prove all mutable inputs are unchanged.

### Inference contract

`InferenceRepository` exposes only exact loading:

```kotlin
suspend fun loadModel(request: LoadRequest): ModelLoadResult
```

The `loadModel(LocalModelEntity)` overloads are removed from `InferenceRepository`, `LlamaInferenceRepository`, and `DiffusionInferenceRepository`. Chat resolves a request first and invokes only the exact method. `ModelLoadRouter` and its rollout/legacy callbacks are deleted; generation-mode validation belongs to `InstalledModelLoadRequestResolver`.

Recommendation rollout modes may continue to control unrelated presentation while that migration remains useful, but they no longer control installed-model loading. Installed-model loading is V2 on debug and release builds.

## Data flow

```text
Model Hub descriptor
    -> bounded descriptor snapshot
    -> durable download batch
    -> verified artifact manifest
    -> transactional Ready model + installed evidence

Library/Chat selection
    -> installed evidence (or one-time exact repair)
    -> current device/settings/profile assessment
    -> exact LoadRequest
    -> inference repository admission + native preflight
    -> native runner
```

## UI behavior

- Selecting a model with valid evidence shows the existing loading state while V2 resolution runs.
- Existing models without evidence show `Preparing model` during automatic repair.
- Offline repair shows: `Connect once to verify this installed model's metadata, then try again.`
- An inadmissible model shows its concrete assessment reason rather than a generic connection failure.
- Manifest or descriptor mismatch shows a safe integrity error and does not offer a bypass.
- Model Hub's `Needs information` chip remains informational and its download action stays enabled.

## Security and integrity

- Persisted evidence is untrusted input and is strictly decoded with bounded values.
- Absolute paths and remote URLs are never accepted from persisted descriptor evidence.
- Every local load target is derived from the trusted storage provider and validated manifest.
- Descriptor identities must match repository, immutable revision, path, size, and at least one canonical remote object identity.
- Local content is rehashed according to the existing artifact resolver before admission.
- User-visible errors remain generic; internal paths, stack traces, and metadata payloads are not logged.
- Repair uses exact manifest identities and does not accept search-result similarity or filename-only matching.

## Failure and concurrency handling

- Cancellation propagates without being converted to an error state.
- Concurrent repair for the same local model is serialized and database writes are idempotent.
- A process death during repair leaves either the prior missing state or a complete evidence record; partial payloads are never authoritative.
- A process death during background download is safe because descriptor evidence is part of the durable batch.
- A changed or deleted file fails revalidation before native loading.
- Unsupported evidence schema versions fail closed and can be repaired by a newer application version.

## Testing

Implementation follows red-green-refactor. Required automated coverage:

1. descriptor codec round trips LLM and diffusion descriptors and rejects oversized, unknown, malformed, or digest-mismatched payloads;
2. download task persistence survives reconstruction with the same evidence and includes its digest in batch identity;
3. download finalization refuses descriptor/manifest mismatch and publishes Ready plus evidence atomically;
4. database migrations preserve existing local-model and download rows;
5. a Ready model selected after process restart resolves a fresh exact `LoadRequest`;
6. an existing Ready model without evidence performs one repair and subsequently loads offline;
7. offline missing-evidence repair returns `NeedsNetwork`, not legacy loading;
8. changed device/settings/profile inputs recompute assessment and plan;
9. manifest or content mismatch blocks before the inference repository;
10. text, image, and video selections require plans matching their generation mode;
11. Chat auto-selection and Library selection use the same resolver;
12. no production interface or repository retains `loadModel(LocalModelEntity)`;
13. Model Hub downloads remain enabled for `Needs information`.

Run focused common/JVM tests throughout, then `./gradlew verifyProject --no-daemon`.

## Device acceptance

On the connected Pixel 9, preserve app data and install the debug build. The existing `openbmb/MiniCPM5-2B-GGUF` installation must:

1. migrate without redownloading the 1.4 GB GGUF;
2. perform one-time exact metadata repair if no evidence exists;
3. reach the exact `LlamaInferenceRepository.loadModel(LoadRequest)` path;
4. reach the native runner without the reassessment error;
5. answer a prompt;
6. continue to load after force-stop with networking disabled once evidence is persisted.

## Documentation

Update the root README and the relevant `composeApp` README recent-change sections. Update `huggingFaceManager/README.md` only if the persisted descriptor contract changes that module's public download API.
