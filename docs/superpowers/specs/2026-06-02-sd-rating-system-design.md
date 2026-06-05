# Stable Diffusion Model Rating System

**Date:** 2026-06-02  
**Branch:** feature/diffusion-chat-window  
**Approach:** B — `SdArchitectureClassifier` + enhanced `rateDiffusion()`

---

## Problem

`ModelListItem` calls `rateLlm()` for all models including diffusion. `rateDiffusion()` exists but is naive — just sums bytes with a flat 400 MB overhead, no architecture awareness. Users cannot tell if a FLUX or SDXL model fits their device before downloading.

---

## Goal

Same rating experience as LLM: suitability chip on search list (coarse estimate) and detail/install page (accurate per-component), plus post-download re-rating of local models via JNI native metadata.

---

## New Files

### `composeApp/core/rating/SdArchitecture.kt`

Enum with known memory profiles per architecture:

| Variant     | Q4 RAM   | F16 RAM   | T5 Encoder | Label      |
|-------------|----------|-----------|------------|------------|
| SD1         | 2.0 GB   | 2.3 GB    | No         | SD 1.x     |
| SDXL        | 3.5 GB   | 6.0 GB    | No         | SDXL       |
| SD3         | 5.0 GB   | 10.0 GB   | Yes        | SD 3       |
| FLUX        | 6.4 GB   | 12.0 GB   | Yes        | FLUX       |
| WAN_SMALL   | 8.0 GB   | 12.0 GB   | No         | Wan 1.3B   |
| WAN_LARGE   | 16.0 GB  | 24.0 GB   | No         | Wan 14B    |
| UNKNOWN     | —        | —         | —          | Unknown    |

Fields per entry: `baseRamBytesQ4: Long`, `baseRamBytesF16: Long`, `hasT5Encoder: Boolean`, `label: String`.

Companion method: `fromNativeString(s: String): SdArchitecture` — maps native `SDVersion` enum name strings to enum entries (FLUX > SD3 > WAN_LARGE > WAN_SMALL > SDXL > SD1 > UNKNOWN priority).

### `composeApp/core/rating/SdArchitectureClassifier.kt`

Stateless `object`. Single public method:

```kotlin
fun classify(tags: List<String>, modelId: String = ""): SdArchitecture
```

Normalizes all tags + modelId path segments to lowercase. Matches against priority-ordered tag sets:

| Priority | Tags matched                                                    | Result    |
|----------|-----------------------------------------------------------------|-----------|
| 1        | flux, flux.1, flux-dev, flux-schnell, flux1                     | FLUX      |
| 2        | stable-diffusion-3, sd3, sd-3, stable-diffusion3               | SD3       |
| 3        | wan2.1-14b, wan-14b, wan2-14b                                   | WAN_LARGE |
| 4        | wan, wan2, wan2.1, wan2.1-1.3b, wan-1.3b                       | WAN_SMALL |
| 5        | stable-diffusion-xl, sdxl, stable-diffusion-xl-base-1.0        | SDXL      |
| 6        | stable-diffusion-v1, stable-diffusion-v1-5, sd1, stable-diffusion | SD1    |
| fallback | —                                                               | UNKNOWN   |

### `diffusionRunner/commonMain/DiffusionModelMetadata.kt`

```kotlin
data class DiffusionModelMetadata(
    val architecture: String,       // native SDVersion name, e.g. "FLUX"
    val dominantQuantType: String?, // "Q4_K", "Q8_0", "F16", or null
    val estimatedRamBytes: Long,    // from ModelLoader.get_params_mem_size()
)
```

---

## Modified Files

### `ModelSuitabilityCalculator.kt` — new overload

```kotlin
fun rateDiffusion(
    hints: DeviceHints,
    architecture: SdArchitecture,
    dominantQuantTag: String? = null,
    totalComponentBytes: Long? = null,
    canOffloadVae: Boolean = true,
    flashAttnAvailable: Boolean = false,
): SuitabilityResult
```

**Weight resolution (priority order):**
1. `totalComponentBytes` if non-null → use directly (`isEstimate = false`)
2. `architecture != UNKNOWN` → scale `baseRamBytesQ4` by quant BPW ratio (`isEstimate = true`)
3. Both null/UNKNOWN → return `SuitabilityResult(rating = UNKNOWN, ...)`

