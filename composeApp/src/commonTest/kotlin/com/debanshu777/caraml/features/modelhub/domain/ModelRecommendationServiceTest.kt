package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourcePoolConfidence
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.recommendation.AssessedPlans
import com.debanshu777.caraml.core.recommendation.AssessmentConfidence
import com.debanshu777.caraml.core.recommendation.AssessmentReason
import com.debanshu777.caraml.core.recommendation.Compatibility
import com.debanshu777.caraml.core.recommendation.CompatibilityChecker
import com.debanshu777.caraml.core.recommendation.Confidence
import com.debanshu777.caraml.core.recommendation.DescriptorBuildResult
import com.debanshu777.caraml.core.recommendation.DiffusionMode
import com.debanshu777.caraml.core.recommendation.DiffusionWorkloadConfig
import com.debanshu777.caraml.core.recommendation.EngineCapabilitySource
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmWorkloadConfig
import com.debanshu777.caraml.core.recommendation.ModelAssessment
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptorFactory
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.NoCalibrationSource
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.QuantizationEvidence
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.recommendation.RecommendationSortKey
import com.debanshu777.caraml.core.recommendation.SuitabilityEngine
import com.debanshu777.caraml.core.recommendation.SupportEvidence
import com.debanshu777.caraml.core.recommendation.WorkloadConfig
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.huggingfacemanager.api.error.DataError
import com.debanshu777.huggingfacemanager.api.error.Result
import com.debanshu777.huggingfacemanager.model.ListModelsResponse
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.huggingfacemanager.model.ModelFileTreeResponse
import com.debanshu777.huggingfacemanager.model.ModelFileWeightFilter
import com.debanshu777.huggingfacemanager.model.TransformerConfigResponse
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import com.debanshu777.huggingfacemanager.sdcpp.SdCppComponent
import com.debanshu777.huggingfacemanager.sdcpp.SdCppModelSetup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ModelRecommendationServiceTest {
    @Test
    fun evaluatesBoundedWindowsWithGlobalConcurrencyAndStableTies() = runTest {
        val metadata = CountingMetadataSource(delayMillis = 1L)
        val evaluator = FakeVariantEvaluator()
        val snapshots = FakeSnapshotSource(snapshot(capturedAt = 1_000L))
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = snapshots,
            variantEvaluator = evaluator,
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery("query-1", models(100), workload())

        assertEquals(48, session.state.value.count { it.descriptorState == DescriptorState.CHECKING })
        assertEquals(52, session.state.value.count { it.descriptorState == DescriptorState.PENDING })

        service.setOrdering(session, RecommendationOrdering.PERSONALIZED)
        service.evaluateInitial(session, RecommendationProfile())

        assertEquals(48, metadata.requestCount)
        assertTrue(metadata.maxConcurrent <= 4)
        assertEquals(
            session.state.value.take(48).sortedBy { it.stableModelId },
            session.state.value.take(48),
        )
        assertEquals((48 until 100).toList(), session.state.value.drop(48).map { it.sourceIndex })

        service.evaluateMore(session, RecommendationProfile())
        assertEquals(72, metadata.requestCount)
        service.evaluateMore(session, RecommendationProfile())
        assertEquals(96, metadata.requestCount)
        service.evaluateMore(session, RecommendationProfile())
        assertEquals(96, metadata.requestCount)
    }

    @Test
    fun emitsEachCompletedRepositoryIncrementally() = runTest {
        val metadata = ModelMetadataSource { repositoryId, _ ->
            val sourceIndex = repositoryId.substringAfterLast('-').toLong()
            delay((sourceIndex + 1L) * 10L)
            readyVariant(repositoryId)
        }
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = FakeVariantEvaluator(),
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery("incremental", models(4), workload())
        val assessedCounts = mutableListOf<Int>()
        val evaluation = launch { service.evaluateInitial(session, RecommendationProfile()) }

        runCurrent()
        repeat(4) {
            advanceTimeBy(10L)
            runCurrent()
            assessedCounts += session.state.value.count { state ->
                state.descriptorState == DescriptorState.ASSESSED
            }
        }
        evaluation.join()

        assertEquals(listOf(1, 2, 3, 4), assessedCounts)
    }

    @Test
    fun serverOrderingOnlyUpdatesChipsWhilePersonalizedOrderingUsesStableIdentity() = runTest {
        val service = ModelRecommendationService(
            metadataSource = CountingMetadataSource(),
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = FakeVariantEvaluator(),
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery(
            "server-order",
            listOf(model("org/zeta"), model("org/alpha"), model("org/middle")),
            workload(),
        )

        service.evaluateInitial(session, RecommendationProfile())
        assertEquals(
            listOf("org/zeta", "org/alpha", "org/middle"),
            session.state.value.map { it.repositoryId },
        )

        service.setOrdering(session, RecommendationOrdering.PERSONALIZED)
        assertEquals(
            listOf("org/alpha", "org/middle", "org/zeta"),
            session.state.value.map { it.repositoryId },
        )

        service.setOrdering(session, RecommendationOrdering.SERVER)
        assertEquals(listOf(0, 1, 2), session.state.value.map { it.sourceIndex })
    }

    @Test
    fun supersedingAQueryCancelsItsEnrichmentAndPreservesCancellationIdentity() = runTest {
        val started = CompletableDeferred<Unit>()
        val metadata = object : ModelMetadataSource {
            override suspend fun describeVariants(
                repositoryId: String,
                mode: ModelHubBrowseMode,
            ): RepositoryVariantSet {
                started.complete(Unit)
                awaitCancellation()
            }
        }
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = FakeVariantEvaluator(),
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val first = service.startQuery("first", models(1), workload())
        var observed: CancellationException? = null
        val evaluation = launch {
            try {
                service.evaluateInitial(first, RecommendationProfile())
            } catch (failure: CancellationException) {
                observed = failure
            }
        }
        runCurrent()
        started.await()

        service.startQuery("second", models(1), workload())
        advanceUntilIdle()

        assertTrue(evaluation.isCompleted)
        assertIs<QuerySupersededCancellationException>(observed)
    }

    @Test
    fun cancellingEvaluationCallerStopsSuspendedMetadataWithoutStoppingSessionFirst() = runTest {
        val metadataStarted = CompletableDeferred<Unit>()
        val metadataCancelled = CompletableDeferred<Unit>()
        val metadata = object : ModelMetadataSource {
            override suspend fun describeVariants(
                repositoryId: String,
                mode: ModelHubBrowseMode,
            ): RepositoryVariantSet {
                metadataStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    metadataCancelled.complete(Unit)
                }
            }
        }
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = FakeVariantEvaluator(),
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery("caller-cancellation", models(1), workload())
        val evaluation = launch { service.evaluateInitial(session, RecommendationProfile()) }
        metadataStarted.await()

        val cancellationFinished = CompletableDeferred<Unit>()
        val canceller = launch {
            evaluation.cancelAndJoin()
            cancellationFinished.complete(Unit)
        }
        runCurrent()
        val stoppedBeforeSessionCancellation = cancellationFinished.isCompleted
        val metadataStoppedBeforeSessionCancellation = metadataCancelled.isCompleted

        session.cancel()
        advanceUntilIdle()
        canceller.join()

        assertTrue(stoppedBeforeSessionCancellation)
        assertTrue(metadataStoppedBeforeSessionCancellation)
        assertEquals(DescriptorState.CHECKING, session.state.value.single().descriptorState)
    }

    @Test
    fun cancellingProfileRerankStopsStaleRefreshWithoutPublishingOrRebuilding() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshCancelled = CompletableDeferred<Unit>()
        val snapshots = object : RecommendationSnapshotSource {
            override suspend fun captureInitial(): DeviceSnapshot = snapshot(capturedAt = 1_000L)

            override suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot {
                refreshStarted.complete(Unit)
                try {
                    awaitCancellation()
                } finally {
                    refreshCancelled.complete(Unit)
                }
            }
        }
        val evaluator = FakeVariantEvaluator()
        val service = ModelRecommendationService(
            metadataSource = CountingMetadataSource(),
            snapshotSource = snapshots,
            variantEvaluator = evaluator,
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 31_001L },
        )
        val session = service.startQuery("rerank-cancellation", models(1), workload())
        service.evaluateInitial(session, RecommendationProfile())
        val stateBeforeRerank = session.state.value
        val rerank = launch { service.rerank(session, RecommendationProfile()) }
        refreshStarted.await()

        val cancellationFinished = CompletableDeferred<Unit>()
        val canceller = launch {
            rerank.cancelAndJoin()
            cancellationFinished.complete(Unit)
        }
        runCurrent()
        val stoppedBeforeSessionCancellation = cancellationFinished.isCompleted
        val refreshStoppedBeforeSessionCancellation = refreshCancelled.isCompleted

        session.cancel()
        advanceUntilIdle()
        canceller.join()

        assertTrue(stoppedBeforeSessionCancellation)
        assertTrue(refreshStoppedBeforeSessionCancellation)
        assertEquals(0, evaluator.rebuildCount)
        assertEquals(stateBeforeRerank, session.state.value)
    }

    @Test
    fun metadataCancellationIsRethrown() = runTest {
        val cancelled = CancellationException("metadata-cancelled")
        val service = ModelRecommendationService(
            metadataSource = ModelMetadataSource { _, _ -> throw cancelled },
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = FakeVariantEvaluator(),
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery("cancellation", models(1), workload())

        var observed: CancellationException? = null
        try {
            service.evaluateInitial(session, RecommendationProfile())
        } catch (failure: CancellationException) {
            observed = failure
        }

        assertIs<CancellationException>(observed)
        assertEquals(cancelled.message, observed.message)
    }

    @Test
    fun malformedRepositoryDegradesOnlyItself() = runTest {
        val metadata = object : ModelMetadataSource {
            override suspend fun describeVariants(
                repositoryId: String,
                mode: ModelHubBrowseMode,
            ): RepositoryVariantSet {
                if (repositoryId.endsWith("bad")) throw IllegalArgumentException("untrusted detail")
                return readyVariant(repositoryId)
            }
        }
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = FakeVariantEvaluator(),
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery(
            "malformed",
            listOf(model("org/bad"), model("org/good")),
            workload(),
        )

        service.evaluateInitial(session, RecommendationProfile())

        assertEquals(DescriptorState.NEEDS_INFORMATION, session.state.value.single { it.repositoryId == "org/bad" }.descriptorState)
        assertEquals(DescriptorState.ASSESSED, session.state.value.single { it.repositoryId == "org/good" }.descriptorState)
    }

    @Test
    fun unsafeDescriptorIdentityDegradesToNeedsInformationBeforeAssessment() = runTest {
        val metadata = ModelMetadataSource { repositoryId, _ ->
            RepositoryVariantSet.Ready(listOf(variant(repositoryId, "../mutable.gguf", 100L)))
        }
        val evaluator = FakeVariantEvaluator()
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = evaluator,
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery("unsafe-identity", models(1), workload())

        service.evaluateInitial(session, RecommendationProfile())

        assertEquals(0, evaluator.assessmentCount)
        assertEquals(DescriptorState.NEEDS_INFORMATION, session.state.value.single().descriptorState)
    }

    @Test
    fun invalidRequestedWorkloadNeverReachesMetadata() = runTest {
        val metadata = CountingMetadataSource()
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = FakeVariantEvaluator(),
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery(
            "invalid-workload",
            models(1),
            workload(userRequestedContextTokens = 0),
        )

        service.evaluateInitial(session, RecommendationProfile())

        assertEquals(0, metadata.requestCount)
        assertEquals(DescriptorState.NEEDS_INFORMATION, session.state.value.single().descriptorState)
    }

    @Test
    fun profileOnlyRerankMakesNoMetadataOrAssessmentRequestAndRefreshesOnlyStaleResources() = runTest {
        var now = 10_000L
        val metadata = CountingMetadataSource()
        val evaluator = FakeVariantEvaluator()
        val snapshots = FakeSnapshotSource(snapshot(capturedAt = now))
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = snapshots,
            variantEvaluator = evaluator,
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { now },
        )
        val session = service.startQuery("profile", models(3), workload())
        service.evaluateInitial(session, RecommendationProfile())
        val metadataAfterInitial = metadata.requestCount
        val assessmentsAfterInitial = evaluator.assessmentCount

        service.rerank(session, RecommendationProfile())
        assertEquals(metadataAfterInitial, metadata.requestCount)
        assertEquals(assessmentsAfterInitial, evaluator.assessmentCount)
        assertEquals(0, evaluator.rebuildCount)
        assertEquals(0, snapshots.refreshCount)

        now += 30_001L
        snapshots.next = snapshot(capturedAt = now)
        service.rerank(session, RecommendationProfile())

        assertEquals(metadataAfterInitial, metadata.requestCount)
        assertEquals(assessmentsAfterInitial, evaluator.assessmentCount)
        assertEquals(3, evaluator.rebuildCount)
        assertEquals(1, snapshots.refreshCount)
    }

    @Test
    fun evaluatesEveryVariantAndNamesTheHighestPolicyCandidate() = runTest {
        val metadata = object : ModelMetadataSource {
            override suspend fun describeVariants(
                repositoryId: String,
                mode: ModelHubBrowseMode,
            ) = RepositoryVariantSet.Ready(
                listOf("Q4_K_M", "Q5_K_M", "Q8_0").mapIndexed { index, quantization ->
                    variant(repositoryId, "$repositoryId-$quantization.gguf", (index + 1L) * 100L)
                },
            )
        }
        val evaluator = FakeVariantEvaluator { descriptor ->
            when {
                descriptor.stablePath().contains("Q8_0") -> RecommendationCategory.RECOMMENDED
                descriptor.stablePath().contains("Q5_K_M") -> RecommendationCategory.USABLE
                else -> RecommendationCategory.INCOMPATIBLE
            }
        }
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = evaluator,
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery("variants", models(1), workload())

        service.evaluateInitial(session, RecommendationProfile())

        val card = session.state.value.single()
        assertEquals(3, evaluator.assessmentCount)
        assertEquals("org/model-000-Q8_0.gguf", card.selectedVariantName)
        assertEquals(RecommendationCategory.RECOMMENDED, card.personalizedResult?.category)
    }

    @Test
    fun moreThanSixtyFourRunnableVariantsAreNeverSampled() = runTest {
        val metadata = object : ModelMetadataSource {
            override suspend fun describeVariants(
                repositoryId: String,
                mode: ModelHubBrowseMode,
            ) = RepositoryVariantSet.Ready(
                (0 until 65).map { variant(repositoryId, "variant-$it.gguf", 100L + it) },
            )
        }
        val evaluator = FakeVariantEvaluator()
        val service = ModelRecommendationService(
            metadataSource = metadata,
            snapshotSource = FakeSnapshotSource(snapshot(1_000L)),
            variantEvaluator = evaluator,
            evaluationDispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L },
        )
        val session = service.startQuery("too-many-variants", models(1), workload())

        service.evaluateInitial(session, RecommendationProfile())

        assertEquals(0, evaluator.assessmentCount)
        assertEquals(DescriptorState.SELECT_VARIANT, session.state.value.single().descriptorState)
    }

    @Test
    fun oversizedVariantCollectionIsBoundedBeforeItCanReachRanking() {
        var iterationCount = 0
        val oversized = object : AbstractCollection<RepositoryVariant>() {
            override val size: Int = 10_000

            override fun iterator(): Iterator<RepositoryVariant> = object : Iterator<RepositoryVariant> {
                private var index = 0

                override fun hasNext(): Boolean = index < size

                override fun next(): RepositoryVariant {
                    iterationCount += 1
                    return variant("org/oversized", "variant-${index++}.gguf", 100L)
                }
            }
        }

        val result = RepositoryVariantSet.Ready(oversized)

        assertTrue(result.variants.size > 64)
        assertTrue(iterationCount <= 65)
    }

    @Test
    fun completeGgufShardsFormOneExactVariantAndIncompleteSetsFailClosed() = runTest {
        val repositoryId = "org/sharded"
        val sha = "a".repeat(40)
        val gateway = FakeMetadataGateway().apply {
            details[repositoryId] = detail(repositoryId, sha)
            trees[repositoryId] = listOf(
                file("model-Q4_K_M-00001-of-00002.gguf", 100L, "oid-1"),
                file("model-Q4_K_M-00002-of-00002.gguf", 110L, "oid-2"),
                file("model-Q8_0.gguf", 300L, "oid-3"),
            )
            configs[repositoryId] = TransformerConfigResponse()
        }
        val source = HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) { null }

        val ready = assertIs<RepositoryVariantSet.Ready>(
            source.describeVariants(repositoryId, ModelHubBrowseMode.LanguageModels),
        )
        assertEquals(2, ready.variants.size)
        val shards = assertIs<LlmModelDescriptor>(
            ready.variants.single { it.displayName.contains("00001-of-00002") }.descriptor,
        )
        assertEquals(
            listOf("model-Q4_K_M-00001-of-00002.gguf", "model-Q4_K_M-00002-of-00002.gguf"),
            shards.files.map { it.path },
        )
        assertEquals(210L, shards.files.sumOf { it.sizeBytes })
        assertTrue(gateway.treeRevisions.all { it.second != "main" && it.second == sha })

        gateway.trees[repositoryId] = listOf(file("model-Q4_K_M-00001-of-00002.gguf", 100L, "oid-1"))
        assertIs<RepositoryVariantSet.NeedsInformation>(
            source.describeVariants(repositoryId, ModelHubBrowseMode.LanguageModels),
        )
    }

    @Test
    fun duplicateGgufShardEntryFailsClosedInsteadOfCreatingABundle() = runTest {
        val repositoryId = "org/duplicate"
        val sha = "b".repeat(40)
        val duplicate = file("model-Q4_K_M-00001-of-00002.gguf", 100L, "oid-1")
        val gateway = FakeMetadataGateway().apply {
            details[repositoryId] = detail(repositoryId, sha)
            trees[repositoryId] = listOf(duplicate, duplicate, file("model-Q4_K_M-00002-of-00002.gguf", 100L, "oid-2"))
            configs[repositoryId] = TransformerConfigResponse()
        }

        val result = HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) { null }
            .describeVariants(repositoryId, ModelHubBrowseMode.LanguageModels)

        assertIs<RepositoryVariantSet.NeedsInformation>(result)
    }

    @Test
    fun crossRepositoryDiffusionComponentsKeepTheirOwnPinnedIdentity() = runTest {
        val repositoryId = "org/diffusion"
        val componentRepository = "org/components"
        val modelSha = "c".repeat(40)
        val componentSha = "d".repeat(40)
        val component = SdCppComponent(ComponentRole.VAE, componentRepository, "vae.safetensors")
        val setup = SdCppModelSetup("Explicit", "Explicit setup", listOf(component))
        val gateway = FakeMetadataGateway().apply {
            details[repositoryId] = detail(repositoryId, modelSha)
            details[componentRepository] = detail(componentRepository, componentSha)
            trees[repositoryId] = listOf(file("model-Q5_K_M.gguf", 200L, "model-oid"))
            trees[componentRepository] = listOf(file("vae.safetensors", 50L, "component-oid"))
        }
        val source = HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) { setup }

        val ready = assertIs<RepositoryVariantSet.Ready>(
            source.describeVariants(repositoryId, ModelHubBrowseMode.DiffusionImage),
        )
        val descriptor = ready.variants.single().descriptor
        val componentIdentity = assertNotNull(
            (descriptor as com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor)
                .components.single { it.role == ComponentRole.VAE }.file,
        )
        assertEquals(componentRepository, componentIdentity.repositoryId)
        assertEquals(componentSha, componentIdentity.revision)
        assertEquals("vae.safetensors", componentIdentity.path)
        assertTrue(gateway.treeRevisions.contains(componentRepository to componentSha))
    }

    @Test
    fun crossRepositoryDiffusionFromAdapterProducesRunnablePlansAndExactBundleEstimate() = runTest {
        val repositoryId = "runwayml/stable-diffusion-v1-5"
        val componentRepository = "org/components"
        val modelSha = "c".repeat(40)
        val componentSha = "d".repeat(40)
        val component = SdCppComponent(ComponentRole.VAE, componentRepository, "vae.safetensors")
        val setup = SdCppModelSetup("Stable Diffusion 1.x", "Explicit setup", listOf(component))
        val gateway = FakeMetadataGateway().apply {
            details[repositoryId] = detail(repositoryId, modelSha)
            details[componentRepository] = detail(componentRepository, componentSha)
            trees[repositoryId] = listOf(file("model-Q5_K_M.gguf", 200L, "model-oid"))
            trees[componentRepository] = listOf(file("vae.safetensors", 50L, "component-oid"))
        }
        val descriptor = assertIs<com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor>(
            assertIs<RepositoryVariantSet.Ready>(
                HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) { setup }
                    .describeVariants(repositoryId, ModelHubBrowseMode.DiffusionImage),
            ).variants.single().descriptor,
        )
        val engine = SuitabilityEngine(
            CompatibilityChecker(EngineCapabilitySource { SupportEvidence.Supported }),
            NoCalibrationSource,
        )

        val assessed = engine.assessPlans(descriptor, diffusionHardware(), diffusionWorkload())

        assertEquals(Compatibility.Compatible, assessed.compatibility)
        assertTrue(assessed.values.isNotEmpty())
        assertTrue(assessed.values.all { it.storageBytes?.highBytes == 250L })
    }

    @Test
    fun missingPinnedComponentOrAmbiguousSetupNeverSynthesizesDiffusionBundle() = runTest {
        val repositoryId = "org/diffusion"
        val componentRepository = "org/components"
        val modelSha = "e".repeat(40)
        val component = SdCppComponent(ComponentRole.VAE, componentRepository, "vae.safetensors")
        val gateway = FakeMetadataGateway().apply {
            details[repositoryId] = detail(repositoryId, modelSha)
            details[componentRepository] = detail(componentRepository, sha = null)
            trees[repositoryId] = listOf(file("model.gguf", 200L, "model-oid"))
        }

        val missingPin = HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) {
            SdCppModelSetup("Explicit", "Explicit setup", listOf(component))
        }.describeVariants(repositoryId, ModelHubBrowseMode.DiffusionImage)
        assertIs<RepositoryVariantSet.NeedsInformation>(missingPin)

        val ambiguous = HuggingFaceModelMetadataSource(gateway, ModelDescriptorFactory()) {
            SdCppModelSetup(
                "Ambiguous",
                "Has alternatives",
                listOf(component.copy(alternatives = listOf(component.copy(filePath = "other.safetensors")))),
            )
        }.describeVariants(repositoryId, ModelHubBrowseMode.DiffusionImage)
        assertIs<RepositoryVariantSet.SelectVariant>(ambiguous)
    }
}

