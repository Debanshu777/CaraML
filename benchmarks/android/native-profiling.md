# Native loading and first-token profiling

`android perfetto` records a bounded workload with scheduling/blocked reasons,
99 Hz app-scoped native call stacks, CPU frequency, process RSS, system memory,
FrameTimeline and supported GPU sources. It probes `perfetto --query-raw` before
building the config. GPU execution and GPU memory are separate capabilities:
the Pixel 9 probe on October 23 exposes GPU memory but **no `gpu.renderstages` or
`gpu.counters`**. An empty GPU slice table on that device cannot establish GPU idle
time, kernel cost or shader compilation cost.

Install the diagnostic APK, unlock the device and use the existing pinned model
and workload arguments. Run one backend at a time with identical context, KV
types, prompt, token count, cache state and starting temperature. Sampled captures
explain costs; use separate untraced repetitions for latency comparisons.

```bash
python3 benchmarks/run.py android perfetto \
  --serial DEVICE_SERIAL --seconds 120 --require-native-spans \
  --output build/benchmark-results/native-profile/cpu-01 \
  -- adb -s DEVICE_SERIAL shell am instrument -w -r EXISTING_HARNESS_ARGUMENTS
```

The command after `--` runs on the host without a shell. Use the current full-app
instrumentation for verification-to-first-token attribution, and the engine
harness only for isolated engine experiments. This script does not change model
files, app preferences, screen timeout, CPU/GPU clocks or root permissions. Avoid
running Macrobenchmark's recorder simultaneously with this independent recorder.

The recorder streams to disk every second, drains ftrace every 100 ms, separates
ftrace/CPU-stack/metadata buffers, and waits for recorder exit plus a stable,
nonempty file. The result fails validation if it has error/data-loss stats,
missing app scheduler/RSS/call-stack data, unfinished CaraML operations, or a failed
workload. `--require-native-spans` additionally requires instrumented native
boundaries. Both capture wrappers require `am instrument -w -r`, a final success
code, explicit harness PASS outcomes (or passing JUnit tests), and no failures.
An ADB exit code of zero alone is insufficient. Thermal snapshots are
saved but must be assessed before accepting a performance comparison.

`dumpsys meminfo` is captured **before and after**, outside the trace. Those
snapshots can include PSS; periodic trace counters contain RSS. We deliberately
avoid frequent `dumpsys meminfo` calls or unsupported `smaps_rollup` access during
the measured operation. No heap allocation profiler is enabled by default; it
adds different overhead and is a separate experiment if memory allocation proves
interesting.

To probe, generate an offline config, or validate an old trace:

```bash
python3 benchmarks/run.py android perfetto --serial DEVICE_SERIAL --probe-only
python3 benchmarks/run.py android perfetto --config-only \
  --query-file SAVED_QUERY_RAW.pb --output build/benchmark-results/native-profile/config-check
python3 benchmarks/run.py android perfetto --analyze CAPTURE.perfetto-trace
```

`--no-cpu-sampling` creates the same scheduling/memory capture without stack
sampling. `--gpu-counter-id N` is accepted only if the device advertises GPU
counters; choose IDs from that device's descriptor rather than borrowing them
from another GPU. Registered sources are not proof of recorded data: always
inspect the quality JSON and trace tables.

`--native-only` omits framework atrace categories (`am`, `wm`, `gfx`, `view`,
`dalvik`) and FrameTimeline while retaining target-app atrace, scheduling,
blocked reasons, CPU frequency, RSS/system memory and available GPU sources.
CPU sampling remains independently controlled by `--no-cpu-sampling`. Use this
profile to isolate framework trace overlap errors; the normal full profile
remains the default. Both `capabilities.json` and `run.json` record
`captureProfile`, and both profiles apply the same strict error/data-loss gate.

## Native boundaries

Android's native spans use `CaraML.native.*`. They contain fixed labels, never
model paths or model/user text. They are inactive when ATrace is disabled and
compile to no-ops on other platforms. Spans stay on the calling native thread;
application coroutine spans must use the app's separate async tracing support.