**Discounts applied to effective weight bytes before ratio:**
- `canOffloadVae = true`: `-400 MB`
- `flashAttnAvailable = true`: `-600 MB`
- `architecture.hasT5Encoder && canOffloadClip`: `-700 MB` (future flag, default false)

**Overhead:** `max(effectiveBytes × 0.20, 400 MB)`

**Ratio buckets:** same as LLM — `< 0.40` BEST, `< 0.65` GOOD, `< 0.90` AVERAGE, `≥ 0.90` POOR.

**Modifiers:**
- `hints.gpuBackendAvailable && architecture in [FLUX, SD3, WAN_SMALL, WAN_LARGE]` → bump up one tier (DiT architectures gain most from GPU)
- `hints.performanceCoreCount < 4 && architecture in [WAN_SMALL, WAN_LARGE]` → bump down one tier (video is compute-heavy)

Existing `rateDiffusion(hints, totalComponentBytes)` stays untouched — no breaking changes.

### `DiffusionRunner.kt` — new expect function

```kotlin
expect suspend fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata?
```

Returns null on failure (corrupted file, unsupported format, pre-download state).

**Platform actuals:** Android + JVM call a new JNI function; iOS calls via cinterop. Native C wrapper invokes:
- `ModelLoader.init_from_file(path)`
- `ModelLoader.get_sd_version()` → architecture string
- `ModelLoader.get_wtype_stat()` → dominant quant type (most frequent ggml_type)
- `ModelLoader.get_params_mem_size(GGML_BACKEND_TYPE_CPU, SD_TYPE_COUNT)` → estimated RAM

### `ModelListItem.kt`

Add diffusion branch before existing `rateLlm()` call:

```kotlin
val rating = when (model.pipelineTag) {
    "text-to-image", "text-to-video" -> {
        val arch = SdArchitectureClassifier.classify(model.tags ?: emptyList(), model.modelId)
        ModelSuitabilityCalculator.rateDiffusion(hints, arch)
    }
    else -> ModelSuitabilityCalculator.rateLlm(hints, model.numParameters, ...)
}
```

### `InstallBundleCard.kt` / `ModelViewModel.kt`

For diffusion detail page: sum `SetupComponentUiState.sizeHint` values across all components → `totalComponentBytes`. Classify tags from `ModelDetailResponse.cardData.tags`. Pass both to new `rateDiffusion()` overload. Display chip same as LLM detail page.

### Chat model picker (local downloaded models)

Call `getDiffusionModelMetadata(localPath)` on diffusion model load. Map result via `SdArchitecture.fromNativeString()`. Feed into `rateDiffusion()`. Cache result — don't re-query native on every recomposition.

---

## Data Flow Summary

```
[Search list]
Model.tags + modelId
  → SdArchitectureClassifier.classify()
  → rateDiffusion(hints, arch)                    ← architecture estimate
  → SuitabilityChip in ModelListItem

[Detail / install page]
ModelDetailResponse component sizeHints (summed)
  + cardData.tags
  → rateDiffusion(hints, arch, totalComponentBytes) ← accurate
  → SuitabilityChip in InstallBundleCard

[Downloaded local model]
DiffusionRunner.getDiffusionModelMetadata(path)   ← new JNI
  → DiffusionModelMetadata
  → SdArchitecture.fromNativeString(arch)
  → rateDiffusion(hints, arch, estimatedRamBytes)  ← most accurate
  → SuitabilityChip in chat model picker
```

---

## Testing

- `SdArchitectureClassifier`: unit tests for each tag set, priority ordering, case-insensitive match, unknown fallback
- `rateDiffusion()` new overload: weight resolution priority, discount application, ratio buckets, modifier edge cases (GPU bump, low-core penalty, caps at BEST/POOR)
- `SdArchitecture.fromNativeString()`: mapping for all SDVersion name patterns
- `DiffusionModelMetadata`: null return on bad path (mocked JNI)

No UI tests needed — existing chip components are already tested via LLM path.

---

## Out of Scope

- ControlNet, LoRA, TAESD rating — not enough HF metadata to rate reliably
- SVD (Stable Video Diffusion) — omitted; treated as UNKNOWN
- Dynamic quant detection at search-list level (no file sizes available) — uses Q4 baseline
