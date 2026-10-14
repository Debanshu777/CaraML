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

- Debug performance validation supports read-only CPU/Vulkan selection with matching resolver/inference settings, records actual GPU layer counts and explicitly labels permitted start thermal states. Current five-model CPU/Vulkan benchmarks are published in the root README.

- Exercised the debug Android runtime matrix on Pixel 9 with SmolLM2, Gemma 3, Qwen 3, LFM2 and Qwen 2.5 through downloads, generation, cancellation and reload.

- The debug runtime cancellation check now signals native cancellation and cancels the collecting coroutine, matching the chat Stop path.

- Aligned app-owned transfers and diffusion installation helpers with app packages; generated resources now use `com.debanshu777.caraml.generated.resources`.

- Grouped app code under `app/` with conventional module names and separate platform launchers.
- Moved desktop execution, hot reload and distribution configuration to `desktopApp`; retained shared JVM implementations and regression tests in `composeApp`.
- Updated Xcode root-wrapper lookup, CI commands and repository-relative source checks.