| Boundary | Meaning |
| --- | --- |
| `backendInitialize`, `backendLibraryLoad`, `backendDevicesInitialize` | Registry/library/device initialization; no artificial warmup is added. |
| `load`, `loadLock`, `releasePreviousModel`, `resolveFit` | Whole native load, operation-gate wait, old-state release and admission fitting. |
| `modelFileLoad` | `llama_model_load_from_file`, including metadata/vocabulary/tensor work. This is not just allocation. Use sampled call stacks to separate upstream internals. |
| `contextInitialize`, `samplerInitialize`, `chatTemplateInitialize` | Context/KV, sampler and template construction. |
| `systemPrompt`, `userPrompt`, `rawPrompt` | Native prompt processing, with nested template/tokenization/prefill stages. |
| `prefillBatch` | A `llama_decode` batch call. It can include graph construction, lazy backend setup and submission; it is not necessarily GPU completion. |
| `firstTokenWait`, `firstTokenSample`, `firstTokenDecodeSubmit` | Existing synchronization, sampling and first decode submission. No new synchronization is introduced. |

User/system prefill may return while backend work remains pending. Compare the
prefill span with `firstTokenWait`; do not equate a short submit span with a fast
GPU. Raw prompt generation already has a synchronization point, recorded as
`rawPrefillWait`. Each phase can contain nested spans, so do not sum them as
independent contributions. Only the first generated token receives per-token
spans; subsequent token throughput is measured by existing aggregate diagnostics.

## Resolving native symbols

Archive the exact APK and unstripped `.so` files from the same native build before
rebuilding. The `.so` build IDs must match the trace's `stack_profile_mapping`
records. Native source changes invalidate old binary/source associations; never
symbolize an older trace with the latest build merely because filenames match.

Current native build products are under
`nativeEngine/build/intermediates/cxx/Release/*/obj/arm64-v8a/`. Keep the runner,
llama and ggml backend libraries, including Vulkan/CPU modules. The installed APK
may omit local symbols that remain in these unstripped products.

Perfetto native samples are recorded even when local symbol names are unavailable.
The bundled processor supports a self-contained trace/symbol archive:

```bash
build/tools/trace_processor bundle --no-auto-symbol-paths --no-auto-proguard-maps \
  --symbol-paths /PATH/TO/MATCHING/UNSTRIPPED/LIBRARIES \
  CAPTURE.perfetto-trace CAPTURE.symbolized.tar
```

