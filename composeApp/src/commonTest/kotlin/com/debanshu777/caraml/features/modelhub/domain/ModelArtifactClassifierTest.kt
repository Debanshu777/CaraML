package com.debanshu777.caraml.features.modelhub.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelArtifactClassifierTest {
    @Test
    fun projectorIsSupportingArtifactRatherThanModelVariant() {
        val result = ModelArtifactClassifier.classify("models/mmproj-model-f16.gguf")

        assertEquals(ModelArtifactRole.PROJECTOR, result.role)
        assertEquals(false, result.recommendationEligible)
    }

    @Test
    fun fastMtpWithoutCorroboratingRepositoryMetadataRemainsUnverified() {
        val result = ModelArtifactClassifier.classify("model-FastMTP-Q4_K_M.gguf")

        assertEquals(ModelArtifactRole.UNVERIFIED, result.role)
        assertEquals(false, result.recommendationEligible)
    }

    @Test
    fun repositoryWideFastMtpTagDoesNotCorroborateAnIndividualFileRole() {
        val result = ModelArtifactClassifier.classify(
            path = "model-FastMTP-Q4_K_M.gguf",
            repositoryTags = listOf("speculative-decoding", "gguf"),
        )

        assertEquals(ModelArtifactRole.UNVERIFIED, result.role)
        assertEquals(false, result.recommendationEligible)
    }

    @Test
    fun ordinaryQuantizedGgufIsEligibleAsPrimaryModel() {
        val result = ModelArtifactClassifier.classify("models/model-Q4_K_M.gguf")

        assertEquals(ModelArtifactRole.PRIMARY_MODEL, result.role)
        assertEquals(true, result.recommendationEligible)
    }
}
