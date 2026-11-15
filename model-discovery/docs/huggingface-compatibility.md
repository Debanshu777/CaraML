# Hugging Face compatibility API

Reference for the existing app API previously shipped in `huggingFaceManager`.
The Hub API, metadata and HTTP factories now live in `model-discovery`. The
`download/`, `sdcpp/` and `DiffusersModelSupport` source packages described below
live in `composeApp` under `com.debanshu777.caraml.core.download.transfer` and
`com.debanshu777.caraml.core.download.diffusion`. The Hub API lives under
`com.debanshu777.caraml.sdk.discovery.huggingface`. Persistent download formats
and background session identities are unchanged. New provider implementations use `ModelProvider` and
`ArtifactSource` instead of extending these Hugging Face DTOs.

Used by `:app:composeApp` to discover and download GGUF models and stable-diffusion component files.

---

## Platforms

Android, iOS arm64/device and arm64 simulator, JVM Desktop (the current SDK target matrix)

HTTP clients:
- Android → Ktor + OkHttp
- iOS → Ktor + Darwin (NSURLSession)
- JVM → Ktor + CIO

---

## Package Structure

```
com.debanshu777.caraml.sdk.discovery.huggingface/
├── HuggingFaceApi.kt                  # Public API facade (entry point)
├── api/
│   ├── RemoteHuggingFaceApiService.kt # Ktor HTTP calls to HuggingFace REST API
│   ├── ClientWrapper.kt               # Wraps platform HttpClientFactory
│   ├── SearchModelsParams.kt          # Search query parameters
│   ├── ListModelsParams.kt            # List/browse parameters
│   └── error/Result.kt               # Typed Result<T, E> (Success/Error)
├── model/
│   ├── SearchModelsResponse.kt        # Search results DTO
│   ├── ModelDetailResponse.kt         # Full model metadata DTO
│   ├── ListModelsResponse.kt          # Paginated model list DTO
│   ├── PipelineTag.kt                 # Model task categories enum
│   ├── ModelSort.kt                   # Sort options (trending, likes, downloads)
│   └── ModelFileWeightFilter.kt       # Filter GGUF files by quant type/size
├── repository/
│   └── HuggingFaceRepository.kt      # Aggregates API + download operations
├── usecase/
│   ├── SearchModelsUseCase.kt         # Invoke: search query → List<Model>
│   ├── GetModelDetailUseCase.kt       # Invoke: modelId → ModelDetailResponse
│   └── GetModelFileTreeUseCase.kt     # Invoke: modelId → file list with sizes
├── download/
│   └── DownloadManager.kt (expect)    # Platform-specific download impl
│       DownloadManager.java.kt        # Shared Android/JVM bounded Ktor transfer
│       DownloadManager.ios.kt         # iOS transfer/import implementation
├── sdcpp/
│   ├── SdCppCuratedCatalog.kt         # Curated list of known SD model bundles
│   ├── SdCppModelSetup.kt             # Install logic for multi-file SD models
└── HttpClientFactory.kt (expect)      # Platform HTTP client factory
    HttpClientFactory.android.kt
    HttpClientFactory.ios.kt
    HttpClientFactory.jvm.kt
```

---

## Public API

### Paged catalog contract

`ModelPageRequest.limit` is replaced by a read-only `pageSize` hint of 30. `ModelPageCursor` retains its opaque public shape but represents a query-bound integer continuation, not a remote Link-header token. `ModelPage` exposes server page index, page size and total count. Do not serialize or reuse continuations across searches, filters or sorts. `/models-json` is the Hub website catalog endpoint; detail and transfer APIs remain separate. Search rows omit creation timestamps and rich tags/GGUF/safetensors metadata; consumers needing those fields must use detail acquisition.

### HuggingFaceApi

Main entry point. Instantiated once and injected via Koin. The factory owns its HTTP clients and returns an `AutoCloseable` facade; close it when the host scope ends (the app does this on Koin disposal). `RemoteHuggingFaceApiService` and `ClientWrapper` also expose `close()`, but borrow their supplied client: closing either releases its derived client and leaves the host client usable. Responses are bounded and decoded manually; the factory preserves `Accept: application/json` without installing content negotiation.

```kotlin
val api = createHuggingFaceApi()

// Paged language-model discovery (the app search/browse path)
val results = api.getModelPage(ModelPageRequest(search = "llama"))
// Pass the successful page’s nextCursor back through request.copy(cursor = nextCursor).
// The compatibility quick-search API remains available separately.

// Get model detail
val detail = api.getModelDetail("bartowski/Meta-Llama-3.1-8B-Instruct-GGUF")

// Get the strict recommendation projection at current HEAD or at one immutable commit
val currentRecommendationDetail = api.getRecommendationModelDetail(modelId)
val installedRecommendationDetail = api.getRecommendationModelDetail(modelId, revision)

// Get the file tree and optional transformer config at the same immutable commit.
// The ordinary config call preserves no-redirect browse behavior.
val files = api.getModelFileTree(modelId, revision, ModelFileWeightFilter.GgufOnly)
val config = api.getModelConfig(modelId, revision)

// Exact installed-evidence repair alone may follow the validated Hub config redirect.
val exactConfig = api.getModelConfig.forExactInstalledRepair(modelId, revision)

// In real code use try/finally or use { ... } around the owning scope.
api.close()
```

