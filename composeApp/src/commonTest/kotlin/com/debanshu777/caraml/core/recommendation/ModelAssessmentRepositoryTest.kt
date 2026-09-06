package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

@OptIn(ExperimentalCoroutinesApi::class)
class ModelAssessmentRepositoryTest {
    @Test
    fun concurrentEqualAssessmentsShareOneCalculation() = runTest {
        val gate = CompletableDeferred<Unit>()
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            gate.await()
            task7Plans()
        }

        val results = listOf(
            async { repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload()) },
            async { repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload()) },
        )
        runCurrent()
        assertEquals(1, invocations)

        gate.complete(Unit)
        val completed = results.awaitAll()
        assertEquals(completed[0], completed[1])
    }

    @Test
    fun noCalibrationSourceStillUsesStableSentinelForSingleFlightAndCaching() = runTest {
        var invocations = 0
        val repository = repository(calibrationSource = NoCalibrationSource) { _, _, _ ->
            invocations += 1
            task7Plans()
        }

        val first = repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload())
        val second = repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload())

        assertEquals(1, invocations)
        assertSame(first.planAssessments, second.planAssessments)
    }

    @Test
    fun profileChangeDoesNotRecalculateObjectiveAssessment() = runTest {
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            task7Plans()
        }
        val snapshot = task6Snapshot()

        val assessment = repository.assess(task7Descriptor(), snapshot, task6LlmWorkload())
        repository.personalize(
            assessment,
            snapshot,
            RecommendationProfile(riskTolerance = RiskTolerance.CONSERVATIVE),
        )
        repository.personalize(
            assessment,
            snapshot,
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )

        assertEquals(1, invocations)
    }

    @Test
    fun cacheHitAssemblesAFreshAssessmentFromCurrentDynamicSnapshot() = runTest {
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            task7Plans()
        }
        val firstSnapshot = task6Snapshot(hostBudget = 4_000, storageBudget = 8_000)
        val secondSnapshot = task6Snapshot(hostBudget = 2_000, storageBudget = 3_000)

        val first = repository.assess(task7Descriptor(), firstSnapshot, task6LlmWorkload())
        val second = repository.assess(task7Descriptor(), secondSnapshot, task6LlmWorkload())

        assertEquals(1, invocations)
        assertNotSame(first, second)
        assertSame(first.planAssessments, second.planAssessments)
        assertEquals(4_000, first.baseHostBudgetBytes)
        assertEquals(2_000, second.baseHostBudgetBytes)
        assertEquals(3_000, second.baseStorageBudgetBytes)
    }

    @Test
    fun equalKeyCacheHitCannotInheritArbitraryFirstCallerEvidence() = runTest {
        val calibration = FixedTask7CalibrationSource()
        val suitabilityEngine = SuitabilityEngine(
            compatibilityChecker = CompatibilityChecker(EngineCapabilitySource { SupportEvidence.Supported }),
            calibrationSource = calibration,
        )
        val repository = ModelAssessmentRepository(
            suitabilityEngine = suitabilityEngine,
            recommendationPolicy = RecommendationPolicy(),
            calibrationSource = calibration,
            assessmentDispatcher = StandardTestDispatcher(testScheduler),
        )
        val secret = "private-first-caller-metadata"
        val first = repository.assess(
            task7Descriptor(evidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, secret))),
            task6Snapshot(hardwareEvidence = listOf(Evidence(AssessmentReason.METADATA_VALIDATED, Confidence.HIGH, secret))),
            task6LlmWorkload(),
        )
        val second = repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload())

        assertTrue(first.evidence.none { it.detail == secret })
        assertTrue(second.evidence.none { it.detail == secret })
        assertSame(first.planAssessments, second.planAssessments)
    }

    @Test
    fun cacheKeyTracksIdentityDescriptorWorkloadHardwareEngineEstimatorAndCalibration() {
        val descriptor = task7Descriptor()
        val workload = task6LlmWorkload()
        val hardware = task6Hardware()
        val base = assessmentCacheKey(
            descriptor,
            workload,
            hardware,
            engineVersion = "runner-1.0.0",
            estimatorVersion = 1,
            calibrationRevision = 7,
        ) ?: fail("valid inputs must produce a cache key")

        assertNotEquals(base, assessmentCacheKey(task7Descriptor(path = "other.gguf"), workload, hardware, "runner-1.0.0", 1, 7))
        assertNotEquals(base, assessmentCacheKey(task7Descriptor(parameterCount = 8_000_000_000), workload, hardware, "runner-1.0.0", 1, 7))
        assertNotEquals(base, assessmentCacheKey(descriptor, task7Workload(contextTokens = 8_192), hardware, "runner-1.0.0", 1, 7))
        assertNotEquals(base, assessmentCacheKey(descriptor, workload, task6Hardware(logicalCores = 12), "runner-1.0.0", 1, 7))
        assertNotEquals(base, assessmentCacheKey(descriptor, workload, hardware, "runner-2.0.0", 1, 7))
        assertNotEquals(base, assessmentCacheKey(descriptor, workload, hardware, "runner-1.0.0", 2, 7))
        assertNotEquals(base, assessmentCacheKey(descriptor, workload, hardware, "runner-1.0.0", 1, 8))
        assertEquals(null, assessmentCacheKey(descriptor, workload, hardware, "runner version with spaces", 1, 7))
    }

    @Test
    fun unsafeDirectDescriptorIdentitiesAreNeverRetainedAsCacheKeys() {
        val workload = task6LlmWorkload()
        val hardware = task6Hardware()

        listOf(
            task7Descriptor(repositoryId = "owner//model"),
            task7Descriptor(path = "folder\\model.gguf"),
            task7Descriptor(objectId = "sha256:unsafe\nidentity"),
        ).forEach { descriptor ->
            assertEquals(
                null,
                assessmentCacheKey(descriptor, workload, hardware, "runner-1.0.0", 1, 7),
                descriptor.file.toString(),
            )
        }
    }

    @Test
    fun oversizedDirectDiffusionDescriptorNeverReachesAssessmentComputer() = runTest {
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            task7Plans()
        }
        val snapshot = task6Snapshot(hostBudget = 4_096, storageBudget = 8_192)

        val assessment = repository.assess(
            task7OversizedDiffusionDescriptor(),
            snapshot,
            task6DiffusionWorkload(),
        )

        assertEquals(0, invocations)
        assertEquals("assessment-unavailable", assessment.assessmentKey)
        assertEquals(listOf(AssessmentReason.INVALID_METADATA), assessment.planAssessments.reasons)
        assertEquals(4_096, assessment.baseHostBudgetBytes)
        assertEquals(8_192, assessment.baseStorageBudgetBytes)
    }

    @Test
    fun concurrentCalibrationFailuresNeverCreateUnboundedUncachedAssessmentWork() = runTest {
        var invocations = 0
        val repository = repository(calibrationSource = FailingTask7CalibrationSource) { _, _, _ ->
            invocations += 1
            task7Plans()
        }

        val assessments = List(ModelAssessmentRepository.MAX_IN_FLIGHT_ENTRIES * 2) {
            async { repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload()) }
        }.awaitAll()

        assertEquals(0, invocations)
        assertTrue(assessments.all { it.assessmentKey == "assessment-unavailable" })
        assertTrue(
            assessments.all {
                it.planAssessments.reasons == listOf(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE)
            },
        )
    }

    @Test
    fun calibrationCancellationIsRethrownByIdentityBeforeAssessmentWork() = runTest {
        val expected = CancellationException("calibration-cancelled")
        var invocations = 0
        val calibration = object : CalibrationSource {
            override fun engineVersion(): String = throw expected
            override fun backendProfileFor(backend: com.debanshu777.caraml.core.platform.BackendKind): BackendPerformanceProfile? = null
            override fun correctionFor(key: CalibrationKey): CalibrationCorrection? = null
            override fun revision(): Long = 1
        }
        val repository = repository(calibrationSource = calibration) { _, _, _ ->
            invocations += 1
            task7Plans()
        }

        val actual = try {
            repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload())
            fail("expected cancellation")
        } catch (cancelled: CancellationException) {
            cancelled
        }

        assertSame(expected, actual)
        assertEquals(0, invocations)
    }

    @Test
    fun completedCacheIsBoundedLeastRecentlyUsed() = runTest {
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            task7Plans()
        }

        repeat(ModelAssessmentRepository.MAX_CACHE_ENTRIES) { index ->
            repository.assess(task7Descriptor(path = "model-$index.gguf"), task6Snapshot(), task6LlmWorkload())
        }
        // Refresh model-0 before overflow: model-1 must now be the least-recently used entry.
        repository.assess(task7Descriptor(path = "model-0.gguf"), task6Snapshot(), task6LlmWorkload())
        repository.assess(
            task7Descriptor(path = "model-${ModelAssessmentRepository.MAX_CACHE_ENTRIES}.gguf"),
            task6Snapshot(),
            task6LlmWorkload(),
        )
        repository.assess(task7Descriptor(path = "model-0.gguf"), task6Snapshot(), task6LlmWorkload())
        repository.assess(task7Descriptor(path = "model-1.gguf"), task6Snapshot(), task6LlmWorkload())

        assertEquals(ModelAssessmentRepository.MAX_CACHE_ENTRIES + 2, invocations)
    }

    @Test
    fun oneCancelledConsumerDoesNotCancelSharedWorkNeededByAnother() = runTest {
        val gate = CompletableDeferred<Unit>()
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            gate.await()
            task7Plans()
        }
        val cancelled = async { repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload()) }
        val survivor = async { repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload()) }
        runCurrent()

        cancelled.cancelAndJoin()
        gate.complete(Unit)
        survivor.await()

        assertEquals(1, invocations)
        assertTrue(cancelled.isCancelled)
    }

    @Test
    fun uniqueInFlightCallerRegistryIsBounded() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = repository { _, _, _ ->
            gate.await()
            task7Plans()
        }
        val active = List(ModelAssessmentRepository.MAX_IN_FLIGHT_ENTRIES) { index ->
            async {
                repository.assess(
                    task7Descriptor(path = "active-$index.gguf"),
                    task6Snapshot(),
                    task6LlmWorkload(),
                )
            }
        }
        runCurrent()

        val failure = try {
            repository.assess(
                task7Descriptor(path = "overflow.gguf"),
                task6Snapshot(),
                task6LlmWorkload(),
            )
            fail("expected bounded in-flight rejection")
        } catch (error: AssessmentCapacityExceededException) {
            error
        }
        assertEquals("Too many assessment calculations are active", failure.message)

        active.forEach { it.cancel() }
        active.forEach { it.join() }
    }

    @Test
    fun finalConsumerCancellationCancelsUnderlyingWorkAndDoesNotCacheIt() = runTest {
        val underlyingCancelled = CompletableDeferred<Unit>()
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            try {
                awaitCancellation()
            } finally {
                underlyingCancelled.complete(Unit)
            }
        }
        val consumer = async { repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload()) }
        runCurrent()

        consumer.cancelAndJoin()
        advanceUntilIdle()
        underlyingCancelled.await()
        assertEquals(1, invocations)

        val retry = async { repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload()) }
        runCurrent()
        assertEquals(2, invocations)
        retry.cancelAndJoin()
    }

    @Test
    fun calculationCancellationIsRethrownByIdentityAndNeverCached() = runTest {
        val expected = CancellationException("fixture-cancellation")
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            throw expected
        }

        repeat(2) {
            val actual = try {
                repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload())
                fail("expected cancellation")
            } catch (error: CancellationException) {
                error
            }
            assertSame(expected, actual)
        }
        assertEquals(2, invocations)
    }

    @Test
    fun failuresAreNeverCached() = runTest {
        val expected = IllegalStateException("fixture-failure")
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            throw expected
        }

        repeat(2) {
            val actual = try {
                repository.assess(task7Descriptor(), task6Snapshot(), task6LlmWorkload())
                fail("expected failure")
            } catch (error: IllegalStateException) {
                error
            }
            assertSame(expected, actual)
        }
        assertEquals(2, invocations)
    }

    @Test
    fun invalidateRemovesMatchingCompletedEntryWithoutTouchingOthers() = runTest {
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            task7Plans()
        }
        val first = task7Descriptor(path = "first.gguf")
        val second = task7Descriptor(path = "second.gguf")
        repository.assess(first, task6Snapshot(), task6LlmWorkload())
        repository.assess(second, task6Snapshot(), task6LlmWorkload())

        repository.invalidate(first.file)
        repository.assess(first, task6Snapshot(), task6LlmWorkload())
        repository.assess(second, task6Snapshot(), task6LlmWorkload())

        assertEquals(3, invocations)
    }

    @Test
    fun invalidateCancelsMatchingInFlightEntryAndAllowsFreshWork() = runTest {
        val firstGate = CompletableDeferred<Unit>()
        val underlyingCancelled = CompletableDeferred<Unit>()
        var invocations = 0
        val repository = repository { _, _, _ ->
            invocations += 1
            if (invocations == 1) {
                try {
                    firstGate.await()
                } finally {
                    underlyingCancelled.complete(Unit)
                }
            }
            task7Plans()
        }
        val descriptor = task7Descriptor()
        val first = async { repository.assess(descriptor, task6Snapshot(), task6LlmWorkload()) }
        runCurrent()

        repository.invalidate(descriptor.file)
        advanceUntilIdle()
        first.join()
        assertTrue(first.isCancelled)
        assertTrue(underlyingCancelled.isCompleted)
        repository.assess(descriptor, task6Snapshot(), task6LlmWorkload())

        assertEquals(2, invocations)
    }

    @Test
    fun calibrationChangeDuringFlightCannotPublishOldRevisionUnderNewRevision() = runTest {
        val calibration = MutableTask7CalibrationSource()
        val firstGate = CompletableDeferred<Unit>()
        var invocations = 0
        val repository = repository(calibrationSource = calibration) { _, _, _ ->
            val invocation = ++invocations
            if (invocation == 1) firstGate.await()
            task7Plans(assessmentKey = "calculation-$invocation")
        }
        val descriptor = task7Descriptor()
        val first = async { repository.assess(descriptor, task6Snapshot(), task6LlmWorkload()) }
        runCurrent()

        calibration.revision = 2
        val newRevision = async { repository.assess(descriptor, task6Snapshot(), task6LlmWorkload()) }
        runCurrent()
        assertEquals("calculation-2", newRevision.await().assessmentKey)

        firstGate.complete(Unit)
        assertEquals("calculation-1", first.await().assessmentKey)
        assertEquals(
            "calculation-2",
            repository.assess(descriptor, task6Snapshot(), task6LlmWorkload()).assessmentKey,
        )
        assertEquals(2, invocations)
    }

    private fun TestScope.repository(
        calibrationSource: CalibrationSource = FixedTask7CalibrationSource(),
        assessmentComputer: suspend (ModelDescriptor, HardwareProfile, WorkloadConfig) -> AssessedPlans,
    ): ModelAssessmentRepository {
        val suitabilityEngine = SuitabilityEngine(
            compatibilityChecker = CompatibilityChecker(EngineCapabilitySource { SupportEvidence.Supported }),
            calibrationSource = calibrationSource,
        )
        return ModelAssessmentRepository(
            suitabilityEngine = suitabilityEngine,
            recommendationPolicy = RecommendationPolicy(),
            calibrationSource = calibrationSource,
            assessmentDispatcher = StandardTestDispatcher(testScheduler),
            assessmentComputer = assessmentComputer,
        )
    }
}

