# Android image-generation benchmarks

This suite measures the production `DiffusionRunner` JNI bridge in a foreground
debug instrumentation process. Native code is compiled with the app's Release
CMake profile. It separates model loading, generation through PNG encoding, and
the approximate times of sampling progress callbacks. It does not include model
discovery, download, admission UI, or drawing the finished image.

## Model selection

The five reference models are SD 1.5, SD-Turbo, SDXL Base, SDXL-Turbo, and
Z-Image-Turbo. These are a practical selection of popular models supported by
stable-diffusion.cpp, **not the literal five most-downloaded repositories or a
quality leaderboard**. Large Qwen Image bundles are outside this first phone
suite. Quantization and resolution must accompany every comparison.

`models.json` pins each public artifact's Hugging Face revision, byte count, and
SHA-256. Weights and generated trial files live under ignored `build/`; they are
not committed. The downloader verifies every completed file before use and the
Android transfer verifies the device copy.

The manifest's `files` list is the default download set. `optional_files` holds
the opt-in preview decoder, and `derived_files` records the locally converted
SDXL-Turbo bundle. Default downloads exclude both optional and derived files.

The phone copies are private benchmark fixtures, not entries in the app's
normal ModelHub inventory. Normal use still goes through the verified
download/import and admission flow.

```sh
python3 benchmarks/run.py diffusion download
./gradlew :app:androidApp:assembleDebug
adb install -r app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
python3 benchmarks/run.py diffusion android --model sd-turbo \
  --tag sdturbo-cpu-baseline --steps 4 --sampler 0 --prediction 0 --scheduler 6 --transfer
python3 benchmarks/run.py diffusion android --model sd-turbo \
  --tag sdturbo-vulkan-probe --backend VULKAN --steps 4 \
  --sampler 0 --prediction 0 --scheduler 6 --mmap 1 --vae-cpu 0
```

Use a unique `--tag` for every trial. The runner refuses to overwrite previous
results. It force-stops only CaraML before each process and preserves installed
app data. Installing the debug APK requires a compatible signing key. The
benchmark is absent from release manifests.

## Method

- Fixed prompt, seed 42, batch one; record sampler, CFG, dimensions, step count,
  quantization, thread count, flash attention, mmap, tiling, and weight placement.
- Establish the current CPU behavior first. Compare CPU/Vulkan and runtime
  settings with the same weights, dimensions, steps, and seed before varying
  sampling quality. Reduced steps are a quality/latency choice, not a kernel
  optimization.
- Report first generation and repeated generations separately; use at least
  three comparable repetitions for a claimed speedup. File caches are not
  flushed. This is a development benchmark, not an isolated laboratory test.
- Preserve thermal-service snapshots, per-step thermal states, app memory and
  GPU clock samples. A GPU clock increase alone does not prove GPU execution;
  backend availability also does not prove a graph ran there. Preflight records
  the requested component placement. Unsupported/failed GPU runs are failures,
  not timings to average into a speedup.
- Generation time includes text encoding, denoising, full VAE decode, PNG
  encoding and JNI byte transfer. It excludes saving/validating the PNG and the
  monitor-thread join. Progress timestamps are polled every 100 ms and are
  approximate; the first observed step includes preparation and step one.
- Keep completed PNGs for visual checks. A file that decodes successfully is not
  by itself evidence of good image quality. Quantization, flash attention, and
  sampler changes may alter pixels despite using the same seed.
- Save failures and timeouts. The native cancellation timer is cooperative; the
  host also terminates an overlong benchmark process. A process kill or OOM has
  no valid generation time.

## Device and interpretation

The October 25 run uses a physical Pixel 9 (Tensor G4 / Mali-G715, approximately
12 GB RAM, Android 17), batch one and the pinned native engines. The selected
files are quantized reference variants, not comparable parameter counts or a
quality ranking. SDXL-Turbo also has a locally converted Q4_0 bundle; its source,
engine revisions and output hash are in `models.json` under
`derived_files.sdxl-turbo-q4_0`. The original
6.94 GB FP16 bundle remains downloaded. The Q4_0 bundle is 2.71 GB; reduced file
size alone is not a measured peak-memory or quality result.

Run the prepared serial matrix and export its compact evidence with:

```sh
python3 benchmarks/run.py diffusion matrix --suite baseline \
  --screen-off-during-cooldown
python3 benchmarks/run.py diffusion summary
```

