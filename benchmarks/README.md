# CaraML performance improvement report

Updated October 24, 2026, Asia/Kolkata.

The largest loading improvements came from removing repeated whole-model hashing and retaining an unchanged model's resident session. Native CPU repacking and buffered ONNX metadata reads reduced additional loading work. Startup profiling helped first display; the scrolling change reduced redundant composition without demonstrating faster frames. Generation speed remains a separate optimization target.

This report covers the Android startup, scrolling, GGUF and ONNX work completed on the Pixel 9. It is the current overview; the [Android guide](android/README.md) and [ONNX guide](onnx/README.md) retain detailed methods, historical experiments, commands and per-trial evidence. Earlier iPhone results remain in the ONNX guide and were not rerun during this Android pass.

## Measured improvements

Each row is a separate experiment with its own baseline. Do not add the savings together or treat successive rows as one continuous measurement.

| Experiment | Before | After | Finding |
| --- | ---: | ---: | --- |
| [Qwen 3B GGUF installed-load verification](android/results/pixel9-minimal-verification-2026-10-23.json) | 20.315 s | 3.287 s | 83.8% lower prepare-to-ready time |
| [Qwen 3B GGUF CPU repacking](android/results/pixel9-repack-fixed-2026-10-24.json) | 3.337 s | 2.536 s | 24.0% lower full-load time with a fixed execution plan |
| [Qwen 3B GGUF ready-model reselection](android/results/pixel9-resident-reselection-2026-10-23.json) | 5.383 s | 0.177 s | Picker action to logic completion; retains the resident session |
| [Qwen 0.5B ONNX initial installed load](onnx/results/pixel9-performance-2026-10-24.json) | 3.807 s | 3.450 s | 9.4% lower |
| Qwen 0.5B ONNX explicit full reload | 3.189 s | 2.684 s | 15.8% lower; still reconstructs the runtime |
| Qwen 0.5B ONNX ready-model reselection | 2.732 s | 0.241 s | 91.2% lower preparation plus repository completion |
| Llama 1B Q4 ONNX initial installed load | 6.281 s | 6.402 s | No established improvement |
| [App startup Baseline Profile](android/results/pixel9-startup-profile-2026-10-23.json) | 525.0 ms | 435.5–448.6 ms | 76–90 ms faster initial display than dependency profiles alone |

Full-load and ONNX comparisons use medians from three fresh processes per condition. GGUF picker results use three sequential selections within each mode's running session, at starting temperatures of 35.8–36.2°C. These were sequential development/debug APK pilots with optimized native code, normal thermal status and recorded configuration; OS file caches and background work were not controlled. ONNX reselection excludes UI interaction and rendering. Neither reselection result measures the first drawn frame.

Startup uses AndroidX Macrobenchmark against a separate non-debuggable, profileable benchmark APK with minification off. The profile comparison used ten launches per case and two candidate batches. Its initial-display metric differs from model loading and from fully drawn readiness.

## Steps taken

### 1 Establish reproducible measurements

Added a separate AndroidX Macrobenchmark module for launcher startup and production chat UI fixtures, plus installed-model journeys for load, prefill and first token. The JetBrains suite informed the setup; the retained harness measures CaraML's Android app and native engines directly.

An audit of 147 saved Perfetto files exposed missing native attribution, inconsistent workload boundaries and packet loss in eight scrolling captures. Capture and runner checks now reject incomplete results, lost trace data, wrong runtime settings, missing foreground execution and invalid thermal conditions. APK hashes, source snapshots, model identities and raw results distinguish each experiment. Traced diagnostics are kept separate from ordinary timing runs.

Added asynchronous application spans, native loading boundaries, a Simpleperf fallback for native stacks, Vulkan timing analysis and ONNX operator profiling. ONNX tracing separates graph validation, tokenizer work, session creation, prefill and selected decoding operations. See [capture and native profiling](android/native-profiling.md).

### 2 Reduce installed-model verification overhead

