# CaraML core

`core` is the UI and engine implementation independent inference facade. Consumers register engine providers, supply artifact access and a platform resource source, then use `InferenceClient` to assess, load, generate, cancel, and close a session. Builders perform no disk, network, or native work.

The default arbiter shares loaded-engine capacity and resource reservations across client facades in the same linked `core` runtime. Unknown memory blocks admission; serious/critical thermal pressure and low-memory readings block new work. Plugin plans are checked against their engine/version, artifact, model, capability, and request fingerprint before loading.

## Recent Changes

- Documented host resource ownership, shared-runtime scope and failed-teardown quarantine in `InferenceClient` KDoc.

- Standardized lifecycle implementation and tests on `com.debanshu777.caraml.sdk.core`.

- Failed partial loads retain native ownership, artifacts and admission capacity. Concurrent/repeated client and session shutdown calls await cleanup and report the same failure outcome.

- Added builder-based provider selection, plan assessment and validation, load/session lifecycle, single-use generation streams, operation-scoped cancellation, and process-shared capacity/resource arbitration. Terminal events arrive after engine finalization and admission cleanup; text resynchronization is explicit.
- Added fake third-party plugin tests for custom typed options and metadata, plan binding, client contention, cancellation, load/close races, and resource admission.

## Current limits

- Experimental extension contracts and core behavior are not a stable published SDK. The shared arbiter covers one linked runtime; duplicate SDK copies or separate classloaders need a lower-level native ownership guard.
- Live operation reservations remain charged until completion for safety. This can conservatively reject work when fresh OS readings already include those allocations.
- A session whose native `unload` or `close` fails stays permanently quarantined with its artifact lease and loaded/resource reservation retained. This deliberately blocks reuse until process exit; a recovery API is not implemented.
- `androidDeviceResources(context, outputDirectory)` and `jvmDeviceResources(outputDirectory)` provide polling resource readings without Koin. An iOS factory is still missing. Recovery evidence, context repair, operation outcome persistence, and media publication orchestration are not implemented here.
