---
description: Fix common CaraML setup and inference problems: missing JDK or Android SDK, native libraries, Vulkan headers, model compatibility, and memory admission.
---

# Troubleshooting

## Gradle cannot find Java, the Android SDK, or a toolchain

Run `java -version` and `./gradlew --version`. Use a full JDK 21, not a JRE, and point `JAVA_HOME` to it. Gradle's daemon criteria may download its configured JetBrains JDK. Dependency and toolchain downloads need network access on the first run.

Set `ANDROID_HOME` or put `sdk.dir=/your/actual/sdk/path` in untracked `local.properties`. Install the versions in [Build and run](getting-started.md). An Android configuration error can appear during a desktop build because the full project includes Android modules.

## Native source files are missing

From the repository root, run:

```bash
git submodule update --init
git submodule status
```

Keep the pinned upstream commits. CaraML applies patches to a build-owned copy; editing the checked-in submodules is not the normal patch workflow.

## Vulkan headers or shader compiler are missing

Install the pinned NDK and Vulkan/SPIR-V headers from the setup guide. For a CPU build, use:

```bash
./gradlew :app:androidApp:assembleDebug -PcaramlAndroidBackend=cpu
```

The optional ONNX WebGPU build has separate requirements; leave it disabled for the standard app.

## Desktop reports an unsatisfied native link

Launch with `./gradlew :app:desktopApp:run` so native compilation and library paths are configured. If you changed JNI packages, rebuild the Kotlin bridge and native code together. For a packaged desktop app, run `./gradlew :app:desktopApp:nativeRuntimeInstalledImageSmoke` and check that its resources contain the selected runtime libraries. The optional `caraml.native.lib.dir` SDK override is authoritative; an invalid override fails instead of loading a different installed library. Report the exact missing library and platform rather than copying unrelated binaries into the app.

## Windows native runtime crashes in msvcp140.dll

An installer can build successfully while the installed-image smoke crashes during native initialization. Newer MSVC headers can construct mutexes that an older `msvcp140.dll`, already loaded by the JVM, cannot lock. Microsoft documents this [mutex compatibility issue and its compatibility definition](https://github.com/microsoft/STL/issues/4730). Desktop MSVC builds define `_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR` before adding the native engines and their dependencies so mutex initialization uses the loaded runtime.

Rebuild with `./gradlew :app:desktopApp:nativeRuntimeInstalledImageSmoke`. Its phase messages identify the engine operation in progress. Fatal JVM reports are written under `app/desktopApp/build/verification/relocated-native-runtime/hs_err_pid*.log`; desktop CI prints them in the **Report native JVM crashes** step after a failure. Inspect the native stack and loaded DLL paths before attributing another access violation to the same cause. This workaround addresses mutex initialization; it does not establish general compatibility with arbitrary older C++ runtimes or validate real-model inference.

## A downloaded model will not load

Check the exact architecture, quantization, complete shard/component set, and [supported formats](models.md). A file extension or successful download is not proof of compatibility. ONNX bundles must contain matching graph, tokenizer, configuration, and external data.

If admission reports insufficient memory, try a smaller model or shorter context and stop other memory-intensive apps. Storage capacity and available working memory are different. Do not bypass admission checks to force a large model onto the device.

## Browsing or downloads fail while chat still works

Local inference and Hub access are separate. Check connectivity, gated-model access, token validity, and the repository's current revision. A fully downloaded supported model can run without Hub access.

## Report a reproducible issue

Use the [bug report form](https://github.com/Debanshu777/CaraML/issues/new?template=bug_report.yml). Include your commit, device/OS, build command, exact model/revision, expected behavior, actual behavior, and redacted logs. Never include access tokens or private prompts.

## Model Hub avatars or catalog pages fail

An author initial is the normal fallback when an avatar is missing, loading or cannot be fetched. Avatar hosts can differ from the Hugging Face API host, so successful model search does not guarantee image access. SVG support is supplied by the shared Coil dependency.

If an appended catalog page fails, retry that page. Changing search or filters, or refreshing, starts at page zero. The client rejects inconsistent page metadata and page-size changes within a continuation; a fresh query can recover after the upstream catalog changes. Server match counts can exceed the 256 repositories loaded in one query and do not indicate how many models have been assessed for your device.

## Native submodule HTTPS allowlist

If `verifyProject` reports that a submodule origin is not the allowlisted public HTTPS remote, compare `git config --get remote.origin.url` with `git remote get-url origin` inside that submodule. A personal `url.*.insteadOf` rule can rewrite a correctly stored HTTPS URL to SSH. Inspect its source with `git config --show-origin --get-regexp 'url\..*\.insteadof'`.

For this checkout, an exact repository-specific exception takes precedence over a broader GitHub rewrite without changing your global preferences:

```bash
git -C libraries/llama.cpp config --local url.https://github.com/ggerganov/llama.cpp.git.insteadOf https://github.com/ggerganov/llama.cpp.git
git -C libraries/stable-diffusion.cpp config --local url.https://github.com/leejet/stable-diffusion.cpp.git.insteadOf https://github.com/leejet/stable-diffusion.cpp.git
./gradlew verifyProject
```

These are local Git configuration changes, not tracked source changes. The pin checker still validates the original allowlisted HTTPS remotes, commits and clean submodule trees.

## A model card is missing or an image does not load

README loading is separate from model files. Open Model card and use Retry after a transient failure. Private/gated cards can require access on Hugging Face, and documents over 256 KiB are not rendered in the app. The linked model title still opens the publisher page. Images need network access and valid HTTP(S) sources; unsupported HTML widgets or embedded media may require the publisher page. Relative README links and images use the model’s pinned revision, which may differ from the repository’s current main branch.

## Legacy download queue after an upgrade

Download database versions 1 and 2 migrate without deleting task records or byte checkpoints. Mutable older tasks with unscoped destinations or missing evidence become terminal secure-path failures; start a new download to obtain current immutable metadata. Records that fail current identity validation are retained but excluded from the active queue. Completed historical rows stay stored with their original status. An unsupported database version fails to open instead of resetting the queue. Preserve the database when reporting an upgrade failure.

## JVM tests with a non-default native profile

App JVM tests obtain their native library directory from `:nativeEngine`’s `desktopArtifactFs` output. Keep `caramlNativeEngines` and `caramlDesktopBackend` on the same Gradle invocation as the test task; do not copy a default-profile library into a profile directory. The producer verifies the resolved library before tests start.
