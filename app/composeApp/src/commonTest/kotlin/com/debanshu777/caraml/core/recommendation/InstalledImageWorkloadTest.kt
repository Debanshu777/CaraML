package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class InstalledImageWorkloadTest {
    @Test
    fun turboRecommendationsReachAdmissionWithoutGenericTwentyStepOverride() {
        for ((id, steps) in mapOf("stabilityai/sd-turbo" to 2, "Green-Sky/SD-Turbo-GGUF" to 2, "stabilityai/sdxl-turbo" to 4)) {
            val workload = imageWorkload(id)
            assertEquals(steps, workload.steps, id)
            assertEquals(512, workload.width, id)
            assertEquals(512, workload.height, id)
        }
    }

    @Test
    fun zImageTurboRetainsItsOwnStepCountAndAspectRatio() {
        val workload = imageWorkload("leejet/Z-Image-Turbo-GGUF")
        assertEquals(8, workload.steps)
        assertEquals(1024, workload.width)
        assertEquals(512, workload.height)
        assertTrue(workload.offloadToCpu)
    }

    @Test
    fun speedPreferenceUsesOnlyExplicitlySupportedOneStepTurboBudgets() {
        for (id in listOf("stabilityai/sd-turbo", "stabilityai/sdxl-turbo")) {
            assertEquals(1, imageWorkload(id, OptimizationPriority.SPEED_EFFICIENCY).steps)
            assertEquals(4, imageWorkload(id, OptimizationPriority.QUALITY_CONTEXT).steps)
        }
        assertEquals(2, imageWorkload("stabilityai/sd-turbo", OptimizationPriority.BALANCED).steps)
        assertEquals(4, imageWorkload("stabilityai/sdxl-turbo", OptimizationPriority.BALANCED).steps)
        assertEquals(8, imageWorkload("leejet/Z-Image-Turbo-GGUF", OptimizationPriority.SPEED_EFFICIENCY).steps)
        assertEquals(20, imageWorkload("unknown/model", OptimizationPriority.SPEED_EFFICIENCY).steps)
    }

    @Test
    fun unknownImageMatchesChatDefaults() {
        val workload = imageWorkload("unknown/model")
        assertEquals(20, workload.steps)
        assertEquals(512, workload.width)
        assertEquals(512, workload.height)
    }

    private fun imageWorkload(id: String, priority: OptimizationPriority = OptimizationPriority.BALANCED) = assertIs<DiffusionWorkloadConfig>(
        InstalledModelWorkloadFactory().create(
            DiffusionModelDescriptor(
                repositoryId = id, revision = "test", components = emptyList(),
                mode = DiffusionMode.IMAGE, family = "test", quantizationDistribution = emptySet(),
                requiredComponentsPresent = true, requiredEngineFeatures = emptySet(), evidence = emptyList(),
            ),
            GenerationMode.Image,
            AppSettings(optimizationPriority = priority),
        ),
    )
}
