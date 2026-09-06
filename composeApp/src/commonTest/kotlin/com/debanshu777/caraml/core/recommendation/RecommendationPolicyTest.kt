package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendCapability
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.BackendStatus
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.HardwareProfile
import com.debanshu777.caraml.core.platform.MemoryTopology
import com.debanshu777.caraml.core.platform.PowerPolicyState
import com.debanshu777.caraml.core.platform.ResourceSnapshot
import com.debanshu777.caraml.core.platform.ResourcePoolConfidence
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.rating.SdArchitecture
import com.debanshu777.huggingfacemanager.sdcpp.ComponentRole
import kotlinx.coroutines.CancellationException
import kotlin.time.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RecommendationPolicyTest {
    private val policy = RecommendationPolicy()

    @Test
    fun mapsEveryFitBandByRiskProfileWithExactBudgetEquality() {
        val rows = listOf(
            Row(FitBand.COMFORTABLE, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.RECOMMENDED),
            Row(FitBand.COMFORTABLE, RiskTolerance.BALANCED, 850, RecommendationCategory.RECOMMENDED),
            Row(FitBand.COMFORTABLE, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.RECOMMENDED),
            Row(FitBand.LIKELY, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.RISKY),
            Row(FitBand.LIKELY, RiskTolerance.BALANCED, 850, RecommendationCategory.USABLE),
            Row(FitBand.LIKELY, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.RECOMMENDED),
            Row(FitBand.BORDERLINE, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.NOT_SUITABLE),
            Row(FitBand.BORDERLINE, RiskTolerance.BALANCED, 850, RecommendationCategory.RISKY),
            Row(FitBand.BORDERLINE, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.RISKY),
            Row(FitBand.NO_FIT, RiskTolerance.CONSERVATIVE, 750, RecommendationCategory.NOT_SUITABLE),
            Row(FitBand.NO_FIT, RiskTolerance.BALANCED, 850, RecommendationCategory.NOT_SUITABLE),
            Row(FitBand.NO_FIT, RiskTolerance.EXPERIMENTAL, 950, RecommendationCategory.NOT_SUITABLE),
        )

        rows.forEach { row ->
            val recommendation = recommend(
                range = rangeFor(row.band, row.policyBudget),
                risk = row.risk,
            )
            assertEquals(row.category, recommendation.category, row.toString())
        }
    }

    @Test
    fun incompatibleAndUnknownCompatibilityPrecedeOtherwiseComfortableFit() {
        val comfortable = task6Range(1, 1, 1)
        val incompatible = policy.recommend(
            task6Assessment(
                compatibility = Compatibility.Incompatible(listOf(AssessmentReason.UNSUPPORTED_FORMAT)),
                plans = listOf(task6PlanAssessment(host = comfortable)),
            ),
            task6Snapshot(),
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )
        val unknown = policy.recommend(
            task6Assessment(
                compatibility = Compatibility.Unknown(listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN)),
                plans = listOf(task6PlanAssessment(host = comfortable)),
            ),
            task6Snapshot(),
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )

        assertEquals(RecommendationCategory.INCOMPATIBLE, incompatible.category)
        assertEquals(listOf(AssessmentReason.UNSUPPORTED_FORMAT), incompatible.reasons)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, unknown.category)
        assertEquals(listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN), unknown.reasons)
    }

    @Test
    fun mismatchedOuterAndInnerAssessmentIdentityOrCompatibilityNeedsInformation() {
        val identityMismatch = policy.recommend(
            task6Assessment(
                assessmentKey = "outer/model@revision:file.gguf",
                innerAssessmentKey = "inner/model@revision:file.gguf",
            ),
            task6Snapshot(),
            RecommendationProfile(),
        )
        val compatibilityMismatch = policy.recommend(
            task6Assessment(
                compatibility = Compatibility.Compatible,
                innerCompatibility = Compatibility.Unknown(
                    listOf(AssessmentReason.ENGINE_SUPPORT_UNKNOWN),
                ),
            ),
            task6Snapshot(),
            RecommendationProfile(),
        )

        listOf(identityMismatch, compatibilityMismatch).forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), result.reasons)
        }
    }

    @Test
    fun definiteCompatibilityPrecedesLiveDeviceChangesButStructuralMismatchStillWins() {
        val incompatible = Compatibility.Incompatible(listOf(AssessmentReason.UNSUPPORTED_FORMAT))
        val staleSnapshot = task6Snapshot(
            topology = MemoryTopology.UNIFIED,
            backends = emptyList(),
            hostBudget = null,
            sharedBudget = null,
        )
        val knownIncompatible = policy.recommend(
            task6Assessment(compatibility = incompatible),
            staleSnapshot,
            RecommendationProfile(),
        )
        val inconsistent = policy.recommend(
            task6Assessment(
                compatibility = incompatible,
                innerCompatibility = Compatibility.Compatible,
            ),
            staleSnapshot,
            RecommendationProfile(),
        )

        assertEquals(RecommendationCategory.INCOMPATIBLE, knownIncompatible.category)
        assertEquals(listOf(AssessmentReason.UNSUPPORTED_FORMAT), knownIncompatible.reasons)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, inconsistent.category)
        assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), inconsistent.reasons)
    }

    @Test
    fun cachedTopologyOrCandidateTopologyMismatchNeedsFreshAssessment() {
        val assessedTopologyMismatch = policy.recommend(
            task6Assessment(innerTopology = MemoryTopology.UNIFIED),
            task6Snapshot(topology = MemoryTopology.UNKNOWN),
            RecommendationProfile(),
        )
        val candidateTopologyMismatch = policy.recommend(
            task6Assessment(
                plans = listOf(
                    task6PlanAssessment(
                        plan = task6LlmPlan(topology = MemoryTopology.UNIFIED),
                        host = null,
                        shared = task6Range(100, 100, 100),
                    ),
                ),
                innerTopology = MemoryTopology.UNKNOWN,
            ),
            task6Snapshot(topology = MemoryTopology.UNKNOWN),
            RecommendationProfile(),
        )

        listOf(assessedTopologyMismatch, candidateTopologyMismatch).forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.DEVICE_CAPABILITIES_CHANGED), result.reasons)
        }
    }

    @Test
    fun callerBuiltBudgetsCannotHidePresentUntrustedBackendHeadroom() {
        val untrustedHeadroom = BackendCapability(
            kind = BackendKind.CUDA,
            status = BackendStatus.AVAILABLE,
            additionalAllocatableBytes = 1_000,
            availabilityConfidence = Confidence.HIGH,
            headroomConfidence = null,
            evidence = listOf(
                Evidence(AssessmentReason.BACKEND_CAPABILITY_VERIFIED, Confidence.HIGH, "availability-only"),
            ),
        )
        val unifiedPlan = task6PlanAssessment(
            plan = task6LlmPlan(backend = BackendKind.CUDA, topology = MemoryTopology.UNIFIED),
            host = null,
            shared = task6Range(100, 100, 100),
        )
        val discretePlan = task6PlanAssessment(
            plan = task6LlmPlan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE),
            host = task6Range(100, 100, 100),
            gpu = task6Range(100, 100, 100),
        )

        val results = listOf(
            policy.recommend(
                task6Assessment(plans = listOf(unifiedPlan)),
                task6Snapshot(
                    hostBudget = null,
                    sharedBudget = 1_000,
                    topology = MemoryTopology.UNIFIED,
                    backends = listOf(untrustedHeadroom),
                ),
                RecommendationProfile(),
            ),
            policy.recommend(
                task6Assessment(plans = listOf(discretePlan)),
                task6Snapshot(
                    hostBudget = 1_000,
                    gpuBudget = 1_000,
                    topology = MemoryTopology.DISCRETE,
                    backends = listOf(untrustedHeadroom),
                ),
                RecommendationProfile(),
            ),
        )

        results.forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), result.reasons)
        }
    }

    @Test
    fun budgetConfidenceAndTopologyShapeMustMatchEveryPresentBaseBudget() {
        val unifiedPlan = task6PlanAssessment(
            plan = task6LlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED),
            host = null,
            shared = task6Range(100, 100, 100),
        )
        val assessment = task6Assessment(plans = listOf(unifiedPlan))
        val validBackend = task6Backend(BackendKind.METAL)
        val missingConfidence = task6Snapshot(
            hostBudget = null,
            sharedBudget = 1_000,
            sharedConfidence = null,
            topology = MemoryTopology.UNIFIED,
            backends = listOf(validBackend),
        )
        val impossibleHostPool = task6Snapshot(
            hostBudget = 1_000,
            sharedBudget = 1_000,
            topology = MemoryTopology.UNIFIED,
            backends = listOf(validBackend),
        )

        listOf(missingConfidence, impossibleHostPool).forEach { snapshot ->
            val result = policy.recommend(assessment, snapshot, RecommendationProfile())
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), result.reasons)
        }
    }

    @Test
    fun lowBackendHeadroomConfidenceCannotBePromotedByCallerBuiltSharedBudget() {
        val lowConfidenceHeadroom = BackendCapability(
            kind = BackendKind.METAL,
            status = BackendStatus.AVAILABLE,
            additionalAllocatableBytes = 1_000,
            availabilityConfidence = Confidence.HIGH,
            headroomConfidence = Confidence.LOW,
            evidence = listOf(
                Evidence(AssessmentReason.RESOURCE_READING_VALIDATED, Confidence.LOW, "metal-headroom"),
            ),
        )
        val accelerated = task6PlanAssessment(
            plan = task6LlmPlan(backend = BackendKind.METAL, topology = MemoryTopology.UNIFIED),
            host = null,
            shared = task6Range(100, 100, 100),
        )

        val result = policy.recommend(
            assessment = task6Assessment(plans = listOf(accelerated)),
            snapshot = task6Snapshot(
                hostBudget = null,
                sharedBudget = 1_000,
                sharedConfidence = Confidence.HIGH,
                topology = MemoryTopology.UNIFIED,
                backends = listOf(lowConfidenceHeadroom),
            ),
            profile = RecommendationProfile(),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
        assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), result.reasons)
    }

    @Test
    fun baseBudgetConfidenceCannotExceedContributingResourcePoolConfidence() {
        val cpuAssessment = task6Assessment()
        val discreteAssessment = task6Assessment(
            plans = listOf(
                task6PlanAssessment(
                    plan = task6LlmPlan(backend = BackendKind.CUDA, topology = MemoryTopology.DISCRETE),
                    host = task6Range(100, 100, 100),
                    gpu = task6Range(100, 100, 100),
                ),
            ),
        )
        val mismatches = listOf(
            cpuAssessment to task6Snapshot(
                hostConfidence = Confidence.HIGH,
                resourceHostConfidence = Confidence.LOW,
            ),
            discreteAssessment to task6Snapshot(
                hostBudget = 1_000,
                gpuBudget = 1_000,
                gpuConfidence = Confidence.HIGH,
                resourceGpuConfidence = Confidence.LOW,
                topology = MemoryTopology.DISCRETE,
                backends = listOf(task6Backend(BackendKind.CUDA)),
            ),
            cpuAssessment to task6Snapshot(
                storageConfidence = Confidence.HIGH,
                resourceStorageConfidence = Confidence.LOW,
            ),
        )

        mismatches.forEach { (assessment, snapshot) ->
            val result = policy.recommend(assessment, snapshot, RecommendationProfile())
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), result.reasons)
        }
    }

    @Test
    fun acceleratedLlmWithUnknownTopologyIsInvalidAtThePolicyBoundary() {
        val plan = task6LlmPlan(
            backend = BackendKind.CUDA,
            topology = MemoryTopology.UNKNOWN,
            gpuLayers = 16,
        )
        val result = policy.recommend(
            task6Assessment(plans = listOf(task6PlanAssessment(plan = plan))),
            task6Snapshot(backends = listOf(task6Backend(BackendKind.CUDA))),
            RecommendationProfile(),
        )

        assertEquals(AssessmentReason.INVALID_WORKLOAD, validateRunPlan(plan))
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
        assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), result.reasons)
    }

    @Test
    fun unavailableBackendInAnyCandidateInvalidatesTheCachedCandidateGraph() {
        val availableCpu = task6PlanAssessment(plan = task6LlmPlan(keyContext = 4_096))
        val staleCuda = task6PlanAssessment(
            plan = task6LlmPlan(
                keyContext = 2_048,
                backend = BackendKind.CUDA,
                topology = MemoryTopology.UNKNOWN,
            ),
        )
        val snapshot = task6Snapshot(
            topology = MemoryTopology.UNKNOWN,
            backends = listOf(
                task6Backend(BackendKind.CPU, BackendStatus.AVAILABLE),
                task6Backend(BackendKind.CUDA, BackendStatus.UNAVAILABLE),
            ),
        )

        val result = policy.recommend(
            task6Assessment(plans = listOf(availableCpu, staleCuda)),
            snapshot,
            RecommendationProfile(),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
        assertEquals(listOf(AssessmentReason.DEVICE_CAPABILITIES_CHANGED), result.reasons)
    }

    @Test
    fun cpuPlanAlsoRequiresACurrentAvailableBackendCapability() {
        val result = policy.recommend(
            task6Assessment(),
            task6Snapshot(backends = emptyList()),
            RecommendationProfile(),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
        assertEquals(listOf(AssessmentReason.DEVICE_CAPABILITIES_CHANGED), result.reasons)
    }

    @Test
    fun oversizedCandidateAndBackendGraphsAreRejectedBeforeIteration() {
        val tooManyLlm = (0..RecommendationPolicyV1.MAX_LLM_CANDIDATES).map { index ->
            task6PlanAssessment(plan = task6LlmPlan(keyContext = 1_024 + index))
        }
        val tooManyDiffusion = (0..RecommendationPolicyV1.MAX_DIFFUSION_CANDIDATES).map { index ->
            task6PlanAssessment(
                plan = task6DiffusionPlan(width = 512 + index),
                host = null,
                shared = task6Range(100, 100, 100),
            )
        }
        val tooManyBackends = BackendKind.entries.map(::task6Backend) + task6Backend(BackendKind.CPU)

        val results = listOf(
            policy.recommend(task6Assessment(plans = tooManyLlm), task6Snapshot(), RecommendationProfile()),
            policy.recommend(
                task6Assessment(plans = tooManyDiffusion, innerTopology = MemoryTopology.UNIFIED),
                task6Snapshot(
                    hostBudget = null,
                    sharedBudget = 1_000,
                    topology = MemoryTopology.UNIFIED,
                    backends = listOf(task6Backend(BackendKind.METAL)),
                ),
                RecommendationProfile(),
            ),
            policy.recommend(
                task6Assessment(),
                task6Snapshot(backends = tooManyBackends),
                RecommendationProfile(),
            ),
        )

        results.forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.COLLECTION_LIMIT_EXCEEDED), result.reasons)
        }
    }

    @Test
    fun underReportingCandidateBackendAndCompatibilityCollectionsFailClosedAtBoundedReads() {
        var candidateReads = 0
        var backendReads = 0
        var compatibilityReads = 0
        val candidates = underReportingCollection(task6PlanAssessment(), 100) { candidateReads += 1 }
        val backends = underReportingCollection(task6Backend(BackendKind.CPU), 100) { backendReads += 1 }
        val compatibility = Compatibility.Incompatible(
            reasons = underReportingCollection(AssessmentReason.UNSUPPORTED_FORMAT, 100) {
                compatibilityReads += 1
            },
        )

        val results = listOf(
            policy.recommend(
                task6Assessment(plans = candidates, innerTopology = MemoryTopology.UNKNOWN),
                task6Snapshot(),
                RecommendationProfile(),
            ),
            policy.recommend(
                task6Assessment(),
                task6Snapshot(backends = backends),
                RecommendationProfile(),
            ),
            policy.recommend(
                task6Assessment(compatibility = compatibility),
                task6Snapshot(),
                RecommendationProfile(),
            ),
        )

        assertTrue(candidateReads <= 25, "candidate reads=$candidateReads")
        assertTrue(backendReads <= 6, "backend reads=$backendReads")
        assertTrue(compatibilityReads <= 33, "compatibility reads=$compatibilityReads")
        results.forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf("COLLECTION_LIMIT_EXCEEDED"), result.reasons.map { it.name })
        }
    }

    @Test
    fun boundedIterableSnapshotStopsAtTheLimitAndDoesNotSwallowCancellation() {
        var reads = 0
        val source = object : Iterable<Int> {
            override fun iterator(): Iterator<Int> = object : Iterator<Int> {
                private var next = 0
                override fun hasNext(): Boolean = next < 100
                override fun next(): Int = next++.also { reads += 1 }
            }
        }

        val snapshot = boundedCollectionSnapshot(source, 4)

        assertEquals(listOf(0, 1, 2, 3), snapshot.values)
        assertTrue(snapshot.limitExceeded)
        assertTrue(reads <= 5, "iterable reads=$reads")
        assertFailsWith<CancellationException> {
            boundedCollectionSnapshot(
                source = Iterable<Int> {
                    object : Iterator<Int> {
                        override fun hasNext(): Boolean = throw CancellationException("cancelled")
                        override fun next(): Int = throw NoSuchElementException()
                    }
                },
                limit = 4,
            )
        }
    }

    @Test
    fun underReportingPlanPerformanceAndCompromiseCollectionsFailClosedAtBoundedReads() {
        var planEvidenceReads = 0
        var performanceEvidenceReads = 0
        var rangeEvidenceReads = 0
        var compromiseReads = 0
        val evidence = Evidence(AssessmentReason.PERFORMANCE_ESTIMATED, Confidence.HIGH, "bounded")
        val performanceRange = requireNotNull(
            PerformanceRange.create(
                low = 1.0,
                likely = 2.0,
                high = 3.0,
                confidence = Confidence.HIGH,
                evidence = underReportingCollection(evidence, 100) { rangeEvidenceReads += 1 },
            ),
        )
        val performance = PerformanceEstimate.Llm(
            promptTokensPerSecond = performanceRange,
            decodeTokensPerSecond = performanceRange,
            timeToFirstTokenSeconds = performanceRange,
            loadTimeSeconds = performanceRange,
            evidence = underReportingCollection(evidence, 100) { performanceEvidenceReads += 1 },
        )
        val planEvidence = task6PlanAssessment(
            evidence = underReportingCollection(evidence, 100) { planEvidenceReads += 1 },
        )
        val performanceEvidence = task6PlanAssessment(performance = performance)
        val compromised = task6PlanAssessment(
            plan = task6LlmPlan(
                compromises = underReportingCollection(RunPlanCompromise.CONTEXT_REDUCED, 100) {
                    compromiseReads += 1
                },
            ),
        )

        listOf(planEvidence, performanceEvidence, compromised).forEach { candidate ->
            val result = policy.recommend(
                task6Assessment(plans = listOf(candidate)),
                task6Snapshot(),
                RecommendationProfile(),
            )
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf("COLLECTION_LIMIT_EXCEEDED"), result.reasons.map { it.name })
        }
        assertTrue(planEvidenceReads <= 65, "plan evidence reads=$planEvidenceReads")
        assertTrue(performanceEvidenceReads <= 65, "performance evidence reads=$performanceEvidenceReads")
        assertTrue(rangeEvidenceReads <= 65, "range evidence reads=$rangeEvidenceReads")
        assertTrue(compromiseReads <= 9, "compromise reads=$compromiseReads")
    }

    @Test
    fun underReportingNestedSnapshotCollectionsFailClosedAtBoundedReads() {
        var backendEvidenceReads = 0
        var instructionReads = 0
        var hardwareEvidenceReads = 0
        var resourceEvidenceReads = 0
        var snapshotEvidenceReads = 0
        val evidence = Evidence(AssessmentReason.RESOURCE_READING_VALIDATED, Confidence.HIGH, "bounded")
        val backend = BackendCapability(
            kind = BackendKind.CPU,
            status = BackendStatus.AVAILABLE,
            additionalAllocatableBytes = null,
            availabilityConfidence = Confidence.HIGH,
            headroomConfidence = null,
            evidence = underReportingCollection(evidence, 100) { backendEvidenceReads += 1 },
        )
        val snapshots = listOf(
            task6Snapshot(backends = listOf(backend)),
            task6Snapshot(
                instructionSets = underReportingCollection("neon", 100) { instructionReads += 1 },
            ),
            task6Snapshot(
                hardwareEvidence = underReportingCollection(evidence, 100) { hardwareEvidenceReads += 1 },
            ),
            task6Snapshot(
                resourceEvidence = underReportingCollection(evidence, 100) { resourceEvidenceReads += 1 },
            ),
            task6Snapshot(
                evidence = underReportingCollection(evidence, 100) { snapshotEvidenceReads += 1 },
            ),
        )

        snapshots.forEach { snapshot ->
            val result = policy.recommend(task6Assessment(), snapshot, RecommendationProfile())
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf("COLLECTION_LIMIT_EXCEEDED"), result.reasons.map { it.name })
        }
        listOf(
            backendEvidenceReads,
            instructionReads,
            hardwareEvidenceReads,
            resourceEvidenceReads,
            snapshotEvidenceReads,
        ).forEach { reads -> assertTrue(reads <= 65, "nested reads=$reads") }
    }

    @Test
    fun candidateSnapshotIsBoundedBeforeCopyingUntrustedCollections() {
        var reads = 0
        val candidate = task6PlanAssessment()
        val untrusted = object : AbstractCollection<PlanAssessment>() {
            override val size: Int = 1
            override fun iterator(): Iterator<PlanAssessment> = object : Iterator<PlanAssessment> {
                private var remaining = 100
                override fun hasNext(): Boolean = remaining > 0
                override fun next(): PlanAssessment {
                    remaining -= 1
                    reads += 1
                    return candidate
                }
            }
        }

        val assessed = AssessedPlans(
            values = untrusted,
            assessmentKey = TASK6_ASSESSMENT_KEY,
            compatibility = Compatibility.Compatible,
            memoryTopology = MemoryTopology.UNKNOWN,
        )

        assertTrue(reads <= RecommendationPolicyV1.MAX_LLM_CANDIDATES + 1)
        val result = policy.recommend(
            task6Assessment(plans = assessed.values),
            task6Snapshot(),
            RecommendationProfile(),
        )
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
    }

    @Test
    fun duplicateMixedAndMalformedPlansFailClosedBeforeUtility() {
        val duplicate = task6PlanAssessment()
        val mixedLlm = task6PlanAssessment(
            plan = task6LlmPlan(topology = MemoryTopology.UNIFIED),
            host = task6Range(100, 100, 100),
        )
        val mixedDiffusion = task6PlanAssessment(
            plan = task6DiffusionPlan(),
            host = null,
            shared = task6Range(100, 100, 100),
        )
        val malformedLlm = task6PlanAssessment(plan = task6LlmPlan(keyContext = 0))
        val malformedDiffusion = task6PlanAssessment(
            plan = task6DiffusionPlan(width = 0),
            host = null,
            shared = task6Range(100, 100, 100),
        )
        val unifiedSnapshot = task6Snapshot(
            hostBudget = null,
            sharedBudget = 1_000,
            topology = MemoryTopology.UNIFIED,
            backends = listOf(task6Backend(BackendKind.CPU), task6Backend(BackendKind.METAL)),
        )

        val results = listOf(
            policy.recommend(task6Assessment(plans = listOf(duplicate, duplicate)), task6Snapshot(), RecommendationProfile()),
            policy.recommend(
                task6Assessment(plans = listOf(mixedLlm, mixedDiffusion), innerTopology = MemoryTopology.UNIFIED),
                unifiedSnapshot,
                RecommendationProfile(),
            ),
            policy.recommend(task6Assessment(plans = listOf(malformedLlm)), task6Snapshot(), RecommendationProfile()),
            policy.recommend(
                task6Assessment(plans = listOf(malformedDiffusion), innerTopology = MemoryTopology.UNIFIED),
                unifiedSnapshot,
                RecommendationProfile(),
            ),
        )

        results.forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), result.reasons)
        }
    }

    @Test
    fun impossibleMemoryPoolShapesAndContradictoryUnknownPerformanceFailClosed() {
        val extraGpuPool = task6PlanAssessment(gpu = task6Range(1, 1, 1))
        val missingUnifiedPool = task6PlanAssessment(
            plan = task6DiffusionPlan(),
            host = null,
            shared = null,
        )
        val unknownWithHighWrapper = task6PlanAssessment(
            confidence = task6Confidence(performance = Confidence.HIGH),
            performance = PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
        )
        val unknownWithoutEvidence = task6PlanAssessment(
            performance = PerformanceEstimate.Unknown(
                AssessmentReason.SPEED_NOT_VERIFIED,
                evidence = emptyList(),
            ),
        )
        val unifiedSnapshot = task6Snapshot(
            hostBudget = null,
            sharedBudget = 1_000,
            topology = MemoryTopology.UNIFIED,
            backends = listOf(task6Backend(BackendKind.METAL)),
        )

        val malformedPool = policy.recommend(
            task6Assessment(plans = listOf(extraGpuPool)),
            task6Snapshot(),
            RecommendationProfile(),
        )
        val missingPool = policy.recommend(
                task6Assessment(plans = listOf(missingUnifiedPool), innerTopology = MemoryTopology.UNIFIED),
                unifiedSnapshot,
                RecommendationProfile(),
        )
        val invalidPerformance = listOf(
            policy.recommend(
                task6Assessment(plans = listOf(unknownWithHighWrapper)),
                task6Snapshot(),
                RecommendationProfile(),
            ),
            policy.recommend(
                task6Assessment(plans = listOf(unknownWithoutEvidence)),
                task6Snapshot(),
                RecommendationProfile(),
            ),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, malformedPool.category)
        assertEquals(listOf(AssessmentReason.ASSESSMENT_GRAPH_INVALID), malformedPool.reasons)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, missingPool.category)
        assertEquals(listOf(AssessmentReason.MEMORY_BOUNDS_UNKNOWN), missingPool.reasons)
        invalidPerformance.forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE), result.reasons)
        }
    }

    @Test
    fun missingSafeMemoryOrStorageUpperBoundsNeedInformation() {
        val missingMemory = policy.recommend(
            task6Assessment(plans = listOf(task6PlanAssessment(host = null))),
            task6Snapshot(),
            RecommendationProfile(),
        )
        val missingStorage = policy.recommend(
            task6Assessment(plans = listOf(task6PlanAssessment(storage = null))),
            task6Snapshot(),
            RecommendationProfile(),
        )
        val missingBudget = policy.recommend(
            task6Assessment(),
            task6Snapshot(hostBudget = null),
            RecommendationProfile(),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, missingMemory.category)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, missingStorage.category)
        assertEquals(RecommendationCategory.NEEDS_INFORMATION, missingBudget.category)
        assertTrue(missingMemory.reasons.contains(AssessmentReason.MEMORY_BOUNDS_UNKNOWN))
        assertTrue(missingStorage.reasons.contains(AssessmentReason.STORAGE_BOUNDS_UNKNOWN))
    }

    @Test
    fun fallbackAndSafetyConfidenceCapsCanOnlyWorsenTheFitCategory() {
        val fallback = task6PlanAssessment(
            plan = task6LlmPlan(compromises = listOf(RunPlanCompromise.CONTEXT_REDUCED)),
            host = task6Range(100, 100, 100),
        )
        val fallbackResult = policy.recommend(
            task6Assessment(plans = listOf(fallback)),
            task6Snapshot(),
            RecommendationProfile(),
        )
        assertEquals(RecommendationCategory.USABLE, fallbackResult.category)
        assertTrue(fallbackResult.reasons.contains(AssessmentReason.FALLBACK_PLAN_REQUIRED))

        val medium = task6PlanAssessment(
            confidence = task6Confidence(memory = Confidence.MEDIUM),
        )
        val mediumConservative = policy.recommend(
            task6Assessment(plans = listOf(medium)),
            task6Snapshot(),
            RecommendationProfile(riskTolerance = RiskTolerance.CONSERVATIVE),
        )
        assertEquals(RecommendationCategory.USABLE, mediumConservative.category)
        assertTrue(mediumConservative.reasons.contains(AssessmentReason.SAFETY_EVIDENCE_LIMITED))

        RiskTolerance.entries.forEach { risk ->
            val low = task6PlanAssessment(confidence = task6Confidence(memory = Confidence.LOW))
            val result = policy.recommend(
                task6Assessment(plans = listOf(low)),
                task6Snapshot(),
                RecommendationProfile(riskTolerance = risk),
            )
            assertEquals(RecommendationCategory.RISKY, result.category, risk.name)
        }
    }

    @Test
    fun recommendationProjectsTheBestAssessedAlternatePlanAsFallback() {
        val primaryPlan = task6LlmPlan(keyContext = 4_096)
        val alternatePlan = task6LlmPlan(keyContext = 2_048)
        val result = policy.recommend(
            task6Assessment(
                plans = listOf(
                    task6PlanAssessment(
                        plan = primaryPlan,
                        metrics = PlanUtilityMetrics(0.9, null, 0.9, 0.9, 0.9),
                    ),
                    task6PlanAssessment(
                        plan = alternatePlan,
                        metrics = PlanUtilityMetrics(0.1, null, 0.1, 0.1, 0.1),
                    ),
                ),
            ),
            task6Snapshot(),
            RecommendationProfile(),
        )

        assertEquals(primaryPlan, result.selectedPlan)
        assertEquals(alternatePlan, result.fallbackPlan)
    }

    @Test
    fun experimentalLikelyFitKeepsExplicitOrderedTightFitWarning() {
        val result = recommend(
            range = task6Range(100, 900, 951),
            risk = RiskTolerance.EXPERIMENTAL,
        )

        assertEquals(RecommendationCategory.RECOMMENDED, result.category)
        assertEquals(
            listOf(
                AssessmentReason.MEMORY_FIT_LIKELY,
                AssessmentReason.TIGHT_MEMORY_FIT,
                AssessmentReason.SPEED_NOT_VERIFIED,
            ),
            result.reasons,
        )
    }

    @Test
    fun performanceMissesDowngradeButCannotUpgradeHardResourceFailure() {
        val missedTarget = recommend(
            range = task6Range(100, 100, 100),
            performance = task6LlmPerformance(decodeLikely = 3.0, confidence = Confidence.MEDIUM),
        )
        val belowHardFloor = recommend(
            range = task6Range(100, 100, 100),
            performance = task6LlmPerformance(decodeLikely = 1.0, confidence = Confidence.MEDIUM),
        )
        val unsafeButFast = recommend(
            range = task6Range(851, 900, 950),
            performance = task6LlmPerformance(decodeLikely = 1_000.0, confidence = Confidence.HIGH),
            metrics = PlanUtilityMetrics(1.0, 1.0, 1.0, 1.0, 1.0),
        )

        assertEquals(RecommendationCategory.USABLE, missedTarget.category)
        assertTrue(missedTarget.reasons.contains(AssessmentReason.PERFORMANCE_TARGET_MISSED))
        assertEquals(RecommendationCategory.NOT_SUITABLE, belowHardFloor.category)
        assertEquals(RecommendationCategory.NOT_SUITABLE, unsafeButFast.category)
        assertTrue(unsafeButFast.reasons.contains(AssessmentReason.MEMORY_NO_FIT))
    }

    @Test
    fun lowPerformanceConfidenceIsVisibleAndCannotMakeAConfidentHardFloorClaim() {
        val result = recommend(
            range = task6Range(100, 100, 100),
            performance = task6LlmPerformance(decodeLikely = 0.1, confidence = Confidence.LOW),
        )

        assertEquals(RecommendationCategory.RISKY, result.category)
        assertTrue(result.reasons.contains(AssessmentReason.SPEED_NOT_VERIFIED))
        assertTrue(result.reasons.contains(AssessmentReason.PERFORMANCE_UNCERTAIN))
    }

    @Test
    fun performanceEstimateKindMustMatchTheExecutablePlan() {
        val diffusionEstimate = PerformanceEstimate.DiffusionImage(
            secondsPerStep = task6PerformanceRange(1.0, Confidence.HIGH),
            totalTimeSeconds = task6PerformanceRange(20.0, Confidence.HIGH),
            referenceTotalTimeSeconds = task6PerformanceRange(20.0, Confidence.HIGH),
            evidence = emptyList(),
        )
        val llmWithDiffusionEstimate = policy.recommend(
            task6Assessment(
                plans = listOf(
                    task6PlanAssessment(
                        performance = diffusionEstimate,
                        confidence = task6Confidence(performance = Confidence.HIGH),
                    ),
                ),
            ),
            task6Snapshot(),
            RecommendationProfile(),
        )

        val diffusionPlan = task6DiffusionPlan()
        val imageWithLlmEstimate = policy.recommend(
            task6Assessment(
                plans = listOf(
                    task6PlanAssessment(
                        plan = diffusionPlan,
                        host = null,
                        shared = task6Range(100, 100, 100),
                        performance = task6LlmPerformance(10.0, Confidence.HIGH),
                        confidence = task6Confidence(performance = Confidence.HIGH),
                    ),
                ),
            ),
            task6Snapshot(
                hostBudget = null,
                sharedBudget = 1_000,
                topology = MemoryTopology.UNIFIED,
                backends = listOf(task6Backend(BackendKind.METAL)),
            ),
            RecommendationProfile(),
        )

        listOf(llmWithDiffusionEstimate, imageWithLlmEstimate).forEach { result ->
            assertEquals(RecommendationCategory.NEEDS_INFORMATION, result.category)
            assertEquals(listOf(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE), result.reasons)
        }
    }

    @Test
    fun comparedRangeAndWrapperPerformanceConfidenceAreReconciledConservatively() {
        val promptHighDecodeLow = PerformanceEstimate.Llm(
            promptTokensPerSecond = task6PerformanceRange(10.0, Confidence.HIGH),
            decodeTokensPerSecond = task6PerformanceRange(0.1, Confidence.LOW),
            timeToFirstTokenSeconds = task6PerformanceRange(1.0, Confidence.HIGH),
            loadTimeSeconds = task6PerformanceRange(1.0, Confidence.HIGH),
            evidence = emptyList(),
        )
        val rangeLowWrapperHigh = recommend(
            range = task6Range(100, 100, 100),
            performance = promptHighDecodeLow,
            performanceWrapperConfidence = Confidence.HIGH,
        )
        val rangeHighWrapperLow = recommend(
            range = task6Range(100, 100, 100),
            performance = task6LlmPerformance(0.1, Confidence.HIGH),
            performanceWrapperConfidence = Confidence.LOW,
        )

        listOf(rangeLowWrapperHigh, rangeHighWrapperLow).forEach { result ->
            assertEquals(RecommendationCategory.RISKY, result.category)
            assertTrue(result.reasons.contains(AssessmentReason.INVALID_PERFORMANCE_EVIDENCE))
            assertTrue(result.reasons.contains(AssessmentReason.PERFORMANCE_UNCERTAIN))
            assertTrue(result.reasons.contains(AssessmentReason.SPEED_NOT_VERIFIED))
        }
    }

    @Test
    fun storageReserveAndNoFitAreProfileIndependent() {
        RiskTolerance.entries.forEach { risk ->
            val result = policy.recommend(
                task6Assessment(
                    plans = listOf(task6PlanAssessment(storage = task6Range(1_001, 1_001, 1_001))),
                ),
                task6Snapshot(storageBudget = 1_000),
                RecommendationProfile(riskTolerance = risk),
            )
            assertEquals(RecommendationCategory.NOT_SUITABLE, result.category, risk.name)
            assertTrue(result.reasons.contains(AssessmentReason.STORAGE_NO_FIT))
        }
    }

    @Test
    fun staleSnapshotCannotBeUsedForFinalPersonalization() {
        val stale = policy.recommend(
            task6Assessment(),
            task6Snapshot(isFresh = false),
            RecommendationProfile(riskTolerance = RiskTolerance.EXPERIMENTAL),
        )

        assertEquals(RecommendationCategory.NEEDS_INFORMATION, stale.category)
        assertEquals(listOf(AssessmentReason.RESOURCE_SNAPSHOT_STALE), stale.reasons)
    }

    @Test
    fun freshFlagCannotOverrideAnExpiredOrFutureCaptureTimestamp() {
        listOf(1L, Clock.System.now().toEpochMilliseconds() + 60_000L).forEach { capturedAt ->
            val stale = policy.recommend(
                task6Assessment(),
                task6Snapshot(isFresh = true, capturedAtEpochMs = capturedAt),
                RecommendationProfile(),
            )

            assertEquals(RecommendationCategory.NEEDS_INFORMATION, stale.category)
            assertEquals(listOf(AssessmentReason.RESOURCE_SNAPSHOT_STALE), stale.reasons)
        }
    }

    @Test
    fun sortKeyReusesOneSelectionAtTheFreshnessBoundary() {
        var clockReads = 0
        val boundaryPolicy = RecommendationPolicy(
            RunPlanOptimizer(clock = {
                clockReads += 1
                31_000L
            }),
        )

        boundaryPolicy.sortKey(
            task6Assessment(),
            task6Snapshot(capturedAtEpochMs = 1_000L),
            RecommendationProfile(),
        )

        assertEquals(1, clockReads)
    }

    @Test
    fun diffusionReferenceTargetUsesTheSameDowngradeAndHardFloorRules() {
        fun image(seconds: Double) = PerformanceEstimate.DiffusionImage(
            secondsPerStep = task6PerformanceRange(seconds / 20.0, Confidence.MEDIUM),
            totalTimeSeconds = task6PerformanceRange(seconds, Confidence.MEDIUM),
            referenceTotalTimeSeconds = task6PerformanceRange(seconds, Confidence.MEDIUM),
            evidence = emptyList(),
        )

        assertEquals(
            RecommendationCategory.USABLE,
            recommend(task6Range(100, 100, 100), performance = image(100.0)).category,
        )
        assertEquals(
            RecommendationCategory.NOT_SUITABLE,
            recommend(task6Range(100, 100, 100), performance = image(181.0)).category,
        )
    }

    @Test
    fun videoPerformanceRemainsNonBlocking() {
        val performance = PerformanceEstimate.DiffusionVideo(
            secondsPerStep = task6PerformanceRange(10.0, Confidence.HIGH),
            secondsPerFrame = task6PerformanceRange(10_000.0, Confidence.HIGH),
            totalTimeSeconds = task6PerformanceRange(80_000.0, Confidence.HIGH),
            comparableForPolicy = false,
            evidence = emptyList(),
        )
        val result = recommend(task6Range(100, 100, 100), performance = performance)

        assertEquals(RecommendationCategory.RECOMMENDED, result.category)
        assertTrue(result.reasons.contains(AssessmentReason.SPEED_NOT_VERIFIED))
    }

    private fun recommend(
        range: EstimateRange,
        risk: RiskTolerance = RiskTolerance.BALANCED,
        performance: PerformanceEstimate = PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
        metrics: PlanUtilityMetrics = PlanUtilityMetrics(),
        performanceWrapperConfidence: Confidence? = null,
    ): PersonalizedRecommendation {
        val diffusionMode = when (performance) {
            is PerformanceEstimate.DiffusionImage -> DiffusionMode.IMAGE
            is PerformanceEstimate.DiffusionVideo -> DiffusionMode.VIDEO
            is PerformanceEstimate.Llm, is PerformanceEstimate.Unknown -> null
        }
        val plan = diffusionMode?.let(::task6DiffusionPlan) ?: task6LlmPlan()
        val planAssessment = task6PlanAssessment(
            plan = plan,
            host = range.takeIf { diffusionMode == null },
            shared = range.takeIf { diffusionMode != null },
            confidence = task6Confidence(
                performance = performanceWrapperConfidence ?: when (performance) {
                    is PerformanceEstimate.Unknown -> Confidence.LOW
                    is PerformanceEstimate.Llm -> performance.decodeTokensPerSecond.confidence
                    is PerformanceEstimate.DiffusionImage -> performance.referenceTotalTimeSeconds.confidence
                    is PerformanceEstimate.DiffusionVideo -> performance.totalTimeSeconds.confidence
                },
            ),
            performance = performance,
            metrics = metrics,
        )
        val snapshot = if (diffusionMode == null) {
            task6Snapshot(hostBudget = 1_000, storageBudget = 2_000)
        } else {
            task6Snapshot(
                hostBudget = null,
                sharedBudget = 1_000,
                storageBudget = 2_000,
                topology = MemoryTopology.UNIFIED,
                backends = listOf(task6Backend(BackendKind.METAL)),
            )
        }
        return policy.recommend(
            task6Assessment(
                plans = listOf(
                    planAssessment,
                ),
            ),
            snapshot,
            RecommendationProfile(riskTolerance = risk),
        )
    }

    private fun rangeFor(band: FitBand, budget: Long): EstimateRange = when (band) {
        FitBand.COMFORTABLE -> task6Range(budget, budget, budget)
        FitBand.LIKELY -> task6Range(budget - 2, budget, budget + 1)
        FitBand.BORDERLINE -> task6Range(budget, budget + 1, budget + 2)
        FitBand.NO_FIT -> task6Range(budget + 1, budget + 2, budget + 3)
    }

    private data class Row(
        val band: FitBand,
        val risk: RiskTolerance,
        val policyBudget: Long,
        val category: RecommendationCategory,
    )
}

