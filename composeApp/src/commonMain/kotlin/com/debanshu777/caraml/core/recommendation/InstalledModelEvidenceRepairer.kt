package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.recommendation.storage.DecodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.EncodedModelEvidence
import com.debanshu777.caraml.core.recommendation.storage.InstalledEvidenceState
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.storage.catalog.InstalledCatalogSnapshot
import com.debanshu777.caraml.core.storage.catalog.InstalledModelCatalogDao
import com.debanshu777.caraml.core.storage.catalog.InstalledModelPublicationCoordinator
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceEntity
import com.debanshu777.caraml.core.storage.evidence.InstalledModelEvidenceRepository
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import kotlinx.coroutines.CancellationException

sealed interface EvidenceRepairResult {
    data class Ready(val descriptor: ModelDescriptor) : EvidenceRepairResult
    data object NeedsNetwork : EvidenceRepairResult
    data class Rejected(val reasons: List<AssessmentReason>) : EvidenceRepairResult
}

class InstalledModelEvidenceRepairer internal constructor(
    private val publicationCoordinator: InstalledModelPublicationCoordinator,
    private val catalogSnapshot: suspend (String) -> InstalledCatalogSnapshot?,
    private val manifestSource: suspend (String) -> ArtifactManifest?,
    private val resolvePersistedHub: suspend (
        LocalModelEntity,
        List<DownloadedComponentEntity>,
        ArtifactManifest,
    ) -> ArtifactIdentityResolution,
    private val evidenceCompareAndSet: suspend (
        String,
        InstalledModelEvidenceEntity?,
        EncodedModelEvidence,
        Long,
    ) -> Boolean,
    private val metadataSource: InstalledDescriptorMetadataSource,
    private val codec: PersistedModelEvidenceCodec,
    private val ggufMetadataInspector: GgufMetadataInspector,
    private val clock: () -> Long,
) {
    constructor(
        artifactResolver: LocalArtifactIdentityResolver,
        catalog: InstalledModelCatalogDao,
        evidenceRepository: InstalledModelEvidenceRepository,
        metadataSource: InstalledDescriptorMetadataSource,
        manifestSource: InstalledModelManifestSource,
        publicationCoordinator: InstalledModelPublicationCoordinator,
        codec: PersistedModelEvidenceCodec = PersistedModelEvidenceCodec(),
        ggufMetadataInspector: GgufMetadataInspector = GgufMetadataInspector(),
        clock: () -> Long,
    ) : this(
        publicationCoordinator = publicationCoordinator,
        catalogSnapshot = catalog::snapshotReady,
        manifestSource = manifestSource::invoke,
        resolvePersistedHub = artifactResolver::resolvePersistedHub,
        evidenceCompareAndSet = evidenceRepository::compareAndSet,
        metadataSource = metadataSource,
        codec = codec,
        ggufMetadataInspector = ggufMetadataInspector,
        clock = clock,
    )

    suspend fun requireComplete(
        modelId: String,
        generationMode: GenerationMode,
    ): EvidenceRepairResult = try {
        publicationCoordinator.coalesceRepair(modelId, generationMode.name) {
            requireCompleteOnce(modelId, generationMode)
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        invalidMetadata()
    }

    private suspend fun requireCompleteOnce(
        modelId: String,
        generationMode: GenerationMode,
    ): EvidenceRepairResult {
        val mode = generationMode.toBrowseMode()
        val captured = publicationCoordinator.withOwnerPublication(modelId) {
            captureVerified(modelId)
        } ?: return invalidMetadata()
        val persisted = captured.completeExactDescriptor(mode)
        if (persisted != null) {
            val enriched = ggufMetadataInspector.enrich(persisted, captured.artifact)
                ?: return fallbackUnlessBaselineChanged(captured, mode, invalidMetadata())
            if (enriched == persisted) {
                return fallbackUnlessBaselineChanged(
                    captured,
                    mode,
                    EvidenceRepairResult.Ready(persisted),
                )
            }
            return persistIfUnchanged(captured, mode, enriched)
        }

        return when (val lookup = metadataSource.findExact(modelId, mode, captured.identities)) {
            InstalledDescriptorLookup.RetryableUnavailable ->
                fallbackUnlessBaselineChanged(captured, mode, EvidenceRepairResult.NeedsNetwork)
            is InstalledDescriptorLookup.Rejected -> fallbackUnlessBaselineChanged(
                captured,
                mode,
                EvidenceRepairResult.Rejected(
                    lookup.reasons.ifEmpty { listOf(AssessmentReason.INVALID_METADATA) },
                ),
            )
            is InstalledDescriptorLookup.Ready -> {
                val enriched = ggufMetadataInspector.enrich(lookup.descriptor, captured.artifact)
                    ?: return fallbackUnlessBaselineChanged(captured, mode, invalidMetadata())
                persistIfUnchanged(captured, mode, enriched)
            }
        }
    }

    private suspend fun captureVerified(modelId: String): VerifiedRepairSnapshot? {
        val baseline = readBaseline(modelId) ?: return null
        val resolution = resolvePersistedHub(
            baseline.catalog.model,
            baseline.catalog.components,
            baseline.manifest,
        )
        val artifact = (resolution as? ArtifactIdentityResolution.Verified)?.artifact ?: return null
        return VerifiedRepairSnapshot(baseline.catalog, baseline.manifest, artifact)
    }

    private suspend fun readBaseline(modelId: String): RepairBaseline? {
        val catalog = catalogSnapshot(modelId)
            ?.takeIf { it.model.modelId == modelId && it.model.componentStatus == LocalModelEntity.STATUS_READY }
            ?: return null
        val manifest = manifestSource(modelId) ?: return null
        return RepairBaseline(catalog, manifest)
    }

    private suspend fun persistIfUnchanged(
        captured: VerifiedRepairSnapshot,
        mode: ModelHubBrowseMode,
        descriptor: ModelDescriptor,
    ): EvidenceRepairResult {
        if (!descriptor.isExactFor(captured, mode)) return invalidMetadata()
        val encoded = try {
            codec.encode(descriptor.requiredInstalledIdentities(), descriptor)
        } catch (_: IllegalArgumentException) {
            return invalidMetadata()
        }
        return publicationCoordinator.withOwnerPublication(captured.modelId) {
            val current = readBaseline(captured.modelId) ?: return@withOwnerPublication invalidMetadata()
            if (!captured.matches(current)) {
                return@withOwnerPublication currentReady(current, mode)
            }
            val replaced = evidenceCompareAndSet(
                captured.modelId,
                captured.catalog.evidence,
                encoded,
                clock(),
            )
            if (!replaced) {
                return@withOwnerPublication readBaseline(captured.modelId)
                    ?.let { currentReady(it, mode) }
                    ?: invalidMetadata()
            }
            val decoded = decode(encoded)?.descriptor
                ?.takeIf { it.isExactFor(captured, mode) }
                ?: return@withOwnerPublication invalidMetadata()
            EvidenceRepairResult.Ready(decoded)
        }
    }

    private suspend fun fallbackUnlessBaselineChanged(
        captured: VerifiedRepairSnapshot,
        mode: ModelHubBrowseMode,
        unchangedResult: EvidenceRepairResult,
    ): EvidenceRepairResult = publicationCoordinator.withOwnerPublication(captured.modelId) {
        val current = readBaseline(captured.modelId) ?: return@withOwnerPublication invalidMetadata()
        if (captured.matches(current)) unchangedResult else currentReady(current, mode)
    }

    private suspend fun currentReady(
        baseline: RepairBaseline,
        mode: ModelHubBrowseMode,
    ): EvidenceRepairResult {
        val resolution = resolvePersistedHub(
            baseline.catalog.model,
            baseline.catalog.components,
            baseline.manifest,
        )
        val artifact = (resolution as? ArtifactIdentityResolution.Verified)?.artifact
            ?: return invalidMetadata()
        val current = VerifiedRepairSnapshot(baseline.catalog, baseline.manifest, artifact)
        val descriptor = current.completeExactDescriptor(mode) ?: return invalidMetadata()
        val enriched = ggufMetadataInspector.enrich(descriptor, artifact) ?: return invalidMetadata()
        return EvidenceRepairResult.Ready(enriched)
    }

    private fun VerifiedRepairSnapshot.completeExactDescriptor(
        mode: ModelHubBrowseMode,
    ): ModelDescriptor? {
        val decoded = catalog.evidence?.let(::decode) ?: return null
        val descriptor = decoded.descriptor ?: return null
        return descriptor.takeIf {
            decoded.state == InstalledEvidenceState.COMPLETE &&
                decoded.artifactIdentities.hasSameExactInstalledIdentities(identities) &&
                it.isExactFor(this, mode)
        }
    }

    private fun ModelDescriptor.isExactFor(
        snapshot: VerifiedRepairSnapshot,
        mode: ModelHubBrowseMode,
    ): Boolean = repositoryId == snapshot.modelId && matchesBrowseMode(mode) &&
        requiredInstalledIdentities().hasSameExactInstalledIdentities(snapshot.identities)

    private fun decode(entity: InstalledModelEvidenceEntity): DecodedModelEvidence? = try {
        decode(
            EncodedModelEvidence(
                state = InstalledEvidenceState.valueOf(entity.evidenceState),
                schemaVersion = entity.schemaVersion,
                payload = entity.payload,
                sha256 = entity.sha256,
            ),
        )
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun decode(encoded: EncodedModelEvidence): DecodedModelEvidence? = try {
        codec.decode(encoded)
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun GenerationMode.toBrowseMode(): ModelHubBrowseMode = when (this) {
        GenerationMode.Text -> ModelHubBrowseMode.LanguageModels
        GenerationMode.Image -> ModelHubBrowseMode.DiffusionImage
        GenerationMode.Video -> ModelHubBrowseMode.DiffusionVideo
    }

    private fun invalidMetadata() =
        EvidenceRepairResult.Rejected(listOf(AssessmentReason.INVALID_METADATA))

    private data class RepairBaseline(
        val catalog: InstalledCatalogSnapshot,
        val manifest: ArtifactManifest,
    )

    private data class VerifiedRepairSnapshot(
        val catalog: InstalledCatalogSnapshot,
        val manifest: ArtifactManifest,
        val artifact: ResolvedLocalArtifact,
    ) {
        val modelId: String = catalog.model.modelId
        val identities: List<ModelFileIdentity> = artifact.components.map(ResolvedArtifactComponent::identity)

        fun matches(other: RepairBaseline): Boolean =
            catalog == other.catalog && manifest == other.manifest
    }
}
