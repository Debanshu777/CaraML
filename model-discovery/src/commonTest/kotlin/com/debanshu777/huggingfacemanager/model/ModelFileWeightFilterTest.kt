package com.debanshu777.huggingfacemanager.model

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelFileWeightFilterTest {

    @Test
    fun ggufOnlyAcceptsGguf() {
        val f = ModelFileWeightFilter.GgufOnly
        assertTrue(f.matchesRepoFilePath("model.gguf"))
        assertTrue(f.matchesRepoFilePath("a/MODEL.GGUF"))
        assertFalse(f.matchesRepoFilePath("weights.safetensors"))
        assertFalse(f.matchesRepoFilePath("model.ckpt"))
        assertFalse(f.matchesRepoFilePath("x.pth"))
    }

    @Test
    fun stableDiffusionAcceptsAllWeightFormats() {
        val f = ModelFileWeightFilter.StableDiffusionCppWeights
        assertTrue(f.matchesRepoFilePath("m.gguf"))
        assertTrue(f.matchesRepoFilePath("m.safetensors"))
        assertTrue(f.matchesRepoFilePath("m.ckpt"))
        assertTrue(f.matchesRepoFilePath("m.pth"))
        assertFalse(f.matchesRepoFilePath("readme.md"))
        assertFalse(f.matchesRepoFilePath(null))
    }
}
