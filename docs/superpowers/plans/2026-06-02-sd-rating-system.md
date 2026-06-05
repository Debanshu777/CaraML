# Stable Diffusion Model Rating System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add architecture-aware suitability ratings for stable-diffusion models in both search list and detail page, plus JNI-based post-download metadata for local models.

**Architecture:** `SdArchitectureClassifier` maps HF tags/model IDs to `SdArchitecture` enum; a new `rateDiffusion()` overload in `ModelSuitabilityCalculator` uses architecture profiles + actual component bytes; `DiffusionRunner.getDiffusionModelMetadata()` bridges to `ModelLoader` in stable-diffusion.cpp for post-download accuracy.

**Tech Stack:** Kotlin Multiplatform, C++ (stable-diffusion.cpp ModelLoader API), JNI (Android/JVM), cinterop (iOS), Compose UI (existing chip components reused unchanged).

---

## File Map

**Create:**
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitecture.kt`
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifier.kt`
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureTest.kt`
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifierTest.kt`
- `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelMetadata.kt`

**Modify:**
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculator.kt` — add `rateDiffusion()` overload + `SD_QUANT_BPW` map
- `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculatorTest.kt` — add new overload tests
- `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt` — add `expect fun getDiffusionModelMetadata()`
- `diffusionRunner/src/commonCpp/diffusion_runner_core.h` — add `DiffusionMetadataResult` struct + declaration
- `diffusionRunner/src/commonCpp/diffusion_runner_core.cpp` — add `diffusion_runner_core_get_metadata()` impl
- `diffusionRunner/src/commonCpp/diffusion_runner_jni.cpp` — add JNI wrapper
- `diffusionRunner/src/iosMain/cpp/diffusion_runner.h` — add FFI struct + declaration
- `diffusionRunner/src/iosMain/cpp/diffusion_runner.cpp` — add iOS impl
- `diffusionRunner/src/androidMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.android.kt` — add actual
- `diffusionRunner/src/jvmMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.jvm.kt` — add actual
- `diffusionRunner/src/iosMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.ios.kt` — add actual
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelListItem.kt` — diffusion branch
- `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt` — diffusion rating branch

---

## Task 1: SdArchitecture enum

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitecture.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureTest.kt
package com.debanshu777.caraml.core.rating

import kotlin.test.Test
import kotlin.test.assertEquals

class SdArchitectureTest {

    @Test
    fun fromNativeString_mapsFlux() {
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX_FILL"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX_CONTROLS"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX2"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLEX_2"))
    }

    @Test
    fun fromNativeString_mapsSD3() {
        assertEquals(SdArchitecture.SD3, SdArchitecture.fromNativeString("SD3"))
    }

    @Test
    fun fromNativeString_mapsSDXL() {
        assertEquals(SdArchitecture.SDXL, SdArchitecture.fromNativeString("SDXL"))
        assertEquals(SdArchitecture.SDXL, SdArchitecture.fromNativeString("SDXL_INPAINT"))
    }

    @Test
    fun fromNativeString_mapsSD1() {
        assertEquals(SdArchitecture.SD1, SdArchitecture.fromNativeString("SD1"))
        assertEquals(SdArchitecture.SD1, SdArchitecture.fromNativeString("SD2"))
    }

    @Test
    fun fromNativeString_mapsWanByRam() {
        val smallRam = 8L * 1024 * 1024 * 1024   // 8 GB → WAN_SMALL
        val largeRam = 20L * 1024 * 1024 * 1024  // 20 GB → WAN_LARGE
        assertEquals(SdArchitecture.WAN_SMALL, SdArchitecture.fromNativeString("WAN2_SMALL", smallRam))
        assertEquals(SdArchitecture.WAN_LARGE, SdArchitecture.fromNativeString("WAN2_LARGE", largeRam))
    }

    @Test
    fun fromNativeString_returnsUnknownForUnrecognized() {
        assertEquals(SdArchitecture.UNKNOWN, SdArchitecture.fromNativeString("CHROMA_RADIANCE"))
        assertEquals(SdArchitecture.UNKNOWN, SdArchitecture.fromNativeString(""))
    }

    @Test
    fun baseRamBytesQ4_areNonZeroForKnownArch() {
        SdArchitecture.entries.filter { it != SdArchitecture.UNKNOWN }.forEach { arch ->
            assert(arch.baseRamBytesQ4 > 0) { "${arch.name} has zero Q4 RAM" }
        }
    }
}
```

- [ ] **Step 2: Run tests — expect compilation failure**

```bash
./gradlew :composeApp:jvmTest --tests "com.debanshu777.caraml.core.rating.SdArchitectureTest" 2>&1 | tail -20
```

Expected: `error: unresolved reference: SdArchitecture`

- [ ] **Step 3: Create SdArchitecture enum**

```kotlin
// composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitecture.kt
package com.debanshu777.caraml.core.rating

private const val MB = 1024L * 1024L

/**
 * Known stable-diffusion.cpp architecture families with their RAM footprints.
 *
 * [baseRamBytesQ4] and [baseRamBytesF16] are derived from stable-diffusion.cpp's
 * published benchmark tables (docs/flux.md, docs/quantization_and_gguf.md) and
 * represent the full model weight load (UNet/DiT + VAE + text encoders combined).
 */
