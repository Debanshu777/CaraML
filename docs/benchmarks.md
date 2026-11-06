---
description: Reproducible on-device AI benchmarks for CaraML, including Pixel 9 CPU and Vulkan results, ONNX comparisons, workloads, and limitations.
---

# On-device AI benchmarks


Start with the [performance improvement report](https://github.com/Debanshu777/CaraML/blob/main/benchmarks/README.md) for the steps taken, measured gains, current bottlenecks, validation status and cleanup record across startup, scrolling, GGUF and ONNX. The dated comparisons below retain their original workloads and are not interchangeable with the latest measurements.

The [October 23 ONNX/GGUF comparison](https://github.com/Debanshu777/CaraML/blob/main/benchmarks/onnx/README.md) covers five
matching checkpoints on Pixel 9, provider placement, thread tuning, memory and
the Llama INT8 compatibility workaround. ONNX dynamic-cache exports use CPU by default; accelerator experiments and
iPhone measurements and iOS simulator regression checks are reported separately. The earlier GGUF-only baseline below used different
quantizations and a longer generation workload, so its numbers are not directly
comparable to that report.

Measured on a **Google Pixel 9 running Android 17**, on **October 15, 2026**.
All inference runs locally through llama.cpp. Native placement logs verified
Vulkan GPU offload for every model below.

| Model | Quantization | CPU (tokens/sec) | Vulkan (tokens/sec) | GPU layers |
|-------|--------------|----------------:|-------------------:|-----------:|
| SmolLM2 135M Instruct | Q4_K_M | 62.50–64.57 | **73.18–73.40** | 31 |
| Gemma 3 270M IT | Q4_K_M | 41.64–42.65 | **42.96–43.20** | 19 |
| Qwen3 0.6B | Q8_0 | 18.91–26.31 | **22.74–22.81** | 29 |
| LFM2 350M | Q4_K_M | 44.68–70.04 | **54.84–57.98** | 17 |
| Qwen2.5 0.5B Instruct | Q4_K_M | 24.57–28.59 | **35.99–36.33** | 25 |

These are sustained **decode** rates, excluding prompt processing and the first
token, shown as the range across two passing runs per backend. Each run generated
at least 512 tokens using matching prompts and settings: greedy sampling,
4,096-token context, 4 generation / 4 batch threads, batch 256, microbatch 64,
and F16 KV cache. The app ran in the foreground with no active downloads;
runs started at nominal or mild thermal status. The debug APK used release-built
native code.

All five models passed load, generation, cancellation, unload, reload, and recovery
checks on the final installed build. GPU acceleration helps some models more than
others; thermal state and OS caching affect the results. These measurements are
an earlier project baseline, rather than a maximum-throughput claim or a measure
of answer quality. They cover the PR #17 working tree with local fixes and existing
edits; they are not a clean release-build certification.

Earlier failed or inactive-GPU trials are excluded from these ranges. Remaining
observations include generic native warnings, a three-model chat picker limit,
and context-dependent answer errors on small models. Raw device captures and
benchmark logs are kept outside the repository.

---