private class CountingMetadataSource(
    private val delayMillis: Long = 0L,
) : ModelMetadataSource {
    var requestCount: Int = 0
    var concurrent: Int = 0
    var maxConcurrent: Int = 0

    override suspend fun describeVariants(
        repositoryId: String,
        mode: ModelHubBrowseMode,
    ): RepositoryVariantSet {
        requestCount += 1
        concurrent += 1
        maxConcurrent = maxOf(maxConcurrent, concurrent)
        try {
            if (delayMillis > 0L) delay(delayMillis)
            return readyVariant(repositoryId)
        } finally {
            concurrent -= 1
        }
    }
}

private class FakeSnapshotSource(
    var next: DeviceSnapshot,
) : RecommendationSnapshotSource {
    var initialCount: Int = 0
    var refreshCount: Int = 0

    override suspend fun captureInitial(): DeviceSnapshot {
        initialCount += 1
        return next
    }

    override suspend fun refreshResources(previous: DeviceSnapshot): DeviceSnapshot {
        refreshCount += 1
        return next
    }
}

private class FakeVariantEvaluator(
    private val category: (ModelDescriptor) -> RecommendationCategory = { RecommendationCategory.RECOMMENDED },
) : RecommendationVariantEvaluator {
    var assessmentCount: Int = 0
    var rebuildCount: Int = 0

    override suspend fun assess(
        descriptor: ModelDescriptor,
        snapshot: DeviceSnapshot,
        workload: WorkloadConfig,
    ): ModelAssessment {
        assessmentCount += 1
        return assessment(descriptor.stablePath())
    }

    override fun rebuild(assessment: ModelAssessment, snapshot: DeviceSnapshot): ModelAssessment {
        rebuildCount += 1
        return assessment
    }

    override fun personalize(
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ) = PersonalizedRecommendation(
        assessmentKey = assessment.assessmentKey,
        category = category(descriptor),
        selectedPlan = null,
        reasons = listOf(AssessmentReason.METADATA_VALIDATED),
        profile = profile,
    )

    override fun sortKey(
        descriptor: ModelDescriptor,
        assessment: ModelAssessment,
        snapshot: DeviceSnapshot,
        profile: RecommendationProfile,
    ): RecommendationSortKey = RecommendationSortKey.create(
        category = category(descriptor),
        confidence = assessment.confidence,
        utility = 1.0,
        worstNormalizedHeadroom = 1.0,
        stableId = descriptor.stablePath(),
    )
}

