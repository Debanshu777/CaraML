package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.rating.SdArchitecture
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
        val ggufVersion = parseGgufVersion(detail.tags)
        val evidence = buildList {
            add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "repository:model-detail"))
            add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "revision:hub-sha"))
            add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "format:file-extension"))
            addAll(identity.evidence)
            if (parameterCount != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "parameters:hub-metadata"))
            }
            if (architecture != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "architecture:hub-metadata"))
            }
            if (contextLimit != null) {
                val source = if (detail.gguf?.contextLength != null) "hub-metadata" else "transformer-config"
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "context:$source"))
            }
            add(
                Evidence(
                    AssessmentReason.METADATA_VALIDATED,
                    if (quantization is QuantizationEvidence.Unknown) Confidence.LOW else Confidence.MEDIUM,
                    "quantization:filename",
                ),
            )
            if (ggufVersion != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "gguf-version:tag"))
            }
            if (shape != null) {
                add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "shape:transformer-config"))
                if (shape.layerCount != null) {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "layer-count:transformer-config"))
                }
                if (shape.kvHeadCount != null) {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "kv-head-count:transformer-config"))
                }
                if (shape.attentionHeadCount != null) {
                    add(
                        Evidence(
                            AssessmentReason.METADATA_VALIDATED,
                            Confidence.MEDIUM,
                            "attention-head-count:transformer-config",
                        ),
                    )
                }
                if (shape.hiddenSize != null) {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "hidden-size:transformer-config"))
                }
                if (shape.headDim != null) {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "head-dim:transformer-config"))
                }
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
                ggufVersion = ggufVersion,
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
        if (setup.components.any { it.repoId != common.repositoryId }) {
            return DescriptorBuildResult.NeedsVariant(
                repositoryId = common.repositoryId,
                reasons = listOf(AssessmentReason.MISSING_REQUIRED_COMPONENT),
                assumedQuantization = null,
            )
        }
        val width = setup.recommendedParams?.width
        val height = setup.recommendedParams?.height
        if (!isValidImageDimension(width) || !isValidImageDimension(height)) {
            return invalid(AssessmentReason.INVALID_METADATA)
        }

        val identities = ArrayList<ModelFileIdentity>(files.size)
        val seenFiles = HashSet<RepositoryPath>(files.size)
        var bundleBytes = 0L
        for (file in files) {
            val identity = buildFileIdentity(common.repositoryId, common.revision, file)
                ?: return invalid(fileValidationReason(file))
            if (!seenFiles.add(RepositoryPath(identity.repositoryId, identity.path))) {
                return invalid(AssessmentReason.INVALID_FILE_PATH)
            }
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

        val filesByKey = identities.associateBy { RepositoryPath(it.repositoryId, it.path) }
        val componentsByKey = setup.components.associateBy { RepositoryPath(it.repoId, it.filePath) }
        val missingRequired = setup.components.any { component ->
            component.required && RepositoryPath(component.repoId, component.filePath) !in filesByKey
        }
        if (missingRequired) return invalid(AssessmentReason.MISSING_REQUIRED_COMPONENT)

        val primaryFiles = identities.mapTo(mutableSetOf()) { RepositoryPath(it.repositoryId, it.path) }
            .apply { removeAll(componentsByKey.keys) }
        if (primaryFiles.isEmpty() && !setup.selfContained) {
            return invalid(AssessmentReason.MISSING_REQUIRED_COMPONENT)
        }
        if (primaryFiles.size != 1) return invalid(AssessmentReason.INVALID_METADATA)

        val components = identities.map { identity ->
            val key = RepositoryPath(identity.repositoryId, identity.path)
            val setupComponent = componentsByKey[key]
            DiffusionComponentDescriptor(
                file = identity,
                role = setupComponent?.role,
                required = setupComponent?.required ?: true,
                isPrimary = key in primaryFiles || setup.selfContained && componentsByKey.isEmpty(),
                quantization = QuantizationParser.parseFilename(identity.path),
            )
        }
        val distribution = components
            .flatMapTo(linkedSetOf()) { it.quantization.quantizations }
        val architecture = resolveDiffusionArchitecture(common.repositoryId, components)
        return DescriptorBuildResult.Ready(
            DiffusionModelDescriptor(
                repositoryId = common.repositoryId,
                revision = common.revision,
                components = components,
                mode = mode,
                family = setup.familyLabel,
                architecture = architecture.value,
                width = width,
                height = height,
                quantizationDistribution = distribution,
                requiredComponentsPresent = true,
                requiredEngineFeatures = emptySet(),
                evidence = buildList {
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "repository:model-detail"))
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "revision:hub-sha"))
                    components.forEach { addAll(it.file.evidence) }
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "format:primary-file-extension"))
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "mode:caller"))
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "family:setup"))
                    add(architecture.evidence)
                    add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "required-components:setup"))
                    add(
                        Evidence(
                            AssessmentReason.METADATA_VALIDATED,
                            if (distribution.isEmpty()) Confidence.LOW else Confidence.MEDIUM,
                            "quantization-distribution:filenames",
                        ),
                    )
                    if (width != null) {
                        add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "width:setup"))
                    }
                    if (height != null) {
                        add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.MEDIUM, "height:setup"))
                    }
                    components.forEach { component ->
                        if (component.role != null) {
                            add(
                                Evidence(
                                    AssessmentReason.METADATA_VALIDATED,
                                    Confidence.HIGH,
                                    "component-role:setup",
                                ),
                            )
                        }
                        add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "component-required:setup"))
                        add(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, "component-primary:derived"))
                        add(
                            Evidence(
                                AssessmentReason.METADATA_VALIDATED,
                                if (component.quantization is QuantizationEvidence.Unknown) {
                                    Confidence.LOW
                                } else {
                                    Confidence.MEDIUM
                                },
                                "component-quantization:filename",
                            ),
                        )
                    }
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

    private fun resolveDiffusionArchitecture(
        repositoryId: String,
        components: List<DiffusionComponentDescriptor>,
    ): DiffusionArchitectureResolution {
        DIFFUSION_ARCHITECTURE_BY_REPOSITORY[repositoryId]?.let { architecture ->
            return DiffusionArchitectureResolution(
                value = architecture,
                evidence = Evidence(
                    AssessmentReason.METADATA_VALIDATED,
                    Confidence.HIGH,
                    "architecture:registry-repository",
                ),
            )
        }
        if (repositoryId in SIZE_DISAMBIGUATED_WAN_REPOSITORIES) {
            var primaryBytes = 0L
            for (component in components) {
                if (!component.isPrimary) continue
                primaryBytes = when (val result = checkedAdd(primaryBytes, component.file.sizeBytes)) {
                    is CheckedLong.Invalid -> return unknownDiffusionArchitecture()
                    is CheckedLong.Value -> result.value
                }
            }
            if (primaryBytes > 0L) {
                return DiffusionArchitectureResolution(
                    value = if (primaryBytes > WAN_LARGE_PRIMARY_BYTES) {
                        SdArchitecture.WAN_LARGE
                    } else {
                        SdArchitecture.WAN_SMALL
                    },
                    evidence = Evidence(
                        AssessmentReason.METADATA_VALIDATED,
                        Confidence.MEDIUM,
                        "architecture:registry-primary-size",
                    ),
                )
            }
        }
        return unknownDiffusionArchitecture()
    }

    private fun unknownDiffusionArchitecture() = DiffusionArchitectureResolution(
        value = null,
        evidence = Evidence(
            AssessmentReason.UNKNOWN_ARCHITECTURE,
            Confidence.LOW,
            "architecture:registry-unrecognized",
        ),
    )

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

    private fun isValidImageDimension(value: Int?): Boolean =
        value == null || value in 1..DescriptorLimits.MAX_IMAGE_DIMENSION

    private fun invalid(reason: AssessmentReason) = DescriptorBuildResult.Invalid(listOf(reason))

    private data class CommonIdentity(val repositoryId: String, val revision: String)

    private data class RepositoryPath(val repositoryId: String, val path: String)

    private data class DiffusionArchitectureResolution(
        val value: SdArchitecture?,
        val evidence: Evidence,
    )

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
        const val WAN_LARGE_PRIMARY_BYTES = 12L * 1_073_741_824L

        val SIZE_DISAMBIGUATED_WAN_REPOSITORIES = setOf(
            "Comfy-Org/Wan_2.1_ComfyUI_repackaged",
            "Comfy-Org/Wan_2.2_ComfyUI_Repackaged",
        )

        val DIFFUSION_ARCHITECTURE_BY_REPOSITORY = mapOf(
            "CompVis/stable-diffusion-v-1-4-original" to SdArchitecture.SD1,
            "runwayml/stable-diffusion-v1-5" to SdArchitecture.SD1,
            "stabilityai/stable-diffusion-2-1" to SdArchitecture.SD1,
            "stabilityai/sd-turbo" to SdArchitecture.SD1,
            "stabilityai/stable-diffusion-xl-base-1.0" to SdArchitecture.SDXL,
            "stabilityai/sdxl-turbo" to SdArchitecture.SDXL,
            "segmind/SSD-1B" to SdArchitecture.SDXL,
            "stabilityai/stable-diffusion-3-medium" to SdArchitecture.SD3,
            "stabilityai/stable-diffusion-3.5-large" to SdArchitecture.SD3,
            "Comfy-Org/stable-diffusion-3.5-fp8" to SdArchitecture.SD3,
            "black-forest-labs/FLUX.1-dev" to SdArchitecture.FLUX,
            "black-forest-labs/FLUX.1-schnell" to SdArchitecture.FLUX,
            "leejet/FLUX.1-dev-gguf" to SdArchitecture.FLUX,
            "leejet/FLUX.1-schnell-gguf" to SdArchitecture.FLUX,
            "black-forest-labs/FLUX.2-dev" to SdArchitecture.FLUX,
            "city96/FLUX.2-dev-gguf" to SdArchitecture.FLUX,
            "black-forest-labs/FLUX.2-klein-4B" to SdArchitecture.FLUX,
            "leejet/FLUX.2-klein-4B-GGUF" to SdArchitecture.FLUX,
            "black-forest-labs/FLUX.2-klein-base-4B" to SdArchitecture.FLUX,
            "leejet/FLUX.2-klein-base-4B-GGUF" to SdArchitecture.FLUX,
            "black-forest-labs/FLUX.2-klein-9B" to SdArchitecture.FLUX,
            "leejet/FLUX.2-klein-9B-GGUF" to SdArchitecture.FLUX,
            "black-forest-labs/FLUX.2-klein-base-9B" to SdArchitecture.FLUX,
            "leejet/FLUX.2-klein-base-9B-GGUF" to SdArchitecture.FLUX,
            "black-forest-labs/FLUX.1-Kontext-dev" to SdArchitecture.FLUX,
            "QuantStack/FLUX.1-Kontext-dev-GGUF" to SdArchitecture.FLUX,
            "city96/Wan2.1-T2V-14B-gguf" to SdArchitecture.WAN_LARGE,
            "QuantStack/Wan2.1_14B_VACE-GGUF" to SdArchitecture.WAN_LARGE,
            "city96/Wan2.1-I2V-14B-480P-gguf" to SdArchitecture.WAN_LARGE,
            "city96/Wan2.1-I2V-14B-720P-gguf" to SdArchitecture.WAN_LARGE,
            "city96/Wan2.1-FLF2V-14B-720P-gguf" to SdArchitecture.WAN_LARGE,
            "calcuis/wan-1.3b-gguf" to SdArchitecture.WAN_SMALL,
            "QuantStack/Wan2.2-TI2V-5B-GGUF" to SdArchitecture.WAN_SMALL,
            "QuantStack/Wan2.2-T2V-A14B-GGUF" to SdArchitecture.WAN_LARGE,
            "QuantStack/Wan2.2-I2V-A14B-GGUF" to SdArchitecture.WAN_LARGE,
        )
    }
}