private class FixedTask7CalibrationSource : CalibrationSource {
    override fun engineVersion(): String = "runner-1.0.0"
    override fun backendProfileFor(backend: com.debanshu777.caraml.core.platform.BackendKind): BackendPerformanceProfile? = null
    override fun correctionFor(key: CalibrationKey): CalibrationCorrection? = null
    override fun revision(): Long = 1
}

private data object FailingTask7CalibrationSource : CalibrationSource {
    override fun engineVersion(): String = throw IllegalStateException("private-calibration-payload")
    override fun backendProfileFor(backend: com.debanshu777.caraml.core.platform.BackendKind): BackendPerformanceProfile? = null
    override fun correctionFor(key: CalibrationKey): CalibrationCorrection? = null
    override fun revision(): Long = 1
}

private class MutableTask7CalibrationSource : CalibrationSource {
    var revision: Long = 1

    override fun engineVersion(): String = "runner-1.0.0"
    override fun backendProfileFor(backend: com.debanshu777.caraml.core.platform.BackendKind): BackendPerformanceProfile? = null
    override fun correctionFor(key: CalibrationKey): CalibrationCorrection? = null
    override fun revision(): Long = revision
}

private fun task7Plans(
    assessmentKey: String = "owner/model@0123456789abcdef0123456789abcdef01234567:model.gguf",
) = AssessedPlans(
    values = emptyList(),
    assessmentKey = assessmentKey,
    compatibility = Compatibility.Unknown(listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN)),
    memoryTopology = MemoryTopology.UNKNOWN,
)