private class FakeMetadataGateway : HuggingFaceMetadataGateway {
    val details = mutableMapOf<String, ModelDetailResponse>()
    val trees = mutableMapOf<String, List<ModelFileTreeResponse>>()
    val configs = mutableMapOf<String, TransformerConfigResponse>()
    val treeRevisions = mutableListOf<Pair<String, String>>()

    override suspend fun getStrictDetail(repositoryId: String): Result<ModelDetailResponse, DataError.Network> =
        details[repositoryId]?.let { Result.Success(it) } ?: Result.Error(DataError.Network.Unknown)

    override suspend fun getTree(
        repositoryId: String,
        revision: String,
        filter: ModelFileWeightFilter,
    ): Result<List<ModelFileTreeResponse>, DataError.Network> {
        treeRevisions += repositoryId to revision
        return trees[repositoryId]?.let { Result.Success(it) } ?: Result.Error(DataError.Network.Unknown)
    }

    override suspend fun getConfig(
        repositoryId: String,
        revision: String,
    ): Result<TransformerConfigResponse, DataError.Network> =
        configs[repositoryId]?.let { Result.Success(it) } ?: Result.Error(DataError.Network.Unknown)
}

private fun models(count: Int): List<ListModelsResponse.Model> =
    (0 until count).map { model("org/model-${it.toString().padStart(3, '0')}") }.reversed()