Matching uses ELF build IDs. See the [official symbolization guide](https://perfetto.dev/docs/learning-more/symbolization)
and [CPU profiling setup](https://perfetto.dev/docs/quickstart/callstack-sampling).
Inspect `perf_sample.unwind_error` and frame/mapping coverage; a nonzero sample
count alone does not prove complete stacks or symbols.

The Pixel 9 recordings contain `linux.perf` sample rows but no call stacks, even
with a debuggable APK and explicit DWARF unwinding. Their scheduling/native spans
remain useful, but those recordings do not close the native call-stack gap. The
separate NDK Simpleperf capture did: the CPU journey contains 2,543 samples, 2,542
with ancestors, and no unknown leaf symbols. Keep this distinction in reports.

`android simpleperf` uses the NDK helper with `--disable_adb_root`, matching
native symbols and 99 Hz DWARF call graphs. It waits for recording to start before
launching the workload, archives `perf.data` and a binary cache, and exports
per-sample frame chains plus coverage/top-frame JSON:

```bash
python3 benchmarks/run.py android simpleperf \
  --serial DEVICE_SERIAL --ndk /PATH/TO/NDK \
  --symbols /PATH/TO/MATCHING/UNSTRIPPED/LIBRARIES --seconds 30 \
  --output build/benchmark-results/native-profile/simpleperf-cpu \
  -- adb -s DEVICE_SERIAL shell am instrument -w -r EXISTING_HARNESS_ARGUMENTS
```

The helper can place matching symbols on-device. This is an explicit fallback,
not run implicitly by `android perfetto`. Do not combine sampling profilers in
the same comparison. Sample ancestor counts overlap and represent CPU activity;
they are not additive elapsed-time measurements. Use them to explain separately
measured native spans, and use untraced repetitions for before/after latency.

## Opt-in Vulkan operation timestamps

The pinned Vulkan backend already supports GPU query timestamps through the
`GGML_VK_PERF_LOGGER` environment variable; no `GGML_VULKAN_PERF` build flag is
needed. First archive `adb shell cmd gpu vkjson` and check that the selected
compute queue has nonzero `timestampValidBits`, a positive `timestampPeriod`,
and support for host query reset. Perfetto GPU-source availability does not
determine Vulkan timestamp-query availability. See the [Khronos timestamp example](https://docs.vulkan.org/samples/latest/samples/api/hpp_timestamp_queries/README.html).
The Pixel 9 probe confirms Mali-G715 timestamp support: the compute-capable
queue family has 64 valid bits, a 40.690105 ns timestamp period and host query
reset enabled.

The debug `PerformanceValidation` harness accepts `-e vulkanTiming 1` only with
`-e phase journey -e backend vulkan`. It enables the logger before backend
initialization, routes native stderr into an app-private `vulkan-timings-*.txt`,
reports the exact filename and byte count, and restores its environment and
stderr at completion. Pull the reported file with `adb exec-out run-as
com.debanshu777.caraml cat files/REPORTED_FILENAME`. The environment record marks
this run `diagnosticOnly=true`; keep it outside the untraced latency matrix.

The default logger inserts per-operation synchronization and waits at each graph
boundary, then reports GPU operation/fusion durations and tensor dimensions.
These timestamps can distinguish matrix operations from attention and copies,
but they change execution overlap. They are not application TTFT, and cannot by
themselves separate driver scheduling from hardware throttling. The logger does
not emit prompt/token contents. Ordinary runs leave it disabled.

Summarize the pulled file with `python3 benchmarks/run.py android vulkan
VULKAN_TIMINGS.txt`. The JSON preserves each graph's operation table and ranks
aggregate operation costs; truncated groups and inconsistent totals fail parsing.

## Vulkan shader compiler experiment

The Pixel 9 diagnostic found matrix multiplication accounts for 99.50% of the
11-token system prefill graph and 98.84% of the 22-token user prefill graph.
Each graph takes about 3.1 seconds under the GPU timestamp logger; flash attention
contributes less than 0.3%. Those diagnostic durations are not untraced TTFT.
The accompanying Perfetto file failed its quality gate on three overlapping
complete events; the separately parsed GPU timestamp log is the evidence for
this operation attribution.

The installed NDK compilers report Shaderc 2022.3 and reject the pinned upstream
cooperative-matrix and integer-dot extension probes. Consequently, those GPU
paths were absent from the original Vulkan build despite device support for the
extensions. Shaderc 2026.4 passes all seven upstream probes. The controlled
ARM64 build includes `GGML_VULKAN_COOPMAT_GLSLC_SUPPORT` and
`GGML_VULKAN_INTEGER_DOT_GLSLC_SUPPORT`; the x86_64 build remains CPU-only.
Runtime cooperative-matrix selection still requires a suitable F16-input,
F32-accumulator shape, and compiler changes can affect floating-point results.
Check backend initialization logs, output validity and separate untraced runs
before accepting a performance improvement.

For macOS 15 or later on Apple Silicon, this recipe extracts the pinned
[Homebrew Shaderc 2026.4 bottle](https://formulae.brew.sh/formula/shaderc) into the
workspace. It requires Python 3.12+ and `curl`, verifies the published archive
SHA-256 before extraction, and does not install anything systemwide. The
[official Shaderc downloads page](https://github.com/google/shaderc/blob/main/downloads.md)
was checked first, but its referenced October 21, 2026 macOS archive returned
HTTP 404, so this experiment uses the versioned Homebrew package instead.
Run from the repository root:

```bash
python3 - <<'PY'
import hashlib, json, platform, subprocess, tarfile
from pathlib import Path

if platform.system() != "Darwin" or platform.machine() != "arm64":
    raise SystemExit("This pinned bottle is for macOS arm64 only; use a host-compatible glslc.")
base = Path("build/tools/shaderc-2026.4")
base.mkdir(parents=True, exist_ok=True)
digest = "1313ffbae9ac1559f7160cc89657ecd5fc324895ff46e15a4bd198ec3bbebec0"
url = "https://ghcr.io/v2/homebrew/core/shaderc/blobs/sha256:" + digest
archive = base / "shaderc-2026.4.arm64_sequoia.bottle.tar.gz"
if not archive.exists():
    auth_url = "https://ghcr.io/token?service=ghcr.io&scope=repository:homebrew/core/shaderc:pull"
    token = json.loads(subprocess.check_output(["curl", "-fsSL", auth_url]))["token"]
    partial = archive.with_suffix(".partial")
    subprocess.run(["curl", "--config", "-", "-fL", "--retry", "2", url,
                    "-o", str(partial)], check=True, text=True,
                   input='header = "Authorization: Bearer ' + token + '"\n')
    if hashlib.sha256(partial.read_bytes()).hexdigest() != digest:
        raise SystemExit("Downloaded archive checksum mismatch; refusing extraction.")
    partial.replace(archive)
if hashlib.sha256(archive.read_bytes()).hexdigest() != digest:
    raise SystemExit("Cached archive checksum mismatch; refusing extraction.")
with tarfile.open(archive, "r:gz") as bundle:
    bundle.extractall(base / "extracted", filter="data")
compiler = base / "extracted/shaderc/2026.4/bin/glslc"
version = subprocess.check_output([str(compiler), "--version"], text=True)
(base / "reproduced-identity.json").write_text(json.dumps({
    "url": url, "archiveSha256": digest, "version": version,
    "compilerSha256": hashlib.sha256(compiler.read_bytes()).hexdigest(),
}, indent=2) + "\n")
print(compiler)
print(version)
PY
```

Other hosts can supply a compatible, validated system or SDK `glslc`; no package
installation is required by the build. To repeat the pinned source's probes,
substitute that executable for `GLSLC` below and retain exit codes, stderr and
generated SPIR-V. The experiment uses the upstream `vulkan1.3` target unchanged;
Shaderc 2026.4 emits SPIR-V 1.6 for that target. The device must support the target
and any selected extensions.

```bash
GLSLC=build/tools/shaderc-2026.4/extracted/shaderc/2026.4/bin/glslc
mkdir -p build/benchmark-results/shader-probes
for shader in libraries/llama.cpp/ggml/src/ggml-vulkan/vulkan-shaders/feature-tests/*.comp; do
  "$GLSLC" -fshader-stage=compute --target-env=vulkan1.3 \
    "$shader" -o "build/benchmark-results/shader-probes/$(basename "$shader").spv" || exit 1
done

./gradlew :app:androidApp:assembleDebug -PcaramlAndroidBackend=vulkan \
  -PcaramlVulkanGlslcPath=build/tools/shaderc-2026.4/extracted/shaderc/2026.4/bin/glslc
```

Omit `caramlVulkanGlslcPath` to restore the default NDK compiler. For each build,
archive its APK, matching unstripped ELF libraries, `CMakeCache.txt`,
`compile_commands.json` and `caraml-vulkan-compiler.txt`. Check the Vulkan
translation unit's feature macros instead of inferring them from device
capability. The October 23 experiment's acquisition identity, all feature
probes, both ABI configurations and extracted macro list are saved under
`build/benchmark-results/measurement-gaps/vulkan-modern-build-metadata/`.
Its native build passed; measured runtime outcomes belong in the benchmark
report, not in compiler-support claims.


Configuration references: [Perfetto service state](https://github.com/google/perfetto/blob/main/protos/perfetto/common/tracing_service_state.proto),
[CPU sampling config](https://github.com/google/perfetto/blob/main/protos/perfetto/config/profiling/perf_event_config.proto),
[process counters](https://github.com/google/perfetto/blob/main/protos/perfetto/config/process_stats/process_stats_config.proto),
[buffer behavior](https://perfetto.dev/docs/concepts/buffers).
