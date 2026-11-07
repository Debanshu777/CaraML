# Contributing to CaraML

Thanks for helping improve local AI on Kotlin Multiplatform. Useful contributions include clear bug reports, documentation, accessibility fixes, device compatibility findings, and focused code changes.

## Before you start

- Read the [setup guide](docs/getting-started.md) and [architecture](docs/architecture.md).
- Check existing [issues](https://github.com/Debanshu777/CaraML/issues) before starting a large change.
- CaraML is **source-available under PolyForm Noncommercial 1.0.0**, not an OSI-approved open-source license. Read [LICENSE](LICENSE) and [commercial-use guidance](docs/licensing.md).
- Submit only code you have the right to contribute. Contributions are offered under the repository license and retain their authorship. Separate commercial licensing of contributed code requires the applicable rights; submitting a PR does not assign copyright.

## Set up and test

```bash
git clone https://github.com/Debanshu777/CaraML.git
cd CaraML
git submodule update --init
./gradlew verifyProject
```

Use JDK 21 and the native/Android tools in [Build and run](docs/getting-started.md). For portable SDK changes without native engines:

```bash
./gradlew -PsdkOnly=true verifySdk
```

The [testing guide](docs/development.md) explains what these gates cover and when Android, iOS, or device checks are also needed. For documentation-only changes, use the [docs build and link checks](docs/maintaining-docs.md).

## Open a pull request

1. Create a focused branch from `main`.
2. Explain the problem and resulting behavior; link an existing issue when applicable.
3. Include the checks you actually ran and any relevant limitations. Add screenshots or a short recording for UI changes.
4. Update affected docs and concise `Recent Changes` entries. Keep dependency versions in the catalog as the source of truth.

Follow existing Kotlin and Compose conventions. First-party packages use `com.debanshu777.caraml`; reusable SDK packages stay under `sdk.*`. Do not introduce app/framework dependencies into portable API/core modules. Preserve native ownership, durable-download invariants, persisted IDs, and JNI/cinterop consistency.

Never commit model weights, private prompts, tokens, signing credentials, local SDK paths, APKs, traces, or generated native binaries. Use small test fixtures and public sample prompts.

## Report bugs and request features

Use the [issue forms](https://github.com/Debanshu777/CaraML/issues/new/choose). For inference failures, include model repository, immutable revision if known, exact files/quantization, device/OS, selected backend, reproduction steps, and redacted logs. A performance report needs its workload and measurement conditions.

Be respectful and specific. Discuss the work, avoid personal attacks, and do not post anyone's private information.