private fun task7OversizedDiffusionDescriptor(): DiffusionModelDescriptor {
    val repositoryId = "owner/diffusion"
    val revision = "0123456789abcdef0123456789abcdef01234567"
    return DiffusionModelDescriptor(
        repositoryId = repositoryId,
        revision = revision,
        components = List(DescriptorLimits.MAX_COMPONENTS + 1) { index ->
            DiffusionComponentDescriptor(
                file = ModelFileIdentity(
                    repositoryId = repositoryId,
                    revision = revision,
                    path = "component-$index.safetensors",
                    sizeBytes = 1,
                    gitOid = null,
                    lfsOid = "sha256:$index",
                    xetHash = null,
                    evidence = emptyList(),
                ),
                role = null,
                required = true,
                isPrimary = index == 0,
            )
        },
        mode = DiffusionMode.IMAGE,
        family = "SDXL",
        architecture = null,
        width = 512,
        height = 512,
        quantizationDistribution = emptySet(),
        requiredComponentsPresent = true,
        requiredEngineFeatures = emptySet(),
        evidence = emptyList(),
    )
}

private fun task7Descriptor(
    path: String = "model.gguf",
    parameterCount: Long = 7_000_000_000,
    repositoryId: String = "owner/model",
    objectId: String = "sha256:${path.encodeToByteArray().fold(1L) { value, byte -> value * 31 + byte }.toString(16).padStart(64, '0').takeLast(64)}",
    evidence: Collection<Evidence> = emptyList(),
): LlmModelDescriptor {
    val revision = "0123456789abcdef0123456789abcdef01234567"
    val identity = ModelFileIdentity(
        repositoryId = repositoryId,
        revision = revision,
        path = path,
        sizeBytes = 1_073_741_824,
        gitOid = null,
        lfsOid = objectId,
        xetHash = null,
        evidence = emptyList(),
    )
    return LlmModelDescriptor(
        repositoryId = identity.repositoryId,
        revision = identity.revision,
        file = identity,
        architecture = "llama",
        quantization = QuantizationEvidence.Known("Q4_K_M"),
        parameterCount = parameterCount,
        contextLimit = 16_384,
        transformerShape = TransformerShape(32, 8, 32, 4_096, 128),
        ggufVersion = 3,
        requiredEngineFeatures = emptyList(),
        evidence = evidence,
    )
}

private fun task7Workload(contextTokens: Int) = LlmWorkloadConfig(
    userRequestedContextTokens = contextTokens,
    contextTokens = contextTokens,
    minimumContextTokens = 512,
    promptTokens = 512,
    generationReserveTokens = 256,
    batchSize = 128,
    microBatchSize = 128,
    sequenceCount = 1,
    kvCacheSelection = KvCacheSelection.Auto,
    allowContextFallback = false,
    allowBatchFallback = false,
    allowKvCacheFallback = false,
    allowedKvCacheTypes = KvCacheType.entries,
    evidence = emptyList(),
)
