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
./gradlew :app:desktopApp:nativeRuntimeInstalledImageSmoke
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

- Download commands persist intent before waiting for iOS restoration outside the lifecycle lock; scheduling then rechecks that intent. Reopened details refresh local installation evidence while retaining metadata, files, and the prepared README. Removed unused DAO methods and test-only Model Hub layout indirection.

- Recent model history stores compact display snapshots, migrates existing visits, and records independently of model file loading. Models reuses the root settings ViewModel. Download controls look up the exact persisted batch, and incomplete cancellation cleanup stays visible with a retry action. Unused Model Hub toolbar, summary, context, sheet, and navigation wrappers were removed; UI coverage exercises current filters, device information, rows, and navigation.

- Desktop distributions carry the selected native runtime and use installed resource paths; `nativeRuntimeInstalledImageSmoke` verifies both inference bridges and artifact storage from relocated jars/resources. Shared app fixes preserve download upgrades, remove same-repository ONNX companions safely, and avoid routine payload hashing for count badges. Model-card normalization uses common Kotlin string editing so it compiles on iOS.

- Shared model monograms and author avatars expose a `size: Dp` parameter with a 36 dp default; see [component sizing](../docs/development.md#model-hub-migration-checks).

- Model details adds a full Model card tab with images, wrapping tables, and revision-aware relative links. Three swipeable text tabs retain the complete overview and pin below the top bar while scrolling. Switching pinned tabs opens the selected section at its beginning. The header shares search’s frosting and reveals the model name when the expanded title scrolls out of view. Technical facts remain grouped. Screen recreation reuses active requests and loaded data, and tab/scroll state survives changes between compact, rail, and sidebar navigation; the ViewModel prepares Markdown once per fetched document for reuse across tab changes. README retry remains independent of downloads. See the [model details guide](../docs/models.md#model-details).

- Dark mode now uses progressively lighter raised surfaces, subtle shared borders, dark button shadows, and a quieter background wash across the app. Light-mode colours are unchanged.

- Models now separates subdued Recently viewed cards from an explicit Explore models or Search results heading, with tighter search spacing and consistent, lightly raised icon buttons. Icon buttons retain a thin 1dp contour and 2dp depth while sharing text buttons’ ink shadow. Both sizes use the shared subtle rim in dark mode.

- Models opens directly to search. The separate Downloads page is available from the Models app bar and Settings, and opens installed models in their matching workspace.
- Unified Model search and Downloads rendering in `ModelRow`, with pure presentation-state mappers and shared selection, repair, recommendation and avatar behavior.

- Centered model-row avatars and selection icons vertically beside the row content.

- Model row dividers start at the title column, after the avatar, and extend through the list’s trailing gutter to the content-pane edge.

- Model rows show compatibility as a tinted icon-and-text tag alongside metrics, wrapping as needed. Details share the same green/amber/red/neutral verdict styling.

- Model rows use accessible status icons and task tags; details retain matching icons with explanatory text.

- Added offline `ModelHubCard` previews for light/dark themes and narrow screens at 200% text size, including metrics, repair and selection states.

- Model search and Downloads share one compact row layout with icon metrics, secondary author/filename text and subtle dividers. Tap a supported row to open it; Downloads retains selection, readiness and “Finish setup” actions without a redundant “Use model” button.

- Language-model discovery now uses server page/count metadata and author avatars, preserving query cancellation, append retries and bounded recommendation assessment.

- Downloaded models open the matching workspace. Chat reconciles removed selections, Model Hub gives the newest detail request ownership, and settings edits merge against persisted values. Cancelled media writes free their cache space, and failed file-list or download commands can be retried.
- Fixed ONNX resident-session fixtures to use the production owner/model directory layout across desktop hosts; JVM failures now print full assertions.
- Added SD-Turbo GGUF discovery, fixed nested-checkpoint installation, and kept image/video models visible alongside frequently used chat models.
- Added [platform setup](../docs/getting-started.md), [contribution guidance](../CONTRIBUTING.md), and a [real-device demo](../docs/demo.md).
- Added Android Vulkan diffusion instrumentation and five-model measurements; see [results and limits](../benchmarks/diffusion/README.md).
- Added Android/JVM/iOS ONNX text support, resident-session reuse, and [device comparisons](../benchmarks/onnx/README.md).
- Added startup, scrolling, model-load, and reselection benchmarks; see the [Android performance report](../benchmarks/android/README.md).
