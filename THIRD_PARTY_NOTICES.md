# Third-party licenses and notices

CaraML's root license covers its first-party code. It does not relicense dependencies, vendored source, fonts, icons, model weights, or third-party contributions for which the maintainer lacks those rights.

This is a navigation guide to notable notices, not a complete binary-distribution license inventory. Preserve applicable notices and review the dependencies actually packaged before distributing a build.

| Component | Notice or source |
| --- | --- |
| llama.cpp | [Pinned submodule license](libraries/llama.cpp/LICENSE) |
| stable-diffusion.cpp | [Pinned submodule license](libraries/stable-diffusion.cpp/LICENSE) |
| ONNX Runtime | [Upstream license](https://github.com/microsoft/onnxruntime/blob/main/LICENSE); runtime versions are documented in [plugin-onnx](plugin-onnx/README.md) |
| Lucide icons | [Bundled license](app/composeApp/src/commonMain/composeResources/files/licenses/lucide-license.txt) |
| Bricolage Grotesque | [Bundled OFL](app/composeApp/src/commonMain/composeResources/files/fonts/bricolage_grotesque_OFL.txt) |
| DM Sans | [Bundled OFL](app/composeApp/src/commonMain/composeResources/files/fonts/dm_sans_OFL.txt) |
| Paper Shaders 0.0.82 (artwork tooling only) | [Apache-2.0 license](https://github.com/paper-design/shaders/blob/main/LICENSE) and [notice](https://github.com/paper-design/shaders/blob/main/NOTICE); pinned in [artwork tooling](tools/docs/artwork/package-lock.json) |
| Other Kotlin/JVM/native dependencies | [Version catalog](gradle/libs.versions.toml), module build files, and each dependency's notices |
| Downloaded model weights | The selected model repository's license and model card; not covered by CaraML's root license |

The documentation uses MkDocs and Material for MkDocs under their own licenses. Paper Shaders renders the original blue-flower cover during artwork creation; published docs include the static result, not its runtime code. The docs wordmark also bundles Bricolage Grotesque under the OFL linked above. The demo's captured app UI uses CaraML's bundled resources. No third-party music or stock footage is included.