private fun model(repositoryId: String) = ListModelsResponse.Model(id = repositoryId, author = "org")

private fun readyVariant(repositoryId: String): RepositoryVariantSet =
    RepositoryVariantSet.Ready(listOf(variant(repositoryId, "$repositoryId-Q4_K_M.gguf", 100L)))

private fun variant(repositoryId: String, path: String, sizeBytes: Long): RepositoryVariant = RepositoryVariant(
    descriptor = LlmModelDescriptor(
        repositoryId = repositoryId,
        revision = "a".repeat(40),
        file = identity(repositoryId, path, sizeBytes),
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = 1_000_000L,
        contextLimit = 4_096,
        transformerShape = null,
        ggufVersion = 3,
        requiredEngineFeatures = emptySet(),
        evidence = emptyList(),
    ),
    displayName = path,
)

private fun identity(repositoryId: String, path: String, sizeBytes: Long) = ModelFileIdentity(
    repositoryId = repositoryId,
    revision = "a".repeat(40),
    path = path,
    sizeBytes = sizeBytes,
    gitOid = "oid-$path",
    lfsOid = null,
    xetHash = null,
    evidence = emptyList(),
)

private fun ModelDescriptor.stablePath(): String = when (this) {
    is LlmModelDescriptor -> "$repositoryId@${revision}:${files.joinToString("+") { it.path }}"
    is com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor ->
        "$repositoryId@${revision}:${components.joinToString("+") { "${it.file.repositoryId}@${it.file.revision}:${it.file.path}" }}"
}

