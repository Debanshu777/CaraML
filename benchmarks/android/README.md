# Android startup and scrolling benchmarks

See the [performance improvement report](../README.md) for the current cross-engine results, steps taken, remaining work and cleanup inventory. This guide retains detailed Android methods and dated experiment evidence.

CaraML uses AndroidX Macrobenchmark for Android UI performance. The controller
(`:benchmarks:android`) runs in a separate process against a non-debuggable,
profileable `benchmark` variant of `:app:androidApp`. It inherits the current
release build settings (including **minification off**), uses local debug signing,
and installs as `com.debanshu777.caraml.benchmark` beside the normal app.

## Current findings — October 24, 2026

These are separate Pixel 9 experiments, not interchangeable scores. Full model
loading, UI readiness and first-token latency have different measurement boundaries.

| Area | Finding | Retained change / next target |
| --- | --- | --- |
| Installed-model verification | Earlier full-load median fell from 20.315 to 3.287 s; fresh steady-load traces contain no payload-checksum spans. | Keep lightweight installed-load checks and publication/recovery checksums. |
| Same-model selection | Median picker action-to-logic-completion fell from 5.383 s to 0.177 s in three warm-device trials per mode. | Reuse the unchanged, idle native session. [Results and limits](results/pixel9-resident-reselection-2026-10-23.json). |
| Native execution | The matched CPU pilot reduces median full load from 3.337 to 2.536 s (24.0%); Vulkan's short-prompt GPU work dominates its first-token delay. | Retain the byte-equivalent repack specialization; keep the default NDK compiler. [Native evidence](results/pixel9-native-followup-2026-10-24.json). |
| Empty-catalog startup | Median readiness is 214 ms with caches preserved versus 485 ms with cold caches. Cold launches compile 27 graphics pipelines. | Keep visual defaults; investigate individual cold rendering and DEX costs. [Startup measurements](results/pixel9-startup-readiness-2026-10-24.json). |
| Streaming scroll | Active-row isolation removes 85% of wrapper compositions but demonstrates no frame-time gain. A 120 Hz run also exposes a short queued-frame delay. | Keep the small state-isolation change; investigate rendering completion around fling transitions. [Scroll measurements](results/pixel9-streaming-row-isolation-2026-10-24.json). |

The new measurements check raw result completeness, lost trace data, effective
runtime configuration, refresh cadence, thermal state, APK identity and preference
restoration. These benchmark checks do not add whole-model validation to app loads.
Real inference plus scrolling, long-output quality, heap allocations and complete
GPU pipeline attribution remain separate follow-ups.

Validation passed for 1,552 JVM tests, 34 benchmark-tool tests and 6,532 native
repack equivalence cases. The full `verifyProject` gate still rejects the existing
SSH submodule remote URLs because its policy requires exact HTTPS URLs; the
remaining gate passes with only `:nativeEngine:verifyNativeSubmodulePins` excluded.
No remotes were changed. [Validation record](results/pixel9-measurement-validation-2026-10-24.json).

## What is measured

| Test | Workload | Metric |
| --- | --- | --- |
| `StartupBenchmark.coldNoCompilation` | Real `MainActivity`, killed process, ART compilation reset | `timeToInitialDisplayMs`, `timeToFullDisplayMs` |
| `StartupBenchmark.coldWarmupProfile` | Same launcher, compiled after 3 warmup journeys | `timeToInitialDisplayMs`, `timeToFullDisplayMs` |
| `StartupBenchmark.coldBaselineProfile` | Same launcher, packaged Baseline Profile required, no training warmups | `timeToInitialDisplayMs`, `timeToFullDisplayMs` |
| `CachePreservingStartupBenchmark.processColdCachesPreserved` | Opt-in fresh process after an untimed ready launch; preserves shader/kernel caches | `timeToInitialDisplayMs`, `timeToFullDisplayMs` |
| `ChatScrollBenchmark.chatHistory` | Production chat screen/shell/effects with 200 fixed messages; 6 scrolls up, 4 down | `frameDurationCpuMs`, `frameOverrunMs` percentiles |
| `ShortChatScrollBenchmark.fastFling / markdownFling / streamingFling` | Opt-in bounded fast flings with plain history, rich completed history, or synthetic live updates | Same frame metrics |

All startup tests are **fresh process** launches. Warmup compilation does not mean
warm startup; only `coldBaselineProfile` explicitly tests the packaged profile. The runner clears only the benchmark
package's data before the suite. Each startup setup launches the real app outside
measurement to initialize preferences/databases, then `COLD` kills its process.
This measures empty-catalog, initialized-data startup, not a fresh install
on every iteration. The cache-preserving variant explicitly kills the process
after setup without requesting Macrobenchmark's `COLD` cache reset; compare it
separately. `ReportDrawnWhen` now waits for the first persisted theme and installed
inventory emissions, startup recovery completion, and the rendered chat shell.
Tests await `caraml-app-ready`. This readiness does not include selected-model
native loading or first-token generation. Older archives predate this definition
and contain TTID only; do not treat their first-frame timing as TTFD.

The runner requires exactly one finite, positive raw TTID and TTFD sample per
requested startup iteration. A missing TTFD, median-only metric, incomplete sample
array, or duplicated startup result fails the run and records
`startupMetricQuality` with `measurementValid=false` in `run.json`.

The scrolling fixture lives only in `src/benchmark`. It uses the same
`ChatScreenContent`, `ChatMessageList`, drawer, composer and frosted surfaces as the
app, with fixed light theme, normal motion and soft effects enabled unless
`--soft-effects off` is supplied. No model is loaded and no
remote data is needed. The normal Application still initializes; fixture launch
and its initial auto-scroll are excluded from the timed block. Every iteration
restarts the fixture and checks that scrolling succeeds. This isolates chat UI
cost; Model Hub pagination and true concurrent inference need separate journeys.

**Fixture v3** uses the page's own `FrostedPageScaffold` backdrop, matching the
production arrangement. It removes a duplicate outer `AuroraBackdrop` present in
earlier fixtures and mirrors the app's IME inset wrapper and system-bar icon
appearance. `run.json` records `fixtureVersion`, `fixtureTheme`, and
`fixtureBackdrop`; the exact sources and APK hash are archived. Compare application
changes only within the same fixture version. A v2-to-v3 improvement is a fixture
correction, not an application performance gain.

The opt-in short journeys default to a 4-second window, 200 messages and a 100-ms
synthetic word-update period. `scroll-markdown` renders rich completed replies;
`scroll-streaming` updates the latest reply as plain `Text`, matching production's
live-rendering branch. The reply is visible initially, then the journey scrolls
away through history. Trace counters must prove sustained updates during the
measured window. These updates do not exercise native inference, tokenization,
context preparation, model memory pressure, or CPU/GPU contention. Treat them as
UI-only workloads, then validate real generation plus scrolling separately.

`--soft-effects on|off` uses the existing policy, including frosted chrome and
related styling; gradients and grain remain. Startup experiments persist that
preference before measurement, but the initial ViewModel default can briefly
enable effects before DataStore emits. Keep effects and cache-state comparisons
independent, and leave R8 off until its separate native/reflection validation passes.

## Run

Requirements: repository Android build prerequisites, Python 3.11+, `adb` on PATH,
and an authorized **physical Android 12+ device**. Unlock it, keep it in portrait,
leave animations enabled at 1x, turn off battery saver, and let it cool. Keep font
scale, brightness, charging state and refresh-rate policy consistent between runs.
Do not interact with it during measurement.

