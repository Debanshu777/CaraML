# Pixel 9 — foreground-verified physical measurements

One warmup, three measured generations; see [method and comparison](../../README.md).
`gpu99` means full Vulkan offload for GGUF; `provider` is an ONNX-only argument.
CPU threads are the `t` value. Model identities and APK hash are retained separately.

| Configuration | Decode tok/s (median; range) | TTFT ms | Load ms | Peak PSS MiB | After load °C |
|---|---:|---:|---:|---:|---:|
| llama32-1b-gguf-CPU-t4-gpu0 | 20.32 (19.96–20.62) | 261.7 | 7030.0 | 2737 | 35.1 |
| llama32-1b-gguf-CPU-t4-gpu99 | 13.18 (13.16–13.21) | 1918.1 | 1110.5 | 2077 | 35.0 |
| llama32-1b-q4-onnx-CPU-t1-gpu0 | 11.84 (11.57–12.10) | 1179.8 | 7340.1 | 2109 | 34.9 |
| llama32-1b-q4-onnx-CPU-t2-gpu0 | 13.70 (13.54–14.21) | 813.5 | 5331.6 | 2103 | 35.1 |
| qwen25-05b-gguf-CPU-t4-gpu0 | 49.32 (48.16–49.85) | 173.8 | 3092.9 | 1228 | 35.5 |
| qwen25-05b-gguf-CPU-t4-gpu99 | 30.13 (29.99–30.24) | 896.9 | 553.4 | 1176 | 34.9 |
| qwen25-05b-onnx-CPU-t1-gpu0 | 11.05 (10.99–11.05) | 368.4 | 3584.5 | 1560 | 35.4 |
| qwen25-05b-onnx-CPU-t2-gpu0 | 12.15 (11.97–12.19) | 342.2 | 3165.3 | 1565 | 34.9 |
| qwen3-06b-gguf-CPU-t4-gpu0 | 38.77 (38.11–39.06) | 152.4 | 3622.9 | 1622 | 35.0 |
| qwen3-06b-gguf-CPU-t4-gpu99 | 24.18 (24.17–24.19) | 994.0 | 697.0 | 1638 | 35.0 |
| qwen3-06b-onnx-CPU-t1-gpu0 | 9.78 (9.72–9.80) | 521.6 | 3138.1 | 1985 | 34.7 |
| qwen3-06b-onnx-CPU-t2-gpu0 | 10.24 (10.10–10.54) | 346.6 | 3007.0 | 1935 | 35.4 |
| smollm2-135m-gguf-CPU-t4-gpu0 | 146.93 (143.05–147.63) | 105.2 | 859.5 | 515 | 35.5 |
| smollm2-135m-gguf-CPU-t4-gpu99 | 67.43 (64.17–67.77) | 364.4 | 194.4 | 768 | 35.5 |
| smollm2-135m-onnx-CPU-t1-gpu0 | 39.76 (39.67–40.17) | 142.8 | 1928.4 | 692 | 35.5 |
| smollm2-135m-onnx-CPU-t2-gpu0 | 34.80 (34.27–35.38) | 121.8 | 1965.7 | 691 | 35.5 |
| smollm2-360m-gguf-CPU-t4-gpu0 | 64.91 (62.40–66.88) | 149.6 | 2218.4 | 999 | 35.2 |
| smollm2-360m-gguf-CPU-t4-gpu99 | 34.07 (33.87–34.26) | 793.8 | 299.4 | 1080 | 35.4 |
| smollm2-360m-onnx-CPU-t1-gpu0 | 18.71 (18.69–18.83) | 258.0 | 2684.8 | 1086 | 35.5 |
| smollm2-360m-onnx-CPU-t2-gpu0 | 21.10 (21.08–21.33) | 197.1 | 2399.6 | 1087 | 35.2 |