The earlier 22–25 second observation was followed by a matched baseline: three repeated checksum-heavy stages consumed most of the 20.315 second installed load. The app now relies on publication-time hashes and checks the current manifest/generation, path containment, regular-file status and expected size during normal loading. Publication and interrupted-write recovery still verify complete file contents, and artifact ownership still protects loading from app-managed replacement.

This policy reduced the matched Qwen 3B prepare-to-ready median to 3.287 seconds. It applies to app-managed verified downloads. A same-size payload change after publication can evade the lightweight checks if the native parser accepts it; a full integrity check remains necessary to prove current bytes match the recorded hash. No persistent hash/mtime cache was introduced.

### 3 Reuse eligible resident sessions

Both llama and ONNX repositories can retain an idle, ready session when the model, artifact, execution configuration and relevant settings still match. Current artifact checks run under existing ownership. Cancellation, failure, context mutation or a changed request invalidates eligibility; explicit full load requests continue to reload.

This avoids tearing down and recreating a healthy model when selecting it again. Tests cover changed files, settings changes, cancellation, contention and lifecycle behavior. The large reselection gains in the table apply to an already resident model, not the first load after launching the app.

### 4 Optimize native CPU repacking

Simpleperf identified repeated tiny copies in Q4_K/Q6_K tensor repacking. [Patch 0007](../libraries/patches/llama.cpp/0007-cpu-repack-constant-interleave.patch) specializes the existing 4-byte and 8-byte interleave operations so the compiler can inline them while preserving tensor bytes.

The fixed-plan CPU comparison held context, batch, microbatch, KV types, threads and GPU placement constant. It reduced the full-load median by 24.0%; first-token time did not improve. Validation includes 6,532 byte-equivalence cases and sanitizer checks. The default NDK compiler remains in use; experimental compiler/Vulkan changes were not promoted.

A default-compiler rebuild changed two embedded Vulkan `diag_f16` shader bytes, replacing an infinity constant with zero. Zero matches the source branch, but the cause and wider shader correctness remain unproven. This CPU comparison does not establish identical Vulkan binaries or a GPU performance improvement.

### 5 Buffer ONNX graph metadata reads

The Java graph reader previously made frequent position, length and single-byte file calls while parsing protobuf metadata. An 8 KiB buffer, logical cursor and cached file length remove those repeated calls. Sparse seeks skip tensor payloads; protobuf bounds and external-data path checks remain. This reader assumes files remain immutable while open.

Qwen graph validation fell from 560 to 116 ms on the initial diagnostic load and from 468 to 9.3 ms on the repeated load. Llama's graph phase also shrank, but session creation dominated and its total initial load did not improve. These phase observations come from diagnostic captures with differing cache/capture conditions; the untraced table above measures total-load outcomes.

### 6 Improve startup readiness and isolate streaming state

Added the app startup Baseline Profile and removed shared Android preview tooling from the runtime dependency path. Fully drawn reporting now waits for persisted theme and inventory, recovery completion and the rendered shell. A composition-state observation bug in that reporting was fixed.

Streaming state now reaches only the active chat row. Diagnostic wrapper compositions fell from 172 to 26, but ordinary frame CPU p95 stayed around 10 ms and showed no improvement. Visual defaults were retained. Speculative `contentType`, broad effects and rendering changes were not adopted on the strength of this pilot.

## Current bottlenecks

