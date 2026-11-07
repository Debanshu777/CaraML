package com.debanshu777.caraml.core.download.diffusion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SdCppModelSetupTest {
    @Test
    fun sdTurboDoesNotRequestNonexistentSdxlEncoder() {
        for (id in listOf("stabilityai/sd-turbo", "Green-Sky/SD-Turbo-GGUF")) {
            val setup = assertNotNull(getModelSetup(id))
            assertTrue(setup.selfContained)
            assertTrue(setup.components.isEmpty())
            assertEquals(0, setup.recommendedParams?.prediction)
            assertEquals("euler", setup.recommendedParams?.samplingMethod)
            assertEquals(6, setup.recommendedParams?.samplingScheduler)
            assertEquals(1f, setup.recommendedParams?.cfgScale)
        }
        assertTrue(assertNotNull(getModelSetup("stabilityai/sdxl-turbo"))
            .components.any { it.role == ComponentRole.CLIP_G })
    }

    @Test
    fun zImageTurboUsesEightStepsAndBaseRetainsGuidance() {
        val turbo = assertNotNull(getModelSetup("leejet/Z-Image-Turbo-GGUF"))
        assertEquals(8, turbo.recommendedParams?.steps)
        assertEquals(1f, turbo.recommendedParams?.cfgScale)
        assertEquals("euler", turbo.recommendedParams?.samplingMethod)
        assertEquals(turbo, getModelSetup("unsloth/Z-Image-Turbo-GGUF"))
        val base = assertNotNull(getModelSetup("unsloth/Z-Image-GGUF"))
        assertEquals(turbo.components, base.components)
        assertEquals(5f, base.recommendedParams?.cfgScale)
        assertNull(base.recommendedParams?.steps)
    }
}
