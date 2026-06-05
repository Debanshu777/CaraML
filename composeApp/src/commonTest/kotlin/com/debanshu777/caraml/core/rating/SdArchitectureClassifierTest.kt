package com.debanshu777.caraml.core.rating

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    // --- Substring match via model ID (compound tag names) ---

    @Test
    fun classifiesSdxlFromCompoundModelId() {
        assertEquals(
            SdArchitecture.SDXL,
            SdArchitectureClassifier.classify(emptyList(), "stabilityai/stable-diffusion-xl-base-1.0"),
        )
    }

    @Test
    fun classifiesSd1FromCompoundModelId() {
        assertEquals(
            SdArchitecture.SD1,
            SdArchitectureClassifier.classify(emptyList(), "runwayml/stable-diffusion-v1-5"),
        )
    }

    @Test
    fun classifiesSd3FromCompoundModelId() {
        assertEquals(
            SdArchitecture.SD3,
            SdArchitectureClassifier.classify(emptyList(), "stabilityai/stable-diffusion-3-medium"),
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

    // --- isDistilled ---

    @Test
    fun isDistilled_sdTurbo() {
        assertTrue(SdArchitectureClassifier.isDistilled("stabilityai/sd-turbo"))
    }

    @Test
    fun isDistilled_sdxlTurbo() {
        assertTrue(SdArchitectureClassifier.isDistilled("stabilityai/sdxl-turbo"))
    }

    @Test
    fun isDistilled_fluxSchnell() {
        assertTrue(SdArchitectureClassifier.isDistilled("black-forest-labs/FLUX.1-schnell"))
    }

    @Test
    fun isDistilled_lcmLora() {
        assertTrue(SdArchitectureClassifier.isDistilled("latent-consistency/lcm-lora-sdv1-5"))
    }

    @Test
    fun isDistilled_ssd1b() {
        assertTrue(SdArchitectureClassifier.isDistilled("segmind/SSD-1B"))
    }

    @Test
    fun isDistilled_tinySd() {
        assertTrue(SdArchitectureClassifier.isDistilled("segmind/tiny-sd"))
    }

    @Test
    fun isDistilled_bkSdm() {
        assertTrue(SdArchitectureClassifier.isDistilled("nota-ai/bk-sdm-v2-tiny"))
    }

    @Test
    fun isNotDistilled_fluxDev() {
        assertFalse(SdArchitectureClassifier.isDistilled("black-forest-labs/FLUX.1-dev"))
    }

    @Test
    fun isNotDistilled_sdxlBase() {
        assertFalse(SdArchitectureClassifier.isDistilled("stabilityai/stable-diffusion-xl-base-1.0"))
    }

    @Test
    fun isNotDistilled_sd15() {
        assertFalse(SdArchitectureClassifier.isDistilled("runwayml/stable-diffusion-v1-5"))
    }

    @Test
    fun isDistilled_withTag() {
        assertTrue(SdArchitectureClassifier.isDistilled("some/model", listOf("turbo")))
    }
}