| Area | Evidence | Current interpretation |
| --- | --- | --- |
| Installed-load checksums | No payload-checksum spans in the accepted steady GGUF and ONNX load traces | Repeated payload hashing has been removed from this path; publication/recovery hashing remains |
| ONNX first load | Candidate Qwen tokenizer about 1.01 s and session creation 1.47 s; Llama tokenizer 1.36 s and session 4.79 s in diagnostic captures | Tokenizer construction and ORT session setup are the next loading targets |
| ONNX Qwen generation | Output-head matrix multiplication plus repeated dequantization used 53.7% of summed ORT run time in one warm diagnostic generation | Investigate the export/kernel path; the float weight output is about 545 MB per run |
| GC | Full Qwen trace: 249.8 ms of concurrent GC cycles, only 4.72 ms in named pause phases; longest pause 1.82 ms | Long GC pauses did not dominate this capture; Llama GC was not captured |
| Memory polling | PSS calls lasted 39–68 ms, but disabling polling did not improve latency in the small matched pilot | Use separate memory diagnostics and `--no-memory-sampling` for latency work; do not claim a production speedup |
| Startup | Fully drawn medians 214 ms with caches preserved and 485 ms with cold caches; cold launches compile 27 graphics pipelines | The 271 ms gap is cache sensitivity, not an app optimization; graphics and DEX costs need isolated experiments |
| Scrolling | No measured frame-time gain from row isolation; one 120 Hz probe showed queued frames adding about one display interval | Profile the swipe-to-fling rendering transition and real inference contention |

The ONNX operator profile used a different prompt from the short app timing workload and recorded CPU kernels. It does not establish GPU/NPU acceleration or general generation speed. Fresh-process startup does not guarantee cold storage. Current frame tests use synthetic streaming, so generation while scrolling remains a separate workload.

## Validation and last verified state

The final ONNX APK completed load, generation and reload checks for SmolLM2 135M/360M, Qwen2.5 0.5B, Qwen3 0.6B and Llama 3.2 1B Q4. SmolLM2 135M answered “Two plus three equals four”; successful execution is not a quality pass. Llama's previously incompatible INT8 Android export remains excluded; the working Q4 bundle was used.

The latest host verification passed **1,571 JVM tests**, including real ONNX reference checks, and **6,532 native repack equivalence cases**. The Android debug build passed. `verifyProject` excluded only `:nativeEngine:verifyNativeSubmodulePins`: existing SSH remote URLs differ from the exact HTTPS spelling required by that gate. No remotes were changed. A macOS ORT telemetry shutdown hang was captured during one attempt; the fresh retry passed. Dated Llama reference prompts now use the saved fixture date without changing production dates.

The last verified APK is `b0394c238445b51e821cd65a91e6ff47f0688048b673f1b3ebbfe067ef5ccb47`. Device cleanup restored the original 30-second screen timeout, verified unchanged preferences and stopped the benchmark process. [Final validation and device evidence](onnx/results/pixel9-performance-2026-10-24.json) distinguish that APK from earlier measured builds. This cleanup/report update changes no application source and does not constitute a new device benchmark.

## Files removed and evidence retained

Removed **654 generated or superseded files**, deduplicated one 171.6 MiB APK through a relative link, and preserved the unique benchmark-controller APK before clearing its build directory. Net logical file size removed is **383.9 MiB**; this is not a measurement of physical APFS free-space gain. [Cleanup inventory](results/cleanup-2026-10-24.json) records paths, reasons, hashes and checks.

Removed categories are the Android benchmark module's generated build tree, Python bytecode caches, a duplicate shader compiler extraction/archive, generated assembly listings, superseded fixed-placement/scroll proposals, stale progress output and failed download headers. The complete measured traces, unique APKs, matching symbols, source snapshots and small result records remain.

The roughly 9.2 GiB of model fixtures remain because documented optional reference/lifecycle tests and current performance passes use them. Benchmark runners are retained: UI, installed-model, engine-only, provider-matrix and Apple workflows cover different cases. Exact APK/source identities and raw traces under ignored `build/benchmark-results/` are reproducibility evidence, not interchangeable build caches.

After cleanup, the ONNX report regenerated with unchanged findings and evidence identities. Local report links resolve, preserved APK hashes match, and the working diff passes whitespace checks.

## Next work in priority order

