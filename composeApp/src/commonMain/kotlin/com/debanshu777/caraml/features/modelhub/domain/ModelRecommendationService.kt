package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.DescriptorLimits
import com.debanshu777.caraml.core.recommendation.DeviceSnapshotProvider
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.DiffusionWorkloadConfig
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmWorkloadConfig
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.ModelAssessmentRepository
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationPolicy
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationSortKey
import com.debanshu777.caraml.core.recommendation.SuitabilityEngine
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.core.recommendation.WorkloadLimits
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock

internal interface RecommendationSnapshotSource {
    suspend fun captureInitial(): DeviceSnapshot
    suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot
}

private class DeviceRecommendationSnapshotSource(
    private val provider: DeviceSnapshotProvider,
) : RecommendationSnapshotSource {
    override suspend fun captureInitial(): DeviceSnapshot = provider.capture()
    override suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot =
        provider.refreshResources(previous)
}

internal interface RecommendationVariantEvaluator {
    suspend fun assess(
        descriptor: ModelDescriptor,
        snapshot: DeviceSnapshot,
        workload: WorkloadConfig,
    ): ModelAssessment

    fun rebuild(assessment: ModelAssessment, snapshot: DeviceSnapshot): ModelAssessment

    fun personalize(
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): PersonalizedRecommendation

    fun sortKey(
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): RecommendationSortKey
}

private class CachedRecommendationVariantEvaluator(
    private val repository: ModelAssessmentRepository,
    private val suitabilityEngine: SuitabilityEngine,
    private val policy: RecommendationPolicy,
) : RecommendationVariantEvaluator {
    override suspend fun assess(
        descriptor: ModelDescriptor,
        snapshot: DeviceSnapshot,
        workload: WorkloadConfig,
    ): ModelAssessment = repository.assess(descriptor, snapshot, workload)

    override fun rebuild(assessment: ModelAssessment, snapshot: DeviceSnapshot): ModelAssessment =
        suitabilityEngine.assemble(assessment.planAssessments, snapshot)

    override fun personalize(
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): PersonalizedRecommendation = repository.personalize(assessment, snapshot, profile)

    override fun sortKey(
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): RecommendationSortKey = policy.sortKey(assessment, snapshot, profile)
}