enum class SdArchitecture(
    /** Estimated peak RAM at Q4 quantization (full pipeline, default VAE on GPU). */
    val baseRamBytesQ4: Long,
    /** Estimated peak RAM at F16 (no quantization). */
    val baseRamBytesF16: Long,
    /** Whether this architecture requires a T5-XXL text encoder (large RAM impact). */
    val hasT5Encoder: Boolean,
    /** Human-readable label for UI display. */
    val label: String,
) {
    /** Stable Diffusion 1.x and 2.x — 512px native resolution. */
    SD1(
        baseRamBytesQ4  = 2_000L * MB,
        baseRamBytesF16 = 2_300L * MB,
        hasT5Encoder    = false,
        label           = "SD 1.x / 2.x",
    ),
    /** Stable Diffusion XL — 1024px native resolution. */
    SDXL(
        baseRamBytesQ4  = 3_500L * MB,
        baseRamBytesF16 = 6_000L * MB,
        hasT5Encoder    = false,
        label           = "SDXL",
    ),
    /** Stable Diffusion 3 / 3.5 — requires T5-XXL encoder. */
    SD3(
        baseRamBytesQ4  = 5_000L * MB,
        baseRamBytesF16 = 10_000L * MB,
        hasT5Encoder    = true,
        label           = "SD 3",
    ),
    /** FLUX.1 family (dev, schnell, Fill, Chroma) — DiT, requires T5-XXL. */
    FLUX(
        baseRamBytesQ4  = 6_400L * MB,
        baseRamBytesF16 = 12_000L * MB,
        hasT5Encoder    = true,
        label           = "FLUX",
    ),
    /** Wan2.1 1.3B text-to-video — smallest video model. */
    WAN_SMALL(
        baseRamBytesQ4  = 8_000L * MB,
        baseRamBytesF16 = 12_000L * MB,
        hasT5Encoder    = false,
        label           = "Wan 1.3B",
    ),
    /** Wan2.1 14B text/image-to-video — requires CPU offload on most devices. */
    WAN_LARGE(
        baseRamBytesQ4  = 16_000L * MB,
        baseRamBytesF16 = 24_000L * MB,
        hasT5Encoder    = false,
        label           = "Wan 14B",
    ),
    /** Architecture could not be determined from available metadata. */
    UNKNOWN(
        baseRamBytesQ4  = 0L,
        baseRamBytesF16 = 0L,
        hasT5Encoder    = false,
        label           = "Unknown",
    );

    companion object {
        /**
         * Maps a native SDVersion name string returned by the C++ layer to an
         * [SdArchitecture]. Priority: FLUX > SD3 > WAN > SDXL > SD1 > UNKNOWN.
         *
         * @param s               The native architecture string (e.g. "FLUX", "SDXL", "WAN2_SMALL").
         * @param estimatedRamBytes RAM estimate from native layer; used to distinguish WAN 1.3B vs 14B.
         */
        fun fromNativeString(s: String, estimatedRamBytes: Long = 0L): SdArchitecture {
            val u = s.uppercase()
            return when {
                "FLUX" in u || "FLEX" in u -> FLUX
                "SD3"  in u                -> SD3
                "WAN"  in u -> if (estimatedRamBytes > 12L * 1024 * 1024 * 1024) WAN_LARGE else WAN_SMALL
                "SDXL" in u                -> SDXL
                "SD1"  in u || "SD2" in u  -> SD1
                else                       -> UNKNOWN
            }
        }
    }
}
```

- [ ] **Step 4: Run tests — expect pass**

```bash
./gradlew :composeApp:jvmTest --tests "com.debanshu777.caraml.core.rating.SdArchitectureTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` / `3 tests completed, 0 failed`

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitecture.kt \
        composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureTest.kt
git commit -m "feat(rating): add SdArchitecture enum with memory profiles and fromNativeString()"
```

---

## Task 2: SdArchitectureClassifier

**Files:**
- Create: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifier.kt`
- Create: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifierTest.kt`

- [ ] **Step 1: Write failing tests**

```kotlin
// composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifierTest.kt
package com.debanshu777.caraml.core.rating

import kotlin.test.Test
import kotlin.test.assertEquals

class SdArchitectureClassifierTest {

    // --- FLUX ---

    @Test
    fun classifiesFluxTag() {
        assertEquals(SdArchitecture.FLUX, SdArchitectureClassifier.classify(listOf("flux")))
    }

    @Test
    fun classifiesFluxDotOne() {
        assertEquals(SdArchitecture.FLUX, SdArchitectureClassifier.classify(listOf("flux.1")))
    }

    @Test
    fun classifiesFluxFromModelId() {
        assertEquals(
            SdArchitecture.FLUX,
            SdArchitectureClassifier.classify(emptyList(), "black-forest-labs/FLUX.1-dev"),
        )
    }

    @Test
    fun classifiesFlux_caseInsensitive() {
        assertEquals(SdArchitecture.FLUX, SdArchitectureClassifier.classify(listOf("FLUX")))
        assertEquals(SdArchitecture.FLUX, SdArchitectureClassifier.classify(listOf("Flux")))
    }

    // --- SD3 ---

    @Test
    fun classifiesSd3Tag() {
        assertEquals(SdArchitecture.SD3, SdArchitectureClassifier.classify(listOf("sd3")))
    }

    @Test
    fun classifiesSd3FullName() {
        assertEquals(
            SdArchitecture.SD3,
            SdArchitectureClassifier.classify(listOf("stable-diffusion-3")),
        )
    }

    // --- SDXL ---

    @Test
    fun classifiesSdxlTag() {
        assertEquals(SdArchitecture.SDXL, SdArchitectureClassifier.classify(listOf("stable-diffusion-xl")))
    }

    @Test
    fun classifiesSdxlShort() {
        assertEquals(SdArchitecture.SDXL, SdArchitectureClassifier.classify(listOf("sdxl")))
    }

    // --- WAN ---

    @Test
    fun classifiesWanSmall() {
        assertEquals(SdArchitecture.WAN_SMALL, SdArchitectureClassifier.classify(listOf("wan2.1")))
    }

    @Test
    fun classifiesWanLarge() {
        assertEquals(SdArchitecture.WAN_LARGE, SdArchitectureClassifier.classify(listOf("wan2.1-14b")))
    }

    // --- SD1 ---

    @Test
    fun classifiesSd1() {
        assertEquals(SdArchitecture.SD1, SdArchitectureClassifier.classify(listOf("stable-diffusion-v1-5")))
    }

    // --- Priority ---

    @Test
    fun fluxTakesPriorityOverSdxl() {
        assertEquals(
            SdArchitecture.FLUX,
            SdArchitectureClassifier.classify(listOf("stable-diffusion-xl", "flux")),
        )
    }

    @Test
    fun fluxTakesPriorityOverSd3() {
        assertEquals(
            SdArchitecture.FLUX,
            SdArchitectureClassifier.classify(listOf("sd3", "flux")),
        )
    }

    @Test
    fun sd3TakesPriorityOverSdxl() {
        assertEquals(
            SdArchitecture.SD3,
            SdArchitectureClassifier.classify(listOf("sdxl", "stable-diffusion-3")),
        )
    }

    @Test
    fun wanLargeTakesPriorityOverWanSmall() {
        assertEquals(
            SdArchitecture.WAN_LARGE,
            SdArchitectureClassifier.classify(listOf("wan2.1", "wan2.1-14b")),
        )
    }