private fun workload(userRequestedContextTokens: Int = 2_048) = LlmWorkloadConfig(
    userRequestedContextTokens = userRequestedContextTokens,
    contextTokens = 2_048,
    minimumContextTokens = 512,
    promptTokens = 128,
    generationReserveTokens = 128,
    batchSize = 128,
    microBatchSize = 64,
    sequenceCount = 1,
    kvCacheSelection = com.debanshu777.caraml.core.recommendation.KvCacheSelection.Auto,
    allowContextFallback = true,
    allowBatchFallback = true,
    allowKvCacheFallback = true,
    allowedKvCacheTypes = com.debanshu777.caraml.core.recommendation.KvCacheType.entries,
    evidence = emptyList(),
)

private fun assessment(key: String) = ModelAssessment(
    assessmentKey = key,
    compatibility = Compatibility.Compatible,
    planAssessments = AssessedPlans(emptyList(), assessmentKey = key),
    baseHostBudgetBytes = 1_000_000L,
    baseGpuBudgetBytes = null,
    baseSharedBudgetBytes = null,
    baseStorageBudgetBytes = 1_000_000L,
    confidence = AssessmentConfidence(
        compatibility = Confidence.HIGH,
        memory = Confidence.HIGH,
        storage = Confidence.HIGH,
        performance = Confidence.HIGH,
    ),
    evidence = emptyList(),
)

