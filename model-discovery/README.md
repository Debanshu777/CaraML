# model-discovery

Optional Kotlin Multiplatform model browsing and transfer SDK. The module is intended to expose provider-neutral operations while keeping Hugging Face details behind provider contracts. The provider-neutral SDK accepts host-supplied HTTP clients or custom providers. The retained Hugging Face compatibility factory also creates platform clients for the existing app.

The current slice includes a Hugging Face adapter behind neutral provider/transfer contracts, an injected Ktor client seam, streaming transfers, offset validation, immutable revision pinning, bounded retries, and cancellable operations. A client built by `huggingFace(...)` owns its derived request client; the supplied client is borrowed unless `owned = true`. A provider passed through `provider(...)` remains borrowed.

This is an SDK prototype, not a production download service. It does not yet provide signed URL refresh, host scheduling integration, durable process-restart reconciliation, or external consumer verification. Transfer validators are compared between retry attempts within one operation, but are not persisted for comparison after a process restart; generic providers must supply immutable revisions or an expected digest for safe cross-restart resume. The task-store check and artifact-store generation fence cannot form one atomic transaction for arbitrary independently supplied implementations; stale finalization across that boundary remains a release gate. Ktor's standard request-retry plugin is rejected, while behavior from custom engines/plugins remains the host's responsibility.

## Provider extension

Hugging Face is one implementation of `ModelProvider` and `ArtifactSource` from
`:core-api`. Another registry, local catalog or private service can implement
those interfaces without Hugging Face DTOs, native libraries or changes to core:

```kotlin
val discovery = ModelDiscovery.Builder()
    .provider(companyRegistry) // ModelProvider; also ArtifactSource when downloading
    .enableDownloads(taskStore, artifactStore)
    .build()
```

Omit `enableDownloads` for browsing only. Each facade selects one provider; use
separate facades for multiple sources. Artifact identities include `ProviderId`,
so providers can use the same remote model/file IDs safely. The executable
[provider isolation test](src/commonTest/kotlin/com/debanshu777/caraml/sdk/discovery/ProviderIsolationTest.kt)
covers two custom providers browsing, resolving and downloading into shared storage.
A federated search/registry layer is not needed for this extension point.

## Implementation layout

- `com.debanshu777.caraml.sdk.discovery`: provider-neutral facade, scoped transfers,
  and download lifecycle handling.
- `com.debanshu777.caraml.sdk.discovery.huggingface`: compatibility Hub API, HTTP factory,
  the built-in `HuggingFaceProvider` adapter, metadata DTOs, repositories and use
  cases. New consumers use neutral SDK contracts.
- Legacy download/storage platform integrations and curated diffusion installation
  rules live in `composeApp` under `core.download.transfer` and
  `core.download.diffusion`.
  Discovery has no app, storage implementation, engine or native-build dependency.

The compatibility factory uses OkHttp (Android), Darwin (iOS arm64/simulator), and
CIO (JVM). Consequently those transports and serialization adapters are now used
runtime dependencies of this module. A custom provider does not instantiate them;
separate provider artifacts are deferred until dependency isolation warrants them.
See the [compatibility API reference](docs/huggingface-compatibility.md).

## Recent Changes

- Standardized neutral discovery under `sdk.discovery` and Hugging Face implementation under `sdk.discovery.huggingface`, using the common `com.debanshu777.caraml` root.

- Removed `huggingFaceManager` as a Gradle module. Moved its Hub API/metadata/client code here and its existing host storage/download/diffusion integrations to `composeApp`.
- Preserved provider-neutral extension contracts and added shared-storage isolation coverage for two custom providers. SDK-only verification still excludes native producers.

- Uses scoped Ktor streaming without whole-body buffering; signed redirects strip inherited authorization at send time. Concurrent shutdown waits for transfer cleanup. The SDK path still borrows caller-supplied clients; the retained compatibility factory uses platform transports and serialization adapters.

- Added provider-neutral search/resolve/download operations with Hugging Face browse and streaming transfer support, borrowed-client handling, bounded retries, range checks, and cancellation.
- Added MockEngine coverage for browse/transfer headers, unsafe resumed ranges, retry/resume integrity, and close-driven cancellation.
