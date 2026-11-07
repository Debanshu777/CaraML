# Physical iPhone 13 results

| Configuration | Decode tok/s (median; range) | TTFT ms | Load ms | Peak RSS MiB | Thermal states | GPU layers |
|---|---:|---:|---:|---:|---|---:|
| llama32-1b-gguf-CPU-t2-gpu0 | 19.32 (18.27–19.40) | 1243.5 | 1525.0 | 1368 | 1 | 0 |
| llama32-1b-gguf-CPU-t2-gpu99 | 22.51 (22.50–22.56) | 162.0 | 2429.3 | 1209 | 0 | 17 |
| llama32-1b-onnx-CPU-t1-gpu0-low-memory | Incomplete; see raw log | — | — | — | — | — |
| llama32-1b-onnx-CPU-t1-gpu0 | Incomplete; see raw log | — | — | — | — | — |
| llama32-1b-onnx-CPU-t2-gpu0 | Incomplete; see raw log | — | — | — | — | — |
| qwen25-05b-gguf-CPU-t2-gpu0 | 57.74 (56.32–58.57) | 303.9 | 737.6 | 802 | 0 | 0 |
| qwen25-05b-gguf-CPU-t2-gpu99 | 38.24 (37.92–40.19) | 81.7 | 754.8 | 788 | 0 | 25 |
| qwen25-05b-onnx-CPU-t1-gpu0 | 11.18 (11.03–11.28) | 289.3 | 1947.7 | 1381 | 0 | — |
| qwen25-05b-onnx-CPU-t2-gpu0 | 11.01 (10.72–11.03) | 212.6 | 1871.9 | 1364 | 1 | — |
| qwen3-06b-gguf-CPU-t2-gpu0 | 41.14 (40.17–42.22) | 499.9 | 732.7 | 974 | 0, 1 | 0 |
| qwen3-06b-gguf-CPU-t2-gpu99 | 35.45 (34.27–36.27) | 100.6 | 758.4 | 955 | 1 | 29 |
| qwen3-06b-onnx-CPU-t1-gpu0 | 9.82 (9.78–9.88) | 382.7 | 2802.0 | 1671 | 0 | — |
| qwen3-06b-onnx-CPU-t2-gpu0 | 10.49 (10.27–10.65) | 259.7 | 2444.8 | 1733 | 0 | — |
| smollm2-135m-gguf-CPU-t2-gpu0 | 181.65 (179.96–183.39) | 105.3 | 186.0 | 301 | 0 | 0 |
| smollm2-135m-gguf-CPU-t2-gpu99 | 39.63 (38.75–42.90) | 80.9 | 187.8 | 295 | 0 | 31 |
| smollm2-135m-onnx-COREML-t1-gpu0 | 32.78 (32.05–32.89) | 71.6 | 948.0 | 549 | 0 | — |
| smollm2-135m-onnx-CPU-t1-gpu0 | 33.28 (33.26–33.34) | 67.6 | 885.9 | 549 | 0 | — |
| smollm2-135m-onnx-CPU-t2-gpu0 | 36.15 (36.08–36.17) | 50.0 | 779.5 | 550 | 0 | — |
| smollm2-360m-gguf-CPU-t2-gpu0 | 68.48 (67.35–70.08) | 335.6 | 392.8 | 566 | 0, 1 | 0 |
| smollm2-360m-gguf-CPU-t2-gpu99 | 40.10 (39.04–40.35) | 99.9 | 406.8 | 557 | 0 | 33 |
| smollm2-360m-onnx-CPU-t1-gpu0 | 16.59 (16.57–16.63) | 145.2 | 1190.4 | 928 | 0 | — |
| smollm2-360m-onnx-CPU-t2-gpu0 | 20.00 (19.92–20.14) | 102.7 | 1022.6 | 922 | 0 | — |

CPU results use the baseline build; Metal results use the corrected shutdown build. Framework hashes are included in each raw result. Failed ONNX Llama loads are retained. COREML rows explicitly fell back to CPU.
