# ONNX and GGUF validation — October 23, 2026

CaraML now discovers, downloads and runs supported Hugging Face ONNX text bundles
alongside GGUF. The implementation validates the complete bundle, pins all files
to one revision, verifies hashes, accounts for F32 caches and switches between
engines without retaining both models. See [runtime scope](../../plugin-onnx/README.md).

## Models and method

The comparison uses five checkpoints: SmolLM2 135M Instruct, SmolLM2 360M Instruct,
Qwen2.5 0.5B Instruct, Qwen3 0.6B and Llama 3.2 1B Instruct. These represent three
architecture families, not five families. They are established small checkpoints
that fit the connected phone; this is not a ranking of all Hugging Face models.
Saved manifests include repository download counts at selection, immutable commit
IDs, filenames, sizes and file hashes.

| Checkpoint | ONNX export | Bundle MiB | GGUF export | File MiB |
|---|---|---:|---|---:|
| SmolLM2 135M | [model_int8.onnx](https://huggingface.co/onnx-community/SmolLM2-135M-Instruct-ONNX/tree/b8a5c0f183b78c55955a5364f610c36668b5e681) | 132.7 | [Q8_0](https://huggingface.co/bartowski/SmolLM2-135M-Instruct-GGUF/tree/09816acd5d99df7be770d85ea30822623dab342c) | 138.1 |
| SmolLM2 360M | [model_int8.onnx](https://huggingface.co/onnx-community/SmolLM2-360M-Instruct-ONNX/tree/fe7c7db4c8921c9e3fa1c65cfd296fb3b1b1a8f9) | 349.7 | [Q8_0](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct-GGUF/tree/593b5a2e04c8f3e4ee880263f93e0bd2901ad47f) | 368.5 |
| Qwen2.5 0.5B | [model_int8.onnx](https://huggingface.co/onnx-community/Qwen2.5-0.5B-Instruct/tree/22942cb7d7ba4cc81bb4673549ca4d4614469b5e) | 495.1 | [Q8_0](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/tree/9217f5db79a29953eb74d5343926648285ec7e67) | 644.4 |
| Qwen3 0.6B | [model_int8.onnx](https://huggingface.co/onnx-community/Qwen3-0.6B-ONNX/tree/58ca03a5e7b354b480b1a82b34bde8dc7b854cc0) | 597.8 | [Q8_0](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/tree/23749fefcc72300e3a2ad315e1317431b06b590a) | 609.8 |
| Llama 3.2 1B | [model_q4.onnx](https://huggingface.co/onnx-community/Llama-3.2-1B-Instruct-ONNX/tree/14007543b6dc92de88daf96a9aa85d2f95ace6ef) | 1625.5 | [Q8_0](https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/tree/067b946cf014b7c697f3654f621d577a3e3afd1c) | 1259.9 |

The Android device is a Pixel 9 with Tensor G4 and approximately 12 GB RAM.
The physical Apple device is an iPhone 13 (iPhone14,5, A15, approximately 4 GB
RAM), running iOS 27.0.1. Both apps are Debug builds with optimized native C++.
Android uses ORT 1.31.0 and Apple uses the pinned official ORT 1.30.0 archive;
cross-device differences therefore include runtime/build differences.
Android measurements use context 2,048, greedy decoding, the same system/user
messages, up to 64 output tokens, one warmup and three measured generations.
Every main-matrix configuration starts at nominal thermal status and battery temperature
at or below 35°C. The phone stays externally powered (Android reports AC power);
cooling intervals use the sleeping display and inference uses a dim, static
foreground activity. The harness verifies focus and display state throughout
generation. An earlier matrix lacked that check and was found hidden behind
the lock screen; those provisional timings are excluded. OS page caches are
not flushed, so load time is not cold-storage latency.
The timing harness measures runtime calls; catalog preparation, artifact-integrity
revalidation, admission checks and network downloads are outside those timings.
The separate app-path checks exercise those steps but are not speed measurements.

The gate is checked immediately before launching instrumentation. Temperature
recorded by the load event is measured after loading, so it can already exceed
35°C. Results retain temperatures and thermal states throughout each run.

The prompt is “Explain why the sky is blue in clear, simple language.” This is
a throughput and lifecycle exercise, not a factual-accuracy benchmark. Small
models sometimes give incorrect explanations; Qwen3 can spend the entire
64-token budget reasoning. Quantization and tokenizers differ, so token rates
are not interchangeable measures of answer quality.

ONNX uses INT8 exports for the first four models. Llama uses its working Q4
export, including external weights. GGUF uses Q8_0 for all five. Llama's ONNX
bundle is therefore not an equivalent-precision comparison and contains enough
unquantized weights to be larger than its GGUF Q8_0 file.

Decode speed excludes time to first token. TTFT includes prompt preparation and
prefill, and measures the first generated token, including a reasoning token for
Qwen3; it is not time to a completed answer. Android PSS is sampled every 100 ms and may miss brief peaks. Reported
memory is process PSS, not a complete accounting of GPU driver allocations.
Profiles establish provider placement and are excluded from speed comparisons.
Apple memory is process peak RSS through each measured generation, including
prior load peaks. It is a different metric from Android's sampled PSS. Apple's
thermal gate requires nominal state before load; any subsequent fair thermal
state is retained in the results rather than silently discarded.

## Correctness and repairs

- All five ONNX bundles passed the Android production path: Hub metadata,
  download coordinator, verified publication, catalog resolution, admission,
  conversation, reset, cancellation, unload and reload.
- Independent Hugging Face tokenizer and sandboxed Jinja reference vectors cover
  all five tokenizers and multi-turn formatting. A native Apple regex discrepancy
  in Qwen whitespace handling was fixed by using Foundation's ICU implementation.
- Prefill is bounded to 128 tokens and native KV outputs are reused rather than
  copied through Kotlin every step. CPU thread count is measured, not equated
  with the number of available cores.
- Measured thread tuning is scoped to the exact graph, runtime and device. The
  Pixel and iPhone measurements below determine their defaults; other devices retain one
  thread unless explicitly configured through the SDK.
- The exact Llama INT8 graph with SHA-256
  `ad53ed44994c27ab60e8024e83fcd83ce38db4d4ad3a28549b432480eb84d180`
  produces incorrect short output on the Pixel despite correct file hashes and
  prompt tokens. ORT 1.23.2, basic graph optimization and disabling KleidiAI did
  not resolve it. The Q4 export works. The app excludes this exact artifact on
  Pixel 9 and directs users to another export; the underlying incompatibility
  remains unresolved. [Output diagnostics](results/diagnostics/llama-int8/README.md)
  retain the tested kernel/runtime variants.
- Core ML rejects the empty past tensors required for a decoder's first step.
  An attempted masked dummy-cache workaround changed quantized results even
  on CPU, so it was removed. Dynamic-cache graphs explicitly fall back to CPU
  on Apple; no altered-cache workaround is shipped.
- The pinned llama.cpp registers Metal as `MTL`. CaraML now recognizes that
  registry name as Metal rather than reporting an unknown backend, with an
  Apple capability regression assertion.
- Physical iPhone tests exposed a second GGUF issue: `shutdown()` left a loaded
  model resident, so Metal asserted during process exit. Shutdown now releases
  the model/context before the backend. All five Metal cases pass after the
  repair. The driver requires a clean process exit as well as completed inference.
- Llama's ONNX Q4 bundle was killed during load on iPhone 13 with both thread
  settings and with CPU arena allocation/prepacking disabled. Memory pressure
  is suspected, but a matching OS memory report was not available. The app
  excludes this exact export on that device and recommends GGUF or a smaller
  ONNX model. The same Q4 bundle runs on the 12 GB Pixel. This is not an exclusion
  of all Llama exports or all iPhones.

## Physical Pixel results

All 20 configurations pass with continuous foreground verification.
[Detailed measurements](results/pixel9/SUMMARY.md) retain both ONNX thread counts,
first-token latency, load time, sampled memory and temperature.

| Checkpoint | ONNX CPU tok/s (threads) | GGUF CPU tok/s | GGUF Vulkan tok/s |
|---|---:|---:|---:|
| SmolLM2 135M Instruct | 39.76 (1) | 146.93 | 67.43 |
| SmolLM2 360M Instruct | 21.10 (2) | 64.91 | 34.07 |
| Qwen2.5 0.5B Instruct | 12.15 (2) | 49.32 | 30.13 |
| Qwen3 0.6B | 10.24 (2) | 38.77 | 24.18 |
| Llama 3.2 1B Instruct | 13.70 (2) | 20.32 | 13.18 |

These are medians of three measured generations; GGUF uses four CPU threads
in both modes. Vulkan offloaded 31, 33, 25, 29 and 17 layers respectively.
GGUF CPU is 1.5–4.1× faster at decoding than the selected ONNX settings here.
CPU also beats Vulkan first-token latency for this short prompt. This result
does not establish the best backend for long prompts, other quantizations or
other phones. ONNX uses one thread for SmolLM2 135M and two for the other four
verified exports on this exact Pixel/runtime combination.

## Physical iPhone results

All five GGUF checkpoints pass CPU and Metal generation, cancellation, reload
and shutdown. Four ONNX checkpoints pass; Llama ONNX Q4 cannot load on this
4 GB phone. [Detailed results and raw measurements](results/iphone13/SUMMARY.md)
include both ONNX thread counts, latency, load time, peak RSS and thermal state.

| Checkpoint | ONNX CPU tok/s (threads) | GGUF CPU tok/s | GGUF Metal tok/s |
|---|---:|---:|---:|
| SmolLM2 135M Instruct | 36.15 (2) | 181.65 | 39.63 |
| SmolLM2 360M Instruct | 20.00 (2) | 68.48 | 40.10 |
| Qwen2.5 0.5B Instruct | 11.18 (1) | 57.74 | 38.24 |
| Qwen3 0.6B | 10.49 (2) | 41.14 | 35.45 |
| Llama 3.2 1B Instruct | Load terminated | 19.32 | 22.51 |

These are medians of three measured generations; GGUF uses two CPU threads in
both modes. Metal offloaded 31, 33, 25, 29 and 17 layers respectively. CPU wins
decode throughput for the smaller checkpoints; Metal reduces GGUF first-token
latency and also wins throughput for Llama. The app's ONNX thread tuning is
limited to the tested iPhone model, ORT version and graph digest.

## Accelerators and the supplied resources

[QNN's official documentation](https://onnxruntime.ai/docs/execution-providers/QNN-ExecutionProvider.html)
confirms Qualcomm-specific GPU and HTP backends. Tensor G4 is not a QNN device.
QNN also requires supported operators and static shapes; these dynamic-cache
Hub graphs cannot be assumed to run unchanged. Snapdragon acceleration needs
a QNN-enabled build, matching vendor libraries and compatible exports. It was
not physically tested here.

The supplied [Hugging Face community article](https://huggingface.co/blog/tugrulkaya/running-large-transformer-models-on-mobile)
is useful background on quantization and mobile runtimes. It does not validate
these five autoregressive exports or establish GPU placement on this phone.

[Core ML](https://onnxruntime.ai/docs/execution-providers/CoreML-ExecutionProvider.html)
can dispatch compatible ONNX partitions to Apple's GPU. Registering it does not
guarantee that every node runs on GPU. The low-level adapter supports Core ML for
compatible graphs, while the app keeps the tested dynamic-cache exports on CPU.
GGUF uses its separate llama.cpp Metal backend.

[Native WebGPU](https://onnxruntime.ai/docs/execution-providers/WebGPU-ExecutionProvider.html)
provides a cross-vendor route through Dawn/Vulkan on Android. An opt-in, pinned
ORT 1.31.0 plugin build is included. The stock plugin missed the Pixel GPU because
its desktop hardware discovery does not cover this device. The Android adapter
now discovers the real Mali-G715 through Dawn/Vulkan. Profiles record actual
WebGPU kernels, but both SmolLM2 INT8 and Q4 fail before the first token during
GPU-to-host transfer (`WaitStatus::TimedOut`). Full validation does not resolve
it, and a bounded notification-retry experiment was removed after failing.
This remains an unresolved runtime/driver integration issue, not a working
GPU path; the ordinary app excludes the optional library and uses tested CPU
execution. [GPU diagnostics](results/diagnostics/webgpu/initial-placement.json)
retain the failed-run placement. No GPU speed is claimed from these profiles.

NNAPI and XNNPACK were also profiled: the tested SmolLM2 INT8 graph executed
all recorded kernels on CPU. Provider names alone are not used as evidence of
acceleration.

## Build and regression evidence

The [JVM gate](results/jvm-validation.json) passes 1,516 tests. The Apple simulator
suites pass four ONNX tests (including the five-model matrix and Unicode
normalization) and 40 llama tests (including five CPU/Metal pairs and shutdown
with a resident model). Both ordinary and optional-WebGPU Android APKs build,
and the signed iPhone build installs and passes a final ONNX lifecycle smoke
test. [Build evidence](results/build-validation.json) and
[artifact hashes](results/environment.json) distinguish the tested builds.
The final ordinary Android activity launched, but the Pixel lock screen prevented
visual Model Hub inspection. Functional app-path checks and the format-switch
regression passed; visual UI review remains unverified.

## Reproduce

The scripts require Python 3.13, Android SDK/ADB and the repository's normal JDK
21/Gradle toolchain. Optional reference generation uses
[`requirements-validation.txt`](requirements-validation.txt). Models are stored
under ignored `build/benchmarks`; weights are not checked into the repository.

```bash
python3 benchmarks/onnx/download_matrix.py
python3 benchmarks/onnx/generate_references.py --with-generation
./gradlew :app:androidApp:assembleDebug
adb -s SERIAL install -r app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
adb -s SERIAL push build/benchmarks /sdcard/Android/data/com.debanshu777.caraml/files/benchmarks/
python3 benchmarks/onnx/run_matrix.py --serial SERIAL --output build/benchmark-results/pixel-matrix
python3 benchmarks/onnx/summarize.py build/benchmark-results/pixel-matrix
python3 benchmarks/onnx/run_app_validation.py --serial SERIAL
./gradlew :plugin-onnx:jvmTest -PonnxFixtureDir="$PWD/build/benchmarks"
./gradlew verifyProject
```

The app-validation command exercises real Hub metadata, the download coordinator,
verified publication, model admission, two chat turns, cancellation and reload.
It reuses completed downloads, preserves paused/cancelled transfer intent and
does not clear app data. Qwen3 has a 256-chunk budget to reach a final answer.

Build the optional Android arm64 WebGPU plugin with `-PonnxWebGpu=true`.
Its script pins the ORT source commit and archive hash and requires NDK
28.2.13676358 and SDK CMake 3.31.1. This is a source build, so the first invocation
downloads and compiles Dawn and its pinned dependencies. The ordinary APK does
not include this experimental provider.

Run the simulator suites on a Mac with Xcode and a bootable arm64 simulator:

```bash
SIMCTL_CHILD_CARAML_ONNX_FIXTURE_DIR="$PWD/build/benchmarks" \
SIMCTL_CHILD_CARAML_GGUF_FIXTURE_DIR="$PWD/build/benchmarks" \
./gradlew :plugin-onnx:iosSimulatorArm64Test :plugin-llama:iosSimulatorArm64Test \
  -PiosSimulatorDevice=BOOTED_SIMULATOR_UDID
```

Simulator timings reflect the host Mac. They are retained as regression evidence
and must not be presented as iPhone speed, battery use or thermal behavior.
Use an already booted simulator for Metal. Kotlin's default standalone simulator
launch could enumerate Metal but failed context allocation; the same executable
passed all five CPU/Metal cases through a normal booted launch. The property above
selects that device and disables standalone launch.
An iPhone 13 running iOS 27.0.1 was connected during validation. Its physical
device runs use the opt-in harness below; simulator results remain separate.
No Snapdragon device is connected.

Build and run the physical iPhone harness using the existing Xcode development
team (replace `IPHONE_UDID` with `xcrun devicectl list devices` output):

```bash
ORG_GRADLE_PROJECT_iosBenchmark=true xcodebuild \
  -project app/iosApp/iosApp.xcodeproj -scheme CaraML-iosApp \
  -configuration Debug -destination 'id=IPHONE_UDID' \
  -derivedDataPath build/ios-device -allowProvisioningUpdates \
  'OTHER_SWIFT_FLAGS=$(inherited) -DCARAML_BENCHMARK' build
xcrun devicectl device install app --device IPHONE_UDID \
  build/ios-device/Build/Products/Debug-iphoneos/CaraML.app
python3 benchmarks/onnx/run_iphone.py --device IPHONE_UDID
```

The harness is excluded from ordinary builds. It copies fixtures only into the
app's `Documents/benchmarks`, keeps the scene foreground, requires nominal
thermal state before each configuration, and records four generations plus
cancellation/reload. Apple memory samples report process peak resident size
from `getrusage`, which is not directly comparable to Android PSS.
