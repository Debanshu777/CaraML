# CaraML application

All CaraML application code lives here. Reusable SDK modules and the native build
producer remain at the repository root.

```text
app/
├── composeApp/   Shared Compose UI, app logic and platform implementations (KMP)
├── androidApp/   Android launcher and APK packaging
├── desktopApp/   JVM launcher, hot reload and desktop distributions
└── iosApp/       Swift/Xcode host for the ComposeApp framework
```

This follows the [recommended Compose Multiplatform structure](https://kotlinlang.org/docs/multiplatform/multiplatform-project-recommended-structure.html):
platform launchers consume shared code. All targets share UI and app logic, so
`composeApp` holds both. Its `androidMain`, `iosMain` and `jvmMain` source sets
retain the platform implementations required by common `expect` declarations.
There is no additional `sharedLogic`/`sharedUI` split.

Gradle paths are `:app:composeApp`, `:app:androidApp` and `:app:desktopApp`.
`:app` is only a grouping project; it has no build script or runtime artifact.
`iosApp` remains an Xcode project. The Android launcher stays separate because
AGP 9 requires the Android application and KMP library plugins in different modules.

Run these commands from the repository root:

```bash
./gradlew :app:androidApp:assembleDebug
./gradlew :app:desktopApp:run
./gradlew :app:desktopApp:packageDistributionForCurrentOS
./gradlew :app:desktopApp:artifactFsInstalledImageSmoke
./gradlew :app:composeApp:jvmTest
./gradlew verifyProject
```

For iOS, open `app/iosApp/iosApp.xcodeproj`. Its build phase runs the root Gradle
wrapper and `:app:composeApp:embedAndSignAppleFrameworkForXcode`. The framework name
remains `ComposeApp`, so Swift imports do not change.

The SVG icon generator is `app/composeApp/tools/generate_app_icons.py` and works
from any working directory. Application IDs and persisted identities are
unchanged. Package conventions are documented in [the root README](../README.md#package-conventions).

## Recent Changes

- Added installed-bundle ONNX performance instrumentation with exact artifact/runtime checks, process trials, separate Perfetto captures, resident-session reuse controls and optional memory sampling. [Pixel 9 results](../benchmarks/onnx/README.md#pixel-9-app-performance-pass--october-24-2026) distinguish model-load gains from generation and benchmark overhead.

- Startup benchmarks now wait for persisted inventory, preferences and recovery readiness. Clean cache-controlled runs distinguish first display from usable UI; synthetic streaming captures also separate activation, sustained scrolling and completion transitions. [Results](../benchmarks/android/README.md).

- Added actual model-picker reselection measurements with temporary reuse on/off controls in the same APK. Pixel 9 median action-to-completion fell from 5.38 s to 177 ms across three trials per mode; these warm-device results measure logic completion, not a drawn frame. [Results and caveats](../benchmarks/android/results/pixel9-resident-reselection-2026-10-23.json).

- Added correlated load-to-first-token instrumentation and fixed-workload Android journeys with explicit requested/resolved/runtime configuration, thermal checks and preference preservation. Exact workloads enter normal assessment/admission; the production planner remains adaptive.

- Installed-model loads now use publication-time checksums and lightweight path/size checks. Qwen 3B CPU prepare-to-ready improved from 20.32 s to 3.29 s on Pixel 9; the debug harness also supports a short `phase smoke` generation check. See [results and limits](../benchmarks/android/README.md#installed-model-load-minimal-verification).

- Added a pinned Qwen2.5 3B download/load case and reproducible CPU/Vulkan trials with separate initialization, model-load and first-token timing. The verified model is available in the Pixel's app catalog; results and trace commands are in the [Android benchmark guide](../benchmarks/android/README.md#detailed-composition-and-model-load-measurements).

- Added an isolated Android [startup/scrolling suite and trace analysis](../benchmarks/android/README.md), a generated launcher Baseline Profile packaged in release, and validated profile comparisons on Pixel 9 with thermal and result-completeness checks.

- Added Android/JVM/iOS ONNX inference and format routing alongside GGUF. Debug instrumentation exercises real Hugging Face bundles, chat, cancellation and reload; [comparison and reproduction](../benchmarks/onnx/README.md).

- Physical Pixel 9 and iPhone 13 benchmarks record runtime selection, throughput, first-token latency, memory and thermal state. Android measurements require continuous foreground verification; iPhone runs require a clean process exit.

- Exercised the debug Android runtime matrix on Pixel 9 with SmolLM2, Gemma 3, Qwen 3, LFM2 and Qwen 2.5 through downloads, generation, cancellation and reload.

- The debug runtime cancellation check now signals native cancellation and cancels the collecting coroutine, matching the chat Stop path.

- Aligned app-owned transfers and diffusion installation helpers with app packages; generated resources now use `com.debanshu777.caraml.generated.resources`.

- Grouped app code under `app/` with conventional module names and separate platform launchers.
- Moved desktop execution, hot reload and distribution configuration to `desktopApp`; retained shared JVM implementations and regression tests in `composeApp`.
- Updated Xcode root-wrapper lookup, CI commands and repository-relative source checks.
