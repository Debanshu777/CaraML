---
description: Choose GGUF language models, supported ONNX bundles, or diffusion models for CaraML. Understand memory requirements, gated Hugging Face models, and acceleration limits.
---

# Choose a model

CaraML discovers models through Hugging Face Hub. Being visible in search does not guarantee that a model's architecture, files, memory needs, or license are suitable for your device.

| Workload | Engine | Start with |
| --- | --- | --- |
| Text chat | llama.cpp | A small instruct model in GGUF format |
| ONNX text chat | ONNX Runtime | A complete supported decoder bundle, not an isolated `.onnx` file |
| Text to image | stable-diffusion.cpp | A model and component set listed in the diffusion report |

## GGUF language models

For an initial smoke test, the project has exercised small models from the SmolLM2, Qwen2.5, Qwen3, Gemma 3, and LFM2 families. Exact checkpoints, quantizations, and results are in the [benchmark reports](benchmarks.md). A different export of the same family can behave differently.

Choose an **instruct/chat** variant and check its model card. Projector, adapter, and auxiliary GGUF files are not standalone chat models. Download all shards when the selected model is split.

Smaller quantizations usually reduce weight storage, but quality and runtime memory vary. Context length, KV cache, batch size, and native working buffers also consume memory. CaraML's admission checks can reject a model that fits on disk but does not fit the current memory budget.

## ONNX bundles

Supported bundles include graph, configuration, tokenizer, and any external tensor data at a single Hub revision. The current implementation targets a specific set of causal text decoder layouts, float32 logits and KV caches, and supported byte-level BPE tokenizers.

FP16 caches/logits, SentencePiece tokenizers, arbitrary chat templates, vision, audio, and ONNX diffusion graphs are outside this implementation. Read [the ONNX compatibility contract](https://github.com/Debanshu777/CaraML/blob/main/plugin-onnx/README.md) before selecting an export.

The app uses CPU for the validated dynamic-cache exports. Provider availability alone does not establish GPU or NPU execution. WebGPU and other provider experiments are documented separately and are not default app features.

## Diffusion models

Some models need additional text encoders or a VAE. Use the app's model-specific install plan; downloading a single weight file may be insufficient. Start from the [five-model Android diffusion report](https://github.com/Debanshu777/CaraML/blob/main/benchmarks/diffusion/README.md), which records image size, steps, backend, outputs, and limitations.

Image generation can take considerably longer and use more memory than a short chat response. SDK diffusion support is narrower than the app's install pipeline: see [SDK limits](architecture.md#current-sdk-limits). The presence of a Videos navigation destination does not establish a validated video-generation workflow.

## Network access and model licenses

Public model browsing and downloads use the network. Gated repositories may require a Hugging Face token and prior acceptance of their access terms. Never paste tokens into bug reports or recordings.

Model weights retain their own licenses and usage restrictions. CaraML's license does not grant rights to third-party models, and a model's license does not grant commercial rights to CaraML. Review both before use.