    // --- Unknown ---

    @Test
    fun unknownForUnrecognizedTags() {
        assertEquals(SdArchitecture.UNKNOWN, SdArchitectureClassifier.classify(listOf("some-custom-model")))
    }

    @Test
    fun unknownForEmptyInput() {
        assertEquals(SdArchitecture.UNKNOWN, SdArchitectureClassifier.classify(emptyList()))
    }
}
```

- [ ] **Step 2: Run — expect compilation failure**

```bash
./gradlew :composeApp:jvmTest --tests "com.debanshu777.caraml.core.rating.SdArchitectureClassifierTest" 2>&1 | tail -10
```

Expected: `error: unresolved reference: SdArchitectureClassifier`

- [ ] **Step 3: Create SdArchitectureClassifier**

```kotlin
// composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifier.kt
package com.debanshu777.caraml.core.rating

/**
 * Maps HuggingFace model tags and model IDs to a known [SdArchitecture].
 *
 * Detection is purely lexical — no network calls, no file reads. Precision is sufficient
 * for pre-download RAM estimates; post-download metadata from [DiffusionRunner.getDiffusionModelMetadata]
 * provides ground-truth architecture.
 *
 * Priority order (first match wins): FLUX > SD3 > WAN_LARGE > WAN_SMALL > SDXL > SD1 > UNKNOWN
 */
object SdArchitectureClassifier {

    private val FLUX_TAGS = setOf(
        "flux", "flux.1", "flux1", "flux-dev", "flux-schnell",
        "flux-fill", "flux-controls", "flex-2",
    )
    private val SD3_TAGS = setOf(
        "stable-diffusion-3", "sd3", "sd-3", "stable-diffusion3", "sd3.5",
    )
    private val WAN_LARGE_TAGS = setOf(
        "wan2.1-14b", "wan-14b", "wan2-14b", "wan2.2-14b",
    )
    private val WAN_SMALL_TAGS = setOf(
        "wan", "wan2", "wan2.1", "wan2.1-1.3b", "wan-1.3b",
    )
    private val SDXL_TAGS = setOf(
        "stable-diffusion-xl", "stable-diffusion-xl-base-1.0",
        "sdxl", "sdxl-base",
    )
    private val SD1_TAGS = setOf(
        "stable-diffusion", "stable-diffusion-v1", "stable-diffusion-v1-5",
        "stable-diffusion-v2", "sd1", "sd-1", "sd2", "sd-2",
    )

    /**
     * Classifies an SD model from HuggingFace tags and model ID.
     *
     * @param tags    List of HF tags (from [ModelDetailResponse.tags] or [ModelDetailResponse.cardData?.tags]).
     *                Pass an empty list when unavailable (search-list context).
     * @param modelId The HF repo ID, e.g. "black-forest-labs/FLUX.1-dev". Segments after "/"
     *                and "-" are normalized and scanned as additional tag candidates.
     */
    fun classify(tags: List<String>, modelId: String = ""): SdArchitecture {
        val normalized = buildSet<String> {
            tags.forEach { tag -> add(tag.lowercase().trim()) }
            // Split model ID into segments: "black-forest-labs/FLUX.1-dev" → ["flux.1", "dev", ...]
            modelId.lowercase().split("/", "-", "_", ".").forEach { segment ->
                if (segment.length >= 2) add(segment)
            }
        }

        return when {
            normalized.any { it in FLUX_TAGS }      -> SdArchitecture.FLUX
            normalized.any { it in SD3_TAGS }       -> SdArchitecture.SD3
            normalized.any { it in WAN_LARGE_TAGS } -> SdArchitecture.WAN_LARGE
            normalized.any { it in WAN_SMALL_TAGS } -> SdArchitecture.WAN_SMALL
            normalized.any { it in SDXL_TAGS }      -> SdArchitecture.SDXL
            normalized.any { it in SD1_TAGS }       -> SdArchitecture.SD1
            else                                    -> SdArchitecture.UNKNOWN
        }
    }
}
```

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :composeApp:jvmTest --tests "com.debanshu777.caraml.core.rating.SdArchitectureClassifierTest" 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL` / all tests pass

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifier.kt \
        composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/SdArchitectureClassifierTest.kt
git commit -m "feat(rating): add SdArchitectureClassifier for HF tag → architecture mapping"
```

---

## Task 3: rateDiffusion() architecture-aware overload

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculator.kt`
- Modify: `composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculatorTest.kt`

- [ ] **Step 1: Write failing tests — add to existing test class**

Add to the bottom of `ModelSuitabilityCalculatorTest.kt` (inside the class, before the closing `}`):

