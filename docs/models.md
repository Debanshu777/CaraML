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

## Find and manage models

The Models page opens to a full-width search field with Filters at the trailing edge. The app bar opens Downloads, and the sidebar opens Device. Menu, Downloads, Filters, and search-clear share a subtle raised icon-button style, with the same ink shadow as text buttons and the shared subtle border and lighter raised face in dark mode. Filters uses a small active-filter dot. Long-press Downloads or Filters (or hover with a pointer) to read its label and count; screen readers also announce the count. Pull down on the results while they are at the top to focus search. The current query stays in the field. **Device** opens storage and device capabilities, with recommendation profiles available on supported configurations.

Use **Filters** to select the model type, text format, sort order, and eligible GGUF parameter range. The badge counts applied groups. The default Text, GGUF, and up-to-6B range shows 3. Text with ONNX shows 2, and Image or Video shows 1. Use **View models** to apply changes. **Reset** restores the defaults in the panel.

The initial search page shows up to four recently viewed models in a subdued two-column grid. History retains up to 20 compact display summaries; recording a visit does not delay the model’s file list. A separate **Explore models** heading introduces discovery results and changes to **Search results** after search or filter interaction. The section hides for the rest of the visit after you type a query or apply filters. Search results show how many complete variants you have downloaded for that repository. A complete sharded model or ONNX bundle contributes one variant. Counts use committed artifact identities and current file sizes without rereading full model payloads; full integrity validation remains separate. Open the result to inspect the installed files and review device fit. Removing an ONNX installation also removes its unreferenced companion files, including files in the same repository; shared companions remain while another installation references them.

Open **Downloads** to manage the active queue or find local models by repository, filename, or author. Long-press a local model to select downloads for removal. Going back restores the previous search.

### Catalog results

Language-model search and browse use Hugging Face’s `/models-json` catalog with format, app, parameter-range and sort filters. Results include the server match count and author avatars when available. Avatars can fall back to initials if unavailable. The server currently returns 30 entries per page; CaraML loads at most 256 repositories per query and assesses a bounded subset. A match count describes catalog results, not verified device compatibility. Changing search or filters starts a new query.

Model rows display `owner/model` in a single title: the owner and first `/` use smaller, regular-weight text in secondary text color, while the model name remains semibold in primary text color. Names without a separator use primary text color throughout. Details uses the same inline styling, falling back to the model author when the ID has no owner.

Search rows supply optional parameter counts; zero counts are treated as unknown. Recommendation assessment separately reads model details, files and configuration at an immutable revision. Catalog tags, filters and parameter counts do not establish executable compatibility.

### Model details

Details uses an inline `owner/model` title with a smaller, regular-weight owner and a semibold model name. The full name always wraps, with no expansion control. Only the model-name portion is a link: its dotted underline opens the publisher’s Hugging Face model card. Parameter/download/like metrics, task, license, and the compact fit badge remain in the overview. The app bar initially shows the back control, then reveals the model name when the expanded title scrolls behind it. It uses the same blur, tint, and fade as search; disabling Soft effects uses the shared opaque fallback. The author avatar and title retain their search-to-details transitions and reduced-motion behavior.

The publisher excerpt loads independently of the file list and is fully expanded at every text size. It remains above all three tabs, including Model card, so switching pages preserves the overview and tab position. The Model card page also retains the complete publisher article. It has no Read more/Less control or separate model-card button. This overview is bounded publisher prose; the complete document appears in the Model card tab. Missing or unreadable cards do not block downloads; curated diffusion descriptions remain a fallback.

**Variants**, **Model card**, and **Technical details** use flat text tabs with a short ink indicator and horizontal swipe navigation at phone and desktop widths. Variants opens first. Once the tab row reaches the top bar, it pins directly below it with the same frosted background. Tap a pinned tab or swipe the pager to begin the selected section below the tabs; selecting the current tab keeps its reading position. Scrolling back toward the overview returns the row to its original place. One vertical scroll area keeps long descriptions and all three tabs reachable at large text sizes; the tab labels wrap and technical values stack below their labels when text is enlarged. The divider above the tabs spans the content pane edge to edge; file dividers start at the filename column and extend to the trailing edge, while text and download controls retain their gutters. Distinct variants lead with quantization labels and show the exact filename. Artifact identity, grouped downloads, progress, pause/resume/cancel/retry, and diffusion installation are unchanged. Compact diffusion layouts keep the install action below the Variants page; wide layouts put it inside that page.

The active model’s metadata, files, and README survive configuration changes while the ViewModel is retained. The selected tab and scroll offset restore with the screen, including when rotation changes between compact and persistent navigation layouts. Returning to the same model reuses its remote request and loaded document while refreshing local installation evidence. Removing a model through Downloads therefore restores its download action when details reopens, including when an earlier completed transfer remains in the queue history. Opening another model or browse mode starts a new request. Explicit retries still fetch again. Markdown is prepared once per fetched document and reused when switching tabs. This is an in-memory lifecycle policy, not a persistent offline cache: process restart loads data again.

**Model card** renders the repository’s full `README.md` at the same immutable revision as its files, within the 256 KiB document limit. It supports headings, lists, code, images, and horizontally scrollable tables with wrapping cells. Relative document links open the pinned repository location; relative images load from that revision’s file endpoint. Fragment links open the matching section on Hugging Face. YAML front matter is hidden, and common HTML image/layout wrappers are adapted for native rendering. Complex HTML widgets, embedded video, and mathematical typesetting are not supported. Images need network access and show loading/failure feedback; failed images have an Open image action. Missing cards, oversized cards, and load failures stay inside this tab; retrying does not reload downloads. Oversized or restricted cards offer a direct Open on Hugging Face action.