internal fun task6Range(low: Long, likely: Long, high: Long): EstimateRange =
    (EstimateRange.create(low, likely, high) as CheckedEstimateRange.Value).range

internal fun task6PerformanceRange(likely: Double, confidence: Confidence): PerformanceRange =
    requireNotNull(PerformanceRange.create(likely, likely, likely, confidence))

internal fun task6Confidence(
    compatibility: Confidence = Confidence.HIGH,
    memory: Confidence = Confidence.HIGH,
    storage: Confidence = Confidence.HIGH,
    performance: Confidence = Confidence.LOW,
) = AssessmentConfidence(compatibility, memory, storage, performance)

internal fun task6LlmPerformance(
    decodeLikely: Double,
    confidence: Confidence,
) = PerformanceEstimate.Llm(
    promptTokensPerSecond = task6PerformanceRange(decodeLikely, confidence),
    decodeTokensPerSecond = task6PerformanceRange(decodeLikely, confidence),
    timeToFirstTokenSeconds = task6PerformanceRange(1.0, confidence),
    loadTimeSeconds = task6PerformanceRange(1.0, confidence),
    evidence = emptyList(),
)

internal fun task6PlanAssessment(
    plan: RunPlan = task6LlmPlan(),
    host: EstimateRange? = task6Range(100, 100, 100),
    gpu: EstimateRange? = null,
    shared: EstimateRange? = null,
    storage: EstimateRange? = task6Range(100, 100, 100),
    confidence: AssessmentConfidence = task6Confidence(),
    evidence: Collection<Evidence> = emptyList(),
    performance: PerformanceEstimate = PerformanceEstimate.Unknown(AssessmentReason.SPEED_NOT_VERIFIED),
    metrics: PlanUtilityMetrics = PlanUtilityMetrics(0.5, null, 0.5, 0.5, 0.5),
) = PlanAssessment(
    plan = plan,
    hostMemoryBytes = host,
    gpuMemoryBytes = gpu,
    sharedMemoryBytes = shared,
    storageBytes = storage,
    confidence = confidence,
    evidence = evidence,
    performance = performance,
    utilityMetrics = metrics,
)