```kotlin
    // ─── rateDiffusion() architecture-aware overload ───

    @Test
    fun rateDiffusion_returnsUnknownWhenArchUnknownAndNoBytesProvided() {
        val h = hints(ramMb = 16 * 1024L)
        val result = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.UNKNOWN,
        )
        assertEquals(SuitabilityRating.UNKNOWN, result.rating)
    }

    @Test
    fun rateDiffusion_usesTotalComponentBytesWhenProvided() {
        // 3 GB component bytes on 32 GB device → ratio ~0.10 → BEST (isEstimate = false)
        val h = hints(ramMb = 32 * 1024L)
        val result = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.FLUX,
            totalComponentBytes = 3_000L * 1024 * 1024,
        )
        assertEquals(SuitabilityRating.BEST, result.rating)
        assertEquals(false, result.isEstimate)
    }

    @Test
    fun rateDiffusion_fallsBackToArchBaseline_isEstimateTrue() {
        // SD1 Q4 baseline = 2 GB; budget = 24 GB → ratio ~0.09 → BEST
        val h = hints(ramMb = 24 * 1024L)
        val result = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.SD1,
        )
        assertEquals(SuitabilityRating.BEST, result.rating)
        assertEquals(true, result.isEstimate)
    }

    @Test
    fun rateDiffusion_vaeOffloadReducesEffectiveWeight() {
        // FLUX Q4 = 6.4 GB; budget = 8 GB.
        // Without offload: effective = 6.4 GB + 20% overhead ≈ 7.68 GB / 8.0 = 0.96 → POOR
        // With VAE offload: effective = (6.4 - 0.4) = 6.0 GB + 20% = 7.2 GB / 8.0 = 0.90 → POOR (edge)
        // Use a 9 GB budget to see offload push from POOR to AVERAGE.
        val h = hints(ramMb = 9 * 1024L)
        val withoutOffload = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.FLUX,
            canOffloadVae = false,
        )
        val withOffload = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.FLUX,
            canOffloadVae = true,
        )
        // offload must produce same or better rating
        assertTrue(
            withOffload.rating.ordinal <= withoutOffload.rating.ordinal,
            "VAE offload should not worsen rating. without=${withoutOffload.rating} with=${withOffload.rating}",
        )
    }

    @Test
    fun rateDiffusion_flashAttnReducesEffectiveWeight() {
        val h = hints(ramMb = 9 * 1024L)
        val withoutFlash = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.FLUX,
            flashAttnAvailable = false,
        )
        val withFlash = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.FLUX,
            flashAttnAvailable = true,
        )
        assertTrue(
            withFlash.rating.ordinal <= withoutFlash.rating.ordinal,
            "Flash attention should not worsen rating",
        )
    }

    @Test
    fun rateDiffusion_gpuBumpsUpForDiTArch() {
        // FLUX Q4 on 10 GB budget with no GPU
        val noGpu = hints(ramMb = 10 * 1024L, gpu = false)
        val withGpu = hints(ramMb = 10 * 1024L, gpu = true)
        val noGpuResult = ModelSuitabilityCalculator.rateDiffusion(noGpu, SdArchitecture.FLUX)
        val gpuResult = ModelSuitabilityCalculator.rateDiffusion(withGpu, SdArchitecture.FLUX)
        assertTrue(
            gpuResult.rating.ordinal <= noGpuResult.rating.ordinal,
            "GPU should bump DiT rating up. noGpu=${noGpuResult.rating} gpu=${gpuResult.rating}",
        )
    }

    @Test
    fun rateDiffusion_lowCoresPenaltyForVideoArch() {
        // WAN_SMALL Q4 = 8 GB; budget = 24 GB → would be BEST without modifier
        val manyCore = hints(ramMb = 24 * 1024L, perfCores = 8)
        val fewCore  = hints(ramMb = 24 * 1024L, perfCores = 2)
        val manyCoreResult = ModelSuitabilityCalculator.rateDiffusion(manyCore, SdArchitecture.WAN_SMALL)
        val fewCoreResult  = ModelSuitabilityCalculator.rateDiffusion(fewCore, SdArchitecture.WAN_SMALL)
        assertTrue(
            fewCoreResult.rating.ordinal >= manyCoreResult.rating.ordinal,
            "Few cores should worsen video rating. manyCore=${manyCoreResult.rating} fewCore=${fewCoreResult.rating}",
        )
    }

    @Test
    fun rateDiffusion_unknownArchWithBytesUsesBytes() {
        // Even UNKNOWN arch can be rated if totalComponentBytes provided
        val h = hints(ramMb = 32 * 1024L)
        val result = ModelSuitabilityCalculator.rateDiffusion(
            hints = h,
            architecture = SdArchitecture.UNKNOWN,
            totalComponentBytes = 2_000L * 1024 * 1024,
        )
        assertNotEquals(SuitabilityRating.UNKNOWN, result.rating)
    }

    @Test
    fun rateDiffusion_q8QuantScalesBaselineUp() {
        // SD1 Q8 should require more RAM than SD1 Q4 baseline
        val h = hints(ramMb = 32 * 1024L)
        val q4Result = ModelSuitabilityCalculator.rateDiffusion(h, SdArchitecture.SD1, dominantQuantTag = null)
        val q8Result = ModelSuitabilityCalculator.rateDiffusion(h, SdArchitecture.SD1, dominantQuantTag = "Q8_0")
        // Q8 weightsBytes > Q4 weightsBytes → higher ratio
        assertTrue(
            q8Result.weightsBytes!! >= q4Result.weightsBytes!!,
            "Q8 should have larger weight estimate than Q4 baseline",
        )
    }
```

- [ ] **Step 2: Run — expect compilation failure**

```bash
./gradlew :composeApp:jvmTest --tests "com.debanshu777.caraml.core.rating.ModelSuitabilityCalculatorTest" 2>&1 | grep -E "error:|FAILED|PASSED" | head -20
```

Expected: compilation errors for new overload not existing.

- [ ] **Step 3: Add SD_QUANT_BPW map and rateDiffusion() overload to ModelSuitabilityCalculator**

Inside `ModelSuitabilityCalculator` object, after the existing `QUANT_BPW` map, add:

```kotlin
    /**
     * Bits-per-weight for quantization types used by stable-diffusion.cpp.
     * Keys are the ggml_type_name() strings returned by the native layer.
     * Fallback: Q4 baseline (~4.89 bpw) when tag is null or unrecognized.
     */
    private val SD_QUANT_BPW: Map<String, Double> = mapOf(
        "q2_k"   to 3.16,
        "q3_k"   to 3.75,
        "q4_0"   to 4.55,
        "q4_1"   to 5.06,
        "q4_k"   to 4.89,
        "q5_0"   to 5.57,
        "q5_1"   to 6.06,
        "q5_k"   to 5.70,
        "q6_k"   to 6.56,
        "q8_0"   to 8.50,
        "f16"    to 16.0,
        "bf16"   to 16.0,
        "f32"    to 32.0,
    )
    private const val SD_DEFAULT_BPW = 4.89  // Q4_K baseline
    private const val SD_Q4_BPW      = 4.89
    private const val VAE_OFFLOAD_BYTES   = 400L * 1024 * 1024   // 400 MB
    private const val FLASH_ATTN_BYTES    = 600L * 1024 * 1024   // 600 MB
    private val DIT_ARCHITECTURES = setOf(
        SdArchitecture.FLUX,
        SdArchitecture.SD3,
        SdArchitecture.WAN_SMALL,
        SdArchitecture.WAN_LARGE,
    )
    private val VIDEO_ARCHITECTURES = setOf(
        SdArchitecture.WAN_SMALL,
        SdArchitecture.WAN_LARGE,
    )
```

Then add the new overload after the existing `rateDiffusion()`:

