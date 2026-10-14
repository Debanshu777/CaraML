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
    @Test
    fun exactGemma4AssistantFilenameIsNotAStandaloneModel() {
        listOf("gemma4-assistant-Q4_K_M.gguf", "gemma-4-assistant-Q8_0.gguf").forEach { path ->
            assertEquals(ModelArtifactRole.UNVERIFIED, ModelArtifactClassifier.classify(path).role)
        }
    }

    @Test
    fun genericAssistantChatFilenameRemainsEligible() {
        listOf("coding-assistant-Q4_K_M.gguf", "gemma4-chat-assistant-Q4_K_M.gguf").forEach { path ->
            assertEquals(ModelArtifactRole.PRIMARY_MODEL, ModelArtifactClassifier.classify(path).role)
        }
    }

}
