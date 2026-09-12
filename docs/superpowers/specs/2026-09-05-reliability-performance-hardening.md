# CaraML Reliability and Performance Hardening Design

## Goal

Prevent the known native lifecycle and model-file integrity failures from reaching process-fatal code, then reduce avoidable streaming and media memory pressure without changing inference output.

## Scope and priorities

Work is split into independently verifiable slices:

1. Native ownership and crash containment.
2. Download path validation and atomic finalization.
3. Generation limits, cancellation, and memory-fit cache correctness.
4. Streaming snapshot and rendering performance.
5. Media retention and sanitized diagnostic breadcrumbs.

The first implementation plan covers slices 1 and 2. Later slices must not begin until the first plan passes its platform gates.

## Native ownership design

All llama model/context operations share one exclusive session gate owned by `LlamaInferenceRepository`. Load, unload, generation (including finalization), summarization, and context reset execute through the same gate. `cancelGeneration()` remains lock-free because it only signals the native atomic cancellation flag and must be able to interrupt a gate holder.

Synchronous repository statistics stop reaching into native state during teardown. They read volatile snapshots updated while the session gate is held. This prevents UI polling from racing a native unload and creates the boundary needed for later UI sampling.

The iOS diffusion bridge must call `diffusion_runner_ios_load_model` inside the `memScoped` block that owns every `cstr` pointer. The JNI bridge must use per-invocation owned strings; no configuration string may have static storage or refer to a temporary after the native call begins.

Exported native boundaries must never let C++ exceptions cross JNI or C ABI boundaries. A subsequent native-boundary task will map allocation failures and standard exceptions to stable failure return values, while leaving cancellation signaling lock-free.

## Download integrity design

Model IDs and relative paths are untrusted. A shared common policy validates them before filesystem or network access:

- Model IDs contain one or two non-empty repository segments and no dot segments, control characters, backslashes, or platform-reserved path characters.
- Relative paths are non-empty, are not absolute, contain no empty, `.` or `..` segments, contain no control characters or platform-reserved path characters, and remain within conservative component and total-length limits.
- URL path components are encoded rather than concatenated as raw input.

Every platform writes to a sibling `.part` path. The final destination remains untouched until all bytes arrive, any advertised content length matches, and the platform file handle has been closed. Only then is the temporary file renamed to the final path. Failure or cancellation removes only the `.part` file.

Only HTTP 2xx responses are accepted. Error bodies are never saved as model content. A null or failed iOS file handle is a hard failure rather than a silent success.

Progress is coalesced: known-length transfers publish when the integer percentage advances, while unknown-length transfers publish at one-MiB byte intervals. Completion always publishes one final event containing the committed local path.

## Error and security behavior

- Coroutine cancellation is always rethrown.
- User-visible messages are stable and generic; prompts, generated text, raw exception messages, and full filesystem paths are not exposed.
- Invalid paths fail before directory creation or network access (CWE-22).
- Partial or HTTP-error content never becomes loadable native input.
- Native operations are serialized to prevent races and use-after-free (CWE-362/CWE-416).

## Compatibility

Public `DownloadManager.download` and inference repository interfaces remain unchanged. Existing successfully downloaded model paths remain valid. The only rejected inputs are malformed or unsafe repository identifiers and paths.

## Verification

- Common unit tests cover path policy, progress coalescing, and the exclusive session gate.
- JVM integration tests use a local HTTP server and temporary directory to verify 2xx commit, non-2xx rejection, incomplete-download cleanup, and path traversal rejection.
- Android lint/build recompiles both configured native ABIs.
- JVM project verification remains green.
- iOS simulator compilation/link dry-runs verify Kotlin/Native and native task wiring where the local toolchain permits.

## Stop condition

The first slice stops when the lifecycle and download regression tests pass, JVM verification passes, Android lint/native compilation passes, and the iOS sources compile. Streaming/media refactors are deliberately excluded from this first slice.

