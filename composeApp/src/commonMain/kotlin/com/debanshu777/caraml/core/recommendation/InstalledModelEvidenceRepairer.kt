package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.AppLogger
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

/** Operation-local result of verifying one exact installed catalog snapshot.
 * This is a handoff between preparation stages, never a persistent hash cache.
 * Strict request creation and the final load boundary still verify current bytes.
 */
class VerifiedInstalledModelArtifact internal constructor(
    internal val model: LocalModelEntity,
    internal val components: List<DownloadedComponentEntity>,
    internal val artifact: ResolvedLocalArtifact,
)

sealed interface EvidenceRepairResult {
    data class Ready(
        val descriptor: ModelDescriptor,
        internal val verifiedArtifact: VerifiedInstalledModelArtifact? = null,
    ) : EvidenceRepairResult
    data object NeedsNetwork : EvidenceRepairResult
    data class Rejected(val reasons: List<AssessmentReason>) : EvidenceRepairResult
}

class InstalledModelEvidenceRepairer internal constructor(
    private val publicationCoordinator: InstalledModelPublicationCoordinator,
    private val catalogSnapshot: suspend (String) -> InstalledCatalogSnapshot?,
    private val manifestSource: suspend (String) -> ArtifactManifest?,
    private val resolveArtifact: suspend (
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
        resolveArtifact = artifactResolver::resolve,
        evidenceCompareAndSet = evidenceRepository::compareAndSet,
        metadataSource = metadataSource,
        codec = codec,
        ggufMetadataInspector = ggufMetadataInspector,
        clock = clock,
    )

    suspend fun requireComplete(
        modelId: String,
        generationMode: GenerationMode,
    ): EvidenceRepairResult {
        val result = try {
            publicationCoordinator.coalesceRepair(modelId, generationMode.name) {
                requireCompleteOnce(modelId, generationMode)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            invalidMetadata()
        }
        AppLogger.i("ModelLoad") {
            "stage=evidence-repair mode=$generationMode outcome=${when (result) {
                is EvidenceRepairResult.Ready -> "READY"
                EvidenceRepairResult.NeedsNetwork -> "NEEDS_NETWORK"
                is EvidenceRepairResult.Rejected -> "REJECTED"
            }} reasons=${(result as? EvidenceRepairResult.Rejected)?.reasons?.joinToString(",") { it.name }.orEmpty()}"
        }
        return result
    }

    private suspend fun requireCompleteOnce(
        modelId: String,
        generationMode: GenerationMode,
    ): EvidenceRepairResult {
        val mode = generationMode.toBrowseMode()
        val captured = publicationCoordinator.withOwnerPublication(modelId) {
            captureVerified(modelId)
        } ?: return invalidMetadata()
        captured.textContainerFailure(mode)?.let { failure ->
            return fallbackUnlessBaselineChanged(captured, mode, failure)
        }
        val persisted = captured.completeExactDescriptor(mode)
        AppLogger.i("ModelLoad") {
            "stage=evidence-captured outcome=VERIFIED hasExactDescriptor=${persisted != null} components=${captured.identities.size}"
        }
        if (persisted != null) {
            val enriched = ggufMetadataInspector.enrich(persisted, captured.artifact)
                ?: return fallbackUnlessBaselineChanged(captured, mode, invalidMetadata())
            if (enriched == persisted) {
                return fallbackUnlessBaselineChanged(
                    captured,
                    mode,
                    captured.ready(persisted),
                )
            }
            return persistIfUnchanged(captured, mode, enriched)
        }

        // The publication and every artifact identity were verified above. A complete
        // local GGUF header can supply load-critical facts without a network lookup.
        if (mode == ModelHubBrowseMode.LanguageModels) {
            val target = captured.artifact.loadTarget as? VerifiedArtifactLoadTarget.File
                ?: return fallbackUnlessBaselineChanged(captured, mode, invalidMetadata())
            val localHeader = ggufMetadataInspector.inspect(target.path)
                ?: return fallbackUnlessBaselineChanged(captured, mode, invalidMetadata())
            localLlmDescriptor(captured, target, localHeader)?.let { local ->
                return persistIfUnchanged(captured, mode, local)
            }
        }

        AppLogger.i("ModelLoad") { "stage=evidence-lookup outcome=EXACT_LOOKUP_REQUIRED" }
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

    private fun localLlmDescriptor(
        captured: VerifiedRepairSnapshot,
        target: VerifiedArtifactLoadTarget.File,
        local: GgufLocalMetadata,
    ): LlmModelDescriptor? {
        val primary = captured.artifact.components.singleOrNull { it.localPath == target.path }
            ?.identity ?: return null
        val quantization = QuantizationParser.parseFilename(primary.path)
            .takeIf { it is QuantizationEvidence.Known } ?: return null
        val descriptor = LlmModelDescriptor(
            repositoryId = captured.modelId,
            revision = primary.revision,
            files = listOf(primary) + captured.identities.filterNot { it == primary },
            architecture = local.architecture,
            quantization = quantization,
            parameterCount = null,
            contextLimit = local.contextLimit,
            transformerShape = local.transformerShape,
            ggufVersion = local.version,
            requiredEngineFeatures = emptyList(),
            evidence = listOf(Evidence(
                AssessmentReason.METADATA_VALIDATED,
                Confidence.HIGH,
                "compatibility:local-gguf-header",
            )),
        )
        return descriptor.takeIf { it.hasCompleteLocalCompatibilityMetadata() && it.isExactFor(captured, ModelHubBrowseMode.LanguageModels) }
    }

    private suspend fun captureVerified(modelId: String): VerifiedRepairSnapshot? {
        val baseline = readBaseline(modelId) ?: return null
        val resolution = resolveArtifact(
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
            captured.ready(decoded)
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
        val resolution = resolveArtifact(
            baseline.catalog.model,
            baseline.catalog.components,
            baseline.manifest,
        )
        val artifact = (resolution as? ArtifactIdentityResolution.Verified)?.artifact
            ?: return invalidMetadata()
        val current = VerifiedRepairSnapshot(baseline.catalog, baseline.manifest, artifact)
        current.textContainerFailure(mode)?.let { return it }
        val descriptor = current.completeExactDescriptor(mode) ?: return invalidMetadata()
        val enriched = ggufMetadataInspector.enrich(descriptor, artifact) ?: return invalidMetadata()
        return current.ready(enriched)
    }

    private fun VerifiedRepairSnapshot.textContainerFailure(
        mode: ModelHubBrowseMode,
    ): EvidenceRepairResult.Rejected? {
        if (mode != ModelHubBrowseMode.LanguageModels) return null
        val target = artifact.loadTarget as? VerifiedArtifactLoadTarget.File ?: return null
        return when (ggufMetadataInspector.recognizeContainer(target.path)) {
            GgufContainerRecognition.GGUF -> null
            GgufContainerRecognition.NON_GGUF ->
                EvidenceRepairResult.Rejected(listOf(AssessmentReason.UNSUPPORTED_FORMAT))
            GgufContainerRecognition.UNKNOWN -> invalidMetadata()
        }
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

        fun ready(descriptor: ModelDescriptor): EvidenceRepairResult.Ready = EvidenceRepairResult.Ready(
            descriptor,
            VerifiedInstalledModelArtifact(catalog.model, catalog.components.toList(), artifact),
        )

        fun matches(other: RepairBaseline): Boolean =
            catalog == other.catalog && manifest == other.manifest
    }
}