Close UI inspection/mirroring tools before testing. The Android CLI's `layout`
helper can retain Android's single UI automation connection; if you used it, stop
its helper with `adb -s SERIAL shell am force-stop com.android.cli.interact.instrumentation`
before running Macrobenchmark. Otherwise the library can fail with
`UiAutomationService ... already registered`. Avoid running UI inspectors during
the suite.

```bash
adb devices -l
python3 benchmarks/run.py android ui --serial SERIAL --label baseline
# Quick wiring/fixture check only; do not use as a performance baseline:
python3 benchmarks/run.py android ui --serial SERIAL --label smoke --iterations 1
# For comparisons, cool between these shorter suites:
python3 benchmarks/run.py android ui --serial SERIAL --label baseline-startup --scenario startup
python3 benchmarks/run.py android ui --serial SERIAL --label baseline-scroll --scenario scroll
# Isolate the packaged profile versus no compilation on the same APK:
python3 benchmarks/run.py android ui --serial SERIAL --label profile-comparison --scenario startup-profile
```

The script builds both APKs, resets the isolated app data, records device state
before/after, and archives fresh JSON/Perfetto files, logs, revision, working-tree
patch, fixture sources, APK hash and run settings under
`build/benchmark-results/android-ui/<UTC timestamp>-<label>/`.
It temporarily extends the screen timeout to 30 minutes during installation and
testing, then restores the original value, including after failure. A secure lock
screen still needs to be unlocked by the device owner. Test XML and logcat are also
archived to diagnose failed runs.
It rejects emulators, old API levels, a sleeping device, battery saver and thermal
throttling before measurement. A non-normal thermal state after measurement also
returns a failure, while preserving all artifacts and recording `testsPassed`
separately from `measurementValid`. Run startup and scrolling separately and cool
between them for comparisons. Macrobenchmark's own validation remains enabled.
Do not suppress errors for results intended for comparison.

For Android Studio, choose `benchmark` in both modules and run the test classes.
For direct Gradle use (manage app data/device state and archive metadata yourself):

```bash
./gradlew :app:androidApp:assembleBenchmark :benchmarks:android:assembleBenchmark
ANDROID_SERIAL=SERIAL ./gradlew :benchmarks:android:connectedBenchmarkAndroidTest
# Filter a single journey:
ANDROID_SERIAL=SERIAL ./gradlew :benchmarks:android:connectedBenchmarkAndroidTest \
  '-Pandroid.testInstrumentationRunnerArguments.class=com.debanshu777.caraml.benchmark.tests.ChatScrollBenchmark'
```

