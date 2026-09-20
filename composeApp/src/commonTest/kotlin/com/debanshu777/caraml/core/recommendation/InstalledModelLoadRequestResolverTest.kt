package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.settings.KvQuantPreset
import com.debanshu777.caraml.core.storage.component.DownloadedComponentEntity
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class InstalledModelLoadRequestResolverTest {
    @Test
    fun readyInstalledModelProducesExactRequestAfterRestart() = runTest {
        val fixture = Fixture()

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)
        val request = assertIs<InstalledModelLoadResolution.Ready>(result).request

        assertEquals(fixture.model.id, request.model.id)
        assertEquals(request.identity, request.artifact?.identity)
        assertEquals(request.assessmentKey, request.assessedPlans?.assessmentKey)
        assertSame(fixture.artifact, request.artifact)
        assertEquals(1, fixture.persistedHubCalls)
        assertEquals(1, fixture.strictRequestCalls)
    }

    @Test
    fun eachResolutionCapturesCurrentResourcesAndProfile() = runTest {
        val fixture = Fixture()
        fixture.snapshots += listOf(
            task6Snapshot(hostBudget = 8_000),
            task6Snapshot(hostBudget = 4_000),
        )
        fixture.settings += listOf(
            AppSettings(riskTolerance = RiskTolerance.BALANCED),
            AppSettings(riskTolerance = RiskTolerance.CONSERVATIVE),
        )

        fixture.resolver().resolve(fixture.model, GenerationMode.Text)
        fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertEquals(2, fixture.captureCalls)
        assertEquals(listOf(8_000L, 4_000L), fixture.assessmentSnapshots.map { it.baseHostBudgetBytes })
        assertEquals(
            listOf(RiskTolerance.BALANCED, RiskTolerance.CONSERVATIVE),
            fixture.personalizedProfiles.map { it.riskTolerance },
        )
    }

    @Test
    fun explicitKvPresetsRemainExactAndDisableKvFallback() {
        val factory = InstalledModelWorkloadFactory()
        val expected = listOf(
            KvQuantPreset.Q4_F16 to Triple(KvCacheType.Q4_0, KvCacheType.F16, setOf(KvCacheType.Q4_0, KvCacheType.F16)),
            KvQuantPreset.Q8_Q8 to Triple(KvCacheType.Q8_0, KvCacheType.Q8_0, setOf(KvCacheType.Q8_0)),
            KvQuantPreset.F16_F16 to Triple(KvCacheType.F16, KvCacheType.F16, setOf(KvCacheType.F16)),
        )

        expected.forEach { (preset, values) ->
            val workload = assertIs<LlmWorkloadConfig>(
                factory.create(task6LlmDescriptor(), GenerationMode.Text, AppSettings(kvQuantPreset = preset)),
            )
            val selection = assertIs<KvCacheSelection.Explicit>(workload.kvCacheSelection)
            assertEquals(values.first, selection.keyType)
            assertEquals(values.second, selection.valueType)
            assertEquals(values.third, workload.allowedKvCacheTypes.toSet())
            assertFalse(workload.allowKvCacheFallback)
        }
    }

    @Test
    fun autoKvPresetKeepsAllSupportedFallbackTypes() {
        val workload = assertIs<LlmWorkloadConfig>(
            InstalledModelWorkloadFactory().create(
                task6LlmDescriptor(),
                GenerationMode.Text,
                AppSettings(kvQuantPreset = KvQuantPreset.AUTO),
            ),
        )

        assertEquals(KvCacheSelection.Auto, workload.kvCacheSelection)
        assertEquals(KvCacheType.entries, workload.allowedKvCacheTypes)
        assertTrue(workload.allowKvCacheFallback)
    }

    @Test
    fun explicitKvPresetRejectsAPlanWithDifferentCacheTypes() = runTest {
        val fixture = Fixture().apply {
            settings += AppSettings(kvQuantPreset = KvQuantPreset.Q4_F16)
        }

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertEquals(
            InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT),
            result,
        )
        assertEquals(0, fixture.strictRequestCalls)
    }

    @Test
    fun textWorkloadIsBoundedByDescriptorContextLimit() {
        val descriptor = task6LlmDescriptor().copyForResolverTest(contextLimit = 2_048)

        val workload = assertIs<LlmWorkloadConfig>(
            InstalledModelWorkloadFactory().create(descriptor, GenerationMode.Text, AppSettings()),
        )

        assertEquals(2_048, workload.userRequestedContextTokens)
        assertEquals(2_048, workload.contextTokens)
        assertEquals(512, workload.minimumContextTokens)
        assertEquals(256, workload.promptTokens)
        assertEquals(256, workload.generationReserveTokens)
        assertEquals(256, workload.batchSize)
        assertEquals(64, workload.microBatchSize)
        assertEquals(1, workload.sequenceCount)
    }

    @Test
    fun imageAndVideoWorkloadsKeepSharedBoundedDefaults() {
        val factory = InstalledModelWorkloadFactory()

        val image = assertIs<DiffusionWorkloadConfig>(factory.createForBrowse(ModelHubBrowseMode.DiffusionImage))
        val video = assertIs<DiffusionWorkloadConfig>(factory.createForBrowse(ModelHubBrowseMode.DiffusionVideo))

        assertEquals(1_024 to 1_024, image.width to image.height)
        assertEquals(1, image.frameCount)
        assertEquals(1_024 to 576, video.width to video.height)
        assertEquals(16, video.frameCount)
        assertTrue(image.allowResolutionFallback)
        assertTrue(video.allowResolutionFallback)
        assertTrue(video.allowFrameCountFallback)
    }

    @Test
    fun gpuPreferenceIsAppliedToEveryFreshAssessment() = runTest {
        val fixture = Fixture()
        fixture.snapshots += listOf(acceleratedSnapshot(), acceleratedSnapshot())
        fixture.settings += listOf(AppSettings(useGpu = false), AppSettings(useGpu = true))
        fixture.planForSnapshot = { snapshot ->
            if (snapshot.hardwareProfile.backends.any { it.kind == BackendKind.METAL }) {
                installedLlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED)
            } else {
                installedLlmPlan(backend = BackendKind.CPU, topology = MemoryTopology.UNIFIED)
            }
        }

        val cpu = assertIs<InstalledModelLoadResolution.Ready>(
            fixture.resolver().resolve(fixture.model, GenerationMode.Text),
        ).request.plan
        val accelerated = assertIs<InstalledModelLoadResolution.Ready>(
            fixture.resolver().resolve(fixture.model, GenerationMode.Text),
        ).request.plan

        assertEquals(BackendKind.CPU, cpu.backend)
        assertEquals(BackendKind.METAL, accelerated.backend)
        assertEquals(listOf(BackendKind.CPU), fixture.assessmentSnapshots[0].hardwareProfile.backends.map { it.kind })
        assertEquals(
            listOf(BackendKind.METAL, BackendKind.CPU),
            fixture.assessmentSnapshots[1].hardwareProfile.backends.map { it.kind },
        )
        assertEquals(
            listOf(BackendKind.CPU),
            fixture.assessmentSnapshots[2].hardwareProfile.backends.map { it.kind },
        )
    }

    @Test
    fun gpuSelectionCarriesAnExactPolicyApprovedCpuAlternative() = runTest {
        val fixture = Fixture().apply {
            snapshots += acceleratedSnapshot()
            settings += AppSettings(useGpu = true)
            planForSnapshot = { snapshot ->
                if (snapshot.hardwareProfile.backends.any { it.kind != BackendKind.CPU }) {
                    installedLlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED)
                } else {
                    installedLlmPlan(backend = BackendKind.CPU, topology = MemoryTopology.UNIFIED)
                }
            }
        }

        val request = assertIs<InstalledModelLoadResolution.Ready>(
            fixture.resolver().resolve(fixture.model, GenerationMode.Text),
        ).request
        val alternative = requireNotNull(request.backendAlternative)

        assertEquals(BackendKind.METAL, request.plan.backend)
        assertEquals(BackendKind.CPU, alternative.plan.backend)
        assertEquals(request.identity, alternative.identity)
        assertEquals(request.artifact, alternative.artifact)
        assertEquals(alternative.assessmentKey, alternative.assessedPlans?.assessmentKey)
        assertEquals(2, fixture.assessmentCalls)
        assertEquals(2, fixture.strictRequestCalls)
    }

    @Test
    fun gpuMemoryNoFitOffersASeparatelyAssessedExactCpuAlternative() = runTest {
        val fixture = Fixture().apply {
            snapshots += acceleratedSnapshot()
            settings += AppSettings(useGpu = true)
            planForSnapshot = { snapshot ->
                if (snapshot.hasAccelerator()) {
                    installedLlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED)
                } else {
                    installedLlmPlan(backend = BackendKind.CPU, topology = MemoryTopology.UNIFIED)
                }
            }
            recommendationForSnapshot = { snapshot ->
                if (snapshot.hasAccelerator()) {
                    RecommendationFixture(
                        category = RecommendationCategory.NOT_SUITABLE,
                        reasons = listOf(AssessmentReason.MEMORY_NO_FIT),
                    )
                } else {
                    RecommendationFixture(RecommendationCategory.RECOMMENDED)
                }
            }
        }

        val result = fixture.resolve()
        val alternative = assertIs<InstalledModelLoadResolution.SafeAlternative>(result)

        assertEquals(AssessmentReason.MEMORY_NO_FIT, alternative.primaryReason)
        assertEquals(BackendKind.CPU, alternative.saferRequest.plan.backend)
        assertEquals(fixture.artifact.identity, alternative.saferRequest.identity)
        assertSame(fixture.artifact, alternative.saferRequest.artifact)
        assertEquals(2, fixture.assessmentCalls)
        assertEquals(1, fixture.strictRequestCalls)
        assertEquals(
            listOf(
                listOf(BackendKind.METAL, BackendKind.CPU),
                listOf(BackendKind.CPU),
            ),
            fixture.assessmentSnapshots.map { snapshot -> snapshot.hardwareProfile.backends.map { it.kind } },
        )
    }

    @Test
    fun gpuNoPlanOffersCpuOnlyWhenCpuPolicyProducesAnExactPlan() = runTest {
        val fixture = Fixture().apply {
            snapshots += acceleratedSnapshot()
            settings += AppSettings(useGpu = true)
            planForSnapshot = { snapshot ->
                if (snapshot.hasAccelerator()) {
                    installedLlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED)
                } else {
                    installedLlmPlan(backend = BackendKind.CPU, topology = MemoryTopology.UNIFIED)
                }
            }
            recommendationForSnapshot = { snapshot ->
                if (snapshot.hasAccelerator()) {
                    RecommendationFixture(
                        category = RecommendationCategory.NOT_SUITABLE,
                        reasons = listOf(AssessmentReason.NO_RUN_PLAN),
                        selectPlan = false,
                    )
                } else {
                    RecommendationFixture(RecommendationCategory.RECOMMENDED)
                }
            }
        }

        val alternative = assertIs<InstalledModelLoadResolution.SafeAlternative>(fixture.resolve())

        assertEquals(AssessmentReason.NO_RUN_PLAN, alternative.primaryReason)
        assertEquals(BackendKind.CPU, alternative.saferRequest.plan.backend)
        assertEquals(2, fixture.assessmentCalls)
        assertEquals(1, fixture.strictRequestCalls)
    }

    @Test
    fun invalidMetadataNeverTriggersCpuFallback() = runTest {
        val fixture = Fixture().apply {
            snapshots += acceleratedSnapshot()
            settings += AppSettings(useGpu = true)
            recommendationForSnapshot = {
                RecommendationFixture(
                    category = RecommendationCategory.NEEDS_INFORMATION,
                    reasons = listOf(AssessmentReason.INVALID_METADATA),
                    selectPlan = false,
                )
            }
        }

        val result = fixture.resolve()

        assertEquals(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.INVALID_METADATA),
            result,
        )
        assertEquals(1, fixture.assessmentCalls)
        assertEquals(0, fixture.strictRequestCalls)
    }

    @Test
    fun engineIncompatibilityNeverTriggersCpuFallback() = runTest {
        val fixture = Fixture().apply {
            snapshots += acceleratedSnapshot()
            settings += AppSettings(useGpu = true)
            recommendationForSnapshot = {
                RecommendationFixture(
                    category = RecommendationCategory.INCOMPATIBLE,
                    reasons = listOf(AssessmentReason.UNSUPPORTED_ENGINE_FEATURE),
                    selectPlan = false,
                )
            }
        }

        val result = fixture.resolve()

        assertEquals(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.UNSUPPORTED_ENGINE_FEATURE),
            result,
        )
        assertEquals(1, fixture.assessmentCalls)
        assertEquals(0, fixture.strictRequestCalls)
    }

    @Test
    fun gpuAndCpuMemoryNoFitRemainBlockedWithoutAnAlternative() = runTest {
        val fixture = Fixture().apply {
            snapshots += acceleratedSnapshot()
            settings += AppSettings(useGpu = true)
            planForSnapshot = { snapshot ->
                if (snapshot.hasAccelerator()) {
                    installedLlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED)
                } else {
                    installedLlmPlan(backend = BackendKind.CPU, topology = MemoryTopology.UNIFIED)
                }
            }
            recommendationForSnapshot = {
                RecommendationFixture(
                    category = RecommendationCategory.NOT_SUITABLE,
                    reasons = listOf(AssessmentReason.MEMORY_NO_FIT),
                )
            }
        }

        val result = fixture.resolve()

        assertEquals(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.MEMORY_NO_FIT),
            result,
        )
        assertEquals(2, fixture.assessmentCalls)
        assertEquals(0, fixture.strictRequestCalls)
    }

    @Test
    fun diffusionGpuNoFitOffersAnExactCpuAlternative() = runTest {
        val descriptor = task6DiffusionDescriptor()
        val model = diffusionModel(descriptor)
        val artifact = diffusionArtifact(model, descriptor)
        val assessmentSnapshots = mutableListOf<DeviceSnapshot>()
        var strictRequestCalls = 0
        val resolver = InstalledModelLoadRequestResolver(
            componentsForModel = { emptyList() },
            requireComplete = { _, _ -> EvidenceRepairResult.Ready(descriptor) },
            resolveArtifact = { _, _ -> ArtifactIdentityResolution.Verified(artifact) },
            captureSnapshot = { acceleratedSnapshot() },
            currentSettings = { AppSettings(useGpu = true) },
            workloadFactory = InstalledModelWorkloadFactory(),
            assess = { _, snapshot, _ ->
                assessmentSnapshots += snapshot
                val plan = installedDiffusionPlan(
                    backend = if (snapshot.hasAccelerator()) BackendKind.METAL else BackendKind.CPU,
                )
                task6Assessment(
                    plans = listOf(task6PlanAssessment(plan = plan)),
                    assessmentKey = "diffusion-assessment-${assessmentSnapshots.size}",
                    innerAssessmentKey = "diffusion-assessment-${assessmentSnapshots.size}",
                    innerTopology = plan.memoryTopology,
                )
            },
            personalize = { assessment, snapshot, profile ->
                val selected = assessment.planAssessments.values.single()
                PersonalizedRecommendation(
                    assessmentKey = assessment.assessmentKey,
                    category = if (snapshot.hasAccelerator()) {
                        RecommendationCategory.NOT_SUITABLE
                    } else {
                        RecommendationCategory.RECOMMENDED
                    },
                    selectedPlan = selected.plan,
                    reasons = if (snapshot.hasAccelerator()) {
                        listOf(AssessmentReason.MEMORY_NO_FIT)
                    } else {
                        emptyList()
                    },
                    profile = profile,
                    selectedPlanAssessment = selected,
                )
            },
            createStrictRequest = { selectedModel, selectedDescriptor, selectedArtifact, assessment, recommendation ->
                strictRequestCalls += 1
                LoadRequestResolution.Ready(
                    LoadRequest(
                        model = selectedModel,
                        identity = selectedArtifact.identity,
                        observationIdentity = requireNotNull(
                            ObservationModelIdentity.fromDescriptor(selectedDescriptor),
                        ),
                        plan = recommendation.selectedPlan as RunPlan,
                        assessmentKey = assessment.assessmentKey,
                        artifact = selectedArtifact,
                        assessedPlans = assessment.planAssessments,
                        profile = recommendation.profile,
                    ),
                )
            },
        )

        val alternative = assertIs<InstalledModelLoadResolution.SafeAlternative>(
            resolver.resolve(model, GenerationMode.Image),
        )

        assertEquals(BackendKind.CPU, alternative.saferRequest.plan.backend)
        assertEquals(model, alternative.saferRequest.model)
        assertEquals(artifact.identity, alternative.saferRequest.identity)
        assertSame(artifact, alternative.saferRequest.artifact)
        assertEquals(2, assessmentSnapshots.size)
        assertEquals(1, strictRequestCalls)
    }

    @Test
    fun hybridSsmArchitectureIsAssessedWithCpuCapabilityBeforePlanSelection() = runTest {
        val fixture = Fixture().apply {
            descriptor = descriptor.copyForResolverTest(architecture = "qwen35")
            evidenceResult = EvidenceRepairResult.Ready(descriptor)
            snapshots += acceleratedSnapshot()
            planForSnapshot = { snapshot ->
                if (snapshot.hardwareProfile.backends.any { it.kind != BackendKind.CPU }) {
                    installedLlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED)
                } else {
                    installedLlmPlan(backend = BackendKind.CPU, topology = MemoryTopology.UNIFIED)
                }
            }
        }

        val request = assertIs<InstalledModelLoadResolution.Ready>(
            fixture.resolver().resolve(fixture.model, GenerationMode.Text),
        ).request

        assertEquals(BackendKind.CPU, request.plan.backend)
        assertEquals(
            listOf(BackendKind.CPU),
            fixture.assessmentSnapshots.single().hardwareProfile.backends.map { it.kind },
        )
    }

    @Test
    fun wrongGenerationModeIsNotAdmissible() = runTest {
        val fixture = Fixture()

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Image)

        assertIs<InstalledModelLoadResolution.NotAdmissible>(result)
        assertEquals(0, fixture.persistedHubCalls)
        assertEquals(0, fixture.assessmentCalls)
    }

    @Test
    fun missingManifestRejectsBeforeAssessmentWithoutRequestConstruction() = runTest {
        val fixture = Fixture()
        fixture.artifactResolution = ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertEquals(
            InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST),
            result,
        )
        assertEquals(0, fixture.assessmentCalls)
        assertEquals(0, fixture.strictRequestCalls)
    }

    @Test
    fun descriptorArtifactMismatchRejectsBeforeAssessment() = runTest {
        val fixture = Fixture()
        fixture.artifact = fixture.artifact.copy(
            components = listOf(
                fixture.artifact.components.single().copy(
                    identity = fixture.identity.copyForResolverTest(path = "other.gguf"),
                ),
            ),
        )

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertEquals(
            InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST),
            result,
        )
        assertEquals(0, fixture.assessmentCalls)
    }

    @Test
    fun repairNetworkAndTerminalRejectionRemainDistinct() = runTest {
        val network = Fixture().apply { evidenceResult = EvidenceRepairResult.NeedsNetwork }
        val rejected = Fixture().apply {
            evidenceResult = EvidenceRepairResult.Rejected(listOf(AssessmentReason.UNSUPPORTED_FORMAT))
        }

        assertEquals(
            InstalledModelLoadResolution.NeedsNetwork,
            network.resolver().resolve(network.model, GenerationMode.Text),
        )
        assertEquals(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.UNSUPPORTED_FORMAT),
            rejected.resolver().resolve(rejected.model, GenerationMode.Text),
        )
        assertEquals(0, rejected.assessmentCalls)
    }

    @Test
    fun repairInvalidMetadataStillSurfacesConcreteManifestRejection() = runTest {
        val fixture = Fixture().apply {
            evidenceResult = EvidenceRepairResult.Rejected(listOf(AssessmentReason.INVALID_METADATA))
            artifactResolution = ArtifactIdentityResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST)
        }

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertEquals(
            InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.STALE_MANIFEST),
            result,
        )
        assertEquals(1, fixture.persistedHubCalls)
        assertEquals(0, fixture.assessmentCalls)
    }

    @Test
    fun needsInformationWithSelectedPlanIsNotAdmissible() = runTest {
        val fixture = Fixture().apply {
            recommendationCategory = RecommendationCategory.NEEDS_INFORMATION
            recommendationReasons = listOf(AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE)
        }

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertEquals(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.RECOMMENDATION_EVIDENCE_INCOMPLETE),
            result,
        )
        assertEquals(0, fixture.strictRequestCalls)
    }

    @Test
    fun notSuitableWithSelectedPlanIsNotAdmissible() = runTest {
        val fixture = Fixture().apply {
            recommendationCategory = RecommendationCategory.NOT_SUITABLE
            recommendationReasons = listOf(AssessmentReason.MEMORY_NO_FIT)
        }

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertEquals(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.MEMORY_NO_FIT),
            result,
        )
        assertEquals(1, fixture.assessmentCalls)
        assertEquals(0, fixture.strictRequestCalls)
    }

    @Test
    fun riskyWithSelectedPlanRemainsAdmissible() = runTest {
        val fixture = Fixture().apply {
            recommendationCategory = RecommendationCategory.RISKY
            recommendationReasons = listOf(AssessmentReason.TIGHT_MEMORY_FIT)
        }

        val result = fixture.resolver().resolve(fixture.model, GenerationMode.Text)

        assertIs<InstalledModelLoadResolution.Ready>(result)
        assertEquals(1, fixture.strictRequestCalls)
    }

    @Test
    fun missingPlanAndInconsistentAssessmentKeysFailClosed() = runTest {
        val noPlan = Fixture().apply { omitSelectedPlan = true }
        val badKey = Fixture().apply { inconsistentAssessmentKey = true }

        assertEquals(
            InstalledModelLoadResolution.NotAdmissible(AssessmentReason.NO_RUN_PLAN),
            noPlan.resolver().resolve(noPlan.model, GenerationMode.Text),
        )
        assertEquals(
            InstalledModelLoadResolution.Rejected(ArtifactIdentityRejection.INVALID_INPUT),
            badKey.resolver().resolve(badKey.model, GenerationMode.Text),
        )
    }

    @Test
    fun cancellationFromEverySuspendBoundaryEscapes() = runTest {
        ResolverStage.entries.forEach { stage ->
            val fixture = Fixture().apply { cancellationStage = stage }
            assertFailsWith<CancellationException>(stage.name) {
                fixture.resolver().resolve(fixture.model, GenerationMode.Text)
            }
        }
    }

    private enum class ResolverStage {
        COMPONENTS,
        REPAIR,
        ARTIFACT,
        SNAPSHOT,
        SETTINGS,
        ASSESSMENT,
        STRICT_REQUEST,
    }

    private class Fixture {
        val model = LocalModelEntity(
            id = 7,
            modelId = "owner/model",
            filename = "model-Q4_K_M.gguf",
            localPath = "/models/model-Q4_K_M.gguf",
            sizeBytes = 1_073_741_824,
            downloadedAt = 1,
            author = "owner",
            libraryName = "gguf",
            pipelineTag = "text-generation",
            componentStatus = LocalModelEntity.STATUS_READY,
        )
        var descriptor = task6LlmDescriptor()
        val identity = descriptor.file
        var artifact = ResolvedLocalArtifact(
            identity = ModelFileIdentity(
                repositoryId = model.modelId,
                revision = "b".repeat(64),
                path = model.filename,
                sizeBytes = identity.sizeBytes,
                gitOid = null,
                lfsOid = "sha256:${"b".repeat(64)}",
                xetHash = null,
                evidence = emptyList(),
            ),
            revisionIdentity = RevisionIdentity.HubCommit(
                listOf(RepositoryCommit(model.modelId, identity.revision)),
            ),
            components = listOf(
                ResolvedArtifactComponent(
                    logicalRole = "model",
                    repositoryId = model.modelId,
                    repositoryRelativePath = identity.path,
                    localPath = model.localPath,
                    byteCount = identity.sizeBytes,
                    contentSha256 = "b".repeat(64),
                    identity = identity,
                ),
            ),
            loadTarget = VerifiedArtifactLoadTarget.File(
                path = model.localPath,
                componentRole = "model",
                repositoryId = model.modelId,
                localRelativePath = identity.path,
            ),
        )
        var evidenceResult: EvidenceRepairResult = EvidenceRepairResult.Ready(descriptor)
        var artifactResolution: ArtifactIdentityResolution? = null
        val snapshots = ArrayDeque<DeviceSnapshot>()
        val settings = ArrayDeque<AppSettings>()
        val assessmentSnapshots = mutableListOf<DeviceSnapshot>()
        val personalizedProfiles = mutableListOf<RecommendationProfile>()
        var persistedHubCalls = 0
        var captureCalls = 0
        var assessmentCalls = 0
        var strictRequestCalls = 0
        var omitSelectedPlan = false
        var inconsistentAssessmentKey = false
        var recommendationCategory = RecommendationCategory.RECOMMENDED
        var recommendationReasons: List<AssessmentReason> = emptyList()
        var recommendationForSnapshot: ((DeviceSnapshot) -> RecommendationFixture)? = null
        var cancellationStage: ResolverStage? = null
        var planForSnapshot: (DeviceSnapshot) -> RunPlan = { installedLlmPlan() }

        suspend fun resolve(): InstalledModelLoadResolution =
            resolver().resolve(model, GenerationMode.Text)

        fun resolver() = InstalledModelLoadRequestResolver(
            componentsForModel = {
                cancelAt(ResolverStage.COMPONENTS)
                emptyList<DownloadedComponentEntity>()
            },
            requireComplete = { _, _ ->
                cancelAt(ResolverStage.REPAIR)
                evidenceResult
            },
            resolveArtifact = { _, _ ->
                cancelAt(ResolverStage.ARTIFACT)
                persistedHubCalls += 1
                artifactResolution ?: ArtifactIdentityResolution.Verified(artifact)
            },
            captureSnapshot = {
                cancelAt(ResolverStage.SNAPSHOT)
                captureCalls += 1
                snapshots.removeFirstOrNull() ?: task6Snapshot()
            },
            currentSettings = {
                cancelAt(ResolverStage.SETTINGS)
                settings.removeFirstOrNull() ?: AppSettings()
            },
            workloadFactory = InstalledModelWorkloadFactory(),
            assess = { _, snapshot, _ ->
                cancelAt(ResolverStage.ASSESSMENT)
                assessmentCalls += 1
                assessmentSnapshots += snapshot
                val planAssessment = task6PlanAssessment(plan = planForSnapshot(snapshot))
                task6Assessment(
                    plans = listOf(planAssessment),
                    assessmentKey = "installed-assessment",
                    innerAssessmentKey = if (inconsistentAssessmentKey) "stale-assessment" else "installed-assessment",
                    innerTopology = planForSnapshot(snapshot).memoryTopology,
                )
            },
            personalize = { assessment, snapshot, profile ->
                personalizedProfiles += profile
                val selected = assessment.planAssessments.values.single()
                val fixture = recommendationForSnapshot?.invoke(snapshot) ?: RecommendationFixture(
                    category = recommendationCategory,
                    reasons = recommendationReasons,
                    selectPlan = !omitSelectedPlan,
                )
                PersonalizedRecommendation(
                    assessmentKey = assessment.assessmentKey,
                    category = if (omitSelectedPlan) RecommendationCategory.NOT_SUITABLE else fixture.category,
                    selectedPlan = selected.plan.takeIf { fixture.selectPlan && !omitSelectedPlan },
                    reasons = if (omitSelectedPlan) listOf(AssessmentReason.NO_RUN_PLAN) else fixture.reasons,
                    profile = profile,
                    selectedPlanAssessment = selected.takeIf { fixture.selectPlan && !omitSelectedPlan },
                )
            },
            createStrictRequest = { selectedModel, selectedDescriptor, selectedArtifact, assessment, recommendation ->
                cancelAt(ResolverStage.STRICT_REQUEST)
                strictRequestCalls += 1
                LoadRequestResolution.Ready(
                    LoadRequest(
                        model = selectedModel,
                        identity = selectedArtifact.identity,
                        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(selectedDescriptor)),
                        plan = recommendation.selectedPlan as RunPlan,
                        assessmentKey = assessment.assessmentKey,
                        artifact = selectedArtifact,
                        assessedPlans = assessment.planAssessments,
                        profile = recommendation.profile,
                    ),
                )
            },
        )

        private fun cancelAt(stage: ResolverStage) {
            if (cancellationStage == stage) throw CancellationException("cancelled")
        }
    }
}