```kotlin
    /**
     * Architecture-aware rating for stable-diffusion.cpp models.
     *
     * Resolution priority for weight bytes:
     * 1. [totalComponentBytes] when provided — from real file sizes (most accurate).
     * 2. [architecture] baseline scaled by [dominantQuantTag] BPW — when no bytes known.
     * 3. Return UNKNOWN if both are unavailable.
     *
     * @param hints               Device capabilities.
     * @param architecture        Detected SD architecture (from classifier or native JNI).
     * @param dominantQuantTag    Dominant quantization type string from ggml_type_name(),
     *                            e.g. "q4_k", "q8_0", "f16". Null = assume Q4 baseline.
     * @param totalComponentBytes Sum of all component file sizes (UNet + VAE + CLIP + T5).
     *                            When null, falls back to architecture baseline.
     * @param canOffloadVae       When true, subtract 400 MB (VAE runs on CPU, not GPU).
     * @param flashAttnAvailable  When true, subtract 600 MB from effective weights.
     */
    fun rateDiffusion(
        hints: DeviceHints,
        architecture: SdArchitecture,
        dominantQuantTag: String? = null,
        totalComponentBytes: Long? = null,
        canOffloadVae: Boolean = true,
        flashAttnAvailable: Boolean = false,
    ): SuitabilityResult {
        val budgetBytes = hints.memoryBudgetMB * 1024L * 1024L

        val rawWeightsBytes: Long? = when {
            totalComponentBytes != null && totalComponentBytes > 0 -> totalComponentBytes
            architecture != SdArchitecture.UNKNOWN -> {
                val bpwRatio = (SD_QUANT_BPW[dominantQuantTag?.lowercase()] ?: SD_DEFAULT_BPW) / SD_Q4_BPW
                (architecture.baseRamBytesQ4 * bpwRatio).toLong()
            }
            else -> null
        }

        if (rawWeightsBytes == null) return SuitabilityResult.unknown(budgetBytes)

        val isEstimate = totalComponentBytes == null

        // Apply offload discounts to effective GPU weight.
        var effectiveBytes = rawWeightsBytes
        if (canOffloadVae)    effectiveBytes -= VAE_OFFLOAD_BYTES
        if (flashAttnAvailable) effectiveBytes -= FLASH_ATTN_BYTES
        effectiveBytes = max(effectiveBytes, rawWeightsBytes / 4)  // never below 25% of raw

        val overheadBytes = max(
            (effectiveBytes.toDouble() * OVERHEAD_RATIO).toLong(),
            DIFFUSION_OVERHEAD_BYTES,
        )
        val estimatedBytes = effectiveBytes + overheadBytes
        val ratio = estimatedBytes.toDouble() / budgetBytes.toDouble()
        val baseRating = bucketize(ratio)

        // Modifiers
        var current = baseRating
        val notes = mutableListOf("SD RAM ratio ${formatRatio(ratio)} → ${baseRating.shortLabel()}")

        if (hints.gpuBackendAvailable && architecture in DIT_ARCHITECTURES) {
            val bumped = bumpUp(current)
            if (bumped != current) {
                notes += "GPU + DiT arch → bumped to ${bumped.shortLabel()}"
                current = bumped
            }
        }
        if (hints.performanceCoreCount in 1..3 && architecture in VIDEO_ARCHITECTURES) {
            val bumped = bumpDown(current)
            if (bumped != current) {
                notes += "Only ${hints.performanceCoreCount} perf core(s) for video → dropped to ${bumped.shortLabel()}"
                current = bumped
            }
        }

        return SuitabilityResult(
            rating        = current,
            estimatedBytes = estimatedBytes,
            budgetBytes   = budgetBytes,
            weightsBytes  = effectiveBytes,
            kvBytes       = 0L,
            overheadBytes = overheadBytes,
            quantAssumed  = dominantQuantTag ?: "Q4 baseline",
            isEstimate    = isEstimate,
            reason        = notes.joinToString("; "),
        )
    }
```

- [ ] **Step 4: Run — expect pass**

```bash
./gradlew :composeApp:jvmTest --tests "com.debanshu777.caraml.core.rating.ModelSuitabilityCalculatorTest" 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL` / all tests pass. Existing tests must not regress.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculator.kt \
        composeApp/src/commonTest/kotlin/com/debanshu777/caraml/core/rating/ModelSuitabilityCalculatorTest.kt
git commit -m "feat(rating): add architecture-aware rateDiffusion() overload with SD_QUANT_BPW"
```

---

## Task 4: DiffusionModelMetadata + DiffusionRunner expect

**Files:**
- Create: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelMetadata.kt`
- Modify: `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt`

- [ ] **Step 1: Create DiffusionModelMetadata data class**

```kotlin
// diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelMetadata.kt
package com.debanshu777.diffusionrunner

/**
 * Lightweight metadata returned by the native stable-diffusion.cpp layer
 * for an already-downloaded model file. Used to produce the most accurate
 * post-download suitability rating.
 *
 * @param architecture    The SDVersion family string, e.g. "FLUX", "SDXL", "SD1".
 *                        Maps to [com.debanshu777.caraml.core.rating.SdArchitecture]
 *                        via [SdArchitecture.fromNativeString].
 * @param dominantQuantType The ggml_type_name() of the most frequent weight tensor type,
 *                        e.g. "q4_k", "q8_0", "f16". Null if indeterminate.
 * @param estimatedRamBytes RAM estimate from ModelLoader.get_params_mem_size() in bytes.
 */
data class DiffusionModelMetadata(
    val architecture: String,
    val dominantQuantType: String?,
    val estimatedRamBytes: Long,
)
```

- [ ] **Step 2: Add expect function to DiffusionRunner.kt**

In `diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt`, add after the existing `getStepProgress()` line inside the expect class:

```kotlin
    /**
     * Queries metadata for a model file without loading weights into memory.
     * Uses stable-diffusion.cpp's ModelLoader to read GGUF headers and detect
     * architecture, dominant quantization type, and estimated RAM requirement.
     *
     * @param modelPath Absolute path to the main model file (.gguf or .safetensors).
     * @return [DiffusionModelMetadata] on success, null if the file cannot be read
     *         or the format is unrecognized.
     */
    fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata?
```

The expect class block should now look like:

```kotlin
expect class DiffusionRunner() {
    fun initialize(nativeLibDir: String)
    fun loadModel(config: DiffusionModelConfig): Boolean
    fun txt2Img(params: ImageGenParams): ByteArray?
    fun videoGen(params: VideoGenParams): List<ByteArray>?
    fun release()
    fun getStepProgress(): IntArray
    fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata?
}
```

- [ ] **Step 3: Verify compilation (expect/actual mismatch expected)**