The revision-qualified use-case signatures exposed by `HuggingFaceApi` are:

```kotlin
// GetRecommendationModelDetailUseCase
suspend operator fun invoke(
    modelId: String,
    revision: String,
): Result<ModelDetailResponse, DataError.Network>

// GetModelFileTreeUseCase
suspend operator fun invoke(
    modelId: String,
    revision: String,
    weightFilter: ModelFileWeightFilter = ModelFileWeightFilter.GgufOnly,
): Result<List<ModelFileTreeResponse>, DataError.Network>

// GetModelConfigUseCase
suspend operator fun invoke(
    modelId: String,
    revision: String,
): Result<TransformerConfigResponse, DataError.Network>

suspend fun forExactInstalledRepair(
    modelId: String,
    revision: String,
): Result<TransformerConfigResponse, DataError.Network>
```

`revision` must be a validated 40–64 character hexadecimal commit. Revision-qualified detail, tree, and config requests are built from encoded path segments. Ordinary `getModelConfig(modelId, revision)` retains no-redirect browse semantics. Only `forExactInstalledRepair(modelId, revision)` may follow one bounded 307, and only when it targets `https://huggingface.co:443/api/resolve-cache/models/{same repository}/{same revision}/config.json`; all other redirects are rejected.

### Model-card documents

`HuggingFaceApi` now exposes `getModelCard: GetModelCardUseCase`; custom implementations of this compatibility facade must supply it. The provider-neutral contracts are unchanged.

```kotlin
val document: Result<ModelCardDocument, DataError.Network> = api.getModelCard(modelId, revision)
```

The call reads `/{owner}/{model}/raw/{revision}/README.md` with the existing repository validation, a validated immutable commit, a 256 KiB response limit, strict UTF-8, and no redirects. Success returns `ModelCardDocument(repositoryId, revision, markdown, excerpt)`: `markdown` retains the whole document after YAML front matter is removed, while `excerpt` remains the first useful prose paragraph capped at 600 characters. Article and excerpt share one front-matter parser: it normalizes a leading BOM and CRLF, and accepts either `---` or `...` as the closing delimiter. Unterminated front matter leaves the normalized body intact but suppresses the excerpt. Cards without prose can still contain a useful full document. This replaces the earlier `String?` excerpt result; callers should use `.excerpt` for a summary and `.markdown` for rendering.

Missing, gated, oversized, or unavailable cards return a normal network error. The app cancels card requests and clears their state on navigation, retries separately, and never blocks file selection on README availability. The native Model card tab resolves relative links/images against the returned revision and renders supported Markdown. Provider-neutral contracts are unchanged.

The details DTO accepts both Boolean gating and Hub `auto`/`manual` strings, and scalar or list `cardData.language`. The strict recommendation projection remains independent: model-card prose cannot establish executable compatibility or device fit.

### DownloadProgressDTO

```kotlin
data class DownloadProgressDTO(
    val bytesDownloaded: Long,
    val totalBytes: Long,
    val progressPercent: Float,    // 0.0–1.0
    val speedBytesPerSec: Long,
    val isComplete: Boolean,
    val error: String?
)
```

---

## Stable Diffusion Support

`SdCppCuratedCatalog` — hardcoded catalog of known working SD model bundles (model + VAE + CLIP + scheduler config). Used in the "Smart Install" flow in `:app:composeApp`.

`SdCppModelSetup` — orchestrates downloading all required components for a given SD model entry.

The app’s `ModelReadinessReconciler` and artifact manifest validation check installed bundles before inference; the obsolete `SdCppComponentChecker` has been removed.

---

## Error Handling

All API calls return `Result<T, DataError.Network>`; coroutine cancellation is still thrown and must not be converted into a result:

```kotlin
when (val result = api.searchModels(params)) {
    is Result.Success -> result.data  // List<Model>
    is Result.Error -> result.error   // DataError.Network
}
```

Network categories are `NoInternet`, `Unauthorized`, `RequestTimeout`, `RateLimited`, `ServerError`, `Serialization`, `Conflict`, `PayloadTooLarge`, `NotFound`, and `Unknown`. `NotFound` is compatibility-scoped: optional revision-qualified config and model-card calls map HTTP 404 to `NotFound`, allowing callers to continue without `config.json`. Existing list, search, detail, and tree calls retain their previous 404 mapping. Invalid exact-repair redirects and malformed or oversized deterministic responses are not transient failures.

