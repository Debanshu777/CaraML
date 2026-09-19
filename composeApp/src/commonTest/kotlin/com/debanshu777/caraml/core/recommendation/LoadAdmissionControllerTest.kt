package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LoadAdmissionControllerTest {
    @Test
    fun riskyLoadRequiresAcknowledgementAndNoFitOffersOneAlternative() = runTest {
        val fallback = plan(context = 2_048)
        var recommendation = recommendation(
            category = RecommendationCategory.RISKY,
            selectedPlan = requestedPlan,
        )
        val controller = controller { _, _ -> recommendation }

        assertIs<LoadAdmission.ConfirmationRequired>(controller.evaluate(request(), null))

        recommendation = recommendation(
            category = RecommendationCategory.NOT_SUITABLE,
            selectedPlan = null,
            fallbackPlan = fallback,
        )
        val alternative = assertIs<LoadAdmission.AlternativeAvailable>(controller.evaluate(request(), null))
        assertEquals(fallback.stableKey, alternative.saferPlan.stableKey)
    }

    @Test
    fun acknowledgementIsBoundToAssessmentAndPlanAndExpiresWithSnapshotWindow() = runTest {
        var now = 10_000L
        val controller = controller(clock = { now }) { _, _ ->
            recommendation(RecommendationCategory.RISKY, requestedPlan)
        }
        val request = request()
        val valid = RiskAcknowledgement(
            assessmentKey = ASSESSMENT_KEY,
            planKey = requestedPlan.stableKey,
            acknowledgedAtEpochMs = now,
        )

        assertIs<LoadAdmission.Ready>(controller.evaluate(request, valid))
        assertIs<LoadAdmission.ConfirmationRequired>(
            controller.evaluate(request, valid.copy(assessmentKey = "other")),
        )
        now += RecommendationPolicyV1.RESOURCE_SNAPSHOT_MAX_AGE_MS + 1L
        assertIs<LoadAdmission.ConfirmationRequired>(controller.evaluate(request, valid))
    }

    @Test
    fun livePressureStopsBeforeNativePreflight() = runTest {
        var preflightCalled = false
        val controller = controller(
            snapshot = snapshot(lowMemory = true),
            preflight = {
                preflightCalled = true
                NativeLoadPreflight.Fit
            },
        ) { _, _ -> recommendation(RecommendationCategory.RECOMMENDED, requestedPlan) }

        assertIs<LoadAdmission.TemporarilyUnavailable>(controller.evaluate(request(), null))
        assertFalse(preflightCalled)
    }

    @Test
    fun seriousThermalStateIsTemporaryAndCompatibilityFailureIsPermanent() = runTest {
        val hot = controller(snapshot = snapshot(thermal = ThermalState.SERIOUS)) { _, _ ->
            recommendation(RecommendationCategory.RECOMMENDED, requestedPlan)
        }
        assertIs<LoadAdmission.TemporarilyUnavailable>(hot.evaluate(request(), null))

        val incompatible = controller { _, _ ->
            recommendation(RecommendationCategory.INCOMPATIBLE, null)
        }
        assertIs<LoadAdmission.Blocked>(incompatible.evaluate(request(), null))
    }

    @Test
    fun quarantineAndNativeNoFitApplyOnlyToExactConfiguration() = runTest {
        val fallback = plan(2_048)
        val quarantined = mutableSetOf(requestedPlan.stableKey)
        val recovery = object : LoadRecoveryState {
            override suspend fun quarantine(identity: ModelFileIdentity, plan: RunPlan, engineVersion: String) =
                if (plan.stableKey in quarantined) LoadQuarantine.TEMPORARY else LoadQuarantine.NONE

            override suspend fun allowExplicitRetry(identity: ModelFileIdentity, plan: RunPlan, engineVersion: String) {
                quarantined -= plan.stableKey
            }
        }
        val quarantinedController = controller(recovery = recovery) { _, _ ->
            recommendation(RecommendationCategory.RECOMMENDED, requestedPlan, fallback)
        }
        assertIs<LoadAdmission.AlternativeAvailable>(quarantinedController.evaluate(request(), null))

        val noFitController = controller(preflight = { NativeLoadPreflight.NoFit }) { _, _ ->
            recommendation(RecommendationCategory.RECOMMENDED, requestedPlan, fallback)
        }
        assertIs<LoadAdmission.AlternativeAvailable>(noFitController.evaluate(request(), null))

        quarantinedController.allowExplicitRetry(request())
        assertIs<LoadAdmission.Ready>(quarantinedController.evaluate(request(), null))
    }

    @Test
    fun fallbackIsNeverExecutedWithoutAcceptance() = runTest {
        val fallback = plan(2_048)
        val controller = controller { _, _ ->
            recommendation(RecommendationCategory.NOT_SUITABLE, null, fallback)
        }
        val offered = assertIs<LoadAdmission.AlternativeAvailable>(controller.evaluate(request(), null))

        assertEquals(requestedPlan.stableKey, offered.original.plan.stableKey)
        assertEquals(fallback.stableKey, offered.saferPlan.stableKey)
        assertTrue(offered.original.plan.stableKey != offered.saferPlan.stableKey)
    }

    @Test
    fun artifactChangedWhileAwaitingConfirmationStopsBeforeNativePreflight() = runTest {
        var artifactCurrent = true
        var preflightCalls = 0
        val controller = controller(
            preflight = {
                preflightCalls++
                NativeLoadPreflight.Fit
            },
            artifactValidator = { artifactCurrent },
        ) { _, _ -> recommendation(RecommendationCategory.RISKY, requestedPlan) }
        val request = request()

        assertIs<LoadAdmission.ConfirmationRequired>(controller.evaluate(request, null))
        artifactCurrent = false
        val acknowledgement = RiskAcknowledgement(
            assessmentKey = ASSESSMENT_KEY,
            planKey = requestedPlan.stableKey,
            acknowledgedAtEpochMs = 10_000L,
        )

        val blocked = assertIs<LoadAdmission.Blocked>(controller.evaluate(request, acknowledgement))
        assertEquals(LoadAdmissionReason.INVALID_MODEL, blocked.reason)
        assertEquals(0, preflightCalls)
    }

    @Test
    fun multipleLlmSequencesAreRejectedBeforeNativePreflight() = runTest {
        var preflightCalls = 0
        val multiSequence = plan(context = 4_096, sequenceCount = 2)
        val controller = controller(
            preflight = {
                preflightCalls++
                NativeLoadPreflight.Fit
            },
        ) { _, _ -> recommendation(RecommendationCategory.RECOMMENDED, multiSequence) }

        val blocked = assertIs<LoadAdmission.Blocked>(
            controller.evaluate(request().copy(plan = multiSequence), null),
        )

        assertEquals(LoadAdmissionReason.INSUFFICIENT_INFORMATION, blocked.reason)
        assertEquals(0, preflightCalls)
    }

    @Test
    fun invalidNativePreflightIsDistinctFromArtifactIdentityFailure() = runTest {
        val controller = controller(preflight = { NativeLoadPreflight.Invalid }) { _, _ ->
            recommendation(RecommendationCategory.RECOMMENDED, requestedPlan)
        }

        val blocked = assertIs<LoadAdmission.Blocked>(controller.evaluate(request(), null))

        assertEquals(LoadAdmissionReason.NATIVE_PREFLIGHT_INVALID, blocked.reason)
    }

    private fun controller(
        snapshot: DeviceSnapshot = snapshot(),
        clock: () -> Long = { 10_000L },
        preflight: suspend (LoadRequest) -> NativeLoadPreflight = { NativeLoadPreflight.Fit },
        artifactValidator: suspend (LoadRequest) -> Boolean = { true },
        recovery: LoadRecoveryState = object : LoadRecoveryState {
            override suspend fun quarantine(identity: ModelFileIdentity, plan: RunPlan, engineVersion: String) =
                LoadQuarantine.NONE

            override suspend fun allowExplicitRetry(identity: ModelFileIdentity, plan: RunPlan, engineVersion: String) = Unit
        },
        recommendation: suspend (LoadRequest, DeviceSnapshot) -> PersonalizedRecommendation,
    ) = LoadAdmissionController(
        snapshotSource = { snapshot },
        recommendationSource = recommendation,
        nativePreflight = preflight,
        artifactValidator = artifactValidator,
        recoveryState = recovery,
        engineVersion = ENGINE_VERSION,
        clock = clock,
    )

    private fun request() = LoadRequest(
        model = model,
        identity = identity,
        observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(task6LlmDescriptor())),
        plan = requestedPlan,
        assessmentKey = ASSESSMENT_KEY,
    )

    private fun recommendation(
        category: RecommendationCategory,
        selectedPlan: RunPlan?,
        fallbackPlan: RunPlan? = null,
    ) = PersonalizedRecommendation(
        assessmentKey = ASSESSMENT_KEY,
        category = category,
        selectedPlan = selectedPlan,
        fallbackPlan = fallbackPlan,
        reasons = listOf(AssessmentReason.TIGHT_MEMORY_FIT),
        profile = RecommendationProfile(),
    )

    private fun plan(context: Int, sequenceCount: Int = 1) = LlmRunPlan(
        contextTokens = context,
        batchSize = 128,
        microBatchSize = 64,
        sequenceCount = sequenceCount,
        keyCacheType = KvCacheType.Q8_0,
        valueCacheType = KvCacheType.Q8_0,
        backend = BackendKind.CPU,
        memoryTopology = MemoryTopology.UNKNOWN,
        gpuLayerCount = 0,
        compromises = if (context < 4_096) listOf(RunPlanCompromise.CONTEXT_REDUCED) else emptyList(),
    )

    private fun snapshot(
        lowMemory: Boolean? = false,
        thermal: ThermalState = ThermalState.NOMINAL,
    ) = DeviceSnapshot(
        hardwareProfile = HardwareProfile(
            cpuArchitecture = "arm64",
            logicalCoreCount = 8,
            performanceCoreCount = 4,
            instructionSets = emptySet(),
            backends = listOf(
                com.debanshu777.caraml.core.platform.BackendCapability(
                    BackendKind.CPU,
                    BackendStatus.AVAILABLE,
                    null,
                    Confidence.HIGH,
                    null,
                    emptyList(),
                ),
            ),
            memoryTopology = MemoryTopology.UNKNOWN,
            evidence = emptyList(),
        ),
        resources = ResourceSnapshot(
            additionalAllocatableHostBytes = 8L shl 30,
            additionalAllocatableGpuBytes = null,
            currentProcessBytes = 128L shl 20,
            freeStorageBytes = 20L shl 30,
            osPressureReserveHostBytes = 256L shl 20,
            observedAppFootprintNoiseP95Bytes = 64L shl 20,
            platformMinimumReserveHostBytes = 512L shl 20,
            lowMemory = lowMemory,
            thermalState = thermal,
            powerPolicyState = PowerPolicyState.NORMAL,
            capturedAtEpochMs = 10_000L,
            evidence = emptyList(),
        ),
        baseHostBudgetBytes = 6L shl 30,
        baseGpuBudgetBytes = null,
        baseSharedBudgetBytes = null,
        baseStorageBudgetBytes = 10L shl 30,
        isFresh = true,
        evidence = emptyList(),
    )

    private companion object {
        const val ASSESSMENT_KEY = "assessment-1"
        const val ENGINE_VERSION = "engine-1"
        val requestedPlan = planStatic(4_096)
        val identity = ModelFileIdentity(
            repositoryId = "owner/model",
            revision = "a".repeat(40),
            path = "model-q4.gguf",
            sizeBytes = 4,
            gitOid = "b".repeat(40),
            lfsOid = null,
            xetHash = null,
            evidence = emptyList(),
        )
        val model = LocalModelEntity(
            modelId = "owner/model",
            filename = "model-q4.gguf",
            localPath = "/private/path/model-q4.gguf",
            sizeBytes = 4,
            downloadedAt = 1,
            author = null,
            libraryName = null,
            pipelineTag = "text-generation",
        )

        private fun planStatic(context: Int) = LlmRunPlan(
            contextTokens = context,
            batchSize = 128,
            microBatchSize = 64,
            sequenceCount = 1,
            keyCacheType = KvCacheType.Q8_0,
            valueCacheType = KvCacheType.Q8_0,
            backend = BackendKind.CPU,
            memoryTopology = MemoryTopology.UNKNOWN,
            gpuLayerCount = 0,
            compromises = emptyList(),
        )
    }
}
