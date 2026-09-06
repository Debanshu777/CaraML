package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.DescriptorBuildResult
import com.debanshu777.caraml.core.recommendation.DescriptorLimits
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.ModelDescriptorFactory
import com.debanshu777.caraml.core.recommendation.ResolvedDiffusionComponentMetadata
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.huggingfacemanager.HuggingFaceApi
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import com.debanshu777.huggingfacemanager.sdcpp.SdCppModelSetup
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup
import kotlinx.coroutines.CancellationException

internal interface HuggingFaceMetadataGateway {
    suspend fun getStrictDetail(repositoryId: String): Result<ModelDetailResponse, DataError.Network>

    suspend fun getTree(
        repositoryId: String,
        revision: String,
        filter: ModelFileWeightFilter,
    ): Result<List<ModelFileTreeResponse>, DataError.Network>

    suspend fun getConfig(
        repositoryId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network>
}

private class ApiHuggingFaceMetadataGateway(
    private val api: HuggingFaceApi,
) : HuggingFaceMetadataGateway {
    override suspend fun getStrictDetail(repositoryId: String) =
        api.getRecommendationModelDetail(repositoryId)

    override suspend fun getTree(
        repositoryId: String,
        revision: String,
        filter: ModelFileWeightFilter,
    ) = api.getModelFileTree(repositoryId, revision, filter)

    override suspend fun getConfig(repositoryId: String, revision: String) =
        api.getModelConfig(repositoryId, revision)
}

class HuggingFaceModelMetadataSource internal constructor(
    private val gateway: HuggingFaceMetadataGateway,
    private val descriptorFactory: ModelDescriptorFactory,
    private val setupResolver: (String) -> SdCppModelSetup?,
) : ModelMetadataSource {
    constructor(
        api: HuggingFaceApi,
        descriptorFactory: ModelDescriptorFactory = ModelDescriptorFactory(),
    ) : this(ApiHuggingFaceMetadataGateway(api), descriptorFactory, ::getModelSetup)

    override suspend fun describeVariants(
        repositoryId: String,
        mode: ModelHubBrowseMode,
    ): RepositoryVariantSet {
        if (!isValidRepositoryId(repositoryId)) return needsInformation(repositoryId, AssessmentReason.INVALID_MODEL_ID)
        return try {
            val detail = gateway.getStrictDetail(repositoryId).successOrNull()
                ?: return needsInformation(repositoryId)
            val revision = detail.sha
            if (revision == null || !isImmutableRevisionValue(revision)) {
                return needsInformation(repositoryId, AssessmentReason.INVALID_REVISION)
            }
            when (mode) {
                ModelHubBrowseMode.LanguageModels -> describeLlm(repositoryId, detail, revision)
                ModelHubBrowseMode.DiffusionImage,
                ModelHubBrowseMode.DiffusionVideo,
                -> describeDiffusion(repositoryId, detail, revision, mode)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            needsInformation(repositoryId)
        }
    }

    private suspend fun describeLlm(
        repositoryId: String,
        detail: ModelDetailResponse,
        revision: String,
    ): RepositoryVariantSet {
        val files = gateway.getTree(repositoryId, revision, ModelFileWeightFilter.GgufOnly).successOrNull()
            ?: return needsInformation(repositoryId)
        val grouped = groupGgufFiles(files) ?: return needsInformation(repositoryId)
        if (grouped.isEmpty()) return selectVariant(repositoryId)
        if (grouped.size > MAX_RUNNABLE_VARIANTS) return selectVariant(repositoryId)
        val config = gateway.getConfig(repositoryId, revision).successOrNull()
        val variants = ArrayList<RepositoryVariant>(grouped.size)
        for (group in grouped) {
            when (val built = descriptorFactory.buildLlm(detail, group, config)) {
                is DescriptorBuildResult.Ready -> variants += RepositoryVariant(
                    descriptor = built.descriptor,
                    displayName = ggufDisplayName(group) ?: return selectVariant(repositoryId),
                )
                is DescriptorBuildResult.NeedsVariant -> return selectVariant(repositoryId, built.reasons)
                is DescriptorBuildResult.Invalid -> return needsInformation(repositoryId, built.reasons)
            }
        }
        return RepositoryVariantSet.Ready(variants)
    }

    private suspend fun describeDiffusion(
        repositoryId: String,
        detail: ModelDetailResponse,
        revision: String,
        browseMode: ModelHubBrowseMode,
    ): RepositoryVariantSet {
        val setup = setupResolver(repositoryId) ?: return selectVariant(repositoryId)
        if (setup.components.size > DescriptorLimits.MAX_COMPONENTS ||
            setup.components.any { it.alternatives.isNotEmpty() }
        ) {
            return selectVariant(repositoryId)
        }
        val mainFiles = gateway.getTree(
            repositoryId,
            revision,
            ModelFileWeightFilter.StableDiffusionCppWeights,
        ).successOrNull() ?: return needsInformation(repositoryId)
        if (!hasUniqueBoundedPaths(mainFiles)) return needsInformation(repositoryId)

        val resolvedExternal = resolveExternalComponents(repositoryId, setup)
            ?: return needsInformation(repositoryId, AssessmentReason.MISSING_REQUIRED_COMPONENT)
        val sameRepositoryComponents = setup.components.filter { it.repoId == repositoryId }
        val mainByPath = mainFiles.associateBy { it.path }
        val sameRepositoryFiles = ArrayList<ModelFileTreeResponse>(sameRepositoryComponents.size)
        for (component in sameRepositoryComponents) {
            val exact = mainByPath[component.filePath]
                ?: return needsInformation(repositoryId, AssessmentReason.MISSING_REQUIRED_COMPONENT)
            sameRepositoryFiles += exact
        }

        val componentPaths = sameRepositoryComponents.mapTo(hashSetOf()) { it.filePath }
        val primaryFiles = mainFiles.filter { it.path !in componentPaths }
        if (primaryFiles.isEmpty()) return selectVariant(repositoryId)
        if (primaryFiles.size > MAX_RUNNABLE_VARIANTS) return selectVariant(repositoryId)
        if (primaryFiles.any { GGUF_SHARD.matches(it.path.orEmpty()) }) return selectVariant(repositoryId)
        if (primaryFiles.count { it.path?.contains('/') == true } > 1) return selectVariant(repositoryId)

        val diffusionMode = when (browseMode) {
            ModelHubBrowseMode.DiffusionVideo -> DiffusionMode.VIDEO
            ModelHubBrowseMode.DiffusionImage -> DiffusionMode.IMAGE
            ModelHubBrowseMode.LanguageModels -> return needsInformation(repositoryId)
        }
        val variants = ArrayList<RepositoryVariant>(primaryFiles.size)
        for (primary in primaryFiles) {
            val exactFiles = listOf(primary) + sameRepositoryFiles
            when (
                val built = descriptorFactory.buildDiffusion(
                    detail = detail,
                    files = exactFiles,
                    setup = setup,
                    mode = diffusionMode,
                    resolvedExternalComponents = resolvedExternal,
                )
            ) {
                is DescriptorBuildResult.Ready -> variants += RepositoryVariant(
                    descriptor = built.descriptor,
                    displayName = diffusionDisplayName(primary, setup) ?: return selectVariant(repositoryId),
                )
                is DescriptorBuildResult.NeedsVariant -> return selectVariant(repositoryId, built.reasons)
                is DescriptorBuildResult.Invalid -> return needsInformation(repositoryId, built.reasons)
            }
        }
        return RepositoryVariantSet.Ready(variants)
    }

    private suspend fun resolveExternalComponents(
        primaryRepositoryId: String,
        setup: SdCppModelSetup,
    ): List<ResolvedDiffusionComponentMetadata>? {
        val external = setup.components.filter { it.repoId != primaryRepositoryId }
        if (external.isEmpty()) return emptyList()
        if (external.map { it.repoId to it.filePath }.toSet().size != external.size) return null
        val snapshots = mutableMapOf<String, Pair<ModelDetailResponse, Map<String?, ModelFileTreeResponse>>>()
        val resolved = ArrayList<ResolvedDiffusionComponentMetadata>(external.size)
        for (component in external) {
            val snapshot = snapshots[component.repoId] ?: run {
                if (!isValidRepositoryId(component.repoId)) return null
                val detail = gateway.getStrictDetail(component.repoId).successOrNull() ?: return null
                val revision = detail.sha
                if (revision == null || !isImmutableRevisionValue(revision)) return null
                val tree = gateway.getTree(
                    component.repoId,
                    revision,
                    ModelFileWeightFilter.StableDiffusionCppWeights,
                ).successOrNull() ?: return null
                if (!hasUniqueBoundedPaths(tree)) return null
                (detail to tree.associateBy { it.path }).also { snapshots[component.repoId] = it }
            }
            val file = snapshot.second[component.filePath] ?: return null
            resolved += ResolvedDiffusionComponentMetadata(component, snapshot.first, file)
        }
        return resolved
    }

    private fun groupGgufFiles(files: List<ModelFileTreeResponse>): List<List<ModelFileTreeResponse>>? {
        if (!hasUniqueBoundedPaths(files)) return null
        val ordinary = mutableListOf<List<ModelFileTreeResponse>>()
        val shards = linkedMapOf<String, MutableList<ShardEntry>>()
        val totalsByPrefix = mutableMapOf<String, Int>()
        for (file in files) {
            val path = file.path ?: return null
            val match = GGUF_SHARD.matchEntire(path)
            if (match == null) {
                ordinary += listOf(file)
                continue
            }
            val prefix = match.groupValues[1]
            val index = match.groupValues[2].toIntOrNull() ?: return null
            val total = match.groupValues[3].toIntOrNull() ?: return null
            if (total !in 1..DescriptorLimits.MAX_COMPONENTS || index !in 1..total) return null
            val recordedTotal = totalsByPrefix[prefix]
            if (recordedTotal != null && recordedTotal != total) return null
            if (recordedTotal == null) totalsByPrefix[prefix] = total
            shards.getOrPut("$prefix#$total") { mutableListOf() } += ShardEntry(index, file)
        }
        val grouped = mutableListOf<List<ModelFileTreeResponse>>()
        grouped += ordinary
        for (entries in shards.values) {
            val total = entries.firstOrNull()?.file?.path?.let(GGUF_SHARD::matchEntire)
                ?.groupValues?.get(3)?.toIntOrNull() ?: return null
            if (entries.size != total || entries.map { it.index }.toSet() != (1..total).toSet()) return null
            grouped += entries.sortedBy { it.index }.map { it.file }
        }
        return grouped.sortedBy { it.first().path }
    }

    private fun hasUniqueBoundedPaths(files: List<ModelFileTreeResponse>): Boolean =
        files.size <= DescriptorLimits.MAX_COMPONENTS &&
            files.all { file ->
                val path = file.path
                path != null && path.length <= DescriptorLimits.MAX_RELATIVE_PATH_LENGTH
            } &&
            files.map { it.path }.toSet().size == files.size

    private fun isImmutableRevisionValue(value: String): Boolean =
        value.length in MIN_REVISION_LENGTH..MAX_REVISION_LENGTH && value.all(::isAsciiHexDigit)

    private fun isAsciiHexDigit(value: Char): Boolean =
        value in '0'..'9' || value in 'a'..'f' || value in 'A'..'F'

    private fun ggufDisplayName(files: List<ModelFileTreeResponse>): String? {
        val first = files.firstOrNull()?.path ?: return null
        val value = if (files.size == 1) {
            first
        } else {
            val last = files.last().path ?: return null
            "$first … $last (${files.size} shards)"
        }
        return value.takeIf { it.length <= MAX_VARIANT_DISPLAY_NAME }
    }

    private fun diffusionDisplayName(
        primary: ModelFileTreeResponse,
        setup: SdCppModelSetup,
    ): String? {
        val paths = buildList {
            add(primary.path ?: return null)
            setup.components.forEach { add("${it.repoId}:${it.filePath}") }
        }
        val value = paths.joinToString(" + ")
        return value.takeIf { it.length <= MAX_VARIANT_DISPLAY_NAME }
    }

    private fun selectVariant(
        repositoryId: String,
        reasons: Collection<AssessmentReason> = listOf(AssessmentReason.SELECT_VARIANT),
    ) = RepositoryVariantSet.SelectVariant(repositoryId, reasons.ifEmpty { listOf(AssessmentReason.SELECT_VARIANT) })

    private fun needsInformation(
        repositoryId: String,
        reason: AssessmentReason = AssessmentReason.INVALID_METADATA,
    ) = needsInformation(repositoryId, listOf(reason))

    private fun needsInformation(repositoryId: String, reasons: Collection<AssessmentReason>) =
        RepositoryVariantSet.NeedsInformation(
            repositoryId,
            reasons.ifEmpty { listOf(AssessmentReason.INVALID_METADATA) },
        )

    private fun isValidRepositoryId(value: String): Boolean {
        if (value.isEmpty() || value != value.trim() || value.length > DescriptorLimits.MAX_MODEL_ID_LENGTH) return false
        val segments = value.split('/')
        return segments.size in 1..2 && segments.all { segment ->
            segment.isNotEmpty() && segment.length <= DescriptorLimits.MAX_REPOSITORY_SEGMENT_LENGTH &&
                segment != "." && segment != ".." && ".." !in segment && "--" !in segment &&
                segment.first() !in ".-" && segment.last() !in ".-" &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }
    }

    private fun <T> Result<T, DataError.Network>.successOrNull(): T? = when (this) {
        is Result.Success -> data
        is Result.Error -> null
    }

    private data class ShardEntry(val index: Int, val file: ModelFileTreeResponse)

    private companion object {
        val GGUF_SHARD = Regex("^(.+)-(\\d{5})-of-(\\d{5})\\.gguf$", RegexOption.IGNORE_CASE)
        const val MAX_VARIANT_DISPLAY_NAME: Int = 4_096
        const val MAX_RUNNABLE_VARIANTS: Int = 64
        const val MIN_REVISION_LENGTH: Int = 40
        const val MAX_REVISION_LENGTH: Int = 64
    }
}
