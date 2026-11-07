---
description: Build CaraML on Android, macOS, Linux, or iOS. Set up native engines, download a model, start local chat, and generate your first image with Stable Diffusion.
---

# Build and run CaraML

Start from source: CaraML does not currently publish ready-to-install releases or a standalone Maven SDK. Check the [license](licensing.md) before commercial use.

## 1. Get the source

```bash
git clone https://github.com/Debanshu777/CaraML.git
cd CaraML
git submodule update --init
```

Initialize the two **top-level** native submodules at their pinned commits. Recursive initialization is unnecessary; optional nested upstream dependencies are outside CaraML's build graph. Do not update the engines to their latest branches to fix a build.

## 2. Install the tools

| Tool | Requirement |
| --- | --- |
| Java | Full JDK 21 with JNI headers; set `JAVA_HOME` to the JDK |
| Android SDK | Platform **37**, used by the current Gradle configuration; target SDK remains 36, minimum device API 28 |
| Android NDK | **28.1.13356709** for the native engine |
| Android CMake | **3.31.1** |
| Desktop native tools | CMake 3.22.1 or newer and a C/C++ compiler; macOS needs Xcode command-line tools |
| iOS | macOS, full Xcode with an SDK supporting the configured iOS 17.2 deployment target; Apple Silicon simulator or arm64 device |

The Gradle wrapper supplies Gradle. [The version catalog](https://github.com/Debanshu777/CaraML/blob/main/gradle/libs.versions.toml) and [native build configuration](https://github.com/Debanshu777/CaraML/blob/main/nativeEngine/build.gradle.kts) are authoritative when versions change.

The full Gradle build configures Android modules even when running desktop. Set `ANDROID_HOME` or create an untracked `local.properties` containing your actual `sdk.dir`. The [portable SDK test path](development.md#test-without-native-engines) avoids app/native producer configuration.

Install the Android packages with Android Studio's SDK Manager, or with `sdkmanager` on your PATH:

```bash
sdkmanager "platforms;android-37" "platform-tools" "ndk;28.1.13356709" "cmake;3.31.1"
sdkmanager --licenses
```

## 3. Choose a platform

### Desktop: macOS or Linux

```bash
./gradlew :app:desktopApp:run
```

The `run` task builds the required native engines automatically. The first build downloads dependencies and compiles C++; subsequent runs reuse build outputs. macOS uses Metal; Linux's documented path uses CPU. Windows distribution tasks exist but still need release validation.

To build native code separately for diagnostics:

```bash
./gradlew :nativeEngine:compileLlamaRunnerDesktop
```

### Android

For a first build without Vulkan header setup:

```bash
./gradlew :app:androidApp:assembleDebug -PcaramlAndroidBackend=cpu
adb install -r app/androidApp/build/outputs/apk/debug/androidApp-debug.apk
```

Use a connected Android device with USB debugging enabled, or an emulator. Physical hardware is needed for meaningful inference performance measurements.

The default build enables Vulkan on arm64 and packages an x86_64 CPU fallback. Install Vulkan and SPIR-V headers first:

```bash
# macOS
brew install vulkan-headers spirv-headers

# Debian/Ubuntu
sudo apt-get install libvulkan-dev spirv-headers

# Build with the default backend
./gradlew :app:androidApp:assembleDebug
```

The shader compiler comes from the NDK. CPU remains a runtime option. Vulkan support and benefit depend on the model and device. See [native build details](https://github.com/Debanshu777/CaraML/blob/main/nativeEngine/README.md).

### iOS

Open `app/iosApp/iosApp.xcodeproj` in Xcode. Select the scheme for the `iosApp` target and an arm64 simulator or device. Scheme names can differ in an existing checkout; `xcodebuild -list -project app/iosApp/iosApp.xcodeproj` lists them. For a physical device, select your own signing team. The Xcode build phase invokes the root Gradle wrapper and embeds the `ComposeApp` framework.

For separate native build diagnostics:

```bash
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosArm64
./gradlew :nativeEngine:mergeLlamaRunnerStaticIosSimulatorArm64
```

Intel iOS simulators are not configured. The Kotlin framework and Xcode target use iOS 17.2 as the deployment minimum; use an Xcode SDK that can build it.

## 4. Have your first conversation

1. Open **Models** from the navigation menu.
2. Find a small **instruct** model in GGUF format. A small quantized model is a useful first smoke test; consult [model selection](models.md).
3. Inspect the exact files and size, then download. Wait until the model is available in your library.
4. Open **Chat**, select the downloaded model, and enter a short prompt such as `Give me three names for a robot gardener.`
5. Use **Stop** to cancel a response. After download, local inference does not need a cloud service.

Model file size is not the same as working memory. If loading is refused, start with a smaller model or context; the [troubleshooting guide](troubleshooting.md) explains common failures.

## 5. Generate an image

1. In **Models → Discover**, open the filters, select **Image**, and apply the filter.
2. Search for **SD-Turbo**. The **Green-Sky/SD-Turbo-GGUF** entry provides self-contained GGUF checkpoints for stable-diffusion.cpp.
3. Review device fit, scroll through the variants, and select the recommended assessed file. Wait for the download and verification to finish.
4. Open **Images** from the navigation menu. Once the model loads, enter a prompt such as `A tiny blue robot watering flowers in a sunny garden, clay art, soft lighting` and generate.

Image models need additional memory for sampling and image decoding. A downloaded file can still exceed the device's current memory budget; use the app's assessment and [model compatibility guide](models.md). The [demo](demo.md) shows a real Pixel capture, while the [benchmark overview](benchmarks.md) links to measured diffusion workloads.
