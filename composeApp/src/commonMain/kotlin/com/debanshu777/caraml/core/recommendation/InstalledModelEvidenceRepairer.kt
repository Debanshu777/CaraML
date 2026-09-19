package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.recommendation.storage.DecodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface EvidenceRepairResult {
    data class Ready(val descriptor: ModelDescriptor) : EvidenceRepairResult
    data object NeedsNetwork : EvidenceRepairResult
    data class Rejected(val reasons: List<AssessmentReason>) : EvidenceRepairResult
}

class InstalledModelEvidenceRepairer(
    private val artifactResolver: LocalArtifactIdentityResolver,
    private val evidenceRepository: InstalledModelEvidenceRepository,
    private val metadataSource: InstalledDescriptorMetadataSource,
    private val codec: PersistedModelEvidenceCodec = PersistedModelEvidenceCodec(),
    private val ggufMetadataInspector: GgufMetadataInspector = GgufMetadataInspector(),
    private val clock: () -> Long,
) {
    private val repairMutex = Mutex()

    suspend fun requireComplete(
        model: LocalModelEntity,
        components: List<DownloadedComponentEntity>,
        generationMode: GenerationMode,
    ): EvidenceRepairResult = repairMutex.withLock {
        requireCompleteLocked(model, components, generationMode)
    }

    private suspend fun requireCompleteLocked(
        model: LocalModelEntity,
        components: List<DownloadedComponentEntity>,
        generationMode: GenerationMode,
    ): EvidenceRepairResult {
        val persisted = readPersistedEvidence(model.modelId)
        val resolution = try {
            artifactResolver.resolvePersistedHub(model, components)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return invalidMetadata()
        }
        val artifact = when (resolution) {
            is ArtifactIdentityResolution.Rejected -> return invalidMetadata()
            is ArtifactIdentityResolution.Verified -> resolution.artifact
        }
        val identities = artifact.components.map(ResolvedArtifactComponent::identity)
        val mode = generationMode.toBrowseMode()

        persisted?.takeIf { decoded ->
            decoded.state == InstalledEvidenceState.COMPLETE &&
                decoded.artifactIdentities.hasSameExactInstalledIdentities(identities) &&
                decoded.descriptor?.repositoryId == model.modelId &&
                decoded.descriptor.matchesBrowseMode(mode) &&
                decoded.descriptor.requiredInstalledIdentities().hasSameExactInstalledIdentities(identities)
        }?.descriptor?.let { descriptor ->
            val enriched = ggufMetadataInspector.enrich(descriptor, artifact) ?: return invalidMetadata()
            return if (enriched == descriptor) {
                EvidenceRepairResult.Ready(descriptor)
            } else {
                persistComplete(model, mode, identities, enriched)
            }
        }

        return when (val lookup = metadataSource.findExact(model.modelId, mode, identities)) {
            InstalledDescriptorLookup.RetryableUnavailable -> EvidenceRepairResult.NeedsNetwork
            is InstalledDescriptorLookup.Rejected -> EvidenceRepairResult.Rejected(
                lookup.reasons.ifEmpty { listOf(AssessmentReason.INVALID_METADATA) },
            )
            is InstalledDescriptorLookup.Ready -> {
                val enriched = ggufMetadataInspector.enrich(lookup.descriptor, artifact) ?: return invalidMetadata()
                persistComplete(model, mode, identities, enriched)
            }
        }
    }

    private suspend fun readPersistedEvidence(modelId: String): DecodedModelEvidence? {
        val encoded = try {
            evidenceRepository.get(modelId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return null
        } ?: return null
        return try {
            codec.decode(encoded)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private suspend fun persistComplete(
        model: LocalModelEntity,
        mode: ModelHubBrowseMode,
        identities: List<ModelFileIdentity>,
        descriptor: ModelDescriptor,
    ): EvidenceRepairResult {
        if (descriptor.repositoryId != model.modelId || !descriptor.matchesBrowseMode(mode) ||
            !descriptor.requiredInstalledIdentities().hasSameExactInstalledIdentities(identities)
        ) {
            return invalidMetadata()
        }
        return try {
            val encoded = codec.encode(descriptor.requiredInstalledIdentities(), descriptor)
            evidenceRepository.put(model.modelId, encoded, clock())
            val decoded = codec.decode(encoded)
            val exactDescriptor = decoded.descriptor
                ?.takeIf { decoded.state == InstalledEvidenceState.COMPLETE }
                ?: return invalidMetadata()
            EvidenceRepairResult.Ready(exactDescriptor)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            invalidMetadata()
        }
    }

    private fun GenerationMode.toBrowseMode(): ModelHubBrowseMode = when (this) {
        GenerationMode.Text -> ModelHubBrowseMode.LanguageModels
        GenerationMode.Image -> ModelHubBrowseMode.DiffusionImage
        GenerationMode.Video -> ModelHubBrowseMode.DiffusionVideo
    }

    private fun invalidMetadata() =
        EvidenceRepairResult.Rejected(listOf(AssessmentReason.INVALID_METADATA))
}
