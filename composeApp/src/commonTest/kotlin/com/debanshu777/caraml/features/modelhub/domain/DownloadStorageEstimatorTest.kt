package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.Evidence
import com.debanshu777.caraml.core.recommendation.DiffusionComponentDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DownloadStorageEstimatorTest {
    private val estimator = DownloadStorageEstimator()

    @Test
    fun newSingleFileCountsFinalBytesAndSiblingTemporaryBytes() {
        val descriptor = descriptor(file("model.gguf", 100L))

        val result = assertIs<StorageRequirement.Ready>(
            estimator.estimate(
                descriptor,
                LocalDownloadInventory.Empty,
                layout(descriptor, freeBytes = gib(20)),
            ),
        )

        assertEquals(200L, result.perVolume.getValue("models").additionalBytes)
    }

    @Test
    fun replacementKeepsOldFinalAndNeedsOneNewGeneration() {
        val identity = file("model.gguf", 100L)
        val descriptor = descriptor(identity)
        val inventory = LocalDownloadInventory(
            listOf(LocalDownloadArtifact(identity.repositoryId, identity.path, finalBytes = 80L)),
        )

        val result = assertIs<StorageRequirement.Ready>(
            estimator.estimate(descriptor, inventory, layout(descriptor, gib(20))),
        )

        assertEquals(100L, result.perVolume.getValue("models").additionalBytes)
    }

    @Test
    fun partiallyPresentBundleCountsOnlyMissingFinalAndLargestConcurrentTemporary() {
        val first = file("model-00001-of-00002.gguf", 100L)
        val second = file("model-00002-of-00002.gguf", 60L)
        val descriptor = descriptor(first, second)
        val inventory = LocalDownloadInventory(
            listOf(LocalDownloadArtifact(first.repositoryId, first.path, finalBytes = first.sizeBytes)),
        )

        val result = assertIs<StorageRequirement.Ready>(
            estimator.estimate(descriptor, inventory, layout(descriptor, gib(20))),
        )

        assertEquals(120L, result.perVolume.getValue("models").additionalBytes)
    }

    @Test
    fun existingSiblingPartReducesOnlyItsAdditionalTemporaryGrowth() {
        val identity = file("model.gguf", 100L)
        val descriptor = descriptor(identity)
        val inventory = LocalDownloadInventory(
            listOf(LocalDownloadArtifact(identity.repositoryId, identity.path, partBytes = 40L)),
        )

        val result = assertIs<StorageRequirement.Ready>(
            estimator.estimate(descriptor, inventory, layout(descriptor, gib(20))),
        )

        assertEquals(160L, result.perVolume.getValue("models").additionalBytes)
    }

    @Test
    fun splitFilesystemsAreAdmittedIndependentlyRatherThanPooled() {
        val identity = file("model.gguf", 100L)
        val descriptor = descriptor(identity)
        val key = ArtifactStorageKey(identity.repositoryId, identity.path)
        val layout = DownloadStorageLayout(
            volumes = mapOf(
                "models" to StorageVolume(freeBytes = gib(20)),
                "cache" to StorageVolume(freeBytes = 550L),
            ),
            locations = mapOf(key to ArtifactStorageLocation(finalVolume = "models", temporaryVolume = "cache")),
        )

        val result = assertIs<StorageRequirement.Blocked>(
            estimator.estimate(descriptor, LocalDownloadInventory.Empty, layout),
        )

        assertEquals("cache", result.volumeId)
        assertEquals(100L, result.perVolume.getValue("models").additionalBytes)
        assertEquals(100L, result.perVolume.getValue("cache").additionalBytes)
    }

    @Test
    fun invalidOrOverflowingInventoryNeverBecomesZeroRequirement() {
        val identity = file("model.gguf", 100L)
        val descriptor = descriptor(identity)
        val invalid = LocalDownloadInventory(
            listOf(LocalDownloadArtifact(identity.repositoryId, identity.path, partBytes = -1L)),
        )

        assertIs<StorageRequirement.NeedsInformation>(
            estimator.estimate(descriptor, invalid, layout(descriptor, gib(20))),
        )
    }

    @Test
    fun diffusionComponentsKeepTheirOwnRepositoryAndRevisionIdentity() {
        val primary = file("model.safetensors", 100L)
        val vae = ModelFileIdentity(
            repositoryId = "org/vae",
            revision = "c".repeat(40),
            path = "vae.safetensors",
            sizeBytes = 60L,
            gitOid = "d".repeat(40),
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        val descriptor = DiffusionModelDescriptor(
            repositoryId = primary.repositoryId,
            revision = primary.revision,
            components = listOf(
                DiffusionComponentDescriptor(primary, role = null, required = true, isPrimary = true),
                DiffusionComponentDescriptor(vae, ComponentRole.VAE, required = true, isPrimary = false),
            ),
            mode = DiffusionMode.IMAGE,
            family = "test",
            architecture = SdArchitecture.SD1,
            quantizationDistribution = emptySet(),
            requiredComponentsPresent = true,
            requiredEngineFeatures = emptySet(),
            evidence = emptyList(),
        )
        val files = descriptor.components.map { it.file }
        val locations = files.associate {
            ArtifactStorageKey(it.repositoryId, it.path) to ArtifactStorageLocation("models", "models")
        }

        val result = assertIs<StorageRequirement.Ready>(
            estimator.estimate(
                descriptor,
                LocalDownloadInventory.Empty,
                DownloadStorageLayout(
                    volumes = mapOf("models" to StorageVolume(gib(20))),
                    locations = locations,
                ),
            ),
        )

        assertEquals(260L, result.perVolume.getValue("models").additionalBytes)
    }

    @Test
    fun duplicateDescriptorTargetNeedsInformation() {
        val identity = file("model.gguf", 100L)
        val descriptor = descriptor(identity, identity)

        assertIs<StorageRequirement.NeedsInformation>(
            estimator.estimate(descriptor, LocalDownloadInventory.Empty, layout(descriptor, gib(20))),
        )
    }
}

private fun descriptor(vararg files: ModelFileIdentity): ModelDescriptor = LlmModelDescriptor(
    repositoryId = "org/model",
    revision = "a".repeat(40),
    files = files.toList(),
    architecture = "llama",
    quantization = QuantizationEvidence.Known("Q4_K_M"),
    parameterCount = 1_000_000L,
    contextLimit = 4_096,
    transformerShape = null,
    ggufVersion = 3,
    requiredEngineFeatures = emptyList(),
    evidence = emptyList<Evidence>(),
)

private fun file(path: String, size: Long) = ModelFileIdentity(
    repositoryId = "org/model",
    revision = "a".repeat(40),
    path = path,
    sizeBytes = size,
    gitOid = "b".repeat(40),
    lfsOid = null,
    xetHash = null,
    evidence = emptyList(),
)

private fun layout(descriptor: ModelDescriptor, freeBytes: Long): DownloadStorageLayout {
    val files = (descriptor as LlmModelDescriptor).files
    return DownloadStorageLayout(
        volumes = mapOf("models" to StorageVolume(freeBytes)),
        locations = files.associate {
            ArtifactStorageKey(it.repositoryId, it.path) to ArtifactStorageLocation("models", "models")
        },
    )
}

private fun gib(value: Long): Long = value * 1024L * 1024L * 1024L
