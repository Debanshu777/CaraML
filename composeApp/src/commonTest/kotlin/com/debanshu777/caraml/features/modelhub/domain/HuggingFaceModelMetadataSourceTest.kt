package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.InstalledDescriptorLookup
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptorFactory
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.exactInstalledDescriptorLookup
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class HuggingFaceModelMetadataSourceTest {
    @Test
    fun exactLookupRequiresRepositoryRevisionPathSizeAndCanonicalObjectId() = runTest {
        val fixture = Fixture()
        val source = fixture.source()
        assertIs<InstalledDescriptorLookup.Ready>(
            source.findExact(fixture.repositoryId, ModelHubBrowseMode.LanguageModels, listOf(fixture.identity)),
        )

        val mismatches = listOf(
            fixture.identity.copyForExactTest(repositoryId = "other/model"),
            fixture.identity.copyForExactTest(revision = "b".repeat(40)),
            fixture.identity.copyForExactTest(path = "renamed.gguf"),
            fixture.identity.copyForExactTest(sizeBytes = fixture.identity.sizeBytes + 1L),
            fixture.identity.copyForExactTest(lfsOid = "sha256:${"f".repeat(64)}"),
        )
        mismatches.forEach { mismatched ->
            val rejected = assertIs<InstalledDescriptorLookup.Rejected>(
                source.findExact(fixture.repositoryId, ModelHubBrowseMode.LanguageModels, listOf(mismatched)),
            )
            assertEquals(listOf(AssessmentReason.INVALID_METADATA), rejected.reasons)
        }
    }

    @Test
    fun gatewayFailureIsRetryableForRepairButBrowseBehaviorRemainsNeedsInformation() = runTest {
        val fixture = Fixture()
        fixture.gateway.detailFailure = true
        val source = fixture.source()

        assertEquals(
            InstalledDescriptorLookup.RetryableUnavailable,
            source.findExact(fixture.repositoryId, ModelHubBrowseMode.LanguageModels, listOf(fixture.identity)),
        )
        assertIs<RepositoryVariantSet.NeedsInformation>(
            source.describeVariants(fixture.repositoryId, ModelHubBrowseMode.LanguageModels),
        )
    }

    @Test
    fun malformedExactIdentitySetIsRejectedBeforeGatewayAccess() = runTest {
        val fixture = Fixture()
        val rejected = assertIs<InstalledDescriptorLookup.Rejected>(
            fixture.source().findExact(
                fixture.repositoryId,
                ModelHubBrowseMode.LanguageModels,
                identities = emptyList(),
            ),
        )

        assertEquals(listOf(AssessmentReason.INVALID_METADATA), rejected.reasons)
        assertEquals(0, fixture.gateway.detailCalls)
    }

    @Test
    fun exactLookupFindsOneRequestedVariantWithoutChangingBoundedBrowseBehavior() = runTest {
        val fixture = Fixture()
        fixture.gateway.tree = (0..64).map { index ->
            ModelFileTreeResponse(
                path = "model-$index-Q4_K_M.gguf",
                size = 100L + index,
                oid = "object-$index",
                type = "file",
            )
        }
        val target = ModelFileIdentity(
            repositoryId = fixture.repositoryId,
            revision = "a".repeat(40),
            path = "model-64-Q4_K_M.gguf",
            sizeBytes = 164L,
            gitOid = "object-64",
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        val source = fixture.source()

        assertIs<RepositoryVariantSet.SelectVariant>(
            source.describeVariants(fixture.repositoryId, ModelHubBrowseMode.LanguageModels),
        )
        val ready = assertIs<InstalledDescriptorLookup.Ready>(
            source.findExact(fixture.repositoryId, ModelHubBrowseMode.LanguageModels, listOf(target)),
        )
        assertEquals(target.path, assertIs<LlmModelDescriptor>(ready.descriptor).file.path)
    }

    @Test
    fun zeroAndMultipleExactVariantsAreRejected() {
        val fixture = Fixture()
        val exact = RepositoryVariant(fixture.descriptor, "exact")
        val zero = exactInstalledDescriptorLookup(
            repositoryId = fixture.repositoryId,
            mode = ModelHubBrowseMode.LanguageModels,
            identities = listOf(fixture.identity.copyForExactTest(path = "other.gguf")),
            variants = RepositoryVariantSet.Ready(listOf(exact)),
        )
        val multiple = exactInstalledDescriptorLookup(
            repositoryId = fixture.repositoryId,
            mode = ModelHubBrowseMode.LanguageModels,
            identities = listOf(fixture.identity),
            variants = RepositoryVariantSet.Ready(listOf(exact, exact.copy(displayName = "same identity again"))),
        )

        assertEquals(
            listOf(AssessmentReason.INVALID_METADATA),
            assertIs<InstalledDescriptorLookup.Rejected>(zero).reasons,
        )
        assertEquals(
            listOf(AssessmentReason.INVALID_METADATA),
            assertIs<InstalledDescriptorLookup.Rejected>(multiple).reasons,
        )
    }

    private class Fixture {
        val repositoryId = "owner/model"
        private val revision = "a".repeat(40)
        private val objectDigest = "c".repeat(64)
        val identity = ModelFileIdentity(
            repositoryId = repositoryId,
            revision = revision,
            path = "model-Q4_K_M.gguf",
            sizeBytes = 100L,
            gitOid = null,
            lfsOid = "sha256:$objectDigest",
            xetHash = null,
            evidence = emptyList(),
        )
        val descriptor = LlmModelDescriptor(
            repositoryId = repositoryId,
            revision = revision,
            file = identity,
            architecture = "llama",
            quantization = QuantizationEvidence.Known("Q4_K_M"),
            parameterCount = 1_000_000L,
            contextLimit = 4_096,
            transformerShape = null,
            ggufVersion = 3,
            requiredEngineFeatures = emptyList(),
            evidence = emptyList(),
        )
        val gateway = FakeGateway(
            detail = ModelDetailResponse(
                id = repositoryId,
                modelId = repositoryId,
                sha = revision,
                gguf = ModelDetailResponse.Gguf(
                    architecture = "llama",
                    contextLength = 4_096,
                    total = 1_000_000L,
                ),
                tags = listOf("gguf-v3"),
            ),
            tree = listOf(
                ModelFileTreeResponse(
                    path = identity.path,
                    size = identity.sizeBytes,
                    oid = identity.lfsOid,
                    type = "file",
                ),
            ),
        )

        fun source() = HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) { null }
    }

    private class FakeGateway(
        private val detail: ModelDetailResponse,
        var tree: List<ModelFileTreeResponse>,
    ) : HuggingFaceMetadataGateway {
        var detailFailure = false
        var detailCalls = 0

        override suspend fun getStrictDetail(repositoryId: String): Result<ModelDetailResponse, DataError.Network> {
            detailCalls += 1
            return if (detailFailure) Result.Error(DataError.Network.Unknown) else Result.Success(detail)
        }

        override suspend fun getTree(
            repositoryId: String,
            revision: String,
            filter: ModelFileWeightFilter,
        ): Result<List<ModelFileTreeResponse>, DataError.Network> = Result.Success(tree)

        override suspend fun getConfig(
            repositoryId: String,
            revision: String,
        ): Result<TransformerConfigResponse, DataError.Network> = Result.Success(TransformerConfigResponse())
    }
}

private fun ModelFileIdentity.copyForExactTest(
    repositoryId: String = this.repositoryId,
    revision: String = this.revision,
    path: String = this.path,
    sizeBytes: Long = this.sizeBytes,
    gitOid: String? = this.gitOid,
    lfsOid: String? = this.lfsOid,
    xetHash: String? = this.xetHash,
) = ModelFileIdentity(repositoryId, revision, path, sizeBytes, gitOid, lfsOid, xetHash, evidence)