internal fun task6Assessment(
    compatibility: Compatibility = Compatibility.Compatible,
    plans: Collection<PlanAssessment> = listOf(task6PlanAssessment()),
    confidence: AssessmentConfidence = task6Confidence(),
    assessmentKey: String = TASK6_ASSESSMENT_KEY,
    innerAssessmentKey: String = assessmentKey,
    innerCompatibility: Compatibility = compatibility,
    innerTopology: MemoryTopology = (plans.firstOrNull()?.plan as? RunPlan)?.memoryTopology
        ?: MemoryTopology.UNKNOWN,
) = ModelAssessment(
    assessmentKey = assessmentKey,
    compatibility = compatibility,
    planAssessments = AssessedPlans(
        values = plans,
        assessmentKey = innerAssessmentKey,
        compatibility = innerCompatibility,
        memoryTopology = innerTopology,
    ),
    baseHostBudgetBytes = 1_000,
    baseGpuBudgetBytes = null,
    baseSharedBudgetBytes = null,
    baseStorageBudgetBytes = 2_000,
    confidence = confidence,
    evidence = emptyList(),
)

internal fun task6Snapshot(
    hostBudget: Long? = 1_000,
    gpuBudget: Long? = null,
    sharedBudget: Long? = null,
    storageBudget: Long? = 2_000,
    isFresh: Boolean = true,
    capturedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    hostConfidence: Confidence? = hostBudget?.let { Confidence.HIGH },
    gpuConfidence: Confidence? = gpuBudget?.let { Confidence.HIGH },
    sharedConfidence: Confidence? = sharedBudget?.let { Confidence.HIGH },
    storageConfidence: Confidence? = storageBudget?.let { Confidence.HIGH },
    resourceHostConfidence: Confidence? = (sharedBudget ?: hostBudget)?.let { Confidence.HIGH },
    resourceGpuConfidence: Confidence? = gpuBudget?.let { Confidence.HIGH },
    resourceStorageConfidence: Confidence? = storageBudget?.let { Confidence.HIGH },
    topology: MemoryTopology = if (gpuBudget == null) MemoryTopology.UNKNOWN else MemoryTopology.DISCRETE,
    backends: Collection<BackendCapability> = listOf(
        task6Backend(if (gpuBudget == null) BackendKind.CPU else BackendKind.CUDA),
    ),
    instructionSets: Collection<String> = emptySet(),
    hardwareEvidence: Collection<Evidence> = emptyList(),
    resourceEvidence: Collection<Evidence> = emptyList(),
    evidence: Collection<Evidence> = emptyList(),
) = DeviceSnapshot(
    hardwareProfile = task6Hardware(
        topology = topology,
        backends = backends,
        instructionSets = instructionSets,
        evidence = hardwareEvidence,
    ),
    resources = task6ResourceSnapshot(
        hostBytes = if (topology == MemoryTopology.UNIFIED) sharedBudget else hostBudget,
        gpuBytes = gpuBudget,
        storageBytes = storageBudget,
        capturedAtEpochMs = capturedAtEpochMs,
        hostConfidence = resourceHostConfidence,
        gpuConfidence = resourceGpuConfidence,
        storageConfidence = resourceStorageConfidence,
        evidence = resourceEvidence,
    ),
    baseHostBudgetBytes = hostBudget,
    baseGpuBudgetBytes = gpuBudget,
    baseSharedBudgetBytes = sharedBudget,
    baseStorageBudgetBytes = storageBudget,
    isFresh = isFresh,
    evidence = evidence,
    budgetConfidence = ResourcePoolConfidence(
        host = hostConfidence,
        gpu = gpuConfidence,
        shared = sharedConfidence,
        storage = storageConfidence,
    ),
)

