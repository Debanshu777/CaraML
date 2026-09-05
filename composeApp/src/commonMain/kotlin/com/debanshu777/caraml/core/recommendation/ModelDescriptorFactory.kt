package com.debanshu777.caraml.core.recommendation

import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponent
import com.debanshu777.huggingfacemanager.sdcpp.SdCppModelSetup

class ModelDescriptorFactory {
    fun buildProvisional(model: ListModelsResponse.Model): DescriptorBuildResult {
        val repositoryId = model.id
            ?.takeIf(::isValidRepositoryId)
            ?: return invalid(AssessmentReason.INVALID_MODEL_ID)
        val parameterCount = model.numParameters
        if (parameterCount != null && parameterCount !in 1..DescriptorLimits.MAX_PARAMETERS) {
            return invalid(AssessmentReason.PARAMETER_LIMIT_EXCEEDED)
        }
        if (!isBoundedString(model.pipelineTag) || !isBoundedString(model.repoType)) {
            return invalid(AssessmentReason.INVALID_METADATA)
        }
        return DescriptorBuildResult.NeedsVariant(
            repositoryId = repositoryId,
            reasons = listOf(AssessmentReason.SELECT_VARIANT),
            assumedQuantization = null,
        )
    }

    fun buildLlm(
        detail: ModelDetailResponse,
        file: ModelFileTreeResponse,
        transformerConfig: TransformerConfigResponse?,
    ): DescriptorBuildResult {
        val common = validateCommon(detail) ?: return invalid(commonValidationReason(detail))
        if (modelFormatForPath(file.path.orEmpty()) != ModelFormat.GGUF) {
            return invalid(AssessmentReason.UNSUPPORTED_FORMAT)
        }
        val identity = buildFileIdentity(common.repositoryId, common.revision, file)
            ?: return invalid(fileValidationReason(file))
        val parameterCount = detail.gguf?.total ?: detail.safetensors?.total
        if (parameterCount != null && parameterCount !in 1..DescriptorLimits.MAX_PARAMETERS) {
            return invalid(AssessmentReason.PARAMETER_LIMIT_EXCEEDED)
        }
        val contextLimit = detail.gguf?.contextLength ?: transformerConfig?.maxPositionEmbeddings
        if (contextLimit != null && contextLimit !in 1..DescriptorLimits.MAX_CONTEXT_TOKENS) {
            return invalid(AssessmentReason.CONTEXT_LIMIT_EXCEEDED)
        }
        val architecture = detail.gguf?.architecture ?: detail.config?.modelType
        if (!isSafeArchitecture(architecture)) return invalid(AssessmentReason.INVALID_METADATA)

        val quantization = QuantizationParser.parseFilename(identity.path)
        if (quantization is QuantizationEvidence.Mixed) {
            return invalid(AssessmentReason.MIXED_QUANTIZATION)
        }
        val shape = when (val validated = buildTransformerShape(transformerConfig)) {
            ShapeResult.Invalid -> return invalid(AssessmentReason.INVALID_METADATA)
            is ShapeResult.Valid -> validated.shape
        }
        val evidence = buildList {
            add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "revision:hub-sha"))
            addAll(identity.evidence)
            if (parameterCount != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "parameters:hub-metadata"))
            }
            if (architecture != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "architecture:hub-metadata"))
            }
            if (shape != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "shape:transformer-config"))
            }
        }
        return DescriptorBuildResult.Ready(
            LlmModelDescriptor(
                repositoryId = common.repositoryId,
                revision = common.revision,
                file = identity,
                architecture = architecture,
                quantization = quantization,
                parameterCount = parameterCount,
                contextLimit = contextLimit,
                transformerShape = shape,
                ggufVersion = parseGgufVersion(detail.tags),
                requiredEngineFeatures = emptySet(),
                evidence = evidence,
            ),
        )
    }

    fun buildDiffusion(
        detail: ModelDetailResponse,
        files: List<ModelFileTreeResponse>,
        setup: SdCppModelSetup,
        mode: DiffusionMode,
    ): DescriptorBuildResult {
        val common = validateCommon(detail) ?: return invalid(commonValidationReason(detail))
        if (files.isEmpty()) return invalid(AssessmentReason.MISSING_REQUIRED_COMPONENT)
        if (files.size > DescriptorLimits.MAX_COMPONENTS) {
            return invalid(AssessmentReason.COMPONENT_LIMIT_EXCEEDED)
        }
        if (!isBoundedString(setup.familyLabel) || !isBoundedString(setup.description)) {
            return invalid(AssessmentReason.INVALID_METADATA)
        }
        if (setup.components.size > DescriptorLimits.MAX_COMPONENTS) {
            return invalid(AssessmentReason.COMPONENT_LIMIT_EXCEEDED)
        }
        val setupReason = validateSetup(setup.components)
        if (setupReason != null) return invalid(setupReason)

        val identities = ArrayList<ModelFileIdentity>(files.size)
        val seenPaths = HashSet<String>(files.size)
        var bundleBytes = 0L
        for (file in files) {
            val identity = buildFileIdentity(common.repositoryId, common.revision, file)
                ?: return invalid(fileValidationReason(file))
            if (!seenPaths.add(identity.path)) return invalid(AssessmentReason.INVALID_FILE_PATH)
            if (modelFormatForPath(identity.path) == null) return invalid(AssessmentReason.UNSUPPORTED_FORMAT)
            bundleBytes = when (val sum = checkedAdd(bundleBytes, identity.sizeBytes)) {
                is CheckedLong.Value -> sum.value
                is CheckedLong.Invalid -> return invalid(sum.reason)
            }
            if (bundleBytes > DescriptorLimits.MAX_BUNDLE_BYTES) {
                return invalid(AssessmentReason.BUNDLE_SIZE_LIMIT_EXCEEDED)
            }
            identities += identity
        }

        val filesByPath = identities.associateBy { it.path }
        val componentsByPath = setup.components.associateBy { it.filePath }
        val missingRequired = setup.components.any { it.required && it.filePath !in filesByPath }
        if (missingRequired) return invalid(AssessmentReason.MISSING_REQUIRED_COMPONENT)

        val primaryPaths = identities.mapTo(mutableSetOf()) { it.path }
            .apply { removeAll(componentsByPath.keys) }
        if (primaryPaths.isEmpty() && !setup.selfContained) {
            return invalid(AssessmentReason.MISSING_REQUIRED_COMPONENT)
        }

        val components = identities.map { identity ->
            val setupComponent = componentsByPath[identity.path]
            DiffusionComponentDescriptor(
                file = identity,
                role = setupComponent?.role,
                required = setupComponent?.required ?: true,
                isPrimary = identity.path in primaryPaths || setup.selfContained && componentsByPath.isEmpty(),
                quantization = QuantizationParser.parseFilename(identity.path),
            )
        }
        val distribution = components
            .flatMapTo(linkedSetOf()) { it.quantization.quantizations }
        return DescriptorBuildResult.Ready(
            DiffusionModelDescriptor(
                repositoryId = common.repositoryId,
                revision = common.revision,
                components = components,
                mode = mode,
                family = setup.familyLabel,
                quantizationDistribution = distribution,
                requiredComponentsPresent = true,
                requiredEngineFeatures = emptySet(),
                evidence = buildList {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "revision:hub-sha"))
                    components.forEach { addAll(it.file.evidence) }
                },
            ),
        )
    }

    private fun validateCommon(detail: ModelDetailResponse): CommonIdentity? {
        val candidateIds = listOfNotNull(detail.modelId, detail.id).distinct()
        if (candidateIds.size != 1) return null
        val repositoryId = candidateIds.single()
        if (!isValidRepositoryId(repositoryId)) return null
        val revision = detail.sha ?: return null
        if (!IMMUTABLE_REVISION.matches(revision)) return null
        if (!isValidParameterCount(detail.gguf?.total) ||
            !isValidParameterCount(detail.safetensors?.total)
        ) {
            return null
        }
        if (!validateDetailCollections(detail)) return null
        return CommonIdentity(repositoryId, revision)
    }

    private fun commonValidationReason(detail: ModelDetailResponse): AssessmentReason {
        val candidateIds = listOfNotNull(detail.modelId, detail.id).distinct()
        if (candidateIds.size != 1 || !isValidRepositoryId(candidateIds.firstOrNull().orEmpty())) {
            return AssessmentReason.INVALID_MODEL_ID
        }
        if (!IMMUTABLE_REVISION.matches(detail.sha.orEmpty())) return AssessmentReason.INVALID_REVISION
        if (!isValidParameterCount(detail.gguf?.total) ||
            !isValidParameterCount(detail.safetensors?.total)
        ) {
            return AssessmentReason.PARAMETER_LIMIT_EXCEEDED
        }
        return AssessmentReason.INVALID_METADATA
    }

    private fun validateDetailCollections(detail: ModelDetailResponse): Boolean {
        if (!isBoundedCollection(detail.tags) || !isBoundedCollection(detail.siblings)) return false
        if (detail.tags.orEmpty().any { it == null || !isBoundedString(it) }) return false
        if (detail.siblings.orEmpty().any { it == null || !isBoundedString(it.rfilename) }) return false
        val architectures = detail.config?.architectures
        if (!isBoundedCollection(architectures)) return false
        if (architectures.orEmpty().any { it == null || !isSafeArchitecture(it) }) return false
        return isBoundedString(detail.pipelineTag) &&
            isBoundedString(detail.libraryName) &&
            isBoundedString(detail.config?.modelType)
    }

    private fun buildFileIdentity(
        repositoryId: String,
        revision: String,
        file: ModelFileTreeResponse,
    ): ModelFileIdentity? {
        if (file.type != null && file.type != "file") return null
        val path = file.path ?: return null
        if (!isValidRelativePath(path)) return null
        if (!isValidFileSize(file.size) || !isValidFileSize(file.lfs?.size)) return null
        val pointerSize = file.lfs?.pointerSize
        if (pointerSize != null && pointerSize !in 0..MAX_LFS_POINTER_BYTES) return null
        val sizeSource = if (file.lfs?.size != null) "lfs" else "tree"
        val size = file.lfs?.size ?: file.size ?: return null
        if (size !in 1..DescriptorLimits.MAX_FILE_BYTES) return null
        if (!isSafeOpaqueIdentity(file.oid) || !isSafeOpaqueIdentity(file.lfs?.oid) ||
            !isSafeOpaqueIdentity(file.xetHash)
        ) {
            return null
        }
        return ModelFileIdentity(
            repositoryId = repositoryId,
            revision = revision,
            path = path,
            sizeBytes = size,
            gitOid = file.oid,
            lfsOid = file.lfs?.oid,
            xetHash = file.xetHash,
            evidence = buildList {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "path:tree"))
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "size:$sizeSource"))
                if (file.lfs?.oid != null) {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "object-id:lfs"))
                }
                if (file.oid != null) {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "object-id:git"))
                }
                if (file.xetHash != null) {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "object-id:xet"))
                }
            },
        )
    }

    private fun fileValidationReason(file: ModelFileTreeResponse): AssessmentReason {
        val path = file.path
        if (path == null || !isValidRelativePath(path)) return AssessmentReason.INVALID_FILE_PATH
        val size = file.lfs?.size ?: file.size
        return when {
            size != null && size > DescriptorLimits.MAX_FILE_BYTES -> AssessmentReason.FILE_SIZE_LIMIT_EXCEEDED
            else -> AssessmentReason.INVALID_METADATA
        }
    }

    private fun buildTransformerShape(config: TransformerConfigResponse?): ShapeResult {
        if (config == null) return ShapeResult.Valid(null)
        val fields = listOf(
            config.numHiddenLayers,
            config.numKeyValueHeads,
            config.numAttentionHeads,
            config.hiddenSize,
            config.headDim,
        )
        if (fields.any { it != null && it !in 1..MAX_TRANSFORMER_FIELD }) return ShapeResult.Invalid
        if (config.maxPositionEmbeddings != null &&
            config.maxPositionEmbeddings !in 1..DescriptorLimits.MAX_CONTEXT_TOKENS
        ) {
            return ShapeResult.Invalid
        }
        val derivedHeadDim = config.headDim ?: run {
            val hidden = config.hiddenSize
            val heads = config.numAttentionHeads
            if (hidden != null && heads != null && hidden % heads == 0) hidden / heads else null
        }
        val hasShape = fields.any { it != null }
        return ShapeResult.Valid(
            if (hasShape) {
                TransformerShape(
                    layerCount = config.numHiddenLayers,
                    kvHeadCount = config.numKeyValueHeads,
                    attentionHeadCount = config.numAttentionHeads,
                    hiddenSize = config.hiddenSize,
                    headDim = derivedHeadDim,
                )
            } else {
                null
            },
        )
    }

    private fun validateSetup(components: List<SdCppComponent>): AssessmentReason? {
        val seenRoles = mutableSetOf<ComponentRole>()
        val seenPaths = mutableSetOf<String>()
        for (component in components) {
            if (!seenRoles.add(component.role)) return AssessmentReason.INVALID_METADATA
            if (!seenPaths.add(component.filePath)) return AssessmentReason.INVALID_FILE_PATH
            if (!isValidRepositoryId(component.repoId) || !isValidRelativePath(component.filePath)) {
                return AssessmentReason.INVALID_FILE_PATH
            }
            if (!isBoundedString(component.sizeHint) ||
                component.alternatives.size > DescriptorLimits.MAX_METADATA_COLLECTION_SIZE
            ) {
                return AssessmentReason.INVALID_METADATA
            }
        }
        return null
    }

    private fun parseGgufVersion(tags: List<String?>?): Int? = tags.orEmpty()
        .asSequence()
        .filterNotNull()
        .mapNotNull { GGUF_VERSION.matchEntire(it)?.groupValues?.get(1)?.toIntOrNull() }
        .singleOrNull()

    private fun isValidRepositoryId(modelId: String): Boolean {
        if (modelId.isEmpty() || modelId != modelId.trim()) return false
        if (modelId.length > DescriptorLimits.MAX_MODEL_ID_LENGTH || '\\' in modelId) return false
        val segments = modelId.split('/')
        return segments.size in 1..2 && segments.all { segment ->
            segment.isNotEmpty() &&
                segment.length <= DescriptorLimits.MAX_REPOSITORY_SEGMENT_LENGTH &&
                segment != "." && segment != ".." && ".." !in segment && "--" !in segment &&
                segment.first() != '.' && segment.first() != '-' &&
                segment.last() != '.' && segment.last() != '-' &&
                segment.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }
        }
    }

    private fun isValidRelativePath(path: String): Boolean {
        if (path.isEmpty() || path != path.trim() || path.length > DescriptorLimits.MAX_RELATIVE_PATH_LENGTH) {
            return false
        }
        if (path.startsWith('/') || path.startsWith('\\') || '\\' in path) return false
        return path.split('/').all { segment ->
            segment.isNotEmpty() && segment != "." && segment != ".." &&
                segment.length <= DescriptorLimits.MAX_PATH_SEGMENT_LENGTH &&
                segment.none { it.code < 32 || it.code == 127 || it in ":*?\"<>|" }
        }
    }

    private fun isSafeArchitecture(value: String?): Boolean = value == null ||
        value.isNotEmpty() && value.length <= MAX_ARCHITECTURE_LENGTH &&
        value.all { it.isLetterOrDigit() || it == '_' || it == '-' || it == '.' }

    private fun isSafeOpaqueIdentity(value: String?): Boolean = value == null ||
        value.isNotEmpty() && value.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
        value.none { it.code < 32 || it.code == 127 }

    private fun isBoundedString(value: String?): Boolean = value == null ||
        value.length <= DescriptorLimits.MAX_METADATA_STRING_LENGTH &&
        value.none { it.code < 32 || it.code == 127 }

    private fun isBoundedCollection(value: Collection<*>?): Boolean =
        value == null || value.size <= DescriptorLimits.MAX_METADATA_COLLECTION_SIZE

    private fun isValidFileSize(value: Long?): Boolean =
        value == null || value in 1..DescriptorLimits.MAX_FILE_BYTES

    private fun isValidParameterCount(value: Long?): Boolean =
        value == null || value in 1..DescriptorLimits.MAX_PARAMETERS

    private fun invalid(reason: AssessmentReason) = DescriptorBuildResult.Invalid(listOf(reason))

    private data class CommonIdentity(val repositoryId: String, val revision: String)

    private sealed interface ShapeResult {
        data class Valid(val shape: TransformerShape?) : ShapeResult
        data object Invalid : ShapeResult
    }

    private companion object {
        val IMMUTABLE_REVISION = Regex("^[0-9a-fA-F]{40,64}$")
        val GGUF_VERSION = Regex("(?i)^gguf(?:[-_:]?v(?:ersion)?[-_:]?)?(\\d+)$")
        const val MAX_ARCHITECTURE_LENGTH = 64
        const val MAX_TRANSFORMER_FIELD = 1_048_576
        const val MAX_LFS_POINTER_BYTES = 1_048_576
    }
}
