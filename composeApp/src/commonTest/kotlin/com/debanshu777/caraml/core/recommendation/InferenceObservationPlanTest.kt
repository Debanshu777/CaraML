package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class InferenceObservationPlanTest {
    @Test
    fun llmIdentityComesFromValidatedDescriptorNotMutableRoomMetadata() {
        val descriptor = task6LlmDescriptor()
        val plan = task6LlmPlan()
        val assessment = task6PlanAssessment(
            plan = plan,
            performance = task6LlmPerformance(8.0, Confidence.HIGH),
        )
        val request = LoadRequest(
            model = localModel(arch = null, filename = "downloaded-without-quantization.bin"),
            identity = descriptor.file,
            plan = plan,
            assessmentKey = "assessment",
            assessedPlans = AssessedPlans(values = listOf(assessment), assessmentKey = "assessment"),
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        )

        val observation = assertNotNull(
            request.toInferenceObservationPlan("native-engine-v1", InferenceObservationPhase.GENERATION),
        )

        assertEquals("llama", observation.key.architectureFamily)
        assertEquals("Q4_K_M", observation.key.quantizationFamily)
    }

    @Test
    fun diffusionBundleIdentityDoesNotDependOnPrimaryFilename() {
        val descriptor = task6DiffusionDescriptor()
        val plan = task6DiffusionPlan()
        val assessment = PlanAssessment(
            plan = plan,
            hostMemoryBytes = task6Range(100, 100, 100),
            gpuMemoryBytes = null,
            sharedMemoryBytes = null,
            storageBytes = task6Range(100, 100, 100),
            confidence = task6Confidence(),
            evidence = emptyList(),
            rawMemoryByPhase = MemoryPhaseEstimates(
                load = MemoryPoolEstimates(hostMemoryBytes = task6Range(20, 30, 40)),
            ),
        )
        val request = LoadRequest(
            model = localModel(arch = null, filename = "model.safetensors"),
            identity = descriptor.components.first().file,
            plan = plan,
            assessmentKey = "assessment",
            assessedPlans = AssessedPlans(values = listOf(assessment), assessmentKey = "assessment"),
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
        )

        val observation = assertNotNull(
            request.toInferenceObservationPlan("native-engine-v1", InferenceObservationPhase.LOAD),
        )

        assertEquals("SDXL", observation.key.architectureFamily)
        assertEquals("F16", observation.key.quantizationFamily)
        assertEquals(30.0, observation.prediction.hostMemoryBytes)
    }

    private fun localModel(arch: String?, filename: String) = LocalModelEntity(
        modelId = "owner/model",
        filename = filename,
        localPath = "/private/model",
        sizeBytes = 1_073_741_824L,
        downloadedAt = 1L,
        author = null,
        libraryName = null,
        pipelineTag = null,
        arch = arch,
    )
}