internal fun task6ResourceSnapshot(
    hostBytes: Long? = 1_000,
    gpuBytes: Long? = null,
    storageBytes: Long? = 2_000,
    capturedAtEpochMs: Long = Clock.System.now().toEpochMilliseconds(),
    hostConfidence: Confidence? = hostBytes?.let { Confidence.HIGH },
    gpuConfidence: Confidence? = gpuBytes?.let { Confidence.HIGH },
    storageConfidence: Confidence? = storageBytes?.let { Confidence.HIGH },
    evidence: Collection<Evidence> = emptyList(),
) = ResourceSnapshot(
    additionalAllocatableHostBytes = hostBytes,
    additionalAllocatableGpuBytes = gpuBytes,
    currentProcessBytes = 100,
    freeStorageBytes = storageBytes,
    osPressureReserveHostBytes = 0,
    observedAppFootprintNoiseP95Bytes = 0,
    platformMinimumReserveHostBytes = 0,
    lowMemory = false,
    thermalState = ThermalState.NOMINAL,
    powerPolicyState = PowerPolicyState.NORMAL,
    capturedAtEpochMs = capturedAtEpochMs,
    evidence = evidence,
    confidence = ResourcePoolConfidence(
        host = hostConfidence,
        gpu = gpuConfidence,
        storage = storageConfidence,
    ),
)