@OptIn(ExperimentalAtomicApi::class)
class ModelRecommendationService internal constructor(
    private val metadataSource: ModelMetadataSource,
    private val snapshotSource: RecommendationSnapshotSource,
    private val variantEvaluator: RecommendationVariantEvaluator,
    private val evaluationDispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    constructor(
        metadataSource: ModelMetadataSource,
        assessmentRepository: ModelAssessmentRepository,
        suitabilityEngine: SuitabilityEngine,
        recommendationPolicy: RecommendationPolicy,
        snapshotProvider: DeviceSnapshotProvider,
        evaluationDispatcher: CoroutineDispatcher = Dispatchers.Default,
        clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
    ) : this(
        metadataSource = metadataSource,
        snapshotSource = DeviceRecommendationSnapshotSource(snapshotProvider),
        variantEvaluator = CachedRecommendationVariantEvaluator(
            assessmentRepository,
            suitabilityEngine,
            recommendationPolicy,
        ),
        evaluationDispatcher = evaluationDispatcher,
        clock = clock,
    )

    private val enrichmentGate = Semaphore(MAX_METADATA_CONCURRENCY)
    private val activeSession = AtomicReference<RecommendationQuerySession?>(null)

    fun startQuery(
        queryId: String,
        models: Collection<ListModelsResponse.Model>,
        workload: WorkloadConfig,
    ): RecommendationQuerySession {
        val validQuery = queryId.isNotBlank() && queryId.length <= MAX_QUERY_ID_LENGTH &&
            queryId.none { it.isISOControl() }
        val collectionWithinLimit = models.size <= MAX_QUERY_MODELS
        val boundedModels = models.take(MAX_QUERY_MODELS)
        val seenRepositoryIds = mutableSetOf<String>()
        val candidates = boundedModels.mapIndexed { index, model ->
            sanitizeCandidate(model, index, seenRepositoryIds, validQuery && collectionWithinLimit && validWorkload(workload))
        }
        val initialStates = candidates.map { candidate ->
            val valid = candidate.repositoryId != null
            RecommendedModelUiState(
                sourceModel = candidate.model,
                repositoryId = candidate.repositoryId,
                descriptorState = when {
                    !valid -> DescriptorState.NEEDS_INFORMATION
                    candidate.sourceIndex < INITIAL_WINDOW -> DescriptorState.CHECKING
                    else -> DescriptorState.PENDING
                },
                objectiveAssessment = null,
                personalizedResult = null,
                selectedVariantName = null,
                stableModelId = candidate.repositoryId ?: "invalid-model-${candidate.sourceIndex}",
                sourceIndex = candidate.sourceIndex,
            )
        }
        val session = RecommendationQuerySession(
            queryId = queryId.take(MAX_QUERY_ID_LENGTH).ifBlank { "invalid-query" },
            candidates = candidates,
            workload = workload,
            initialStates = initialStates,
        )
        while (true) {
            val previous = activeSession.load()
            if (activeSession.compareAndSet(previous, session)) {
                previous?.cancel()
                return session
            }
        }
    }

    suspend fun evaluateInitial(session: RecommendationQuerySession, profile: RecommendationProfile) {
        enrichAndEmit(session, minOf(INITIAL_WINDOW, session.candidates.size), profile)
    }

    suspend fun evaluateMore(session: RecommendationQuerySession, profile: RecommendationProfile) {
        val target = minOf(session.evaluatedCount + MORE_WINDOW, MAX_ENRICHED_MODELS, session.candidates.size)
        enrichAndEmit(session, target, profile)
    }

    suspend fun setOrdering(
        session: RecommendationQuerySession,
        ordering: RecommendationOrdering,
    ) {
        session.setOrdering(ordering)
    }

    suspend fun rerank(session: RecommendationQuerySession, profile: RecommendationProfile) {
        session.runEvaluation {
            val (storedSnapshot, storedEvaluations) = session.recordsSnapshot()
            if (storedSnapshot == null || storedEvaluations.isEmpty()) return@runEvaluation
            val stale = !storedSnapshot.resources.isFreshAt(clock())
            val snapshot = if (stale) {
                withContext(evaluationDispatcher) { snapshotSource.refreshResources(storedSnapshot) }
                    .also { session.replaceSnapshot(it) }
            } else {
                storedSnapshot
            }
            val rerankedEvaluations = linkedMapOf<Int, RepositoryEvaluation>()
            val rerankedStates = linkedMapOf<Int, RecommendedModelUiState>()
            withContext(evaluationDispatcher) {
                for ((sourceIndex, evaluation) in storedEvaluations.entries.sortedBy { it.key }) {
                    val refreshed = RepositoryEvaluation(
                        variants = evaluation.variants.map { variant ->
                            variant.copy(
                                assessment = if (stale) variantEvaluator.rebuild(variant.assessment, snapshot) else variant.assessment,
                            )
                        },
                    )
                    rerankedEvaluations[sourceIndex] = refreshed
                    val candidate = session.candidates.getOrNull(sourceIndex) ?: continue
                    rerankedStates[sourceIndex] = assessedState(candidate, refreshed, snapshot, profile, session.workload)
                }
            }
            session.replaceReranked(rerankedEvaluations, rerankedStates)
        }
    }

    /** Re-runs local assessment for already-cached descriptors after calibration changes. */
    suspend fun reassessCached(session: RecommendationQuerySession, profile: RecommendationProfile) {
        session.runEvaluation {
            val (snapshot, storedEvaluations) = session.recordsSnapshot()
            if (snapshot == null || storedEvaluations.isEmpty()) return@runEvaluation
            val reassessedEvaluations = linkedMapOf<Int, RepositoryEvaluation>()
            val reassessedStates = linkedMapOf<Int, RecommendedModelUiState>()
            withContext(evaluationDispatcher) {
                for ((sourceIndex, evaluation) in storedEvaluations.entries.sortedBy { it.key }) {
                    val reassessed = RepositoryEvaluation(
                        variants = evaluation.variants.map { variant ->
                            variant.copy(
                                assessment = variantEvaluator.assess(
                                    variant.variant.descriptor,
                                    snapshot,
                                    session.workload,
                                ),
                            )
                        },
                    )
                    reassessedEvaluations[sourceIndex] = reassessed
                    val candidate = session.candidates.getOrNull(sourceIndex) ?: continue
                    reassessedStates[sourceIndex] = assessedState(
                        candidate,
                        reassessed,
                        snapshot,
                        profile,
                        session.workload,
                    )
                }
            }
            session.replaceReranked(reassessedEvaluations, reassessedStates)
        }
    }

    suspend fun refreshForAdmission(
        session: RecommendationQuerySession,
        state: RecommendedModelUiState,
        profile: RecommendationProfile,
    ): RecommendedModelUiState? = session.runEvaluation {
        val (previous, evaluations) = session.recordsSnapshot()
        val oldSnapshot = previous ?: return@runEvaluation null
        val evaluation = evaluations[state.sourceIndex] ?: return@runEvaluation null
        val refreshedSnapshot = withContext(evaluationDispatcher) {
            snapshotSource.refreshResources(oldSnapshot)
        }
        session.replaceSnapshot(refreshedSnapshot)
        val refreshed = RepositoryEvaluation(
            evaluation.variants.map { it.copy(assessment = variantEvaluator.rebuild(it.assessment, refreshedSnapshot)) },
        )
        val candidate = session.candidates.getOrNull(state.sourceIndex) ?: return@runEvaluation null
        assessedState(candidate, refreshed, refreshedSnapshot, profile, session.workload)
    }

    private suspend fun enrichAndEmit(
        session: RecommendationQuerySession,
        targetCount: Int,
        profile: RecommendationProfile,
    ) {
        session.runEvaluation {
            val range = session.reserveTo(targetCount)
            if (range.isEmpty()) return@runEvaluation
            val candidates = range.mapNotNull(session.candidates::getOrNull)
                .filter { it.repositoryId != null }
            if (candidates.isEmpty()) return@runEvaluation
            val snapshot = try {
                session.snapshotOrCapture {
                    withContext(evaluationDispatcher) { snapshotSource.captureInitial() }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                candidates.forEach { candidate ->
                    session.recordTerminal(needsInformationState(candidate))
                }
                return@runEvaluation
            }
            supervisorScope {
                candidates.map { candidate ->
                    async {
                        evaluateCandidate(session, candidate, snapshot, profile)
                    }
                }.awaitAll()
            }
        }
    }

    private suspend fun evaluateCandidate(
        session: RecommendationQuerySession,
        candidate: RecommendationCandidate,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ) {
        val repositoryId = requireNotNull(candidate.repositoryId)
        try {
            when (
                val variants = enrichmentGate.withPermit {
                    metadataSource.describeVariants(repositoryId, modeFor(session.workload))
                }
            ) {
                is RepositoryVariantSet.NeedsInformation ->
                    session.recordTerminal(needsInformationState(candidate))
                is RepositoryVariantSet.SelectVariant ->
                    session.recordTerminal(selectVariantState(candidate))
                is RepositoryVariantSet.Ready -> {
                    if (variants.inputLimitExceeded || variants.variants.size > MAX_RUNNABLE_VARIANTS) {
                        session.recordTerminal(selectVariantState(candidate))
                        return
                    }
                    val validated = validateVariants(repositoryId, variants.variants)
                    if (validated == null) {
                        session.recordTerminal(needsInformationState(candidate))
                    } else {
                        val assessed = ArrayList<AssessedVariant>(validated.size)
                        for ((variant, stableIdentity) in validated) {
                            assessed += AssessedVariant(
                                variant = variant,
                                stableIdentity = stableIdentity,
                                assessment = variantEvaluator.assess(variant.descriptor, snapshot, session.workload),
                            )
                        }
                        val evaluation = RepositoryEvaluation(assessed)
                        session.recordEvaluation(
                            candidate.sourceIndex,
                            evaluation,
                            assessedState(candidate, evaluation, snapshot, profile, session.workload),
                        )
                    }
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            session.recordTerminal(needsInformationState(candidate))
        }
    }

    private fun assessedState(
        candidate: RecommendationCandidate,
        evaluation: RepositoryEvaluation,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
        workload: WorkloadConfig,
    ): RecommendedModelUiState {
        val selected = evaluation.variants.minWithOrNull(
            Comparator { left, right ->
                val leftKey = variantEvaluator.sortKey(
                    left.variant.descriptor,
                    left.assessment,
                    snapshot,
                    profile,
                )
                val rightKey = variantEvaluator.sortKey(
                    right.variant.descriptor,
                    right.assessment,
                    snapshot,
                    profile,
                )
                val ranked = leftKey.compareTo(rightKey)
                if (ranked != 0) ranked else left.stableIdentity.compareTo(right.stableIdentity)
            },
        ) ?: return needsInformationState(candidate)
        val personalized = variantEvaluator.personalize(
            selected.variant.descriptor,
            selected.assessment,
            snapshot,
            profile,
        )
        val sortKey = variantEvaluator.sortKey(
            selected.variant.descriptor,
            selected.assessment,
            snapshot,
            profile,
        )
        return RecommendedModelUiState(
            sourceModel = candidate.model,
            repositoryId = candidate.repositoryId,
            descriptorState = DescriptorState.ASSESSED,
            objectiveAssessment = selected.assessment,
            personalizedResult = personalized,
            selectedVariantName = selected.variant.displayName,
            stableModelId = selected.stableIdentity,
            sourceIndex = candidate.sourceIndex,
            sortKey = sortKey,
            selectedDescriptor = selected.variant.descriptor,
            workload = workload,
        )
    }

    private fun needsInformationState(candidate: RecommendationCandidate) = RecommendedModelUiState(
        sourceModel = candidate.model,
        repositoryId = candidate.repositoryId,
        descriptorState = DescriptorState.NEEDS_INFORMATION,
        objectiveAssessment = null,
        personalizedResult = null,
        selectedVariantName = null,
        stableModelId = candidate.repositoryId ?: "invalid-model-${candidate.sourceIndex}",
        sourceIndex = candidate.sourceIndex,
    )

    private fun selectVariantState(candidate: RecommendationCandidate) = RecommendedModelUiState(
        sourceModel = candidate.model,
        repositoryId = candidate.repositoryId,
        descriptorState = DescriptorState.SELECT_VARIANT,
        objectiveAssessment = null,
        personalizedResult = null,
        selectedVariantName = null,
        stableModelId = candidate.repositoryId ?: "invalid-model-${candidate.sourceIndex}",
        sourceIndex = candidate.sourceIndex,
    )

    private fun validateVariants(
        repositoryId: String,
        values: List<RepositoryVariant>,
    ): List<Pair<RepositoryVariant, String>>? {
        if (values.isEmpty() || values.size > MAX_RUNNABLE_VARIANTS) return null
        var totalFiles = 0
        var totalIdentityCharacters = 0L
        val identities = HashSet<String>(values.size)
        val validated = ArrayList<Pair<RepositoryVariant, String>>(values.size)
        for (variant in values) {
            if (variant.descriptor.repositoryId != repositoryId ||
                variant.displayName.isBlank() || variant.displayName.length > MAX_VARIANT_DISPLAY_NAME ||
                variant.displayName.any { it.isISOControl() }
            ) return null
            val files = descriptorFiles(variant.descriptor) ?: return null
            if (files.size > DescriptorLimits.MAX_COMPONENTS - totalFiles) return null
            totalFiles += files.size
            val stableIdentity = exactStableIdentity(variant.descriptor) ?: return null
            val identityLength = stableIdentity.length.toLong()
            if (identityLength > MAX_TOTAL_IDENTITY_CHARACTERS - totalIdentityCharacters) return null
            totalIdentityCharacters += identityLength
            if (!identities.add(stableIdentity)) return null
            validated += variant to stableIdentity
        }
        return validated
    }

    private fun descriptorFiles(descriptor: ModelDescriptor): List<com.debanshu777.caraml.core.recommendation.ModelFileIdentity>? =
        if (!isValidRepositoryId(descriptor.repositoryId) || !isImmutableRevision(descriptor.revision) ||
            descriptor.requiredEngineFeatures.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE ||
            descriptor.requiredEngineFeatures.any { !isSafeMetadataString(it, requireNonBlank = true) } ||
            !isValidEvidence(descriptor.evidence)
        ) {
            null
        } else when (descriptor) {
            is LlmModelDescriptor -> {
                val files = descriptor.files
                if (files.isEmpty() || files.size > DescriptorLimits.MAX_COMPONENTS) return null
                if (descriptor.file != files.first() ||
                    descriptor.architecture?.let { !isSafeMetadataString(it, requireNonBlank = true) } == true ||
                    descriptor.parameterCount?.let { it !in 1..DescriptorLimits.MAX_PARAMETERS } == true ||
                    descriptor.contextLimit?.let { it !in 1..DescriptorLimits.MAX_CONTEXT_TOKENS } == true ||
                    descriptor.ggufVersion?.let { it !in 1..MAX_METADATA_INTEGER } == true ||
                    descriptor.transformerShape?.let { shape ->
                        listOf(
                            shape.layerCount,
                            shape.kvHeadCount,
                            shape.attentionHeadCount,
                            shape.hiddenSize,
                            shape.headDim,
                        ).any { it != null && it !in 1..MAX_METADATA_INTEGER }
                    } == true || !isValidQuantization(descriptor.quantization)
                ) return null
                if (files.size > 1 && !isCompleteShardSet(files.map { it.path })) return null
                files
            }
            is DiffusionModelDescriptor -> {
                if (descriptor.components.isEmpty() || descriptor.components.size > DescriptorLimits.MAX_COMPONENTS ||
                    descriptor.components.count { it.isPrimary } != 1 ||
                    descriptor.components.map { it.file.repositoryId to it.file.path }.toSet().size != descriptor.components.size ||
                    !isSafeMetadataString(descriptor.family, requireNonBlank = true) ||
                    descriptor.width?.let { it !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION } == true ||
                    descriptor.height?.let { it !in 1..DescriptorLimits.MAX_IMAGE_DIMENSION } == true ||
                    descriptor.quantizationDistribution.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE ||
                    descriptor.quantizationDistribution.any { !isSafeMetadataString(it, requireNonBlank = true) } ||
                    descriptor.components.any { !isValidQuantization(it.quantization) } ||
                    descriptor.components.filter { it.role != null }.map { it.role }.distinct().size !=
                    descriptor.components.count { it.role != null }
                ) return null
                val primary = descriptor.components.single { it.isPrimary }.file
                if (primary.repositoryId != descriptor.repositoryId || primary.revision != descriptor.revision) return null
                descriptor.components.map { it.file }
            }
        }.takeIf { files ->
            var totalBytes = 0L
            files.all { file ->
                val valid = isValidRepositoryId(file.repositoryId) && isImmutableRevision(file.revision) &&
                    isValidRelativePath(file.path) && file.sizeBytes in 1..DescriptorLimits.MAX_FILE_BYTES &&
                    isSafeOpaqueIdentity(file.gitOid) && isSafeOpaqueIdentity(file.lfsOid) &&
                    isSafeOpaqueIdentity(file.xetHash) && isValidEvidence(file.evidence) &&
                    file.sizeBytes <= DescriptorLimits.MAX_BUNDLE_BYTES - totalBytes
                if (valid) totalBytes += file.sizeBytes
                valid
            }
        }

    private fun exactStableIdentity(descriptor: ModelDescriptor): String? {
        val files = descriptorFiles(descriptor) ?: return null
        val components = files.sortedWith(compareBy({ it.repositoryId }, { it.revision }, { it.path }))
        val value = buildString {
            components.forEachIndexed { index, file ->
                if (index > 0) append('+')
                append(file.repositoryId).append('@').append(file.revision).append(':').append(file.path)
            }
        }
        return value.takeIf { it.length.toLong() <= MAX_TOTAL_IDENTITY_CHARACTERS }
    }

    private fun isCompleteShardSet(paths: List<String>): Boolean {
        val matches = paths.map { GGUF_SHARD.matchEntire(it) ?: return false }
        val prefixes = matches.map { it.groupValues[1] }.toSet()
        val totals = matches.mapNotNull { it.groupValues[3].toIntOrNull() }.toSet()
        if (prefixes.size != 1 || totals.size != 1) return false
        val total = totals.single()
        if (total != paths.size || total !in 1..DescriptorLimits.MAX_COMPONENTS) return false
        val indexes = matches.mapNotNull { it.groupValues[2].toIntOrNull() }.toSet()
        return indexes == (1..total).toSet()
    }

    private fun isAsciiHexDigit(value: Char): Boolean =
        value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

    private fun isImmutableRevision(value: String): Boolean =
        value.length in MIN_REVISION_LENGTH..MAX_REVISION_LENGTH && value.all(::isAsciiHexDigit)

    private fun isValidRelativePath(value: String): Boolean {
        if (value.isEmpty() || value != value.trim() || value.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH ||
            value.startsWith('/') || value.startsWith('\\') || '\\' in value
        ) return false
        return value.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.length <= DescriptorLimits.MAX_PATH_SEGMENT_LENGTH &&
                segment.none { it.code < 32 || it.code == 127 || it in ":*?\"<>|" }
        }
    }

    private fun isSafeOpaqueIdentity(value: String?): Boolean = value == null ||
        isSafeMetadataString(value, requireNonBlank = true)

    private fun isSafeMetadataString(value: String, requireNonBlank: Boolean = false): Boolean =
        (!requireNonBlank || value.isNotBlank()) && value.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
            value.none { it.isISOControl() }

    private fun isValidEvidence(values: List<com.debanshu777.caraml.core.recommendation.Evidence>): Boolean =
        values.size <= MAX_EVIDENCE_ENTRIES && values.all { evidence ->
            evidence.detail?.let { isSafeMetadataString(it) } != false
        }

    private fun isValidQuantization(value: com.debanshu777.caraml.core.recommendation.QuantizationEvidence): Boolean =
        value.quantizations.size <= DescriptorLimits.MAX_METADATA_COLLECTION_SIZE &&
            value.quantizations.all { isSafeMetadataString(it, requireNonBlank = true) }

    private fun sanitizeCandidate(
        model: ListModelsResponse.Model,
        sourceIndex: Int,
        seenRepositoryIds: MutableSet<String>,
        inputsValid: Boolean,
    ): RecommendationCandidate {
        val id = model.id?.takeIf(::isValidRepositoryId)
        val valid = inputsValid && id != null && seenRepositoryIds.add(id) &&
            safeNullableString(model.author) && safeNullableString(model.gated) &&
            safeNullableString(model.lastModified) && safeNullableString(model.pipelineTag) &&
            safeNullableString(model.repoType) &&
            model.downloads?.let { it >= 0 } != false && model.likes?.let { it >= 0 } != false &&
            model.numParameters?.let { it in 1..DescriptorLimits.MAX_PARAMETERS } != false
        val sanitized = ListModelsResponse.Model(
            author = model.author?.takeIf(::safeString),
            downloads = model.downloads?.takeIf { it >= 0 },
            gated = model.gated?.takeIf(::safeString),
            id = id.takeIf { valid },
            isLikedByUser = model.isLikedByUser,
            lastModified = model.lastModified?.takeIf(::safeString),
            likes = model.likes?.takeIf { it >= 0 },
            numParameters = model.numParameters?.takeIf { it in 1..DescriptorLimits.MAX_PARAMETERS },
            pipelineTag = model.pipelineTag?.takeIf(::safeString),
            `private` = model.`private`,
            repoType = model.repoType?.takeIf(::safeString),
        )
        return RecommendationCandidate(sanitized, id.takeIf { valid }, sourceIndex)
    }

    private fun validWorkload(value: WorkloadConfig): Boolean = when (value) {
        is LlmWorkloadConfig -> value.userRequestedContextTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            value.contextTokens in 1..value.userRequestedContextTokens &&
            value.minimumContextTokens in 1..value.contextTokens &&
            value.promptTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            value.generationReserveTokens in 1..DescriptorLimits.MAX_CONTEXT_TOKENS &&
            value.promptTokens <= value.contextTokens - value.generationReserveTokens &&
            value.batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            value.microBatchSize in 1..value.batchSize &&
            value.sequenceCount in 1..WorkloadLimits.MAX_SEQUENCE_COUNT &&
            value.allowedKvCacheTypes.isNotEmpty() &&
            value.allowedKvCacheTypes.size <= com.debanshu777.caraml.core.recommendation.KvCacheType.entries.size &&
            when (val selection = value.kvCacheSelection) {
                com.debanshu777.caraml.core.recommendation.KvCacheSelection.Auto -> true
                is com.debanshu777.caraml.core.recommendation.KvCacheSelection.Explicit ->
                    selection.keyType in value.allowedKvCacheTypes && selection.valueType in value.allowedKvCacheTypes
            } && isValidEvidence(value.evidence)
        is DiffusionWorkloadConfig -> value.width in 1..DescriptorLimits.MAX_IMAGE_DIMENSION &&
            value.height in 1..DescriptorLimits.MAX_IMAGE_DIMENSION &&
            value.minimumWidth in 1..value.width && value.minimumHeight in 1..value.height &&
            value.frameCount in 1..WorkloadLimits.MAX_DIFFUSION_FRAMES &&
            value.minimumFrameCount in 1..value.frameCount && value.batchSize in 1..WorkloadLimits.MAX_BATCH_SIZE &&
            value.steps in 1..WorkloadLimits.MAX_DIFFUSION_STEPS &&
            value.maxVramBytes?.let { it in 1..DescriptorLimits.MAX_BUNDLE_BYTES } != false &&
            isValidEvidence(value.evidence)
    }

    private fun modeFor(value: WorkloadConfig) = when (value) {
        is LlmWorkloadConfig -> com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode.LanguageModels
        is DiffusionWorkloadConfig -> when (value.mode) {
            com.debanshu777.caraml.core.recommendation.DiffusionMode.IMAGE ->
                com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode.DiffusionImage
            com.debanshu777.caraml.core.recommendation.DiffusionMode.VIDEO ->
                com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode.DiffusionVideo
        }
    }

    private fun isValidRepositoryId(value: String): Boolean {
        if (value.isEmpty() || value != value.trim() || value.length > DescriptorLimits.MAX_MODEL_ID_LENGTH || '\\' in value) {
            return false
        }
        val segments = value.split('/')
        return segments.size in 1..2 && segments.all { segment ->
            segment.isNotEmpty() && segment.length <= DescriptorLimits.MAX_REPOSITORY_SEGMENT_LENGTH &&
                segment != "." && segment != ".." && ".." !in segment && "--" !in segment &&
                segment.first() !in ".-" && segment.last() !in ".-" &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }
    }

    private fun safeNullableString(value: String?): Boolean = value == null || safeString(value)
    private fun safeString(value: String): Boolean = value.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
        value.none { it.isISOControl() }

    private companion object {
        const val INITIAL_WINDOW = 48
        const val MORE_WINDOW = 24
        const val MAX_ENRICHED_MODELS = 96
        const val MAX_METADATA_CONCURRENCY = 4
        const val MAX_RUNNABLE_VARIANTS = 64
        const val MAX_QUERY_MODELS = 256
        const val MAX_QUERY_ID_LENGTH = 256
        const val MAX_VARIANT_DISPLAY_NAME = 4_096
        const val MAX_TOTAL_IDENTITY_CHARACTERS = 4_500_000L
        const val MAX_METADATA_INTEGER = 1_048_576
        const val MAX_EVIDENCE_ENTRIES = 64
        const val MIN_REVISION_LENGTH = 40
        const val MAX_REVISION_LENGTH = 64
        val GGUF_SHARD = Regex("^(.+)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
    }
}
