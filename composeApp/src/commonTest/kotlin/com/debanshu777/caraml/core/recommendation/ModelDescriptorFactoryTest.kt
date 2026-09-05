package com.debanshu777.caraml.core.recommendation

import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponent
import com.debanshu777.huggingfacemanager.sdcpp.SdCppModelSetup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelDescriptorFactoryTest {
    private val factory = ModelDescriptorFactory()

    @Test
    fun rejectsOverflowAndDoesNotAssumeAQuantization() {
        val invalid = factory.buildLlm(
            detail(totalParameters = Long.MAX_VALUE),
            file(size = -1L),
            null,
        )
        assertIs<DescriptorBuildResult.Invalid>(invalid)

        val missingVariant = factory.buildProvisional(listModel(numParameters = 7_000_000_000L))
        assertIs<DescriptorBuildResult.NeedsVariant>(missingVariant)
        assertNull(missingVariant.assumedQuantization)
    }

    @Test
    fun quantizationParserUsesTokenBoundariesAndIgnoresCase() {
        assertEquals(
            QuantizationEvidence.Known("Q4_K_M"),
            QuantizationParser.parseFilename("weights-q4_k_m.GGUF"),
        )
        assertEquals(
            QuantizationEvidence.Unknown,
            QuantizationParser.parseFilename("acmeq4_k_mish.gguf"),
        )
        assertIs<QuantizationEvidence.Mixed>(
            QuantizationParser.parseFilename("weights-Q4_K_M-Q8_0.gguf"),
        )
    }

    @Test
    fun preservesImmutableIdentityAndPrefersLfsSize() {
        val result = assertIs<DescriptorBuildResult.Ready>(
            factory.buildLlm(
                detail(),
                file(
                    size = 12L,
                    lfsSize = 34L,
                    lfsOid = "sha256:lfs",
                    oid = "git-oid",
                    xetHash = "xet-hash",
                ),
                null,
            ),
        )
        val descriptor = assertIs<LlmModelDescriptor>(result.descriptor)
        assertEquals(REVISION, descriptor.revision)
        assertEquals(34L, descriptor.file.sizeBytes)
        assertEquals("sha256:lfs", descriptor.file.lfsOid)
        assertEquals("git-oid", descriptor.file.gitOid)
        assertEquals("xet-hash", descriptor.file.xetHash)
        assertTrue(descriptor.file.evidence.any { it.detail == "size:lfs" })
    }

    @Test
    fun rejectsInvalidSecondarySizeEvenWhenLfsSizeWouldOtherwiseWin() {
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildLlm(
                detail(),
                file(size = -1L, lfsSize = 34L),
                null,
            ),
        )
    }

    @Test
    fun derivesHeadDimensionOnlyForAnExactDivision() {
        val divisible = assertIs<DescriptorBuildResult.Ready>(
            factory.buildLlm(
                detail(),
                file(),
                TransformerConfigResponse(
                    numHiddenLayers = 32,
                    numKeyValueHeads = 8,
                    numAttentionHeads = 32,
                    hiddenSize = 4_096,
                    maxPositionEmbeddings = 8_192,
                ),
            ),
        )
        assertEquals(128, assertIs<LlmModelDescriptor>(divisible.descriptor).transformerShape?.headDim)

        val nonDivisible = assertIs<DescriptorBuildResult.Ready>(
            factory.buildLlm(
                detail(),
                file(),
                TransformerConfigResponse(numAttentionHeads = 3, hiddenSize = 4_096),
            ),
        )
        assertNull(assertIs<LlmModelDescriptor>(nonDivisible.descriptor).transformerShape?.headDim)
    }

    @Test
    fun enforcesFileBundleParameterContextAndPathCeilings() {
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildLlm(detail(), file(size = DescriptorLimits.MAX_FILE_BYTES + 1L), null),
        )
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildLlm(detail(totalParameters = DescriptorLimits.MAX_PARAMETERS + 1L), file(), null),
        )
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildLlm(
                detail(contextLength = DescriptorLimits.MAX_CONTEXT_TOKENS + 1),
                file(),
                null,
            ),
        )
        assertIs<DescriptorBuildResult.Invalid>(factory.buildLlm(detail(), file(path = "../model.gguf"), null))
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildLlm(detail(), file(path = "a".repeat(1_025) + ".gguf"), null),
        )

        val setup = SdCppModelSetup("family", "description", emptyList(), selfContained = true)
        val tooLargeBundle = listOf(
            file(path = "a.safetensors", size = DescriptorLimits.MAX_FILE_BYTES),
            file(path = "b.safetensors", size = DescriptorLimits.MAX_FILE_BYTES),
            file(path = "c.safetensors", size = 1L),
        )
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildDiffusion(detail(), tooLargeBundle, setup, DiffusionMode.IMAGE),
        )
    }

    @Test
    fun rejectsDuplicateEmptyExcessAndMissingDiffusionComponents() {
        val setup = SdCppModelSetup(
            familyLabel = "family",
            description = "description",
            components = listOf(
                SdCppComponent(ComponentRole.VAE, "owner/model", "vae.safetensors"),
            ),
        )
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildDiffusion(
                detail(),
                listOf(file(path = "main.safetensors"), file(path = "main.safetensors")),
                setup,
                DiffusionMode.IMAGE,
            ),
        )
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildDiffusion(detail(), listOf(file(path = "")), setup, DiffusionMode.IMAGE),
        )
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildDiffusion(detail(), listOf(file(path = "main.safetensors")), setup, DiffusionMode.IMAGE),
        )
        assertIs<DescriptorBuildResult.Invalid>(
            factory.buildDiffusion(
                detail(),
                (0..DescriptorLimits.MAX_COMPONENTS).map { file(path = "component-$it.safetensors") },
                SdCppModelSetup("family", "description", emptyList(), selfContained = true),
                DiffusionMode.IMAGE,
            ),
        )
    }

    @Test
    fun preservesMixedQuantizationDistributionForACompleteDiffusionGraph() {
        val setup = SdCppModelSetup(
            familyLabel = "family",
            description = "description",
            components = listOf(
                SdCppComponent(ComponentRole.VAE, "owner/model", "vae-F16.safetensors"),
            ),
        )
        val result = assertIs<DescriptorBuildResult.Ready>(
            factory.buildDiffusion(
                detail(),
                listOf(
                    file(path = "main-Q4_K_M.gguf"),
                    file(path = "vae-F16.safetensors"),
                ),
                setup,
                DiffusionMode.IMAGE,
            ),
        )
        val descriptor = assertIs<DiffusionModelDescriptor>(result.descriptor)
        assertEquals(setOf("Q4_K_M", "F16"), descriptor.quantizationDistribution)
        assertEquals(ComponentRole.VAE, descriptor.components.single { !it.isPrimary }.role)
    }

    @Test
    fun rejectsOverlongAndOversizedRemoteCollections() {
        val overlongDetail = detail().copy(
            gguf = detail().gguf?.copy(architecture = "a".repeat(65)),
        )
        assertIs<DescriptorBuildResult.Invalid>(factory.buildLlm(overlongDetail, file(), null))

        val oversizedTags = detail().copy(tags = List(257) { "tag-$it" })
        assertIs<DescriptorBuildResult.Invalid>(factory.buildLlm(oversizedTags, file(), null))
    }

    @Test
    fun descriptorSnapshotsCallerOwnedCollections() {
        val evidence = mutableListOf(Evidence(AssessmentReason.INVALID_METADATA, Confidence.HIGH))
        val features = mutableSetOf("feature-a")
        val descriptor = LlmModelDescriptor(
            repositoryId = "owner/model",
            revision = REVISION,
            file = ModelFileIdentity(
                repositoryId = "owner/model",
                revision = REVISION,
                path = "model.gguf",
                sizeBytes = 1L,
                gitOid = null,
                lfsOid = null,
                xetHash = null,
                evidence = evidence,
            ),
            architecture = "llama",
            quantization = QuantizationEvidence.Unknown,
            parameterCount = null,
            contextLimit = null,
            transformerShape = null,
            ggufVersion = null,
            requiredEngineFeatures = features,
            evidence = evidence,
        )

        evidence.clear()
        features.clear()

        assertEquals(1, descriptor.evidence.size)
        assertEquals(1, descriptor.file.evidence.size)
        assertEquals(setOf("feature-a"), descriptor.requiredEngineFeatures)
    }

    private fun detail(
        totalParameters: Long? = 7_000_000_000L,
        contextLength: Int? = 8_192,
    ) = ModelDetailResponse(
        id = "owner/model",
        modelId = "owner/model",
        sha = REVISION,
        gguf = ModelDetailResponse.Gguf(
            architecture = "llama",
            contextLength = contextLength,
            total = totalParameters,
        ),
    )

    private fun file(
        path: String = "model-Q4_K_M.gguf",
        size: Long? = 4_000_000_000L,
        lfsSize: Long? = null,
        lfsOid: String? = null,
        oid: String? = null,
        xetHash: String? = null,
    ) = ModelFileTreeResponse(
        path = path,
        size = size,
        type = "file",
        oid = oid,
        xetHash = xetHash,
        lfs = lfsSize?.let { ModelFileTreeResponse.Lfs(oid = lfsOid, size = it) },
    )

    private fun listModel(numParameters: Long?) = ListModelsResponse.Model(
        id = "owner/model",
        numParameters = numParameters,
    )

    private companion object {
        const val REVISION = "0123456789abcdef0123456789abcdef01234567"
    }
}