internal fun task6Hardware(
    logicalCores: Int = 8,
    performanceCores: Int? = 4,
    backend: BackendKind = BackendKind.CPU,
    topology: MemoryTopology = MemoryTopology.UNKNOWN,
    backends: Collection<BackendCapability> = listOf(task6Backend(backend)),
    instructionSets: Collection<String> = emptySet(),
    evidence: Collection<Evidence> = emptyList(),
) = HardwareProfile(
    cpuArchitecture = "fixture",
    logicalCoreCount = logicalCores,
    performanceCoreCount = performanceCores,
    instructionSets = instructionSets,
    backends = backends,
    memoryTopology = topology,
    evidence = evidence,
)

internal fun task6Backend(
    kind: BackendKind,
    status: BackendStatus = BackendStatus.AVAILABLE,
) = BackendCapability(
    kind = kind,
    status = status,
    additionalAllocatableBytes = null,
    availabilityConfidence = Confidence.HIGH,
    headroomConfidence = null,
    evidence = listOf(
        Evidence(
            if (status == BackendStatus.AVAILABLE) {
                AssessmentReason.BACKEND_CAPABILITY_VERIFIED
            } else {
                AssessmentReason.REQUIRED_BACKEND_UNAVAILABLE
            },
            Confidence.HIGH,
            "fixture-${kind.name.lowercase()}",
        ),
    ),
)