AGP copies output beneath
`benchmarks/android/build/outputs/connected_android_test_additional_output/`.
Open `.perfetto-trace` files in Android Studio or [Perfetto](https://ui.perfetto.dev/).
The HTML test report is under `benchmarks/android/build/reports/androidTests/`.

## Compare changes

1. Run baseline and candidate on the same physical device, OS and display settings.
   Use 10 measured iterations (default) and at least 3 full runs of each revision.
   Alternate baseline/candidate order and cool between suites.
2. Compare each startup test's median separately. For scrolling, compare CPU-frame
   p50/p90/p95/p99 and frame-overrun p95/p99. Positive `frameOverrunMs` means the frame
   exceeded its actual deadline; negative values have headroom. A 60 Hz screen has
   a 16.67 ms refresh interval; at 120 Hz it is about 8.33 ms. FrameTimeline's allowed
   app deadline can span multiple refresh intervals. CPU duration alone is not
   a GPU or complete presentation-time measurement.
3. Inspect outlier traces and the before/after device metadata. Treat single-run
   changes as signals to investigate. Establish variance before adding a CI failure
   threshold; do not compare devices or claim a win from a one-iteration smoke run.

Run performance gates on a pinned physical-device runner, separately from
`verifyProject`. The existing Android CI job compiles both APKs; emulator measurements should
not become performance budgets.

## Generate the startup Baseline Profile

The checked-in `app/androidApp/src/main/baseline-prof.txt` is generated from the
real launcher with `BaselineProfileRule`. `ProfileInstaller` is included in the
production app. AGP merges and compiles these rules into the APK's
`assets/dexopt/baseline.prof` and `baseline.profm`.

```bash
# Physical Android 13+ device; generation changes the checked-in profile:
python3 benchmarks/run.py android ui --serial SERIAL --label profile-generation --scenario generate-profile
# Let the phone cool, then measure the packaged profile on the rebuilt APK:
python3 benchmarks/run.py android ui --serial SERIAL --label profile-validation --scenario startup-profile
# Only the installed-profile case, for comparing two revisions using the same compilation mode:
python3 benchmarks/run.py android ui --serial SERIAL --label profile-only --scenario startup-baseline
```

The runner copies only a fresh, nonempty generated profile after the generator
passes and verifies that it covers `MainActivity` and excludes `ChatScrollActivity`.
Generation is opt-in and does not run as part of the ordinary benchmark suite.
Its archive records the generated profile hash; measurement archives preserve the
input profile. Regenerate after changes to the launcher, UI dependencies or critical
startup paths. The current journey uses an empty installed-model database; it does
not cover model inference or scrolling. Generation is not a timing measurement.

For this first optimization, `includeInStartupProfile` is false: we isolate ART
precompilation without also changing DEX layout. A separate Startup Profile and R8
experiment should follow with its own before/after measurements. The existing
benchmark module collects profiles directly instead of adding a second set of
plugin-generated build variants. See [Android's profile generation guidance](https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile)
and [the supported source file location](https://developer.android.com/topic/performance/baselineprofiles/manually-create-measure).

## Analyze the traces step by step

Download the [official Perfetto trace processor](https://perfetto.dev/docs/getting-started/command-line-analysis)
once, then use the checked-in SQL queries to summarize every iteration:

```bash
mkdir -p build/tools
curl -fL https://get.perfetto.dev/trace_processor -o build/tools/trace_processor
chmod +x build/tools/trace_processor
python3 benchmarks/run.py android analyze build/benchmark-results/android-ui/ARCHIVE \
  --trace-processor build/tools/trace_processor \
  --output build/benchmark-results/android-ui/ARCHIVE/trace-analysis.json
```

1. Check `run.json`: successful tests, normal thermal endpoints and matching device
   settings. Use Macrobenchmark JSON as the authoritative TTID/frame metric result.
   Perfetto's startup-window duration uses slightly different boundaries.
2. Open a median and a slow `.perfetto-trace` in [Perfetto](https://ui.perfetto.dev/).
   Find the measured app process, expand its main thread and `RenderThread`, and
   zoom from activity launch through the first presented frame.
3. Read `bindApplication`, `activityStart`, Compose composition/measure and the first
   `DrawFrames` interval. Expand `CreateGraphicsPipeline-CompileAfterCacheMiss` on
   `RenderThread`. Check main-thread scheduling: sleeping while rendering completes
   is different from executing expensive Kotlin. Inclusive slices overlap; do not
   add them together as independent startup costs.
4. Compare `coldNoCompilation` with `coldBaselineProfile` on the same APK. A useful
   profile should reduce main-thread execution/composition while shader compilation
   remains comparable. COLD deliberately clears shader caches, so it exposes
   first-use rendering work even after the app's Kotlin code is precompiled.
5. For scrolling, inspect app FrameTimeline deadline misses and Macrobenchmark
   frame-overrun p95/p99. CPU frame time alone is not a full presentation budget.
   The recorded Pixel 9 traces have a 16.6 ms app frame budget, while archived active
   display/render metadata reports 120 Hz. Do not infer refresh rate from deadline
   duration; validate active mode and observed frame cadence separately. Streaming, images and Model Hub
   also need separate journeys.
6. Change one cause, preserve the workload and visuals, then repeat cooled runs.
   Add detailed Compose tracing before attributing generic composition costs to
   particular composables. Investigate blur/backdrop shader variants with controlled
   A/B traces; the current traces identify pipeline compilation, not a specific
   visual effect as its sole cause. Avoid merely deferring work past TTID: add a
   meaningful fully-drawn readiness signal before trying staged startup.

## First optimization: startup Baseline Profile — October 23, 2026

The follow-up startup suites recorded NORMAL (0) thermal status at both endpoints.
The app-specific profile was collected from the real launcher, stabilized after
six collection iterations, and contains no synthetic scrolling activity rules.
The release APK builds successfully with a compiled profile of about 26 KB.

| Comparison (10 cold launches per case; candidate measured twice) | Median TTID |
| --- | ---: |
| Same candidate APK, compilation disabled | 548.5 / 546.5 ms |
| Only dependency-provided profiles, `Partial(Require)` | 525.0 ms |
| CaraML + dependency profiles, `Partial(Require)` | 435.5 / 448.6 ms |

The app-specific profile improves the observed median by **76.4–89.5 ms (14.5–17.0%)** over
the library-only control. The first batch's 113.0 ms (20.6%) difference versus `None` includes
the benefit of library profiles too; it must not all be attributed to the new
CaraML rules. The control temporarily excluded the generated source profile, then
restored it. These are local device measurements, not universal performance guarantees
or a calibrated regression threshold. They measure a profile that has been installed
and compiled, not the first launch immediately after an arbitrary sideload.
The [result record](results/pixel9-startup-profile-2026-10-23.json) preserves individual
measurements, profile/APK hashes, trace summaries and archive locations. The final
confirmation has two passing tests, no failures/skips, and 20 traces.

Trace evidence supports the improvement: main-thread running time falls from
193.0 to 127.1–131.9 ms and composition slices from 97.0 to 59.2–60.4 ms (medians). GPU pipeline
compilation stays comparable at 115.4 versus 118.6–119.1 ms, with 27 cache misses per
startup. These inclusive times overlap and should not be added together.

The follow-up below completed detailed composition tracing and removed shared
Android `uiTooling`. Remaining experiments:

1. **First-render cost:** collect detailed Compose traces, then isolate the
   backdrop/blur render passes with one controlled change at a time. Preserve visual
   quality and verify screenshots. Pipeline compilation is a measured cost; the
   current system traces alone cannot assign all of it to Haze or Aurora.
2. **Code loading:** evaluate R8 and a separate Startup Profile. Keep JNI,
   reflection, resource and model-loading validation in that experiment. Release
   minification remains off in this result.
3. **Usable readiness:** define when theme/data/first screen are ready and report
   fully drawn. Measure TTFD alongside TTID before deferring startup work.
4. **Scrolling:** establish a fresh, cooled scrolling baseline, then add streaming
   and high-refresh journeys. The original 10 traces have 10,289 on-time frames,
   3 app deadline misses and 2 non-animating entries at a 16.6 ms frame budget.
   One missed frame spends 12.7 ms in frame-timeline dispatch on `RenderThread`;
   its main-thread frame is only 2.8 ms. The original thermal caveat still applies.

## Detailed composition and model-load measurements

The release-tooling cleanup removed 219,596 uncompressed DEX bytes. Ten profiled
cold launches measured 439.2 ms median, within the earlier 435.5–448.6 ms range:
no additional startup speedup is established. Composition diagnostics found
48–56 ms inside `NavigationHost`, versus about 1.7 ms creating the grain brush.

Two startup candidates were tested and reverted. Deferring secondary screen view
models did not reduce composition time. Lazy loading the llama library reduced
composition from 58.7 to 39.7 ms, but did not establish an improvement in total
launch time. Those later runs started warmer (35.5–35.6°C versus 32.2°C), despite
all thermal status samples being NORMAL; they do not prove a causal regression.
The runner now also requires a starting battery temperature at most 35°C by default
(`--max-start-temperature`), and comparisons should match temperatures and alternate
candidate/control order. See the [experiment record](results/pixel9-followup-2026-10-23.json).

Use detailed tracing to locate work, then disable it for timing comparisons:

```bash
python3 benchmarks/run.py android ui --serial DEVICE --label compose-diagnostic \
  --scenario startup-baseline --iterations 3 --full-tracing
build/tools/trace_processor query -f benchmarks/android/analysis/composition.sql TRACE
```

`--full-tracing` adds Compose runtime tracing only to the benchmark app and Perfetto
binaries only to its controller. Normal release builds contain neither dependency.
The [Android composition tracing guide](https://developer.android.com/develop/ui/compose/tooling/tracing)
describes this setup. The SQL reports inclusive and self durations; inclusive nested
slices overlap. Macrobenchmark's cold-launch setup also clears shader caches, so
the roughly 120 ms of pipeline compilation represents that deliberately cold
condition, not necessarily every everyday launch.

For a midsized engine workload, use the official Qwen2.5 3B Instruct Q4_K_M export
(2,104,932,768 bytes). Its exact revision and SHA-256 are stored in
[the manifest](results/manifests/qwen2.5-3b-gguf.json).

```bash
python3 benchmarks/run.py models download Qwen/Qwen2.5-3B-Instruct-GGUF \
  qwen2.5-3b-instruct-q4_k_m.gguf build/benchmarks/qwen2.5-3b-gguf \
  --revision 7dabda4d13d513e3e842b20f0d435c732f172cbe
./gradlew :app:androidApp:assembleDebug
adb -s DEVICE install -r app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
python3 benchmarks/run.py android load --serial DEVICE \
  --bundle build/benchmarks/qwen2.5-3b-gguf --trials 3 --runs 1 --threads 4 --gpu 0
# Repeat with --gpu 99 for all supported Vulkan layers, using the same installed APK.
# Add --trace for a separate diagnostic run with Perfetto load markers.
build/tools/trace_processor query -f benchmarks/android/analysis/model-load.sql TRACE
```

The runner verifies host and device hashes, starts each trial in a fresh process,
waits for NORMAL thermal status and battery temperature at most 35°C, and records
one load, one warmup generation and the requested measured generations. It checks
foreground continuity and restores the screen timeout. Context is 2,048 tokens;
each synthetic sky-explanation response is capped at 64 tokens with greedy sampling.
It saves the installed APK hash, raw output and result JSON under
`build/benchmark-results/model-load/`. The controller never clears normal app data.

These are debug-managed/release-native **engine** measurements. Weight verification
reads the model before testing; OS page caches are uncontrolled. They are not
cold-storage timings, sustained-throughput tests, or full user-visible loading
latency. `load_ms` includes engine initialization and native model loading, with
both also reported separately. TTFT starts at prompt submission after loading.
PSS is sampled process memory, not a complete accounting of GPU allocations.

To exercise the normal verified download/catalog/admission path and leave the
model available in CaraML, the debug harness has a pinned `qwen2.5-3b` case:

```bash
adb -s DEVICE shell am instrument -w -e case qwen2.5-3b -e phase download \
  com.debanshu777.caraml/com.debanshu777.caraml.benchmark.ModelRuntimeValidation
adb -s DEVICE shell am instrument -w -e case qwen2.5-3b -e phase load -e backend cpu \
  com.debanshu777.caraml/com.debanshu777.caraml.benchmark.PerformanceValidation
# Use phase run for generation, cancellation, unload and reload validation.
```

Keep download, UI timing and inference runs sequential. Repeatedly reading a large
model, rendering UI and generating text all influence subsequent thermal conditions.

### Pixel 9: Qwen2.5 3B Q4_K_M, October 23, 2026

Three fresh-process trials per backend, identical installed APK and prompt. All
sampled thermal statuses were NORMAL, and every trial verified foreground focus.
Vulkan actually allocated 37 layers. Medians:

| Metric | CPU, four threads | Vulkan, 37 layers |
|---|---:|---:|
| Engine initialization + model load | 3.00 s | 1.75 s |
| First prompt TTFT after loading | 1.13 s | 6.81 s |
| Load + first prompt TTFT (paired per trial) | **4.13 s** | **8.59 s** |
| Later warmed prompt TTFT | 1.03 s | 5.91 s |
| Warmed decode throughput, 64-token replies | 9.77 tokens/s | 10.06 tokens/s |

CPU gives the shorter wait for an answer in this workload. This is a measured
per-model/device recommendation, not a global GPU policy change. The fresh-prompt
row uses the harness's first (warmup) generation, rather than substituting its
later warmed result. Load + TTFT excludes app/catalog preparation and test-controller
overhead; it is not an end-to-end UI stopwatch measurement.

Native CPU logs locate 2.8–3.0 s in `MODEL_ALLOCATION` and only 29–38 ms in
`CONTEXT_ALLOCATION`. Reducing context is not the leading load-time opportunity
here. Reusing the resident model avoids repeating this work; clearing chat context
is different from unloading/reloading weights. The generated sky explanation was
nonempty and coherent; this short smoke test does not assess general answer quality.
See the [raw per-trial result record](results/pixel9-qwen3b-2026-10-23.json).

The normal chat UI also passed a smoke test with Qwen 3B selected: it answered
“2 plus 3 is 5.” under the saved Vulkan preference. Logs measured 3.12 s to the
first token and 4.13 s total for that eight-token reply. The UI's displayed 0.81 s
is its decode interval, not the total wait. This different prompt/configuration is
not included in the controlled backend comparison. Qwen remains selected and the
phone's original 30-second screen timeout was restored after testing.

### Installed-model load: minimal verification

Installed models now use the checksum recorded when the download is published.
Normal preparation, request resolution, admission and the final native-load boundary
check the published manifest/generation, path containment, regular-file status and
expected byte count **without re-reading all model bytes**. GGUF header/native
compatibility and memory checks remain. The repository-root lock still covers native
loading, preventing app-managed replacement or cleanup during that operation.
Publication and interrupted-write recovery retain full checksum verification.
`validatedBundle()` remains the full integrity check; `installedBundleForLoad()` is
the lightweight path. No persistent hash/mtime cache was added.

**Tradeoff:** a same-size payload edit or bit corruption after publication may go
undetected by loading if the native parser accepts it. Lightweight checks do not
prove current content equals the published checksum. This is the intentional
performance policy for app-managed downloads; unverified file imports should not
be routed into this path.

On the Pixel 9, Qwen2.5 3B Q4_K_M (2.10 GB), three fresh-process CPU loads before
and three after gave the following medians. Both builds used context 4,096,
threads 4/4, batch 256, AUTO KV resolved to 1/1, GPU layers 0 and temperature 0.
Each run passed with thermal status NORMAL and foreground CPU affinity 0–7.

| Full app load stage | Before | After |
| --- | ---: | ---: |
| Prepare installed metadata | 7,151 ms | 88 ms |
| Resolve load request | 2,833 ms | 127 ms |
| Guarded load, including native load and system prompt | 10,328 ms | 3,067 ms |
| **Total prepare → ready** | **20,315 ms** | **3,287 ms** |

That is **17.03 seconds / 83.8% less waiting** (6.18× faster). After totals ranged
from 3.18 to 3.42 seconds. This measures the real app load path, not engine-only
loading. It excludes process startup, startup reconciliation and reply generation.
These are sequential development-build measurements with uncontrolled OS file
caches, not cold-storage or release-performance guarantees. Numeric user settings
were overridden in memory; saved preferences and downloaded model data were retained.
The earlier 21.7–25.1-second observations used other APKs; this table uses a new
matched baseline. See [per-trial data and APK identities](results/pixel9-minimal-verification-2026-10-23.json).

Validation: 1,365 app JVM tests passed, including payload-read avoidance, truncation,
removal, changed bindings, same-size edits, pending journals and existing cancellation/
root-lock cases. `verifyProject` and the Android debug build passed with the existing
`:nativeEngine:verifyNativeSubmodulePins` remote-allowlist check excluded; native
remotes were not changed. CPU and Vulkan smoke runs both answered “2 plus 3 is 5.”
The one-shot first-token timings were 543 ms CPU and 5,900 ms Vulkan; these are
follow-up leads, not enough trials to change backend defaults.

Repeat a load or a short real-generation smoke test:

```bash
adb -s DEVICE shell am instrument -w -e case qwen2.5-3b -e phase load -e backend cpu \
  com.debanshu777.caraml/com.debanshu777.caraml.benchmark.PerformanceValidation
adb -s DEVICE shell am instrument -w -e case qwen2.5-3b -e phase smoke -e backend vulkan \
  com.debanshu777.caraml/com.debanshu777.caraml.benchmark.PerformanceValidation
```

Next, measure first-token latency at the same production context/KV settings across
CPU and Vulkan, and check that starting a new conversation reuses the resident
model. Verification is no longer the dominant load cost in this workload.

## Native follow-up — October 24, 2026

The first native optimization is a byte-identical CPU repack specialization.
The modern Vulkan compiler remains an **opt-in experiment, rejected for default
promotion pending follow-up**: it improves measured prefill but slows decode.
The Android Vulkan build still defaults to the NDK compiler. See the
[compact results, identities and exclusions](results/pixel9-native-followup-2026-10-24.json)
and [capture/reproduction guide](native-profiling.md).

### Measurement gaps closed

Full-app async phases and fixed-label native spans now distinguish preparation,
request resolution, admission/preflight, root-lock wait, model/context setup,
system/user prefill and first-token completion. They do not record prompt text.
Native submissions can return before GPU completion; nested spans are not additive.

Perfetto's `linux.perf` produced 3,010 CPU sample rows but **zero call stacks**
on the Pixel 9, so its sampling gate correctly failed. The separate NDK Simpleperf
capture provides 2,543 samples, 2,542 with ancestors, 2,167 touching llama/GGML,
and zero unknown leaf symbols, with matching unstripped libraries archived.
That closes native attribution for this CPU journey; it does not fix Perfetto
unwinding or provide a heap-allocation profile.

The device exposes GPU memory, but no Perfetto GPU execution/counter source.
Vulkan query timestamps independently measure operation costs. The original
framework-inclusive GPU trace failed on three overlapping complete events.
The new `--native-only` profile retains app atrace, scheduler, frequency and
memory data while omitting framework categories and FrameTimeline. Its modern
compiler capture has **zero errors/data loss, 78 closed native spans and 635 RSS
samples**, with no incomplete CaraML spans. Quality rules were not relaxed;
the normal full capture remains available for UI analysis.

### CPU load: repack, not remaining checksums

Within 296 native-load samples, 239 (80.7%) pass through CPU repack buffer writes:
162 are in Q4_K repack and 76 in Q6_K repack. Tiny copies dominate those stacks.
Preflight separately contributes 63 samples, including 34 in vocabulary setup and
17 in metadata loading. These are overlapping diagnostic stack counts, not
elapsed-time percentages.

Build-owned patch `0007` specializes the existing 4/8-byte interleave sizes at
compile time so the compiler can inline the inner copies. Tensor layout and
quantization stay identical. All **6,532 byte-equivalence cases** pass, including
random/edge inputs and ASan/UBSan checks; the test is part of `verifyProject`.

The completed **fixed-plan CPU pilot** uses three untraced fresh-process runs per
build. The control temporarily omits only patch `0007`; all other logical build
inputs match, the patch is restored, and packaged native build IDs match the
default NDK build. Managed DEX is byte-identical. Every accepted CPU run uses
context 4,096, batch 256, microbatch 64, F16/F16 KV, threads 4/4, GPU layers 0,
`offloadKqv=false` and `autoFit=false`.

| Full-app CPU load | Control | Repack specialization |
| --- | ---: | ---: |
| Trial 1 | 4,034 ms | 3,257 ms |
| Trial 2 | 3,337 ms | 2,321 ms |
| Trial 3 | 3,210 ms | 2,536 ms |
| Median | **3,337 ms** | **2,536 ms (24.0% lower)** |

The repository-load median falls from 3,094 to 2,332 ms; time outside that call
stays near 221 versus 217 ms. All six initial loads take the same two-attempt
confirmation path, including 35–46 ms for the control's first admission attempt
and 39 ms for each candidate; both attempts are included in these totals. All
18 short greedy replies match, with zero recorded generation GCs. First-prompt
TTFT medians are 580 versus 598 ms, so this pilot establishes no TTFT improvement.

This is a small sequential pilot, with all control trials before candidate trials.
Thermal status is NORMAL throughout, with endpoint battery temperatures
33.7–35.4°C control and 34.7–35.3°C candidate. OS caches and background work remain
uncontrolled. An earlier mixed candidate group stopped after one valid CPU and one
valid Vulkan trial because the next trial could not cool within 120 seconds; those
trials are retained but excluded from this comparison. The replacement CPU group
allowed 180 seconds to cool while keeping the same ≤35°C start gate. The earlier
one-CPU/one-Vulkan harness check is also separate. See [all trials, admission
attempts, exclusions and build provenance](results/pixel9-repack-fixed-2026-10-24.json).

Repeated repository requests take medians of 3,747 versus 3,052 ms and have attempt
counts 2/1/2 in each group. These timings include admission and are **not evidence
of resident reuse or model-picker latency**. The single candidate Vulkan journey
has TTFT 5.923/5.895/5.820 seconds across its three prompts; this is one process,
not a repeated Vulkan distribution or a GPU A/B result.

One rebuild discrepancy remains: `libggml-vulkan.so` differs in its build ID and
two embedded `diag_f16` SPIR-V bytes, changing a float constant from infinity to
zero; zero matches that shader's source branch. Native `.text` code and all ELF
sections except `.rodata` and the build ID match. DIAG is absent in the saved Qwen GPU operation logs. The
cause and wider shader correctness are unproven; no identical-Vulkan-binary or
GPU-performance claim is made for this CPU comparison.

Earlier untraced fresh-process full-app CPU loads produced these exploratory subsets,
matched by **logged** context 4,096, F16/F16 KV, threads 4/4, GPU layers 0 and batch:

| Logged batch | Baseline loads | Candidate loads | Baseline median | Candidate | Observed reduction |
| --- | ---: | ---: | ---: | ---: | ---: |
| 128 | 2 | 1 | 3,458.5 ms | 2,601 ms | 24.8% |
| 256 | 4 | 1 | 3,757.5 ms | 2,389 ms | 36.4% |

These are **not a completed matched A/B matrix**. Adaptive batch drift invalidated
the aggregate baseline matrices. Candidate trial 3 took 2,286 ms but resolved to
context 2,048 and failed the resident-request check; it is excluded. Only one
accepted candidate remains per batch. Baseline logs also predate explicit
ubatch/offload/autofit fields, so full effective-plan equality is not established.
All included runs were thermal NORMAL, but temperature, background activity and
OS caches were not identical. The observations support the targeted change without
establishing a robust speedup bound. Short greedy reply hashes match.

The CPU and both Vulkan steady-load traces contain **zero payload-checksum spans**.
Simpleperf records 15 SHA samples, 14 attributable to the benchmark's APK identity
hash outside timed model loading; one is unattributed. This agrees with the
lightweight installed-load policy and payload-read avoidance tests. It is not a
claim that hashing never runs: publication/recovery integrity checks remain, and
same-size post-publication corruption can still evade ordinary loading checks.
The earlier **20.315 → 3.287 s** verification improvement is a separate established
experiment; do not add its saving to these later timings.

### Vulkan: faster prefill, slower decode

Shaderc 2026.4 passes all seven upstream compiler probes and builds ARM64 with
cooperative-matrix/integer-dot support; x86_64 retains its CPU fallback. The NDK
compiler's missing features are confirmed in archived compile commands. However,
the runtime-selected matrix shape is not directly logged: the sanitized logger
drops upstream debug capability messages.

Compare GPU timestamp diagnostics with each other, keeping their added
synchronization separate from ordinary app latency:

| GPU graph median | NDK compiler | Shaderc 2026.4 | Change |
| --- | ---: | ---: | ---: |
| System prefill, 11 tokens | 3,075.50 ms | 2,781.63 ms | −9.6% |
| User prefill, 22 tokens | 3,116.17 ms | 2,784.43 ms | −10.6% |
| Single-token decode | 99.37 ms | 137.43 ms | **+38.3%** |

The old prefill graphs spend 99.50%/98.84% in matrix multiplication and less than
0.3% in flash attention. Modern Q4_K vector kernels slow by 56–65%, while Q6_K
decode remains nearly unchanged. The source enables integer-dot vector kernels
for Q4_K on Mali but excludes Q6_K except on Intel. The next narrow experiment is
`GGML_VK_DISABLE_MMVQ=1`, which disables that vector path while preserving
integer-dot prefill. It has **not been run or enabled by default**.

Timestamped harness TTFT changes from 3.245–3.274 s to 2.956–2.975 s. Comparing
that modern value with the older **untraced ~6 s** result would be wrong: the
timestamp logger synchronizes system prefill during load/reset, moving work out
of the subsequent TTFT interval. Three graph repetitions share one process per
compiler; they are not three independent trials. Output hashes match this short
greedy workload, but broader accuracy and long-context behavior remain untested.

Initial whole-load time increases from 6,795 to 8,531 ms in these diagnostics.
The extra time is inside model-file loading (1,356 → 3,360 ms), including
1,041 ms of disk wait in the modern capture. Reload reverses the result
(2,291 → 1,442 ms), and first system prefill gets shorter. This does not establish
a persistent load regression or shader compilation as its cause. The pinned
backend retains compiled pipelines in process but passes a null Vulkan pipeline
cache when creating them; driver-internal caching remains possible.

### Remaining native gates

Repeat the CPU pilot with more trials and alternating build order before treating
24% as a general speedup bound. The modern compiler still needs repeated untraced
Vulkan comparisons with complete effective-plan equality and a quiet device. Run the MMVQ
experiment before considering modern-compiler promotion; then isolate integer dot
and cooperative matrices only if needed. Validate sustained decode and longer
answers as well as matching short outputs. Add safe runtime pipeline selection
and compilation timing before attributing load delay to shaders, and investigate
the default-compiler DIAG rebuild discrepancy separately. Cold-storage
loads, release performance, heap allocation, publication/recovery hashing and real
inference concurrent with scrolling remain separate workloads.


## Perfetto audit — October 23, 2026

This is the historical audit before the [native follow-up](#native-follow-up--october-24-2026)
above closed several of its measurement gaps.

This audit reprocessed **147 saved files: 146 readable traces and one empty capture**.
It used Perfetto v58.2 SQL, scheduler states, FrameTimeline, slice nesting, the archived
APK/run metadata, and the full-app verification experiment. No further app behavior
was changed. [Machine-readable findings and per-file quality checks](results/pixel9-perfetto-audit-2026-10-23.json)
link to the full local audit and frame records under `build/benchmark-results/perfetto-audit/`.

### Findings, ordered by potential user impact

| Finding | Evidence | Proposed next action |
| --- | --- | --- |
| Repeated model checksums caused substantial load delay; already addressed | Full-app median **20.315 → 3.287 s**, three runs per build. Four individually logged passes in the last baseline totaled **10.872 s**, scanning at least **8.42 GB of logical model data**, plus manifest checks outside those markers. | Retain the minimal installed-load policy. Measure publication separately before reducing any remaining download checks. |
| Native model initialization dominates the remaining load | CPU trace: **2,248.77 ms** native load, **2,185.35 ms** caller scheduled/running, **40.73 ms** I/O sleep and **22.69 ms** runnable delay. Associated logs put **2,213 ms** in `llama_model_load_from_file` and **25 ms** in context creation. | Sample native stacks and add subphase markers around metadata/vocabulary/tensor setup. Reuse the resident model where possible. Context-size tuning alone is unlikely to remove this measured loading cost. |
| Vulkan has a first-answer latency problem in the measured workload | Three isolated runs: first prompt TTFT medians **6.81 s Vulkan / 1.13 s CPU**. After the verification change, single production-path smoke runs showed **5.90 s / 0.543 s**, respectively. | Capture CPU/Vulkan prefill, graph preparation, submission and first decode at identical production settings. Consider a CPU low-latency preference only after repeated matched trials. There is no saved Vulkan Perfetto capture proving the cause. |
| First-frame graphics setup is a startup cost | Ten profiled startup traces have **27 pipeline compilation misses per launch**, **118.1 ms median** compile time on RenderThread; earlier analysis measured **164.0 ms median** first DrawFrames. | A/B blur, layered backgrounds and grain separately. Record both shader-cache-cold and cache-preserving process launches. Preserve visual quality unless the experiment shows a meaningful win. |
| DEX/app initialization and UI construction delay the first frame | Latest retained startup build: **84.4 ms bindApplication**, **45.7 ms OpenDexFilesFromOat**, **58.0 ms main-thread I/O sleep**, **58.7 ms Compose recompose**. These overlap. | Test R8 with JNI/reflection/serialization keep rules, then Startup Profile DEX layout independently. Trace DI constructors and library loading before deferring specific work. |
| Re-selecting the current model may incur avoidable reload work | Source: `ChatViewModel.selectModel()` calls `loadSelectedModel()` when the selected ID is unchanged. That path releases runners for assessment. No saved trace measures this UI action. | Add a resident-model reuse path only when ready state, artifact generation, backend and effective configuration match; preserve explicit retry/reload behavior. Validate with a dedicated reselection journey. |

The checksum speedup is established by **full-app instrumentation and stage logs**,
not by a before/after pair of end-to-end Perfetto files. The 10.872 seconds covers
only the four named resolver hashes; the larger 17.028-second overall improvement
also includes manifest validation reads. Logical bytes scanned are not equivalent
to physical flash reads because file caches were uncontrolled. This experiment
measures load latency, not a change in token decode throughput. Small manifest or
output-string digests are not comparable to repeatedly hashing a 2.10 GB model.

Full checksum validation still exists in publication, recovery and some storage
inspection/cleanup paths (`ArtifactBundlePublisher`, `ArtifactManifestStore`). Their
source contains repeated `readValidated()` calls, so time from download reaching
100% to model-ready is a plausible next measurement. The saved traces do not
quantify that cost; do not remove download integrity checks on this evidence alone.

### Startup: interpret wall time and CPU time separately

The closest retained startup build reports **439.2 ms median** in Macrobenchmark.
Perfetto's `android_startups` interval is **441.6 ms** with this processor version;
these are separate interval definitions and should not be mixed in an A/B table.

In the three detailed composition captures, `NavigationHost` takes **48.3–55.6 ms
wall time**, but only **22.0–29.8 ms on CPU**. One 52.36-ms sample comprises 24.26 ms
running, 24.97 ms I/O wait, 0.43 ms unclassified uninterruptible sleep, and 2.71 ms
runnable delay. Its body eagerly obtains four ViewModels. Those facts make DI/native
library construction worth instrumenting; they do not identify a particular
constructor or prove all of the time is Compose recomposition. The nested drawer,
app, navigation and Compose durations must not be added together.

Prior lazy-screen and lazy-native experiments were reverted: they did not establish
a total startup improvement and started warmer than the control. The native deferral
experiment reduced measured composition time but did not prove a first-display win.
The grain-brush creation slice was only about 1.7 ms; it is a smaller priority than
the larger graphics setup and app initialization costs.

[Android's Macrobenchmark implementation](https://android.googlesource.com/platform/frameworks/support/+/d61daf26f130a5a5d64f79737e674fb051ecf580/benchmark/benchmark-macro/src/main/java/androidx/benchmark/macro/Macrobenchmark.kt)
clears shader caches for COLD startup. Therefore 118 ms of first-frame pipeline
compilation is a real cost in this scenario, but not necessarily paid on every
ordinary app opening. These are CPU-side graphics-driver slices, not measured GPU
kernel execution. Haze is a candidate for an A/B test; the trace does not attribute
all pipeline compilation to Haze.

### Scrolling: rare stalls, with an unreliable baseline

Across the ten measured windows, the traces contain **10,294 observed app frame
rows**, including three `App Deadline Missed` and two `Non Animating` rows. Their
expected app budget is about **16.60 ms**. The original device snapshot reports an
active 120 Hz display/render rate; the budget is not a refresh-period measurement.
This corrects the initial audit's inference of roughly 60 Hz. Across observed surface frames, main-thread
`doFrame` wall-time p95/p99 is **3.77/4.60 ms**, and RenderThread `DrawFrames`
p95/p99 is **6.22/7.20 ms**. These intervals can overlap and differ from
Macrobenchmark's `frameDurationCpuMs`; they are not additive frame CPU usage.

However, **8/10 scrolling files report `traced_buf_sequence_packet_loss`**, and four
also report dropped overlapping slice events. Only iterations 004 and 005 have no
reported error/loss stats: together they show 2,052 frame rows, one app deadline
miss and one non-animating row. The original suite also warmed from thermal NORMAL
to LIGHT. These observations do **not** establish a precise production jank rate.

The longest RenderThread stall is in clean iteration 004, frame token **157789406**:
`DrawFrames` takes **17.06 ms**, while the corresponding main `doFrame` takes
**2.84 ms**. Inside it, `dispatchSetFrameTimelineInfo` takes **12.68 ms**:
**12.49 ms asleep**, **0.105 ms running**, and **0.083 ms runnable**, with wakeups
from the app's Binder thread. This is a synchronization/wait issue worth following
through Binder/SurfaceFlinger; rewriting LazyColumn composition is not justified
by this frame. The exact remote lock/service cause remains unproven.

Other observations: about one `Compose:recompose` per displayed frame, averaging
0.091 ms per slice; `measureAndLayout` averages 0.317 ms; recorded draw work averages
0.211 ms. Layer flushing averages 0.877 ms and reaches 3.13 ms. Background GC appears,
but these traces do not establish a dominant main-thread GC-pause problem.
`GPU completion:waitForever` is a fence wait and must not be counted as CPU work.

Before optimizing scrolling, recapture with a suitable trace buffer/drain setup or
shorter measured windows and reject traces with data-loss stats. Then add fast
flings, active token streaming, markdown/code-heavy messages, download activity and
120-Hz scenarios. The current synthetic-history test uses slow swipes and does not
exercise those cases. See [Perfetto buffer/data-loss guidance](https://perfetto.dev/docs/concepts/buffers).

### Measurement gaps to fix next

1. **No full application load trace:** add correlated spans for preparation, manifest
   checks, request resolution, admission/preflight, root-lock wait, native model/context
   setup, system prompt, user prefill and first decoded token. Coroutine phases need
   async trace spans; synchronous sections must stay on one thread. Do not trace user text.
2. **Native attribution is missing:** all 146 readable captures contain zero CPU
   samples, heap-allocation profile rows and GPU execution slices. Add symbolized
   native CPU sampling; collect supported GPU events/counters and memory RSS/PSS
   alongside it. Zero rows mean absent data, not zero cost. The existing native trace
   uses CPU/context 2,048, not the full-app context-4,096 experiment.
3. **App-ready is not first-frame-ready:** startup currently measures initial display,
   and there is no `reportFullyDrawn`/`ReportDrawn` instrumentation. Measure usable
   chat readiness and selected-model readiness separately from the first frame.
4. **Fresh performance baselines are needed:** startup and scroll traces predate the
   minimal-verification change. Use current source, nondebuggable builds, a pinned
   model/configuration, matched temperature, and both cold and warm-cache workloads.
   Keep instrumentation overhead and debug JIT activity out of release claims.

Recommended order: improve capture quality and full-load observability; profile native
load and Vulkan first-token latency; eliminate confirmed redundant reloads; test
first-frame graphics and DEX initialization; then revisit scrolling under realistic load.
No estimated saving above should be treated as a guaranteed gain before an A/B run.

### Reproduce the trace queries

The SQL is saved in `analysis/audit-{quality,startup,scroll,model}.sql`. Choose the
matching scenario; startup and scrolling queries target the isolated benchmark app,
while the model query targets the normal app's diagnostic load markers.

```bash
build/tools/trace_processor query -f benchmarks/android/analysis/audit-quality.sql TRACE
build/tools/trace_processor query -f benchmarks/android/analysis/audit-startup.sql STARTUP_TRACE
build/tools/trace_processor query -f benchmarks/android/analysis/audit-scroll.sql SCROLL_TRACE
build/tools/trace_processor query -f benchmarks/android/analysis/audit-model.sql MODEL_TRACE
```

## Initial physical-device validation — October 23, 2026

Subsequent [trace audit](#perfetto-audit--october-23-2026) found packet loss in eight scrolling traces; treat the scrolling figures below as exploratory.

All three tests passed on Pixel 9 / Android 17 (API 37), with 10 measured iterations
each and 30 saved Perfetto traces. The [compact result record](results/pixel9-2026-10-23.json)
contains the APK hash, source revision/dirty state and metric summaries. Full data,
test logs and fixture sources remain in its referenced local archive under `build/`.

| Measurement | Result |
| --- | ---: |
| Cold startup, no compilation — median initial display | 658.6 ms |
| Cold startup, 3 warmup journeys then compilation — median initial display | 516.2 ms |
| Chat scrolling — CPU frame p50 / p95 / p99 | 6.53 / 8.88 / 10.21 ms |
| Chat scrolling — frame overrun p95 / p99 | 0.0004 / 0.75 ms |

**Exploratory results, not regression thresholds:** the device's thermal status
changed from NORMAL (0) to LIGHT (1) during the combined suite. Macrobenchmark
reported no thermal sleep, so its built-in behavior alone did not exclude this
warming. The runner now rejects a non-normal post-run thermal status. Use separate,
cooled startup and scrolling runs before drawing performance conclusions. The
original 30-second screen timeout was restored after validation.

## Startup readiness and cache pilot — October 24, 2026

The [three-condition result record](results/pixel9-startup-readiness-2026-10-24.json)
retains nine valid launches, the same APK/profile hashes, raw timings and bounded
Perfetto phase measurements. All used the packaged Baseline Profile, a 60 Hz
endpoint policy, R8 off, and thermal NORMAL at both endpoints (32.8–33.6°C).
These are sequential three-launch pilots with background activity, not regression
thresholds. Startup uses initialized empty data; fully drawn means persisted initial
theme/inventory, recovery and the rendered shell are ready. It excludes model load.

| Condition | Initial display median | Fully drawn median | Fully drawn range |
| --- | ---: | ---: | ---: |
| Process cold, caches preserved, effects on | 183.82 ms | 214.03 ms | 213.50–215.39 ms |
| Process cold, caches preserved, effects off | 174.80 ms | 211.02 ms | 205.54–327.64 ms |
| COLD cache clearing, effects on | 445.99 ms | 484.98 ms | 457.22–496.49 ms |

**Keep visual defaults.** Effects-off changes median fully drawn by only 3.01 ms
(1.4%). Its first measured launch has 27 pipeline compilation misses totaling
100.38 ms, a 132.00 ms first RenderThread draw and a matching 131.91 ms main-thread
sleep. The other two effects-off launches and all three cache-preserving effects-on
launches have no pipeline compilation misses. Preserving caches does not guarantee
resident pipelines; the outlier is retained, not discarded as background noise.
The settings flow initially emits the default effects-on theme, so this experiment
also includes the subsequent persisted-preference transition.

**Cold graphics and file access remain the larger startup costs.** All three COLD
launches compile 27 pipelines (124.86–131.52 ms). Their first draw has a 190.30 ms
median, versus 21.09 ms with caches preserved. Main-thread explicit I/O wait rises
from zero observed to a 52.07 ms median, and DEX-open slices rise from 9.81 to
24.58 ms. The 270.95 ms fully-drawn difference is cache sensitivity, not an app
optimization. These intervals overlap and must not be added together. Evaluate
individual cold rendering costs and DEX layout/size separately; this pilot does not
justify changing production R8 or broadly disabling effects.

With caches preserved and effects on, the additional first-display-to-ready gap
is 27.30–35.03 ms. It contains 9.09–9.80 ms main-thread CPU and 17.26–24.91 ms sleeping;
it is not evidence of another 30 ms verification block. Main-thread runnable time
through fully drawn is 5.77–7.97 ms. Play Store consumes 143.61 CPU-ms during the
first launch and none during the next two; concurrent CPU alone does not establish
causal blocking. Each result retains in-window process CPU and scheduling data.

The first pilot failed before measurement because the inventory State was read
only inside `SideEffect`, so changes did not invalidate composition. Reading its
Boolean during composition fixes reporting while preserving the actual readiness
conditions. The failed archive is explicitly excluded. Further native model and
scroll measurements remain separate from these empty-catalog startup results.

## Streaming row isolation pilot — October 24, 2026

The [before/after result record](results/pixel9-streaming-row-isolation-2026-10-24.json)
retains three ordinary timing iterations and one separate full-Compose diagnostic
per APK. Fixture v3 uses 200 messages, completed Markdown history, synthetic word
updates every 100 ms and a requested 4-second fling window. Effects stay on, R8
stays off, and the packaged Baseline Profile is unchanged. All eight traces pass
quality gates with no packet-loss/parser errors; thermal status is NORMAL at both
endpoints (ordinary before 33.2→33.6°C, after 33.4→33.8°C; diagnostics reach 34.0°C).
Active display modes at endpoints are 60 Hz, with separately recorded callback
and surface cadence. These updates do **not** run native inference concurrently.

| Ordinary run | Actual window | Updates | Frame CPU p95 | Overrun p95 | Overrun p99 |
| --- | ---: | ---: | ---: | ---: | ---: |
| Before 1 | 4.67 s | 45 | 10.27 ms | 0.125 ms | 2.840 ms |
| Before 2 | 4.70 s | 46 | 9.69 ms | 0.172 ms | 2.564 ms |
| Before 3 | 4.09 s | 40 | 9.93 ms | 0.352 ms | 1.516 ms |
| After 1 | 4.11 s | 40 | 10.21 ms | 0.244 ms | 3.986 ms |
| After 2 | 4.05 s | 39 | 10.14 ms | 0.175 ms | 5.105 ms |
| After 3 | 4.11 s | 40 | 10.09 ms | −0.103 ms | 1.135 ms |

Percentiles above come from each run's raw Macrobenchmark frame arrays; diagnostic
tracing timings are excluded. Update interval medians stay within 100.56–100.71 ms.
Actual windows vary because gestures and checks finish before the loop exits, so
scheduler CPU is normalized to the measured duration. Median main-thread CPU is
153.28→154.24 ms/s; median frame CPU p95 is 9.93→10.14 ms. **This pilot demonstrates
no frame-latency improvement.** The sequential sample of three runs per APK is too
small to assign the mixed p99 results to this change.

The separate diagnostic traces explain the retained change. Passing streaming
state only to the active row reduces `ChatMessageListItem` compositions from
172 to 26 (84.9%) with 39 updates in both traces. Its own CPU falls from 0.885 to
0.262 ms over approximately 4.08 seconds. `ChatMessageList` (41), streaming bubble
(20), message bubble (25), and Markdown (2) call counts stay unchanged. This is a
small removal of unnecessary wrapper work; child rendering already skipped
effectively. Inclusive composition times overlap and must not be added. No
`contentType`, animation, Markdown, or rendering changes are included.

The largest in-window frames occur during activation, 17–28 ms after measurement
starts, when the fixture replaces a completed answer with a streaming prefix and
changes composer controls. Macrobenchmark also includes some stop-transition
frames **after** `measureBlock`: restoring the completed rich answer contributes
7.70/11.20 ms overruns in the first two candidate runs and 6.17 ms in baseline run
two. These values remain in the table. Separate, fully contained frames after the
first word update have overrun p99 ranges of 0.86–1.49 ms before and 0.84–1.24 ms
after. They show no clear sustained-scroll regression, but are a diagnostic view,
not a replacement performance score. There are no measured-window pipeline
compilation misses. Future work should measure activation and sustained streaming
as distinct workloads, and add real inference contention before drawing conclusions
about model generation while scrolling.

### Candidate-only 120 Hz probe

Three additional ordinary timing runs use the row-isolation candidate in a
separately rebuilt APK; this is not an A/B experiment. All pass trace quality with
thermal NORMAL and 33.3→33.8°C endpoints. Updates remain sustained at approximately
100.5–100.6 ms intervals. The same result record includes the full per-run data.

| Run | Window / updates | Raw frames | Frame CPU p95 | Overrun p95 / p99 | Activation overrun |
| --- | ---: | ---: | ---: | ---: | ---: |
| 1 | 4.19 s / 41 | 336 | 9.38 ms | −1.889 / 0.501 ms | 16.82 ms |
| 2 | 4.31 s / 42 | 376 | 8.85 ms | −1.702 / 0.370 ms | 14.82 ms |
| 3 | 4.39 s / 43 | 376 | 8.78 ms | −1.481 / 0.041 ms | 17.96 ms |

The active display mode is 120 Hz at both endpoints. Callback interval medians are
8.34–8.36 ms, surface-frame interval medians are 8.339 ms, and observed VsyncPeriod
is 8.333 ms. Expected FrameTimeline duration remains 16.6 ms: it is the scheduled
rendering budget, **not** the display period. Negative overruns therefore do not
mean every frame completed within 8.33 ms, nor does median cadence establish
uninterrupted 120 fps. See [Perfetto's FrameTimeline semantics](https://perfetto.dev/docs/data-sources/frametimeline).

The largest in-window frames are activation, 15–18 ms after the window begins.
Fully contained frames after the first word update have overrun p99 of
−0.284/−0.114/−0.396 ms, but run two still exposes a latency problem: one missed
deadline at +624 ms is followed by 80 `Buffer Stuffing` frames over approximately
665 ms. Joining their surface tokens to SurfaceFlinger display tokens shows
frame-start-to-display medians of 26.15 ms immediately before, 34.70 ms during,
and 26.53 ms after this period. The queued range is 33.78–35.07 ms: roughly one
additional display interval. These are trace frame-to-display timings, not a
physical touch-to-photon latency measurement. Buffer stuffing can preserve cadence
while adding latency, so deadline percentiles alone miss this issue.

The initiating frame handles the last MOVE before the first swipe's UP. Its
`doFrame` takes 4.13 ms (3.75 ms CPU, 0.17 ms runnable); `DrawFrames` takes 5.60 ms
of RenderThread CPU. Frame completion occurs another 7.21 ms after `DrawFrames`
ends and is marked `Delayed Latch Fence Unsignaled`. There is no I/O wait or Google
process CPU during that frame, and the nearest synthetic text updates are 78 ms
before and 22 ms after it. Lazy prefetch composition overlaps rendering, but the
trace cannot establish that it caused the delay. The auto-scroll effect is not
keyed to token text; an auto-scroll restart is also unproven. No measured-window
pipeline compilation misses occur. A targeted rendering/GPU capture of this
swipe-to-fling transition is the next useful diagnostic; broadly changing effects
or prefetch is not justified by this single event.

## Where JetBrains' suite fits

[JetBrains' multiplatform suite](https://github.com/JetBrains/compose-multiplatform/blob/master/benchmarks/multiplatform/README.md)
is a standalone framework benchmark project with synthetic scenes and several
rendering modes. Keep it as a separate, commit-pinned experiment when investigating
Compose upgrades or platform rendering differences. Its Gradle commands belong to
that checkout; copying its entire build into CaraML would not measure CaraML's
launcher or actual Android journeys. Its `REAL` rendering mode and Android
Macrobenchmark answer related but different questions.

Continue using the existing [ONNX/GGUF suite](../onnx/README.md) for native inference
throughput, first-token latency, memory and thermals. Keep those results separate
from the UI baseline, then add a controlled streaming-plus-scroll journey later.

Sources: [Macrobenchmark setup](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-overview),
[metrics](https://developer.android.com/topic/performance/benchmarking/macrobenchmark-metrics),
[benchmark releases](https://developer.android.com/jetpack/androidx/releases/benchmark).
