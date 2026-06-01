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
