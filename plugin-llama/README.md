# CaraML llama plugin

`plugin-llama` implements the text-generation capability using its own Kotlin/JNI/cinterop bridge. A host registers `LlamaPlugin(nativeLibraryDirectory)` with `InferenceClient`, supplies a local-file `ArtifactAccess`, and creates typed `LlamaRequest` values with `LlamaSessionOptions`. The runner is created lazily, so registering a plugin does not load a native library.

The adapter lives in `com.debanshu777.caraml.sdk.plugin.llama`; its native bridge
and tests live in the `.bridge` subpackage. Kotlin declarations, JNI symbols,
iOS cinterop and consumer keep rules use the same package hierarchy. See [native bridge details](docs/native-bridge.md).

## Recent Changes

- Standardized adapter and bridge packages under `com.debanshu777.caraml.sdk.plugin.llama`; updated JNI symbols, field descriptors, cinterop and shrinker rules together.

- Validated Android arm64 packaging, iOS device/simulator compilation, and opt-in desktop JNI fixtures with CheckJNI; see [corrective evidence](../docs/sdk/implementation-evidence.md#corrective-implementation--2026-10-14).

- Absorbed the former `runner` Gradle module; app consumers and native parity tasks now depend on `plugin-llama`.
- Preserve JNI enum descriptors through R8 and propagate unresolved partial-load ownership to core, retaining artifact leases and resource reservations.

- Added typed GGUF preflight/load/session mapping with content and reasoning deltas, parser resynchronization events, operation-scoped cancellation, and a shared native-runtime lease.
- Added fake-bridge lifecycle tests without requiring native binaries.

## Current limits

- Requires a local GGUF file and the host-packaged llama native artifact. Discovery and storage are separate optional modules.
- The shared gate coordinates modules using this SDK runtime. Legacy direct calls to `LlamaRunner`, duplicate classloaders, and unrelated native copies remain outside its ownership.
- Native model history remains owned by llama.cpp. Context repair, summarization, resumable turns, and measured P5 device/native parity are not implemented or claimed by this adapter.