`android-suites.json` contains the `baseline`, `tuning` and `smoke` suites;
omitting `--suite` selects `baseline`. A custom JSON array of trial arguments
can still be passed as the positional matrix path without `--suite`.

The first four matrix trials waited up to 180 seconds for thermal status zero.
From SDXL Base onward, cooldown uses live HAL readings: CPU and GPU at most
45°C, virtual skin at most 38°C, no active compute cooling, and API status at
most LIGHT. The Pixel can remain at LIGHT because of USB/skin sensors even
when CPU/GPU cooling is inactive. The optional display-off cooldown does not
disable thermal controls; foreground instrumentation wakes the display again.
If the limits are not reached within 180 seconds, the runner records a warm
start and proceeds. These are development observations, not controlled
comparisons. Results are resumable by tag, including recorded failures.
Each process logs sanitized counts of graph nodes assigned to backend
buffers. `other` includes buffers whose device cannot be identified, including
CPU host buffers. These counts are not kernel timings or GPU utilization, and
upstream multi-device schedulers may not expose the callback.

The initial SD-Turbo baseline used the old V-prediction override and produced an
invalid image. Exclude it from quality-preserving speedup claims. Later CPU/GPU
comparisons use epsilon prediction, Euler and the same timestep schedule. The
four-step standard SD models measure execution cost; they are not converged
quality samples. The normal model defaults remain separate from this matrix.

The optional `--preview 1` comparison uses the explicitly approximate SD1/2
TAESD decoder. Download it with `diffusion download --model preview-decoder`.
It is not an SDXL decoder and is not enabled in the app by default. A preview
result must not be presented as equivalent to a full-VAE result.

For memory experiments, `--max-vram 3 --segmented 1` bounds managed GPU
allocations to 3 GiB and permits graph segmentation. `--prefetch 1` additionally
allows overlap and requires segmentation. These controls are opt-in; a managed
GPU budget is not a cap on total process/system memory. Failed runs preserve the
matching Android process-exit reason when available. GPU clock telemetry is
optional and native log collection is scoped to the benchmark process.

## Changes under test

- Android image admission can select Vulkan. Video retains its CPU policy.
  Text encoders remain on CPU; image VAE placement follows the admitted plan.
- Installed-image dimensions, steps and residency recommendations reach
  admission before the exact execution configuration is published. SD-Turbo
  uses epsilon/Euler and trailing timesteps; Z-Image-Turbo requests eight steps.
- The existing preferences select SD-Turbo budgets of one step for Speed,
  two for Balanced, and four for Quality. SDXL-Turbo uses one for Speed and
  four for Balanced/Quality. Image weights use mmap under
  the verified artifact lease. Reduced steps can change quality.
  Re-select the model after changing the preference.
- Image loading tensor counts no longer appear as sampling progress. Sampling
  state is published atomically and reset after each native operation; video
  retains upstream multi-stage progress.
- A Vulkan SD1/2/XL VAE workspace guard accounts for the diagnostic's 1,984 MiB
  full-decoder workspace at 512². The generic activation formula was far too
  small. The guard reserves 2–3 GiB at 512² and accounts for the pinned decoder's
  256-pixel tiles. This is a conservative estimate based on a specific native
  build, not calibration for every driver or architecture.
- Direct VAE convolution reduced diagnostic workspace but made decoding slower;
  it is not promoted. Global flash attention did not improve VAE decode.

## Pixel 9 measurements — October 25, 2026

The initial matrix uses **512×512, four steps, batch one, one generation per
cell**. CPU runs keep the VAE on CPU and disable mmap; Vulkan runs put denoising
and VAE decoding on GPU and enable mmap. Both keep text encoding and source
parameters on CPU. These observations compare combined execution settings,
with recorded but uncontrolled thermal/cache conditions; they are not a
statistical speedup claim or a model-quality leaderboard.

| Model / variant | CPU total | Vulkan total | Vulkan seconds/step after first | Visual outcome |
| --- | ---: | ---: | ---: | --- |
| SD-Turbo, F16/Q8 bundle | 180.3 s | 82.4 s | 12.0 s | Usable |
| SD 1.5, mixed-type Q4-named bundle | 342.9 s | 128.6 s | 23.2 s | Under-converged |
| SDXL Base, Q4_0 | 369.4 s | 288.2 s | 61.5 s | Under-converged |
| SDXL-Turbo, converted Q4_0 | 218.9 s | 162.1 s | 30.6 s | Usable |
| Z-Image-Turbo, Q4_0 + Qwen3 Q4_K_M | Low-memory kill | Exceeded time budget | 238.4 s (one interval) | No completed PNG |

