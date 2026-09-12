# CaraML P0/P1 Runtime Containment Design

## Goal

Contain the audited P0/P1 crash and performance paths across Android, JVM, and iOS without changing successful text or image inference output.

## Approved scope

1. Reject video requests whose combined dimensions and frame count can exhaust memory.
2. Make JNI result conversion safe when the JVM cannot allocate arrays or local references.
3. Wire stable-diffusion cancellation through Kotlin, JNI, iOS FFI, and the native core.
4. Replace the long-held global diffusion handle mutex with reference-counted handle ownership and per-handle operation serialization.
5. Reject llama prompts that cannot fit while preserving the existing conversation for recovery.
6. Stop iOS from emulating video with repeated still-image generations until a real native video ABI is implemented.
7. Store completed generated media in a session cache and decode it off the Compose thread.
8. Base model-load decisions on a current conservative memory budget and native metadata when available.

P2 database migration, download resume/integrity, and crash-reporting integration are explicitly excluded.

## Diffusion request policy

Image validation remains capped at 16,777,216 pixels. Video validation additionally caps `width * height * videoFrames` at 16,777,216 frame-pixels, calculated with `Long`. This permits the current 512x512x16 default and 512x512x64, while rejecting requests whose returned raw RGBA frames alone would exceed 64 MiB.

The repository performs a second memory-envelope check before generation using the current device budget. It reserves memory for the loaded model and runtime headroom and rejects output whose conservative raw-frame estimate cannot fit. Rejection happens before JNI/FFI invocation and uses a stable generic message.

## Native diffusion ownership and cancellation

The native registry stores `shared_ptr<SdHandle>`. The registry mutex is held only while inserting, looking up, or removing a handle. Each handle owns an operation mutex; image/video generation locks it for the native operation, so only one operation uses a context at once.

Cancellation looks up a shared handle and calls upstream `sd_cancel_generation(ctx, SD_CANCEL_ALL)` under a short cancellation mutex, without waiting for the operation mutex. Release removes the handle from the registry, signals cancellation, waits for the operation mutex, then nulls and frees the context while excluding concurrent cancellation. A generation that already owns a shared reference therefore cannot observe freed context memory.

Android/JVM expose the cancel operation through JNI. iOS exposes it through the C ABI. `ChatViewModel` signals the active repository before cancelling its coroutine, including mode switches and model reloads.

## JNI allocation safety

Native PNG buffers use scoped cleanup so every return path frees them. Each JNI class lookup, array allocation, element acquisition, and array write is checked for null or a pending exception before the next JNI operation. A pending `OutOfMemoryError` is preserved for the JVM; native code does not clear or replace it. Partial video conversion releases all remaining native buffers and local references.

## Llama context behavior

User-prompt tokens are never truncated from the tail. Prompt processing reserves at least the requested generation length, clamped to the context capacity. If the rendered prompt cannot fit, native code returns a dedicated context-full code before committing the user message.

Chat history is appended only after prompt decode succeeds. If a KV-changing decode fails, the KV cache is cleared and marked for full reconstruction on the next request while the prior logical chat history remains intact. The repository maps context-full to a typed exception and always runs native finalization/snapshot cleanup for a started turn.

## iOS video behavior

`DiffusionRunner.supportsVideoGeneration()` is false on iOS and true on Android/JVM. The chat state presents a stable unsupported-platform error before loading or generating video. The existing repeated-`txt2img` loop is removed, preventing misleading output and N-times diffusion work.

## Generated media retention

Completed PNGs are atomically written to a process-session cache through a common `GeneratedMediaStore`. `ChatMessage` stores internal cache paths rather than retaining generated `ByteArray` objects. Video writes are transactional: a failed write removes all frames written for that message. Each file is capped at 96 MiB and the complete session is capped at 512 MiB.

Compose loads only visible media through the store and performs PNG decoding on `Dispatchers.Default`. The store validates that every read/delete remains beneath its private session root. `ChatViewModel.onCleared()` clears the session directory. Existing byte-backed preview data remains supported but is also decoded off-main.

## Dynamic memory budget

CPU/GPU capability detection remains cached, but memory budget is refreshed for each request. Android and JVM use the smaller of 70% total physical memory and 75% currently available physical memory. iOS keeps its conservative physical-memory ceiling where no reliable cross-version Jetsam limit is available.

Diffusion preflight prefers native `estimatedRamBytes` metadata over the 1.1x on-disk approximation, adds component weights not represented by the main file, and reserves output/runtime headroom. Unknown metadata falls back conservatively rather than bypassing validation.

## Error and security behavior

- Validation failures occur before native allocation (CWE-20/CWE-400).
- JNI allocation failures never lead to null dereferences or leaked buffers (CWE-690).
- Handle release cannot race context use (CWE-362/CWE-416).
- Coroutine cancellation is rethrown unchanged.
- User-visible errors are stable and do not expose prompts, generated content, exception details, or local paths.
- Generated-media paths are internal and constrained to the session cache root (CWE-22).

## Verification

- Focused JVM tests cover video frame-pixel limits, memory-envelope arithmetic, llama error mapping, media-store containment/cleanup, and off-main media state boundaries where testable.
- Android lint rebuilds both configured native ABIs and JNI.
- iOS simulator compilation rebuilds the C ABI and Kotlin/Native cinterop.
- `verifyProject`, `git diff --check`, and a final diff/security review gate completion.

## Stop condition

Stop after the approved P0/P1 paths are implemented and verified. Do not add database migrations, download resume/checksum work, telemetry SDKs, or unrelated refactors.