1. **ONNX first load:** isolate tokenizer construction and session initialization, then evaluate reusable compiled tokenizer data or supported session preparation with memory and invalidation checks.
2. **ONNX generation:** test an output-head export/kernel change against fixed prompts, token references, memory use and untraced latency before adopting it.
3. **Startup:** separate cold graphics compilation from DEX/file access; evaluate profile/R8 changes with JNI and reflection validation.
4. **Scrolling:** capture the 120 Hz swipe-to-fling transition and benchmark actual inference alongside scrolling.
5. **Confidence and release coverage:** alternate build order, increase repetitions, test a release-equivalent configuration and longer outputs, and resolve the submodule URL policy mismatch before calling the full gate green.

## Benchmark commands

Use **`python3 benchmarks/run.py`** as the single entry point. `--help` works at each level and does not access a device or require optional ONNX packages.

```bash
python3 benchmarks/run.py --help
python3 benchmarks/run.py android ui --help
python3 benchmarks/run.py onnx performance --help
python3 benchmarks/run.py models restore --help
python3 benchmarks/run.py test
```

The Python harness was consolidated from **30 files to 19**: 13 implementation files and six test files. Small download/matrix launchers now share model preparation and device modules; Perfetto/Simpleperf share profiling and completion checks; trace analysis and Vulkan summaries share an analysis module. Related tests were grouped with their assertions retained. Android PSS and iPhone RSS summaries remain separate subcommands in one module.

The remaining modules have distinct responsibilities: UI benchmarks, native engine loads, installed-model journeys, ONNX performance passes, profiling, source archiving, model preparation, device matrices, iPhone execution and report generation. Combining these into one large script would make their different measurement boundaries harder to maintain. The new command interface keeps those boundaries explicit without requiring users to remember the module filenames.

| Task | Command prefix |
| --- | --- |
| Startup, scrolling, Baseline Profile | `python3 benchmarks/run.py android ui` |
| GGUF engine loads / installed CPU–Vulkan journeys | `python3 benchmarks/run.py android load` / `android journey` |
| Perfetto / Simpleperf diagnostics | `python3 benchmarks/run.py android perfetto` / `android simpleperf` |
| UI trace / Vulkan log analysis | `python3 benchmarks/run.py android analyze` / `android vulkan` |
| ONNX installed-model performance | `python3 benchmarks/run.py onnx performance` |
| Engine runs / paired matrix / functional validation | `python3 benchmarks/run.py onnx engine` / `onnx matrix` / `onnx validate` |
| iPhone runs | `python3 benchmarks/run.py onnx iphone` |
| Summaries with platform-specific memory units | `python3 benchmarks/run.py onnx summary android` / `onnx summary iphone` |
| Download one bundle / restore fixtures / generate references | `python3 benchmarks/run.py models download` / `models restore` / `models references` |
| Regenerate ONNX comparison / run offline tests | `python3 benchmarks/run.py onnx report` / `test` |

Use the new commands in place of the removed scripts; their workload options remain the same. Saved result metadata and frozen source archives retain the original paths as historical evidence. All 50 previous offline tests pass after consolidation, alongside six new routing/workflow tests. ONNX report findings and evidence identities are unchanged; saved Android/iPhone summaries and analysis of three archived UI traces match the previous scripts exactly. This refactor makes no performance claim and did not run new device measurements.

## Reproduction and evidence map

| Purpose | Entry point |
| --- | --- |
| Startup, scrolling, Baseline Profile and GGUF journeys | [Android benchmark guide](android/README.md) |
| Native stacks, scheduler/memory traces and Vulkan analysis | [Native profiling guide](android/native-profiling.md) |
| ONNX installed loads, reuse, memory diagnostics and provider history | [ONNX benchmark guide](onnx/README.md) |
| Rebuild the ONNX report from retained archives | `PYTHONDONTWRITEBYTECODE=1 python3 benchmarks/run.py onnx report` |
| Exact cleanup actions and retained aliases | [Cleanup record](results/cleanup-2026-10-24.json) |

Keep each workload's fixed model revision, settings, APK identity and timing boundary when comparing future changes. Collect diagnostics separately and retain failed trials with their exclusion reasons.