Technical details groups available facts into **Model specifications**, **Usage**, **Repository**, **Tags**, and **Device fit**, with clear headings and separate surfaces. Parameter count and context are prominent metrics; architecture/library, task/languages/base model, and license/dates sit in their corresponding groups. Tags wrap individually. Empty metadata sections are omitted. **Advertised context** is the model’s reported limit; CaraML’s device context may be lower. Repository-wide storage is not presented as a selected download’s size.

The overview no longer repeats a suggested filename and its size. **Memory estimate · compatibility unverified** is CaraML’s provisional browse-fit explanation, in the Device fit section of Technical details. It uses model/file metadata, workload estimates, and a device resource snapshot; it is not publisher copy or proof that the model can run. The estimate remains provisional when runtime compatibility is unknown, and stale readings are identified. The displayed provisional candidate is chosen by the existing recommendation logic, which sorts by download size and favors a single-file candidate; this can produce an auxiliary-looking filename such as `steer.gguf`. Removing the overview summary does not change artifact classification, fit policy, or native-load admission.

| Information | Source | Current presentation |
| --- | --- | --- |
| Parameters, downloads, likes, task, license | `/api/models/{id}` and `cardData` | Compact header; absent values are omitted |
| Advertised context, architecture, languages, base model, dates | `gguf`, `config`, and `cardData` | Technical details tab |
| Publisher description | Revision-pinned `README.md` | Full Markdown in Model card; bounded overview excerpt; linked model name |
| Variant size and complete bundle members | Revision-pinned file tree and existing artifact assessment | File list and install plan |
| Datasets, evaluations, newer model versions, custom license links | Optional model-card metadata | Possible future additions; not displayed in this pass |

Hugging Face separates [model-card metadata from its prose](https://huggingface.co/docs/hub/model-cards). These fields vary by repository. Publisher benchmarks, task tags, and advertised limits do not replace CaraML’s device-fit assessment.

## GGUF language models

For an initial smoke test, the project has exercised small models from the SmolLM2, Qwen2.5, Qwen3, Gemma 3, and LFM2 families. Exact checkpoints, quantizations, and results are in the [benchmark reports](benchmarks.md). A different export of the same family can behave differently.

Choose an **instruct/chat** variant and check its model card. Projector, adapter, and auxiliary GGUF files are not standalone chat models. Download all shards when the selected model is split.

Smaller quantizations usually reduce weight storage, but quality and runtime memory vary. Context length, KV cache, batch size, and native working buffers also consume memory. CaraML's admission checks can reject a model that fits on disk but does not fit the current memory budget.

## ONNX bundles

Supported bundles include graph, configuration, tokenizer, and any external tensor data at a single Hub revision. The current implementation targets a specific set of causal text decoder layouts, float32 logits and KV caches, and supported byte-level BPE tokenizers. Empty or undersized logits are rejected before sampling on Apple and Java runtimes. Java cache dimensions are checked during load, and temporary input tensors are released when preparation fails.

FP16 caches/logits, SentencePiece tokenizers, arbitrary chat templates, vision, audio, and ONNX diffusion graphs are outside this implementation. Read [the ONNX compatibility contract](https://github.com/Debanshu777/CaraML/blob/main/plugin-onnx/README.md) before selecting an export.

The app uses CPU for the validated dynamic-cache exports. Provider availability alone does not establish GPU or NPU execution. WebGPU and other provider experiments are documented separately and are not default app features.

## Diffusion models

Some models need additional text encoders or a VAE. Use the app's model-specific install plan; downloading a single weight file may be insufficient. Start from the [five-model Android diffusion report](https://github.com/Debanshu777/CaraML/blob/main/benchmarks/diffusion/README.md), which records image size, steps, backend, outputs, and limitations.

Image generation can take considerably longer and use more memory than a short chat response. SDK diffusion support is narrower than the app's install pipeline: see [SDK limits](architecture.md#current-sdk-limits). The presence of a Videos navigation destination does not establish a validated video-generation workflow.

## Removing incomplete installations

Downloads can remove selected partial or damaged installations even when payloads are missing or fail checksum validation. Shared files and other installed variants remain protected. File cleanup uses contained, non-symlink paths; it never recursively removes a repository root. If a legacy directory or damaged metadata cannot establish which bytes belong to the selected record, the catalog entry is removed with a cleanup warning and those bytes are retained.

## Download cancellation

The download queue shows cancellation as pending until the platform has stopped and staged checkpoints are cleaned. **Cancellation cleanup pending** remains visible if cleanup is interrupted or fails; choose **Retry cancellation** to retry. Startup also retries persisted cleanup. Published completed files are retained. Terminal failure history does not displace active or paused downloads from the queue, and Details controls resolve the exact persisted task.

## Network access and model licenses

Public model browsing and downloads use the network. Gated repositories may require a Hugging Face token and prior acceptance of their access terms. Never paste tokens into bug reports or recordings.

Model weights retain their own licenses and usage restrictions. CaraML's license does not grant rights to third-party models, and a model's license does not grant commercial rights to CaraML. Review both before use.
