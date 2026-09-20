package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
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
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponent
import com.debanshu777.huggingfacemanager.sdcpp.SdCppModelSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class HuggingFaceModelMetadataSourceTest {
    @Test
    fun exactLookupUsesInstalledOwnerRevisionInsteadOfCurrentHead() = runTest {
        val fixture = Fixture()
        val currentHead = "b".repeat(40)
        fixture.gateway.currentDetail = fixture.gateway.currentDetail.copy(sha = currentHead)

        val ready = assertIs<InstalledDescriptorLookup.Ready>(
            fixture.source().findExact(
                fixture.repositoryId,
                ModelHubBrowseMode.LanguageModels,
                listOf(fixture.identity),
            ),
        )

        assertEquals(fixture.identity.revision, ready.descriptor.revision)
        assertEquals(emptyList(), fixture.gateway.unqualifiedDetailRequests)
        assertEquals(
            listOf(fixture.repositoryId to fixture.identity.revision),
            fixture.gateway.qualifiedDetailRequests,
        )
        assertEquals(
            listOf(fixture.repositoryId to fixture.identity.revision),
            fixture.gateway.treeRequests,
        )
        assertEquals(
            listOf(fixture.repositoryId to fixture.identity.revision),
            fixture.gateway.configRequests,
        )
    }

    @Test
    fun exactDiffusionLookupPinsEachExternalRepositoryToItsInstalledRevision() = runTest {
        val owner = "owner/diffusion"
        val external = "owner/components"
        val ownerRevision = "a".repeat(40)
        val externalRevision = "c".repeat(40)
        val primary = identity(owner, ownerRevision, "model-Q4_K_M.gguf", 200L, "primary-oid")
        val component = identity(external, externalRevision, "vae.safetensors", 50L, "component-oid")
        val setup = SdCppModelSetup(
            familyLabel = "Exact diffusion",
            description = "Pinned external component fixture",
            components = listOf(SdCppComponent(ComponentRole.VAE, external, component.path)),
        )
        val gateway = RevisionGateway(
            currentDetails = mapOf(
                owner to detail(owner, "b".repeat(40)),
                external to detail(external, "d".repeat(40)),
            ),
            pinnedDetails = mapOf(
                (owner to ownerRevision) to detail(owner, ownerRevision),
                (external to externalRevision) to detail(external, externalRevision),
            ),
            trees = mapOf(
                (owner to ownerRevision) to listOf(file(primary)),
                (external to externalRevision) to listOf(file(component)),
            ),
        )
        val source = HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) { setup }

        val ready = assertIs<InstalledDescriptorLookup.Ready>(
            source.findExact(owner, ModelHubBrowseMode.DiffusionImage, listOf(primary, component)),
        )

        val descriptor = assertIs<DiffusionModelDescriptor>(ready.descriptor)
        assertEquals(
            setOf(owner to ownerRevision, external to externalRevision),
            descriptor.components.map { it.file.repositoryId to it.file.revision }.toSet(),
        )
        assertEquals(emptyList(), gateway.unqualifiedDetailRequests)
        assertEquals(
            listOf(owner to ownerRevision, external to externalRevision),
            gateway.qualifiedDetailRequests,
        )
        assertEquals(
            listOf(owner to ownerRevision, external to externalRevision),
            gateway.treeRequests,
        )
    }

    @Test
    fun conflictingOrDuplicateRepositoryRevisionsRejectBeforeGatewayAccess() = runTest {
        val fixture = Fixture()
        val conflicting = fixture.identity.copyForExactTest(path = "other.gguf", revision = "b".repeat(40))
        val ambiguousDuplicate = fixture.identity.copyForExactTest(lfsOid = "sha256:${"d".repeat(64)}")
        val source = fixture.source()

        assertIs<InstalledDescriptorLookup.Rejected>(
            source.findExact(
                fixture.repositoryId,
                ModelHubBrowseMode.LanguageModels,
                listOf(fixture.identity, conflicting),
            ),
        )
        assertIs<InstalledDescriptorLookup.Rejected>(
            source.findExact(
                fixture.repositoryId,
                ModelHubBrowseMode.LanguageModels,
                listOf(fixture.identity, ambiguousDuplicate),
            ),
        )

        assertEquals(emptyList(), fixture.gateway.unqualifiedDetailRequests)
        assertEquals(emptyList(), fixture.gateway.qualifiedDetailRequests)
    }

    @Test
    fun exactLookupRejectsReturnedShaDifferentFromInstalledRevision() = runTest {
        val fixture = Fixture()
        fixture.gateway.pinnedDetail = fixture.gateway.pinnedDetail.copy(sha = "b".repeat(40))

        val rejected = assertIs<InstalledDescriptorLookup.Rejected>(
            fixture.source().findExact(
                fixture.repositoryId,
                ModelHubBrowseMode.LanguageModels,
                listOf(fixture.identity),
            ),
        )

        assertEquals(listOf(AssessmentReason.INVALID_REVISION), rejected.reasons)
        assertEquals(emptyList(), fixture.gateway.treeRequests)
    }

    @Test
    fun exactLookupPropagatesCancellationFromPinnedDetail() = runTest {
        val fixture = Fixture()
        fixture.gateway.detailThrowable = CancellationException("cancelled")

        assertFailsWith<CancellationException> {
            fixture.source().findExact(
                fixture.repositoryId,
                ModelHubBrowseMode.LanguageModels,
                listOf(fixture.identity),
            )
        }
    }

    @Test
    fun exactLookupRequiresRepositoryRevisionPathSizeAndCanonicalObjectId() = runTest {
        val fixture = Fixture()
        val source = fixture.source()
        assertIs<InstalledDescriptorLookup.Ready>(
            source.findExact(fixture.repositoryId, ModelHubBrowseMode.LanguageModels, listOf(fixture.identity)),
        )

        val mismatches = listOf(
            fixture.identity.copyForExactTest(repositoryId = "other/model") to AssessmentReason.INVALID_METADATA,
            fixture.identity.copyForExactTest(revision = "b".repeat(40)) to AssessmentReason.INVALID_REVISION,
            fixture.identity.copyForExactTest(path = "renamed.gguf") to AssessmentReason.INVALID_METADATA,
            fixture.identity.copyForExactTest(sizeBytes = fixture.identity.sizeBytes + 1L) to AssessmentReason.INVALID_METADATA,
            fixture.identity.copyForExactTest(lfsOid = "sha256:${"f".repeat(64)}") to AssessmentReason.INVALID_METADATA,
        )
        mismatches.forEach { (mismatched, reason) ->
            val rejected = assertIs<InstalledDescriptorLookup.Rejected>(
                source.findExact(fixture.repositoryId, ModelHubBrowseMode.LanguageModels, listOf(mismatched)),
            )
            assertEquals(listOf(reason), rejected.reasons)
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
        assertEquals(
            listOf(fixture.repositoryId to fixture.identity.revision),
            fixture.gateway.qualifiedDetailRequests,
        )
        assertEquals(listOf(fixture.repositoryId), fixture.gateway.unqualifiedDetailRequests)
    }

    @Test
    fun missingOptionalTransformerConfigDoesNotBlockExactLookup() = runTest {
        val fixture = Fixture()
        fixture.gateway.configError = DataError.Network.NotFound

        assertIs<InstalledDescriptorLookup.Ready>(
            fixture.source().findExact(
                fixture.repositoryId,
                ModelHubBrowseMode.LanguageModels,
                listOf(fixture.identity),
            ),
        )
    }

    @Test
    fun transformerConfigNetworkAuthAndServerFailuresAreRetryableForExactLookup() = runTest {
        listOf(
            DataError.Network.NoInternet,
            DataError.Network.Unauthorized,
            DataError.Network.ServerError,
        ).forEach { error ->
            val fixture = Fixture()
            fixture.gateway.configError = error

            assertEquals(
                InstalledDescriptorLookup.RetryableUnavailable,
                fixture.source().findExact(
                    fixture.repositoryId,
                    ModelHubBrowseMode.LanguageModels,
                    listOf(fixture.identity),
                ),
            )
        }
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
        assertEquals(emptyList(), fixture.gateway.unqualifiedDetailRequests)
        assertEquals(emptyList(), fixture.gateway.qualifiedDetailRequests)
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
            currentDetail = ModelDetailResponse(
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
        var currentDetail: ModelDetailResponse,
        var tree: List<ModelFileTreeResponse>,
    ) : HuggingFaceMetadataGateway {
        var pinnedDetail: ModelDetailResponse = currentDetail
        var detailFailure = false
        var configError: DataError.Network? = null
        var detailThrowable: Throwable? = null
        val unqualifiedDetailRequests = mutableListOf<String>()
        val qualifiedDetailRequests = mutableListOf<Pair<String, String>>()
        val treeRequests = mutableListOf<Pair<String, String>>()
        val configRequests = mutableListOf<Pair<String, String>>()

        override suspend fun getStrictDetail(repositoryId: String): Result<ModelDetailResponse, DataError.Network> {
            unqualifiedDetailRequests += repositoryId
            detailThrowable?.let { throw it }
            return if (detailFailure) Result.Error(DataError.Network.Unknown) else Result.Success(currentDetail)
        }

        override suspend fun getStrictDetail(
            repositoryId: String,
            revision: String,
        ): Result<ModelDetailResponse, DataError.Network> {
            qualifiedDetailRequests += repositoryId to revision
            detailThrowable?.let { throw it }
            return if (detailFailure) Result.Error(DataError.Network.Unknown) else Result.Success(pinnedDetail)
        }

        override suspend fun getTree(
            repositoryId: String,
            revision: String,
            filter: ModelFileWeightFilter,
        ): Result<List<ModelFileTreeResponse>, DataError.Network> {
            treeRequests += repositoryId to revision
            return Result.Success(tree)
        }

        override suspend fun getConfig(
            repositoryId: String,
            revision: String,
        ): Result<TransformerConfigResponse, DataError.Network> {
            configRequests += repositoryId to revision
            return configError?.let { Result.Error(it) }
                ?: Result.Success(TransformerConfigResponse())
        }
    }
}

private class RevisionGateway(
    private val currentDetails: Map<String, ModelDetailResponse>,
    private val pinnedDetails: Map<Pair<String, String>, ModelDetailResponse>,
    private val trees: Map<Pair<String, String>, List<ModelFileTreeResponse>>,
) : HuggingFaceMetadataGateway {
    val unqualifiedDetailRequests = mutableListOf<String>()
    val qualifiedDetailRequests = mutableListOf<Pair<String, String>>()
    val treeRequests = mutableListOf<Pair<String, String>>()

    override suspend fun getStrictDetail(repositoryId: String): Result<ModelDetailResponse, DataError.Network> {
        unqualifiedDetailRequests += repositoryId
        return currentDetails[repositoryId]?.let { Result.Success(it) }
            ?: Result.Error(DataError.Network.Unknown)
    }

    override suspend fun getStrictDetail(
        repositoryId: String,
        revision: String,
    ): Result<ModelDetailResponse, DataError.Network> {
        qualifiedDetailRequests += repositoryId to revision
        return pinnedDetails[repositoryId to revision]?.let { Result.Success(it) }
            ?: Result.Error(DataError.Network.Unknown)
    }

    override suspend fun getTree(
        repositoryId: String,
        revision: String,
        filter: ModelFileWeightFilter,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> {
        treeRequests += repositoryId to revision
        return trees[repositoryId to revision]?.let { Result.Success(it) }
            ?: Result.Error(DataError.Network.Unknown)
    }

    override suspend fun getConfig(
        repositoryId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network> = Result.Error(DataError.Network.Unknown)
}

private fun identity(
    repositoryId: String,
    revision: String,
    path: String,
    sizeBytes: Long,
    objectId: String,
) = ModelFileIdentity(
    repositoryId = repositoryId,
    revision = revision,
    path = path,
    sizeBytes = sizeBytes,
    gitOid = objectId,
    lfsOid = null,
    xetHash = null,
    evidence = emptyList(),
)

private fun detail(repositoryId: String, revision: String) = ModelDetailResponse(
    id = repositoryId,
    modelId = repositoryId,
    sha = revision,
)

private fun file(identity: ModelFileIdentity) = ModelFileTreeResponse(
    path = identity.path,
    size = identity.sizeBytes,
    oid = identity.gitOid,
    type = "file",
)

private fun ModelFileIdentity.copyForExactTest(
    repositoryId: String = this.repositoryId,
    revision: String = this.revision,
    path: String = this.path,
    sizeBytes: Long = this.sizeBytes,
    gitOid: String? = this.gitOid,
    lfsOid: String? = this.lfsOid,
    xetHash: String? = this.xetHash,
) = ModelFileIdentity(repositoryId, revision, path, sizeBytes, gitOid, lfsOid, xetHash, evidence)