internal fun task6LlmPlan(
    keyContext: Int = 4_096,
    backend: BackendKind = BackendKind.CPU,
    topology: MemoryTopology = MemoryTopology.UNKNOWN,
    gpuLayers: Int? = if (backend == BackendKind.CPU) 0 else 16,
    compromises: Collection<RunPlanCompromise> = emptyList(),
) = LlmRunPlan(
    contextTokens = keyContext,
    batchSize = 128,
    microBatchSize = 128,
    sequenceCount = 1,
    keyCacheType = KvCacheType.Q8_0,
    valueCacheType = KvCacheType.Q8_0,
    backend = backend,
    memoryTopology = topology,
    gpuLayerCount = gpuLayers,
    compromises = compromises,
)

private fun <T> underReportingCollection(
    value: T,
    actualCount: Int,
    onRead: () -> Unit,
): Collection<T> = object : AbstractCollection<T>() {
    override val size: Int = 0

    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private var remaining = actualCount

        override fun hasNext(): Boolean = remaining > 0

        override fun next(): T {
            if (remaining <= 0) throw NoSuchElementException()
            remaining -= 1
            onRead()
            return value
        }
    }
}

internal fun task6LlmDescriptor(
    sizeBytes: Long = 1_073_741_824L,
    parameterCount: Long? = 7_000_000_000L,
    quantization: QuantizationEvidence = QuantizationEvidence.Known("Q4_K_M"),
) = LlmModelDescriptor(
    repositoryId = "owner/model",
    revision = "0123456789abcdef0123456789abcdef01234567",
    file = ModelFileIdentity(
        repositoryId = "owner/model",
        revision = "0123456789abcdef0123456789abcdef01234567",
        path = "model-Q4_K_M.gguf",
        sizeBytes = sizeBytes,
        gitOid = null,
        lfsOid = "sha256:fixture",
        xetHash = null,
        evidence = emptyList(),
    ),
    architecture = "llama",
    quantization = quantization,
    parameterCount = parameterCount,
    contextLimit = 16_384,
    transformerShape = TransformerShape(32, 8, 32, 4_096, 128),
    ggufVersion = 3,
    requiredEngineFeatures = emptyList(),
    evidence = emptyList(),
)