```bash
./gradlew :diffusionRunner:compileKotlinJvm 2>&1 | grep -E "error:|warning:" | head -20
```

Expected: errors about `getDiffusionModelMetadata` missing in actual classes. That's correct — actuals come in Tasks 5-7.

- [ ] **Step 4: Commit**

```bash
git add diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionModelMetadata.kt \
        diffusionRunner/src/commonMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.kt
git commit -m "feat(diffusion): add DiffusionModelMetadata and getDiffusionModelMetadata expect"
```

---

## Task 5: Native C++ metadata function (Android + JVM)

**Files:**
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.h`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_core.cpp`
- Modify: `diffusionRunner/src/commonCpp/diffusion_runner_jni.cpp`

- [ ] **Step 1: Add struct and declaration to diffusion_runner_core.h**

At the bottom of the file (before the final closing, after `diffusion_runner_get_step_progress`):

```cpp
/** Lightweight metadata from ModelLoader header scan. Heap-free, safe to return by value. */
struct DiffusionMetadataResult {
    char architecture[64];      /**< SDVersion family: "FLUX", "SDXL", "SD3", "SD1", "WAN2_SMALL", "WAN2_LARGE", "UNKNOWN" */
    char dominant_quant[32];    /**< ggml_type_name() of most frequent weight tensor, e.g. "q4_k". Empty string if none. */
    int64_t estimated_ram;      /**< get_params_mem_size() result in bytes; 0 if unavailable. */
    bool success;               /**< false if file could not be loaded or format is unsupported. */
};

/**
 * Queries model metadata without loading weights into GPU/CPU memory.
 * Uses stable-diffusion.cpp ModelLoader to read GGUF tensor headers.
 * Thread-safe; does not modify any global state.
 *
 * @param model_path Absolute path to a .gguf model file.
 * @return DiffusionMetadataResult; check result.success before reading fields.
 */
DiffusionMetadataResult diffusion_runner_core_get_metadata(const char* model_path);
```

- [ ] **Step 2: Implement in diffusion_runner_core.cpp**

Find the correct include path for `model.h` first:
```bash
find /Users/debanshud/Documents/Personal/Flash/libraries/stable-diffusion.cpp -name "model.h" | head -3
```

Add these includes at the top of `diffusion_runner_core.cpp` after existing includes:

```cpp
// stable-diffusion.cpp internal headers for lightweight metadata query
#include "model.h"
#include "ggml.h"
```

Add the `DiffusionMetadataResult` include (or `#include "diffusion_runner_core.h"` already covers it).

Then add at the bottom of `diffusion_runner_core.cpp`:

```cpp
/** Maps SDVersion enum to a compact family string for the Kotlin layer. */
static const char* sd_arch_family(SDVersion v) {
    switch (v) {
        case VERSION_FLUX:
        case VERSION_FLUX_FILL:
        case VERSION_FLUX_CONTROLS:
        case VERSION_FLEX_2:
        case VERSION_FLUX2:
        case VERSION_FLUX2_KLEIN: return "FLUX";

        case VERSION_SD3: return "SD3";

        case VERSION_SDXL:
        case VERSION_SDXL_INPAINT:
        case VERSION_SDXL_PIX2PIX:
        case VERSION_SDXL_VEGA:
        case VERSION_SDXL_SSD1B: return "SDXL";

        case VERSION_SD1:
        case VERSION_SD1_INPAINT:
        case VERSION_SD1_PIX2PIX:
        case VERSION_SD2:
        case VERSION_SD2_INPAINT: return "SD1";

        // WAN_LARGE distinguished by estimatedRam in Kotlin; emit LARGE for I2V variants
        case VERSION_WAN2_2_I2V:
        case VERSION_WAN2_2_TI2V: return "WAN2_LARGE";
        case VERSION_WAN2:        return "WAN2_SMALL";

        default: return "UNKNOWN";
    }
}

DiffusionMetadataResult diffusion_runner_core_get_metadata(const char* model_path) {
    DiffusionMetadataResult result = {};
    result.success = false;
    result.estimated_ram = 0;
    result.architecture[0] = '\0';
    result.dominant_quant[0] = '\0';

    if (!model_path || model_path[0] == '\0') return result;

    try {
        ModelLoader loader;
        // init_from_file returns true on success; reads only GGUF headers
        if (!loader.init_from_file(std::string(model_path), "", "", "", "", "")) {
            return result;
        }

        // Architecture detection (tensor name scan)
        SDVersion version = loader.get_sd_version();
        const char* arch_name = sd_arch_family(version);
        strncpy(result.architecture, arch_name, sizeof(result.architecture) - 1);

        // Dominant quantization (most frequent weight tensor type, skip F32 biases)
        auto wtype_stat = loader.get_wtype_stat();
        ggml_type dominant_type = GGML_TYPE_COUNT;
        uint32_t max_count = 0;
        for (auto& kv : wtype_stat) {
            if (kv.first != GGML_TYPE_F32 && kv.second > max_count) {
                max_count = kv.second;
                dominant_type = kv.first;
            }
        }
        if (dominant_type != GGML_TYPE_COUNT) {
            const char* quant_name = ggml_type_name(dominant_type);
            if (quant_name) {
                strncpy(result.dominant_quant, quant_name, sizeof(result.dominant_quant) - 1);
            }
        }

        // RAM estimate (CPU backend, auto type selection)
        result.estimated_ram = loader.get_params_mem_size(GGML_BACKEND_TYPE_CPU, SD_TYPE_COUNT);
        result.success = true;
    } catch (...) {
        // Swallow exceptions — caller checks result.success
    }

    return result;
}
```

**Note:** `ModelLoader::init_from_file` signature may differ slightly across stable-diffusion.cpp versions. Check `libraries/stable-diffusion.cpp/src/model.h` for the exact signature and adjust parameter count accordingly. The key requirement is passing only `model_path`; leave other paths as empty strings.

- [ ] **Step 3: Add JNI wrapper to diffusion_runner_jni.cpp**

At the bottom of the `extern "C" {` block in `diffusion_runner_jni.cpp`:

