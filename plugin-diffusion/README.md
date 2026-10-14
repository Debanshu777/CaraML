# CaraML Diffusion Plugin

Optional image-generation engine provider with its own Kotlin/JNI/cinterop bridge. It depends on `:core-api` and the internal `:native-runtime` process gate; it does not depend on the application, storage, discovery, or llama runner.

The first adapter is deliberately limited to one leased local model file and single-image PNG generation. Its public request/options types belong to the plugin; native runner types stay behind the adapter. It validates dimensions, pixel count, steps, prompt size, output size, artifact identity, and plan identity before execution. The pixel-based transient-memory figure is a provisional estimate, not measured admission evidence. Video, multi-file component bundles, LoRAs, persistent preferences, and model installation remain outside this initial plugin.

Callers supply a `GeneratedOutputSink`, native library directory, and optionally a coroutine dispatcher. The native process gate is held during preflight and throughout loaded-session ownership; generation calls are serialized, cancellation signals the runner without taking the shared gate, and session close waits for native teardown. A successful sink commit owns the output by operation ID even if the caller cancels before observing its `MediaPublished` event; sinks should make committed results discoverable through their own operation/result records.

If native release fails, the process gate stays held because shared GGML state may still be live. Failed partial loads explicitly transfer unresolved ownership to core so the artifact lease and resource reservation stay held too. This intentionally quarantines further native engine use in the process instead of admitting another engine into uncertain state.

Bridge sources and tests live in this module, preserving the existing
`com.debanshu777.diffusionrunner` package/JNI names. See [native bridge details](docs/native-bridge.md).

## Recent Changes

- Validated Android arm64 packaging, iOS device/simulator compilation, and opt-in desktop JNI fixtures with CheckJNI; see [corrective evidence](../docs/sdk/implementation-evidence.md#corrective-implementation--2026-10-14).

- Absorbed the former `diffusionRunner` Gradle module, including Android keep rules and iOS bindings; native parity runs through `plugin-diffusion`.
- Preserve the runtime-backend enum descriptor through R8 and retain partial-load ownership when native release fails.

- Added an optional diffusion `EngineProvider` with a typed bounded image request, shared native-runtime lease, native preflight/load adapter, progress and cancellation forwarding, and staged PNG publication.
- Added fake-runtime contract tests for image publication, local artifact binding, workload limits, plan identity, and one-shot operation collection.
