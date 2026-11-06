---
description: Contribute to CaraML with portable SDK tests, JVM verification, Android and iOS checks, package conventions, and focused pull requests.
---

# Contributing and testing

Documentation, reproducible bug reports, accessibility improvements, and focused fixes are welcome. Read [CONTRIBUTING.md](https://github.com/Debanshu777/CaraML/blob/main/CONTRIBUTING.md) and the [license](licensing.md) before contributing.

## Test without native engines

For portable SDK changes, start with a clone without native submodules:

```bash
./gradlew -PsdkOnly=true verifySdk
```

This configures `core-api`, `core`, `storage`, `model-discovery`, and `native-runtime`; it runs their JVM tests, common metadata compilation, and dependency boundary checks. JDK 21 and dependency downloads are still needed. It does not test inference plugins or the app.

## Verify the application

After the [full setup](getting-started.md):

```bash
./gradlew verifyProject
```

This is the preferred local and CI JVM gate. It includes portable SDK verification, plugin/app JVM tests, desktop launcher compilation, native pin checks, and selected native tests. It is not a substitute for Android assembly, iOS compilation, packaged-app checks, or real-model runs.

| Change | Additional relevant check |
| --- | --- |
| Android UI or packaging | `./gradlew :app:androidApp:assembleDebug` and a device/emulator journey |
| iOS bridge or shared Kotlin | `./gradlew :app:composeApp:compileKotlinIosArm64 :app:composeApp:compileKotlinIosSimulatorArm64` on macOS |
| Desktop packaging | `./gradlew :app:desktopApp:artifactFsInstalledImageSmoke` |
| Native engine fixtures | `CARAML_NATIVE_PARITY=true ./gradlew :plugin-llama:jvmTest :plugin-diffusion:jvmTest` |
| Docs or public assets | `python -m mkdocs build --strict` and `python tools/docs/check_site.py` after [docs setup](maintaining-docs.md) |

Use the narrowest meaningful checks while iterating, then the relevant project gate before a code PR. Native parity checks may download model fixtures. Avoid starting with `allTests`: it includes platform tasks with additional host requirements.

## Find the right code

- Shared app features: `app/composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/`.
- App DI, navigation, settings, and downloads: the sibling `core/` package.
- Portable contracts and orchestration: root `core-api/` and `core/` modules.
- Model discovery: `model-discovery/`; provider-specific Hub code lives under `sdk.discovery.huggingface`.
- Inference: `plugin-llama/`, `plugin-onnx/`, and `plugin-diffusion/`.
- Native builds and pinned upstream source preparation: `nativeEngine/`.

See the [architecture](architecture.md) for dependency boundaries. Preserve persisted identifiers and keep JNI symbols, cinterop definitions, and consumer keep rules synchronized when bridge packages move.

## Keep changes reviewable

Describe the user-visible problem, final behavior, and checks actually run. Add screenshots or a short recording for UI changes. Report device, OS, model revision/quantization, backend, and workload when making performance claims.

Do not commit downloaded models, tokens, local SDK paths, APKs, native build outputs, or benchmark traces. Update only the affected module READMEs, and replace stale `Recent Changes` entries with concise current ones.