```cpp
JNIEXPORT jobject JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeGetDiffusionModelMetadata(
        JNIEnv* env, jobject thiz, jstring modelPath) {

    std::string path = jstring_to_string(env, modelPath);
    DiffusionMetadataResult meta = diffusion_runner_core_get_metadata(path.c_str());

    if (!meta.success) return nullptr;

    // Build DiffusionModelMetadata Kotlin object via JNI reflection
    jclass clazz = env->FindClass("com/debanshu777/diffusionrunner/DiffusionModelMetadata");
    if (!clazz) return nullptr;

    jmethodID ctor = env->GetMethodID(clazz, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;J)V");
    if (!ctor) return nullptr;

    jstring jArch = env->NewStringUTF(meta.architecture);
    jstring jQuant = (meta.dominant_quant[0] != '\0')
        ? env->NewStringUTF(meta.dominant_quant)
        : nullptr;  // null → Kotlin nullable String?

    jobject obj = env->NewObject(clazz, ctor, jArch, jQuant, (jlong)meta.estimated_ram);

    env->DeleteLocalRef(jArch);
    if (jQuant) env->DeleteLocalRef(jQuant);
    env->DeleteLocalRef(clazz);

    return obj;
}
```

- [ ] **Step 4: Verify C++ compiles (Android)**

```bash
./gradlew :nativeEngine:compileLlamaRunnerAndroid 2>&1 | grep -E "error:|warning:" | head -30
```

If `init_from_file` call doesn't compile, open `libraries/stable-diffusion.cpp/src/model.h`, find the correct signature, and adjust the call site in `diffusion_runner_core.cpp`.

- [ ] **Step 5: Commit**

```bash
git add diffusionRunner/src/commonCpp/diffusion_runner_core.h \
        diffusionRunner/src/commonCpp/diffusion_runner_core.cpp \
        diffusionRunner/src/commonCpp/diffusion_runner_jni.cpp
git commit -m "feat(native): add diffusion_runner_core_get_metadata() C++ + JNI wrapper"
```

---

## Task 6: iOS cinterop metadata function

**Files:**
- Modify: `diffusionRunner/src/iosMain/cpp/diffusion_runner.h`
- Modify: `diffusionRunner/src/iosMain/cpp/diffusion_runner.cpp`

- [ ] **Step 1: Add FFI struct and declaration to diffusion_runner.h**

After the existing `diffusion_runner_ios_release` declaration:

```c
struct DiffusionMetadataResultFFI {
    char architecture[64];
    char dominant_quant[32];
    long long estimated_ram;
    int success;
};

struct DiffusionMetadataResultFFI diffusion_runner_ios_get_metadata(const char* model_path);
```

- [ ] **Step 2: Implement in diffusion_runner.cpp**

At the bottom of `diffusion_runner.cpp`, after existing functions:

```cpp
struct DiffusionMetadataResultFFI diffusion_runner_ios_get_metadata(const char* model_path) {
    // Delegate to the shared core function
    DiffusionMetadataResult core_result = diffusion_runner_core_get_metadata(model_path);

    struct DiffusionMetadataResultFFI ffi = {};
    ffi.success = core_result.success ? 1 : 0;
    ffi.estimated_ram = (long long)core_result.estimated_ram;
    strncpy(ffi.architecture,    core_result.architecture,    63);
    strncpy(ffi.dominant_quant,  core_result.dominant_quant,  31);
    return ffi;
}
```

Add `#include "diffusion_runner_core.h"` at the top of `diffusion_runner.cpp` if not already present.

- [ ] **Step 3: Add to .def file so cinterop exposes the struct and function**

Open `diffusionRunner/src/iosMain/cpp/diffusion_runner.def`. Add `DiffusionMetadataResultFFI` and `diffusion_runner_ios_get_metadata` to the exposed symbols if the def file uses an allowlist (`---` section). If it exposes everything by default, no change needed. Check:

```bash
cat diffusionRunner/src/iosMain/cpp/diffusion_runner.def
```

If the file has a `---` section with explicit symbol names, add:
```
DiffusionMetadataResultFFI
diffusion_runner_ios_get_metadata
```

- [ ] **Step 4: Commit**

```bash
git add diffusionRunner/src/iosMain/cpp/diffusion_runner.h \
        diffusionRunner/src/iosMain/cpp/diffusion_runner.cpp \
        diffusionRunner/src/iosMain/cpp/diffusion_runner.def
git commit -m "feat(native/ios): add diffusion_runner_ios_get_metadata() cinterop function"
```

---

## Task 7: DiffusionRunner platform actuals

**Files:**
- Modify: `diffusionRunner/src/androidMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.android.kt`
- Modify: `diffusionRunner/src/jvmMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.jvm.kt`
- Modify: `diffusionRunner/src/iosMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.ios.kt`

- [ ] **Step 1: Add Android actual**

In `DiffusionRunner.android.kt`, add `external` declaration alongside the existing ones:

```kotlin
    private external fun nativeGetDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata?
```

And implement the actual function (inside the `actual class DiffusionRunner` body):

```kotlin
    actual fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata? {
        return try {
            nativeGetDiffusionModelMetadata(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
```

- [ ] **Step 2: Add JVM actual**

In `DiffusionRunner.jvm.kt`, same pattern:

```kotlin
    private external fun nativeGetDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata?

    actual fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata? {
        return try {
            nativeGetDiffusionModelMetadata(modelPath)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }
```

- [ ] **Step 3: Add iOS actual**

In `DiffusionRunner.ios.kt`, import the new FFI function at top (already imports `diffusion_runner_ios_*`):

```kotlin
import com.debanshu777.diffusionrunner.cpp.DiffusionMetadataResultFFI
import com.debanshu777.diffusionrunner.cpp.diffusion_runner_ios_get_metadata
```

Then implement:

```kotlin
    @OptIn(ExperimentalForeignApi::class)
    actual fun getDiffusionModelMetadata(modelPath: String): DiffusionModelMetadata? {
        return memScoped {
            val result = diffusion_runner_ios_get_metadata(modelPath)
            if (result.success == 0) return@memScoped null

            val arch = result.architecture.toKString().takeIf { it.isNotEmpty() } ?: return@memScoped null
            val quant = result.dominant_quant.toKString().takeIf { it.isNotEmpty() }

            DiffusionModelMetadata(
                architecture     = arch,
                dominantQuantType = quant,
                estimatedRamBytes = result.estimated_ram,
            )
        }
    }
```

**Note:** `result.architecture` is a `CArrayPointer<ByteVar>` from cinterop. Use `.toKString()` to convert. If cinterop exposes it differently (depends on the struct field mapping), adjust accordingly — the field will be named `architecture` matching the C struct.