private data class RecommendationFixture(
    val category: RecommendationCategory,
    val reasons: List<AssessmentReason> = emptyList(),
    val selectPlan: Boolean = true,
)

private suspend fun InstalledModelLoadRequestResolver.resolve(
    model: LocalModelEntity,
    mode: GenerationMode,
): InstalledModelLoadResolution = when (val preparation = prepare(model, mode)) {
    is InstalledModelLoadPreparation.Ready -> resolve(preparation)
    is InstalledModelLoadPreparation.Terminal -> preparation.resolution
}

private fun DeviceSnapshot.hasAccelerator(): Boolean =
    hardwareProfile.backends.any { it.kind != BackendKind.CPU }

private fun diffusionModel(descriptor: DiffusionModelDescriptor) = LocalModelEntity(
    id = 8,
    modelId = descriptor.repositoryId,
    filename = descriptor.components.first { it.isPrimary }.file.path,
    localPath = "/models/diffusion",
    sizeBytes = descriptor.components.sumOf { it.file.sizeBytes },
    downloadedAt = 1,
    author = "owner",
    libraryName = "diffusers",
    pipelineTag = "text-to-image",
    componentStatus = LocalModelEntity.STATUS_READY,
)

private fun diffusionArtifact(
    model: LocalModelEntity,
    descriptor: DiffusionModelDescriptor,
): ResolvedLocalArtifact {
    val components = descriptor.components.mapIndexed { index, component ->
        ResolvedArtifactComponent(
            logicalRole = if (component.isPrimary) "model" else "vae",
            repositoryId = component.file.repositoryId,
            repositoryRelativePath = component.file.path,
            localPath = "${model.localPath}/${component.file.path}",
            byteCount = component.file.sizeBytes,
            contentSha256 = (index + 1).toString().repeat(64),
            identity = component.file,
        )
    }
    return ResolvedLocalArtifact(
        identity = ModelFileIdentity(
            repositoryId = model.modelId,
            revision = "d".repeat(64),
            path = model.filename,
            sizeBytes = components.sumOf(ResolvedArtifactComponent::byteCount),
            gitOid = null,
            lfsOid = "sha256:${"d".repeat(64)}",
            xetHash = null,
            evidence = emptyList(),
        ),
        revisionIdentity = RevisionIdentity.HubCommit(
            listOf(RepositoryCommit(model.modelId, descriptor.revision)),
        ),
        components = components,
        loadTarget = VerifiedArtifactLoadTarget.Directory(
            path = model.localPath,
            storageOwner = model.modelId,
            nativeConsumedRelativePaths = components.map(ResolvedArtifactComponent::repositoryRelativePath),
        ),
    )
}

