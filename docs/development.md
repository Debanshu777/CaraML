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

Do not commit downloaded models, tokens, local SDK paths, APKs, native build outputs, or benchmark traces.

## Keep documentation current

Every change to code, tests, build configuration, dependencies, or developer tooling must include documentation updates in the same change set. Update the relevant pages in `docs/`, the root README and its `Recent Changes`, and the affected module READMEs. Revise explanations, examples, commands, defaults, support claims, and limitations to match the final implementation; a changelog bullet alone does not replace an outdated guide.

For internal refactors and test-only changes, describe the developer impact and verification here and in the affected module README. Keep unrelated module READMEs unchanged and replace stale `Recent Changes` entries with concise current ones. Record measured evidence for performance claims; use the version catalog as the dependency source of truth.

Coding agents follow the same requirement through [AGENTS.md](https://github.com/Debanshu777/CaraML/blob/main/AGENTS.md#documentation-and-readme-update-policy); `CLAUDE.md` imports that shared policy. Agents must review the final diff for documentation impact, make the edits before handing off, and report which docs and checks they updated. Documentation cannot be deferred to a later session or waived for a code change. The PR template records the same expectations for contributors.

Run the [strict docs build and generated-site checks](maintaining-docs.md#validate-before-publishing) after documentation edits, and review rendered pages after layout or media changes. These checks verify links and site metadata; matching the prose to code still requires reviewing the implementation.