---

## Recent Changes

- Added full revision-pinned model-card documents with optional excerpts and mixed gating/language decoding for the compact details screen.

- Retired the standalone Gradle module; provider code is owned by `model-discovery`, while legacy download, native filesystem and curated install code is owned by the app.

- App-owned startup reconciliation fairly completes stale full-byte download rows only after exact published-artifact proof, preserving paused/cancelled intent and using final compare-and-set guards. Streaming deadlines, validator checkpoints, leases and bundle publication remain in the application's download pipeline.


<!-- Updated at end of each Claude Code session -->

- Android and Desktop secure artifact reads reuse a bounded per-source buffer during hashing and manifest reads, avoiding a new managed byte array for every chunk
- Language-model browse and search use bounded `/models-json` envelopes, query-bound page continuations, server totals and author metadata. Recommendation detail, tree and config acquisition retain their revision-bound endpoints.
- Manifest-backed storage uses segment-aware relative paths on Unix and Windows while rejecting root escape; Windows publication retains the pinned target-directory handle through native atomic rename, and durable owner replacement keeps prior entries cleanup-retryable until acknowledgement
- Native iOS completion import accepts only a typed, allowlisted HTTPS response provenance captured from the real platform response; persisted recovery metadata is bounded and cannot synthesize URL or status defaults
- A narrow shared artifact-root lifetime discovers and locks expected roots plus repositories from every bounded main/staged/previous owner-bundle recovery candidate, then recovery-validates without recursively locking and holds the same canonical locks used by download commit, iOS import, validation, and cleanup
- Every download, publish, discard, and iOS import write requires a canonical `.caraml-artifacts/<bundle-digest>/...` destination; unscoped manifests are unreadable, invalid pending journals remain quarantined without exposing torn state, bundle digests reject ambiguous duplicate coordinates, and crash-recoverable pruning preserves other linked revisions
- Downloads now resume verified staged files with validated `Range`/`If-Range` responses, safely restart on full responses, and preserve synchronized checkpoints without weakening root containment
- Android downloads now pin the trusted app-owned models root directly, avoiding SELinux-forbidden reads of `/` while retaining descriptor-relative no-symlink artifact operations
- Recommendation evidence consumes only bounded immutable Hub identities and sanitized metadata; profile reranking is network-free, and unknown, oversized, duplicate, or inconsistent fields remain unavailable rather than inferred
- Platform storage providers now expose a sibling `recommendation_cache.db` path so derived calibration evidence remains isolated from the primary app database
- Storage providers now reject symlinked model roots and require canonical model paths to remain beneath the canonical trusted models parent
- Downloads require pinned artifact identities and roots, strict UTF-8 descriptor-relative regular-file operations, and idempotent rollback journals; the native backend is exercised from packaged Desktop images and Android APKs
- Downloads now validate repository/file paths, prevent storage-root escape, stage into `.part` files, verify HTTP status and byte counts, sync/close before commit, and preserve existing files on failure across JVM, Android, and iOS
- Native iOS background-session results stop before checkpoint mutation when manifest recovery is quarantined; otherwise they are imported from a no-follow regular-file descriptor, re-hashed, and committed through the same exact-artifact manifest transaction before becoming visible
- Progress emissions are coalesced to percentage changes (or 1 MiB for unknown lengths), avoiding channel/UI pressure during multi-gigabyte downloads
- Recommendation detail supports validated immutable-revision lookups; exact installed repair alone follows at most one exact same-Hub config redirect, while ordinary browse preserves no-redirect behavior
- Validated bundle reads and bundle publication now preserve coroutine cancellation while waiting on real artifact-root locks instead of translating it into absence or a failure value
- Added JVM loopback integration tests for success, HTTP failure, truncation, traversal, and final-file preservation
- `nota-ai/bk-sdm-tiny` registry now sets `prediction=0` (EPS) — skips `is_using_v_parameterization_for_sd2()` probe; `offloadToCpu` reverted (moot since Vulkan is now disabled for diffusion at build level via `SD_VULKAN=OFF`)
- `nota-ai/bk-sdm-tiny` registry entry now sets `prediction=0` (EPS) — prevents `is_using_v_parameterization_for_sd2()` probe from running a test UNet forward pass; SD1.x is always EPS, never V-pred
- `SdCppRecommendedParams` gained `seed: Long?` (registry-pinned seed for deterministic / debug generation)
- SD-Turbo, SDXL-Turbo, LCM-LoRA registry entries now carry distilled-correct defaults (4–6 steps, cfg=1.0–1.5, `euler_a` / `lcm` sampler) instead of the generic 20-step / cfg=7 defaults
- Smart install bundle support for multi-component diffusion models
- Curated install plans are checked through the app’s current readiness and artifact-manifest pipeline.
- `ModelFileWeightFilter` for GGUF quantization variant filtering