private fun snapshot(capturedAt: Long): DeviceSnapshot {
    val hardware = HardwareProfile(
        cpuArchitecture = "arm64",
        logicalCoreCount = 8,
        performanceCoreCount = 4,
        instructionSets = emptySet(),
        backends = emptyList(),
        memoryTopology = MemoryTopology.UNKNOWN,
        evidence = emptyList(),
    )
    val resources = ResourceSnapshot(
        additionalAllocatableHostBytes = 2_000_000L,
        additionalAllocatableGpuBytes = null,
        currentProcessBytes = 100_000L,
        freeStorageBytes = 2_000_000L,
        osPressureReserveHostBytes = 0L,
        observedAppFootprintNoiseP95Bytes = 0L,
        platformMinimumReserveHostBytes = 1L,
        lowMemory = false,
        thermalState = ThermalState.NOMINAL,
        powerPolicyState = PowerPolicyState.NORMAL,
        capturedAtEpochMs = capturedAt,
        evidence = emptyList(),
        confidence = ResourcePoolConfidence(host = Confidence.HIGH, storage = Confidence.HIGH),
    )
    return DeviceSnapshot(
        hardwareProfile = hardware,
        resources = resources,
        baseHostBudgetBytes = 1_000_000L,
        baseGpuBudgetBytes = null,
        baseSharedBudgetBytes = null,
        baseStorageBudgetBytes = 1_000_000L,
        isFresh = true,
        evidence = emptyList(),
        budgetConfidence = ResourcePoolConfidence(host = Confidence.HIGH, storage = Confidence.HIGH),
    )
}