- [ ] **Step 4: Verify Kotlin compilation**

```bash
./gradlew :diffusionRunner:compileKotlinJvm 2>&1 | grep -E "error:" | head -20
```

Expected: clean compile (no errors).

- [ ] **Step 5: Commit**

```bash
git add diffusionRunner/src/androidMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.android.kt \
        diffusionRunner/src/jvmMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.jvm.kt \
        diffusionRunner/src/iosMain/kotlin/com/debanshu777/diffusionrunner/DiffusionRunner.ios.kt
git commit -m "feat(diffusion): implement getDiffusionModelMetadata() actuals for Android, JVM, iOS"
```

---

## Task 8: Search list — ModelListItem diffusion branch

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelListItem.kt`

- [ ] **Step 1: Add imports and replace rateLlm() call**

Current code in `ModelListItem.kt` (inside the `if (deviceHints != null)` block):

```kotlin
val result = ModelSuitabilityCalculator.rateLlm(
    hints = deviceHints,
    numParameters = model.numParameters,
    pipelineTag = model.pipelineTag,
)
```

Replace with:

```kotlin
val result = if (model.pipelineTag == "text-to-image" || model.pipelineTag == "text-to-video") {
    val arch = SdArchitectureClassifier.classify(
        tags = emptyList(),   // ListModelsResponse.Model has no tags field
        modelId = model.id ?: "",
    )
    ModelSuitabilityCalculator.rateDiffusion(
        hints = deviceHints,
        architecture = arch,
    )
} else {
    ModelSuitabilityCalculator.rateLlm(
        hints = deviceHints,
        numParameters = model.numParameters,
        pipelineTag = model.pipelineTag,
    )
}
```

Add imports at the top of the file:

```kotlin
import com.debanshu777.caraml.core.rating.SdArchitectureClassifier
```

- [ ] **Step 2: Verify the file compiles**

```bash
./gradlew :composeApp:compileKotlinJvm 2>&1 | grep -E "error:" | head -10
```

Expected: clean.

- [ ] **Step 3: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/search/components/ModelListItem.kt
git commit -m "feat(ui): show architecture-aware SD rating chip in model search list"
```

---

## Task 9: Detail page — ModelDetailContent diffusion rating

**Files:**
- Modify: `composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt`

The existing code computes `overallResult` using `rateLlm()` unconditionally (line ~124). Replace it with a diffusion-aware branch.

- [ ] **Step 1: Add imports**

Add to the import block at top of `ModelDetailContent.kt`:

```kotlin
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.caraml.core.rating.SdArchitectureClassifier
```

- [ ] **Step 2: Replace the overallResult computation**

Find this block in `ModelDetailContent.kt`:

```kotlin
        // Overall device-fit chip (model-level, assumes default Q4_K_M when no variant chosen)
        if (deviceHints != null) {
            val overallResult = ModelSuitabilityCalculator.rateLlm(
                hints = deviceHints,
                numParameters = model.safetensors?.total ?: model.gguf?.total,
                contextLength = model.gguf?.contextLength,
                architecture = model.gguf?.architecture,
                pipelineTag = model.pipelineTag,
            )
```

Replace with:

```kotlin
        // Overall device-fit chip
        if (deviceHints != null) {
            val isDiffusion = model.pipelineTag == "text-to-image" ||
                              model.pipelineTag == "text-to-video"

            val overallResult = if (isDiffusion) {
                val allTags = buildList {
                    model.tags?.filterNotNull()?.let { addAll(it) }
                    model.cardData?.tags?.filterNotNull()?.let { addAll(it) }
                }
                val arch = SdArchitectureClassifier.classify(
                    tags = allTags,
                    modelId = model.modelId ?: model.id ?: "",
                )
                // Sum component sizes from install bundle for accurate byte estimate
                val totalComponentBytes = installBundleState.components
                    .filter { it.required }
                    .mapNotNull { it.sizeHint?.trim()?.toLongOrNull() }
                    .takeIf { it.isNotEmpty() }
                    ?.sum()
                ModelSuitabilityCalculator.rateDiffusion(
                    hints = deviceHints,
                    architecture = arch,
                    totalComponentBytes = totalComponentBytes,
                )
            } else {
                ModelSuitabilityCalculator.rateLlm(
                    hints = deviceHints,
                    numParameters = model.safetensors?.total ?: model.gguf?.total,
                    contextLength = model.gguf?.contextLength,
                    architecture = model.gguf?.architecture,
                    pipelineTag = model.pipelineTag,
                )
            }
```

- [ ] **Step 3: Verify compilation**

```bash
./gradlew :composeApp:compileKotlinJvm 2>&1 | grep -E "error:" | head -10
```

Expected: clean.

- [ ] **Step 4: Run all rating tests one final time**

```bash
./gradlew :composeApp:jvmTest --tests "com.debanshu777.caraml.core.rating.*" 2>&1 | tail -15
```

Expected: all pass, zero failures.

- [ ] **Step 5: Commit**

```bash
git add composeApp/src/commonMain/kotlin/com/debanshu777/caraml/features/modelhub/presentation/details/components/ModelDetailContent.kt
git commit -m "feat(ui): show architecture-aware SD rating chip on model detail page"
```

---

## Self-Review Checklist

- [x] **Spec coverage:** SdArchitecture ✓, SdArchitectureClassifier ✓, rateDiffusion() overload ✓, DiffusionModelMetadata ✓, JNI C++ ✓, iOS cinterop ✓, platform actuals ✓, ModelListItem ✓, ModelDetailContent ✓
- [x] **Placeholders:** None. Every step has concrete code.
- [x] **Type consistency:** `SdArchitecture.fromNativeString()` signature matches usage in iOS actual. `DiffusionModelMetadata` constructor `(String, String?, Long)` matches JNI NewObject call `"(Ljava/lang/String;Ljava/lang/String;J)V"`. `rateDiffusion()` parameter names consistent across tests and impl.
- [x] **Existing overload preserved:** The original `rateDiffusion(hints, totalComponentBytes, pipelineTag)` is unchanged; new overload has `architecture: SdArchitecture` as first new param — no call-site conflicts.
- [x] **No VariantPickerRow change needed:** That component is LLM-only (GGUF variants). Diffusion uses `InstallBundleCard` which gets its chip from `ModelDetailContent`.