private fun installedDiffusionPlan(backend: BackendKind) = DiffusionRunPlan(
    mode = DiffusionMode.IMAGE,
    width = 1_024,
    height = 1_024,
    frameCount = 1,
    batchSize = 1,
    steps = 20,
    vaeTiling = false,
    offloadToCpu = backend == BackendKind.CPU,
    keepClipOnCpu = backend == BackendKind.CPU,
    keepVaeOnCpu = backend == BackendKind.CPU,
    maxVramBytes = null,
    layerStreaming = false,
    requiresUserAcceptance = false,
    backend = backend,
    memoryTopology = MemoryTopology.UNIFIED,
    compromises = if (backend == BackendKind.CPU) {
        listOf(DiffusionPlanCompromise.CPU_OFFLOAD)
    } else {
        emptyList()
    },
)

private fun acceleratedSnapshot() = task6Snapshot(
    sharedBudget = 8_000,
    topology = MemoryTopology.UNIFIED,
    backends = listOf(task6Backend(BackendKind.METAL), task6Backend(BackendKind.CPU)),
)

private fun installedLlmPlan(
    backend: BackendKind = BackendKind.CPU,
    topology: MemoryTopology = MemoryTopology.UNKNOWN,
) = LlmRunPlan(
    contextTokens = 4_096,
    batchSize = 256,
    microBatchSize = 64,
    sequenceCount = 1,
    keyCacheType = KvCacheType.Q8_0,
    valueCacheType = KvCacheType.Q8_0,
    backend = backend,
    memoryTopology = topology,
    gpuLayerCount = if (backend == BackendKind.CPU) 0 else 16,
    compromises = emptyList(),
)

private fun LlmModelDescriptor.copyForResolverTest(
    contextLimit: Int? = this.contextLimit,
    architecture: String? = this.architecture,
) = LlmModelDescriptor(
    repositoryId = repositoryId,
    revision = revision,
    files = files,
    architecture = architecture,
    quantization = quantization,
    parameterCount = parameterCount,
    contextLimit = contextLimit,
    transformerShape = transformerShape,
    ggufVersion = ggufVersion,
    requiredEngineFeatures = requiredEngineFeatures,
    evidence = evidence,
)

private fun ModelFileIdentity.copyForResolverTest(path: String) = ModelFileIdentity(
    repositoryId = repositoryId,
    revision = revision,
    path = path,
    sizeBytes = sizeBytes,
    gitOid = gitOid,
    lfsOid = lfsOid,
    xetHash = xetHash,
    evidence = evidence,
)
