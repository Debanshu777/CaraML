# CaraML Diffusion Plugin

Optional image-generation engine provider with its own Kotlin/JNI/cinterop bridge. It depends on `:core-api` and the internal `:native-runtime` process gate; it does not depend on the application, storage, discovery, or llama runner.

The first adapter is deliberately limited to one leased local model file and single-image PNG generation. Its public request/options types belong to the plugin; native runner types stay behind the adapter. It validates dimensions, pixel count, steps, prompt size, output size, artifact identity, and plan identity before execution. The pixel-based transient-memory figure is a provisional estimate, not measured admission evidence. Video, multi-file component bundles, LoRAs, persistent preferences, and model installation remain outside this initial plugin.

Callers supply a `GeneratedOutputSink`, native library directory, and optionally a coroutine dispatcher. The native process gate is held during preflight and throughout loaded-session ownership; generation calls are serialized, cancellation signals the runner without taking the shared gate, and session close waits for native teardown. A successful sink commit owns the output by operation ID even if the caller cancels before observing its `MediaPublished` event; sinks should make committed results discoverable through their own operation/result records.

If native release fails after a load attempt, the process gate stays held because shared GGML state may still be live. Failed partial loads explicitly transfer unresolved ownership to core so the artifact lease and resource reservation stay held too. Core quarantines a session whose teardown fails. A direct plugin caller can retry close while the session is unavailable for generation. The process gate stays held until teardown succeeds.

The adapter lives in `com.debanshu777.caraml.sdk.plugin.diffusion`; its native
bridge and tests live in the `.bridge` subpackage. Kotlin declarations, JNI
symbols, iOS cinterop and consumer keep rules use the same package hierarchy. See [native bridge details](docs/native-bridge.md).

## Recent Changes

- Made SDK diffusion preflight a synchronous probe under the shared runtime gate. It creates no model handle and returns without invoking loaded-session teardown. Generation stops progress polling before publishing output or a terminal event. Direct session close can retry a failed native release.

- Added explicit sampling schedules across Kotlin/JNI/iOS, coherent image sampling progress that filters tensor-loading counts, preserved video stage progress, and sanitized graph-backend counts. Vulkan image VAE placement now honors the admitted plan while text encoding retains CPU safety.

- Standardized adapter and bridge packages under `com.debanshu777.caraml.sdk.plugin.diffusion`; updated JNI symbols, metadata lookup, cinterop and shrinker rules together.

- Documented single-model/image scope and output ownership in KDoc; [verification status](../README.md#verification-status) records the scope of platform and JNI checks.

- Absorbed the former `diffusionRunner` Gradle module, including Android keep rules and iOS bindings; native parity runs through `plugin-diffusion`.
- Preserve the runtime-backend enum descriptor through R8 and retain partial-load ownership when native release fails.

- Added an optional diffusion `EngineProvider` with a typed bounded image request, shared native-runtime lease, native preflight/load adapter, progress and cancellation forwarding, and staged PNG publication.
- Added fake-runtime contract tests for image publication, local artifact binding, workload limits, plan identity, and one-shot operation collection.