internal fun task6LlmWorkload(allowFallbacks: Boolean = false) = LlmWorkloadConfig(
    userRequestedContextTokens = 4_096,
    contextTokens = 4_096,
    minimumContextTokens = 512,
    promptTokens = 512,
    generationReserveTokens = 256,
    batchSize = 128,
    microBatchSize = 128,
    sequenceCount = 1,
    kvCacheSelection = KvCacheSelection.Auto,
    allowContextFallback = allowFallbacks,
    allowBatchFallback = allowFallbacks,
    allowKvCacheFallback = allowFallbacks,
    allowedKvCacheTypes = KvCacheType.entries,
    evidence = emptyList(),
)

internal fun task6InvalidLlmWorkload() = LlmWorkloadConfig(
    userRequestedContextTokens = 0,
    contextTokens = 0,
    minimumContextTokens = 0,
    promptTokens = 0,
    generationReserveTokens = 0,
    batchSize = 0,
    microBatchSize = 0,
    sequenceCount = 0,
    kvCacheSelection = KvCacheSelection.Auto,
    allowContextFallback = true,
    allowBatchFallback = true,
    allowKvCacheFallback = true,
    allowedKvCacheTypes = KvCacheType.entries,
    evidence = emptyList(),
)

internal fun task6DiffusionDescriptor(
    mode: DiffusionMode = DiffusionMode.IMAGE,
    architecture: SdArchitecture = SdArchitecture.SDXL,
    quantizationDistribution: Set<String> = setOf("F16"),
): DiffusionModelDescriptor {
    val primary = ModelFileIdentity(
        repositoryId = "owner/diffusion",
        revision = "0123456789abcdef0123456789abcdef01234567",
        path = "model.safetensors",
        sizeBytes = 2_147_483_648L,
        gitOid = null,
        lfsOid = "sha256:diffusion",
        xetHash = null,
        evidence = emptyList(),
    )
    val vae = ModelFileIdentity(
        repositoryId = primary.repositoryId,
        revision = primary.revision,
        path = "vae.safetensors",
        sizeBytes = 268_435_456L,
        gitOid = null,
        lfsOid = "sha256:vae",
        xetHash = null,
        evidence = emptyList(),
    )
    return DiffusionModelDescriptor(
        repositoryId = primary.repositoryId,
        revision = primary.revision,
        components = listOf(
            DiffusionComponentDescriptor(primary, null, required = true, isPrimary = true),
            DiffusionComponentDescriptor(vae, ComponentRole.VAE, required = true, isPrimary = false),
        ),
        mode = mode,
        family = "SDXL",
        architecture = architecture,
        width = 1_024,
        height = 1_024,
        quantizationDistribution = quantizationDistribution,
        requiredComponentsPresent = true,
        requiredEngineFeatures = emptyList(),
        evidence = emptyList(),
    )
}

internal fun task6DiffusionWorkload() = DiffusionWorkloadConfig(
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
    offloadToCpu = false,
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

internal fun task6DiffusionPlan(
    mode: DiffusionMode = DiffusionMode.IMAGE,
    width: Int = 512,
    height: Int = 512,
    frames: Int = if (mode == DiffusionMode.IMAGE) 1 else 8,
    steps: Int = 20,
) = DiffusionRunPlan(
    mode = mode,
    width = width,
    height = height,
    frameCount = frames,
    batchSize = 1,
    steps = steps,
    vaeTiling = false,
    offloadToCpu = false,
    keepClipOnCpu = false,
    keepVaeOnCpu = false,
    maxVramBytes = null,
    layerStreaming = false,
    requiresUserAcceptance = false,
    backend = BackendKind.METAL,
    memoryTopology = MemoryTopology.UNIFIED,
    compromises = emptyList(),
)

private const val TASK6_ASSESSMENT_KEY =
    "owner/model@0123456789abcdef0123456789abcdef01234567:model-Q4_K_M.gguf"