Total generation includes lazy weight materialization, text encoding,
sampling, VAE decoding, PNG encoding and JNI transfer. The separate native
`loadModel` call is lazy (roughly 0.1–0.4 seconds here), so its time is not a
useful estimate of readiness for the first image. Post-generation app PSS in
the raw data is **not peak memory** and does not account for all GPU memory.

The four-step standard SD 1.5/SDXL results measure execution cost; their blurred
images are not suitable quality comparisons. Their normal step budgets were
not reduced to four in the app. The original SD-Turbo V-prediction baseline
took 141.4 seconds but produced nearly uniform noise, so it is excluded from
the table. Correctness comes before any latency comparison.
An earlier split-file SD 1.5 fixture failed preflight; its failure is retained,
and the table uses the subsequently verified self-contained GGUF bundle.

The Z-Image CPU process was killed by Android for low memory before sampling.
Its Vulkan run completed steps one and two at 251.7 and 490.1 seconds. After
the 600-second cooperative deadline failed to return promptly, the host
terminated the trial at 665 seconds of trial wall time; **665 seconds is not a
generation time**. Z-Image is also reported as `OTHER_IMAGE` by native preflight;
the app's typed memory estimator does not yet admit it as a supported
architecture. Its measurements exercise the JNI harness, not normal app
admission.

Successful SD-Turbo GPU generation recorded 10,738 scheduled graph nodes with
Vulkan buffers, alongside CPU-host/unknown buffers. The CPU reference recorded
no Vulkan-buffer nodes. GPU clock samples corroborate activity but are not
utilization measurements; this phone restricts its utilization sysfs counter.

The shorter tuning pass uses the same native libraries with additional
debug-only memory controls and a maximum **60-second cooldown**. Warm starts
are retained and labeled. Three same-context resident generations are recorded
separately from first-generation/process trials.

```sh
python3 benchmarks/run.py diffusion matrix --suite tuning \
  --cooldown 60 --screen-off-during-cooldown
python3 benchmarks/run.py diffusion summary
```

| Tuning case | Steps | Total generation | Finding |
| --- | ---: | ---: | --- |
| SD-Turbo, full VAE, CPU source-weight residency | 1 | 52.9 s | Recognizable; object-detail artifacts, warm start |
| SD-Turbo, full VAE, CPU source-weight residency | 2 | 62.2 s | Cleaner spout in this prompt |
| SD-Turbo, GPU source-weight residency | 1 | 45.9 s first; 44.1 / 44.0 s repeated | All three PNGs match the first one-step sample exactly |
| SD-Turbo, F16 weights | 1 | 46.8 s | About 0.54 GB more parameters; no established quality gain |
| SD-Turbo, flash attention off | 1 | 46.3 s | No controlled evidence for changing the default |
| SD-Turbo, approximate TAESD decoder | 1 | 19.5 s | Decode/output tail 2.1 s; visibly changed color/texture |
| SD-Turbo, tiled full VAE | 1 | 82.4 s | Decode/output tail 67.5 s; memory fallback, not a speed setting |
| SD 1.5, F16 weights | 4 | 124.7 s | Still under-converged; small timing difference is inconclusive |
| SDXL-Turbo Q4_0 | 1 | 76.3 s | Rough, patchy output; four steps retained for Balanced/Quality |
| Z-Image-Turbo, 256², 3 GiB managed GPU budget, segmentation | 1 | 109.0 s | Blurred diagnostic; eight-step quality not established |
| Same Z-Image probe, flash attention off | 1 | 113.0 s | No practical latency improvement |

All tuning cases use 512² unless explicitly marked 256². They are individual
observations, except for the three consecutive same-context residency runs;
those repetitions are not independent process trials. Temperature, caches,
step count and source-weight residency differ between rows. Do not attribute
their entire time difference to one optimization.

The final installed APK completed a **two-step SD-Turbo image in 58.0 seconds**
with full VAE, mmap, Vulkan and GPU source-weight residency. It reached steps
one/two at 15.6/27.6 seconds; the remaining decode/output tail was 30.4 seconds.
Its PNG hash matches the reviewed two-step tuning image exactly. This is the
final bridge smoke test, not a full ModelHub/UI performance measurement. The
final APK changes only `libdiffusion_runner.so` among the native libraries to
preserve video stage progress; all GGML kernels match the timing APK. Separate
APK and native hashes are retained in the provenance record.

