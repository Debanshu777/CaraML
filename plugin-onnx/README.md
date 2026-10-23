# CaraML ONNX text runtime

`plugin-onnx` adds ONNX Runtime 1.31.0 on Android/JVM and the pinned official
1.30.0 Apple runtime on iOS arm64 and arm64 simulator. The app routes installed
`.onnx` bundles here and `.gguf` files to llama.cpp, keeping one text engine resident.

## Supported bundles

The initial scope is single-decoder causal text models with Hugging Face byte-level
BPE tokenizers, ChatML or text-only Llama 3 prompts, float32 logits, and rank-four
float32 KV caches. SmolLM2, Qwen2.5, Qwen3 and Llama 3.2 exports were exercised on
a Pixel 9. See the [five-model comparison](../benchmarks/onnx/README.md).

Discovery accepts `model.onnx`, `model_int8.onnx`, `model_uint8.onnx`,
`model_quantized.onnx`, `model_q4.onnx`, `decoder_model_merged.onnx`, and
`decoder_model_merged_quantized.onnx` at the repository root or under `onnx/`.
Each variant includes root `config.json` and `tokenizer.json`, optional tokenizer
and generation configuration, and matching `.data` / `_data` / `_data_N` files.
Every file is pinned to the same immutable Hub revision and hash-verified before
atomic publication. Graph external-data references are checked before ORT opens
them; arbitrary paths and unrelated files are rejected.

FP16 caches/logits, SentencePiece tokenizers, arbitrary Jinja templates, separate
encoder/decoder pipelines, vision, audio and diffusion ONNX graphs are outside
this implementation. A file's `.onnx` extension alone does not establish support.

## Execution and memory

`createOnnxTextRuntime()` returns an owned, unloadable runtime. Supply a graph path
within its complete local bundle, then stream `generate(messages, maxNewTokens)`.
Generation rebuilds the prompt cache for each conversation, prefills in chunks of
128 tokens, and reuses native KV tensors during decode. Cancellation terminates
the active ORT run; unload releases session and cache ownership.

The app defaults to one CPU thread. Measured graph-specific choices use two
threads where helpful on Pixel 9 (ORT 1.31.0) and iPhone 13 (ORT 1.30.0); the
[report](../benchmarks/onnx/README.md) records the selected configurations. This
tuning is scoped to verified graph digests and device, not assumed for other hardware. The low-level
configuration exposes CPU, XNNPACK, NNAPI, Core ML, WebGPU and QNN requests for
experiments. AUTO uses CPU on Java platforms; Apple attempts Core ML only for
graphs without past-cache inputs. The application explicitly selects CPU for the
validated dynamic-cache exports.
NNAPI initialization failure falls back to CPU with an explicit runtime diagnostic.
Provider registration does not prove node offload: on the tested SmolLM2 INT8
graph, both NNAPI fallback and XNNPACK profiles contained only CPU nodes. There is
no inference of GPU/NPU use from provider registration. GGUF retains Vulkan on
Android and Metal on Apple. The [benchmark report](../benchmarks/onnx/README.md)
records actual placement and measured provider results.

Apple Core ML cannot consume zero-element initial past tensors. Cache-bearing
graphs fall back to CPU with a diagnostic before execution. Padding past tensors
was experimentally rejected because it changes quantized output. The Apple
bridge retains native cache ownership and uses the same tokenizer/chat formatting
as Android/JVM. Its official runtime archive is hash-pinned by `prepare_apple.py`.

The optional Android arm64 WebGPU plugin is built with `-PonnxWebGpu=true`.
Its Android hardware discovery finds the Pixel Mali-G715 and profiles confirm
GPU kernels, but tested INT8/Q4 exports fail on GPU-to-host transfer before the
first token. It remains experimental and is absent from the ordinary APK.
Profiling enables full WebGPU validation; the application does not enable this provider. QNN requests require a separately
built QNN-enabled runtime, Qualcomm vendor libraries and compatible static-shape
graphs. Snapdragon ONNX acceleration remains untested. The connected iPhone
runs these ONNX cache graphs on CPU; its separately tested GGUF path uses Metal.

ONNX uses F32 KV accounting and a conservative host-memory estimate; llama.cpp
speed estimates and recovery identities are not reused. The existing admission,
risk acknowledgement, integrity and crash-recovery checks still apply. The exact
Llama 3.2 INT8 artifact that returned incorrect answers on Pixel 9 is excluded on
that device; use its tested Q4 export. This workaround does not fix the underlying
runtime/export incompatibility.
The large Llama Q4 ONNX bundle also failed to load on the 4 GB iPhone 13 and is
excluded there; its GGUF counterpart works. Disabling CPU arena allocation and
weight prepacking did not resolve that load failure. `lowMemory` exposes this
allocation tradeoff for explicit SDK experiments, without making it an app default.

## Verification

```bash
./gradlew :plugin-onnx:jvmTest
./gradlew verifyProject
# Optional real-model tokenizer/template and greedy-token reference checks:
./gradlew :plugin-onnx:jvmTest -PonnxFixtureDir="$PWD/build/benchmarks"
```

The default JVM suite uses a tiny checked-in generated ONNX graph and covers
prefill/decode, cancellation, reload, context bounds, UTF-8 streaming, malformed
graphs and external-data confinement. Optional reference tests require separately
downloaded models and reference JSON; default tests do not download model weights.
The benchmark README documents Android instrumentation and reproducible downloads.

## Recent Changes

- Added Android/JVM/iOS ONNX text inference, bounded prefill, native KV reuse and explicit provider diagnostics.
- Added pinned Apple runtime packaging, simulator lifecycle/reference checks and optional Android WebGPU builds; incompatible Core ML cache graphs explicitly use CPU.
- Added real tokenizer/template parity checks and device-tested bundle admission, chat, cancellation and reload.