private fun diffusionHardware() = HardwareProfile(
    cpuArchitecture = "arm64",
    logicalCoreCount = 8,
    performanceCoreCount = 4,
    instructionSets = emptySet(),
    backends = listOf(
        BackendCapability(
            kind = BackendKind.CPU,
            status = BackendStatus.AVAILABLE,
            additionalAllocatableBytes = null,
            availabilityConfidence = Confidence.HIGH,
            headroomConfidence = null,
            evidence = emptyList(),
        ),
    ),
    memoryTopology = MemoryTopology.UNKNOWN,
    evidence = emptyList(),
)

private fun diffusionWorkload() = DiffusionWorkloadConfig(
    mode = DiffusionMode.IMAGE,
    width = 512,
    height = 512,
    minimumWidth = 512,
    minimumHeight = 512,
    frameCount = 1,
    minimumFrameCount = 1,
    batchSize = 1,
    steps = 20,
    vaeTiling = false,
    offloadToCpu = true,
    keepClipOnCpu = false,
    keepVaeOnCpu = false,
    maxVramBytes = null,
    layerStreaming = false,
    allowResolutionFallback = false,
    allowFrameCountFallback = false,
    allowVaeTilingFallback = false,
    allowMaxVramFallback = false,
    allowLayerStreamingFallback = false,
    evidence = emptyList(),
)

private fun detail(repositoryId: String, sha: String?) = ModelDetailResponse(
    id = repositoryId,
    modelId = repositoryId,
    sha = sha,
    gguf = ModelDetailResponse.Gguf(
        architecture = "llama",
        contextLength = 4_096,
        total = 1_000_000L,
    ),
    tags = listOf("gguf-v3"),
)

private fun file(path: String, size: Long, oid: String) = ModelFileTreeResponse(
    path = path,
    size = size,
    oid = oid,
    type = "file",
)
