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

Launch with `./gradlew :app:desktopApp:run` so native compilation and library paths are configured. If you changed JNI packages, rebuild the Kotlin bridge and native code together. Report the exact missing library and platform rather than copying unrelated binaries into the app.

## A downloaded model will not load

Check the exact architecture, quantization, complete shard/component set, and [supported formats](models.md). A file extension or successful download is not proof of compatibility. ONNX bundles must contain matching graph, tokenizer, configuration, and external data.

If admission reports insufficient memory, try a smaller model or shorter context and stop other memory-intensive apps. Storage capacity and available working memory are different. Do not bypass admission checks to force a large model onto the device.

## Browsing or downloads fail while chat still works

Local inference and Hub access are separate. Check connectivity, gated-model access, token validity, and the repository's current revision. A fully downloaded supported model can run without Hub access.

## Report a reproducible issue

Use the [bug report form](https://github.com/Debanshu777/CaraML/issues/new?template=bug_report.yml). Include your commit, device/OS, build command, exact model/revision, expected behavior, actual behavior, and redacted logs. Never include access tokens or private prompts.