The resulting app presets use **SD-Turbo 1 / 2 / 4 steps** for
Speed / Balanced / Quality. Full VAE remains the default; TAESD remains an
explicit SDK/benchmark option because it changes image appearance. No default
precision, flash-attention or direct-convolution change is justified by these
trials. VAE decode is still a substantial latency floor. Z-Image's one-step
256² probes are already too slow to justify a claimed fast eight-step profile
on this phone, and its normal app admission remains unsupported.

Validation passed: `verifyProject` (including native regression tests), Android
debug and release assembly, the iOS arm64 Kotlin bridge compile, and all 61
benchmark Python tests. No real iOS image-generation benchmark was performed.
The final smoke configuration is the `smoke` suite in `android-suites.json`:
`python3 benchmarks/run.py diffusion matrix --suite smoke`.

Evidence: [compact trial records](results/pixel9-2026-10-25.json),
and [supporting evidence](results/supporting-evidence.json) with `visual_review`,
`failure_analysis` and `build_provenance` sections. The summary command rewrites
only the compact trial records; supporting evidence is maintained separately.
PNGs, full logs and thermal snapshots remain in
`build/diffusion-benchmark/results/` locally.

## Reproducing the SDXL-Turbo conversion on macOS

Build the pinned desktop libraries before compiling the small converter. This
uses the same sd.cpp/GGML sources as the app, without changing the submodules.

```sh
./gradlew :nativeEngine:compileLlamaRunnerDesktop
diffusion_build=nativeEngine/build/llama-runner-desktop/macos
c++ -std=c++17 -O3 -I libraries/stable-diffusion.cpp/include \
  benchmarks/diffusion/convert_model.cpp \
  "$diffusion_build/sd-build/libstable-diffusion.a" \
  "$diffusion_build/llama-build/ggml/src/libggml.a" \
  "$diffusion_build/llama-build/ggml/src/libggml-cpu.a" \
  "$diffusion_build/llama-build/ggml/src/ggml-blas/libggml-blas.a" \
  "$diffusion_build/llama-build/ggml/src/ggml-metal/libggml-metal.a" \
  "$diffusion_build/llama-build/ggml/src/libggml-base.a" \
  -framework Accelerate -framework Foundation -framework Metal -framework MetalKit \
  -o build/diffusion-benchmark/convert-model
build/diffusion-benchmark/convert-model \
  build/diffusion-benchmark/models/sdxl-turbo.safetensors \
  build/diffusion-benchmark/models/sdxl-turbo-q4_0.gguf
```

Verify the output against `models.json`'s `derived_files.sdxl-turbo-q4_0` entry.
The derived file is used
with `--model sdxl-turbo --quantized 1`; the original FP16 download is preserved.

## Sources

- [Hugging Face download ranking](https://huggingface.co/models?pipeline_tag=text-to-image&sort=downloads)
- [SD-Turbo model card](https://huggingface.co/stabilityai/sd-turbo): SD 2.1,
  512×512, 1–4 steps, no classifier-free guidance.
- [SDXL-Turbo model card](https://huggingface.co/stabilityai/sdxl-turbo): SDXL,
  512×512, 1–4 steps.
- [TAESD model card](https://huggingface.co/madebyollin/taesd): an approximate
  preview decoder; SDXL needs different weights.
- [Pinned native Z-Image instructions](../../libraries/stable-diffusion.cpp/docs/z_image.md):
  Turbo uses eight steps and CFG 1; base uses guidance.
- [Pinned native performance notes](../../libraries/stable-diffusion.cpp/docs/performance.md):
  flash attention trades memory against backend-dependent speed; CPU weight
  residency and CPU graph execution are distinct settings.

## Recent Changes

- Consolidated this suite from 19 files to 12 while preserving trial arguments,
  model identities, review evidence and all existing Python tests. Named suites
  replace separate matrix files; custom argument-array matrices remain supported.
- Added pinned, checksummed downloads and foreground Android JNI measurements
  with failure records, PNG output, per-step progress, and thermal evidence.
- Recorded five-model CPU/Vulkan comparisons, tuning tradeoffs, final APK
  verification, and SD-Turbo 1/2/4-step presets on a physical Pixel 9.
