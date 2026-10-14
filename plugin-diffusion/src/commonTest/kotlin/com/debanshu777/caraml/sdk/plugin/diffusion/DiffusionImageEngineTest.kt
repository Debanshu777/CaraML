@file:OptIn(com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi::class)

package com.debanshu777.caraml.sdk.plugin.diffusion

import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionArchitecture
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionBackendDeviceType
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionBackendKind
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionComponentRole
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionFitReport
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionMemoryConfidence
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionModelConfig
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionParameterPlacement
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionPreflightBackend
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionPreflightComponent
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionPreflightResult
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionQuantization
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.DiffusionRuntimePlacement
import com.debanshu777.caraml.sdk.plugin.diffusion.bridge.ImageGenParams
import com.debanshu777.caraml.sdk.api.ArtifactId
import com.debanshu777.caraml.sdk.api.ArtifactLease
import com.debanshu777.caraml.sdk.api.ArtifactLocation
import com.debanshu777.caraml.sdk.api.ArtifactRef
import com.debanshu777.caraml.sdk.api.CompletionReason
import com.debanshu777.caraml.sdk.api.GeneratedMediaMetadata
import com.debanshu777.caraml.sdk.api.GeneratedMediaResult
import com.debanshu777.caraml.sdk.api.GeneratedOutputSink
import com.debanshu777.caraml.sdk.api.GenerationEvent
import com.debanshu777.caraml.sdk.api.InferenceException
import com.debanshu777.caraml.sdk.api.ModelDescriptor
import com.debanshu777.caraml.sdk.api.ModelId
import com.debanshu777.caraml.sdk.api.OperationId
import com.debanshu777.caraml.sdk.api.ProviderId
import com.debanshu777.caraml.sdk.api.StagedOutput
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiffusionImageEngineTest {
    @Test
    fun imageGenerationPublishesBoundedPngAndCompletes() = runTest {
        val fixture = Fixture()
        val provider = fixture.provider()
        val request = fixture.request()
        val plan = assertIs<com.debanshu777.caraml.sdk.api.PreflightResult.Ready>(
            provider.preflight(request, fixture.lease()),
        ).plan
        val session = provider.load(plan)

        val events = session.generate(request.operationId, request).toList()

        assertEquals(1, fixture.sink.commits)
        assertEquals(4, fixture.sink.byteCount)
        assertTrue(events.any { it is GenerationEvent.MediaPublished })
        assertEquals(CompletionReason.END_OF_TURN, assertIs<GenerationEvent.Completed>(events.last()).reason)
        assertEquals(1, events.count { it is GenerationEvent.Completed || it is GenerationEvent.Failed })
        session.close()
        assertEquals(2, fixture.runtime.releaseCalls)
    }

    @Test
    fun preflightRequiresMatchingLeasedArtifactAndRejectsOversizedRequest() = runTest {
        val fixture = Fixture()
        val provider = fixture.provider()
        assertIs<com.debanshu777.caraml.sdk.api.PreflightResult.Unsupported>(
            provider.preflight(fixture.request(), fixture.lease(artifactId = "other-artifact")),
        )

        val tooLarge = fixture.request(width = 2048, height = 1024)
        assertIs<com.debanshu777.caraml.sdk.api.PreflightResult.Unsupported>(
            provider.preflight(tooLarge, fixture.lease()),
        )
        assertEquals(0, fixture.runtime.preflightCalls)
    }

    @Test
    fun admittedPlanCannotBeUsedWithDifferentShapeOrCollectedTwice() = runTest {
        val fixture = Fixture()
        val provider = fixture.provider()
        val request = fixture.request()
        val plan = assertIs<com.debanshu777.caraml.sdk.api.PreflightResult.Ready>(
            provider.preflight(request, fixture.lease()),
        ).plan
        val session = provider.load(plan)
        val changed = fixture.request(width = 512, height = 256)
        assertFailsWith<InferenceException.InvalidPlan> {
            session.generate(changed.operationId, changed)
        }

        val flow = session.generate(request.operationId, request)
        flow.toList()
        assertFailsWith<InferenceException.InvalidPlan> { flow.toList() }
        session.close()
    }

    @Test
    fun outputCommitFailureIsReportedAsPublicationFailureAndWrongCancelIsIgnored() = runTest {
        val fixture = Fixture()
        fixture.sink.failCommit = true
        val provider = fixture.provider()
        val request = fixture.request()
        val plan = assertIs<com.debanshu777.caraml.sdk.api.PreflightResult.Ready>(
            provider.preflight(request, fixture.lease()),
        ).plan
        val session = provider.load(plan)

        session.cancel(OperationId("another-operation"))
        assertEquals(0, fixture.runtime.cancelCalls)
        val events = session.generate(request.operationId, request).toList()
        val failure = assertIs<GenerationEvent.Failed>(events.last())
        assertEquals("diffusion_output_publication_failed", failure.error.code)
        assertEquals(1, fixture.sink.aborts)
        session.close()
    }

    @Test
    fun failedPartialLoadExposesOwnerUntilNativeTeardownSucceeds() = runTest {
        val fixture = Fixture()
        val provider = fixture.provider()
        val plan = assertIs<com.debanshu777.caraml.sdk.api.PreflightResult.Ready>(
            provider.preflight(fixture.request(), fixture.lease()),
        ).plan
        fixture.runtime.failLoad = true
        fixture.runtime.failRelease = true
        val failure = assertFailsWith<com.debanshu777.caraml.sdk.api.UnreleasedEngineLoad> { provider.load(plan) }
        try {
            assertEquals(null, com.debanshu777.caraml.sdk.runtime.NativeRuntimeGate.tryAcquire())
        } finally {
            fixture.runtime.failRelease = false
            failure.owner.close()
        }
        val released = kotlin.test.assertNotNull(com.debanshu777.caraml.sdk.runtime.NativeRuntimeGate.tryAcquire())
        released.close()
    }

    private class Fixture {
        val runtime = FakeRuntime()
        val sink = FakeSink()

        fun provider() = DiffusionEngineProvider(
            outputSink = sink,
            nativeLibDir = "",
            dispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
            runtimeFactory = DiffusionRuntimeFactory { runtime },
        )

        fun request(width: Int = 512, height: Int = 512) = DiffusionImageRequest(
            operationId = OperationId("image-1"),
            model = ModelDescriptor(ModelId("model-1"), "Test model"),
            artifact = ArtifactRef(ArtifactId("artifact-1"), ProviderId("local")),
            image = DiffusionImageParameters(prompt = "a small test image", width = width, height = height),
        )

        fun lease(path: String = "/models/test.gguf", artifactId: String = "artifact-1") = object : ArtifactLease {
            override val artifact = ArtifactRef(ArtifactId(artifactId), ProviderId("local"))
            override val location = ArtifactLocation.LocalFile(path)
            override suspend fun close() = Unit
        }
    }

    private class FakeRuntime : DiffusionRuntime {
        var failLoad = false
        var failRelease = false
        var preflightCalls = 0
        var releaseCalls = 0
        var cancelCalls = 0
        override fun initialize(nativeLibDir: String) = Unit
        override fun preflight(config: DiffusionModelConfig): DiffusionPreflightResult {
            preflightCalls++
            return DiffusionPreflightResult.Fit(
                DiffusionFitReport(
                    architecture = DiffusionArchitecture.SD1,
                    quantization = DiffusionQuantization.F16,
                    memoryConfidence = DiffusionMemoryConfidence.HIGH,
                    segmentedCompute = false,
                    prefetch = false,
                    autoFit = false,
                    components = listOf(
                        DiffusionPreflightComponent(
                            sourceRole = DiffusionComponentRole.DIFFUSION_MODEL,
                            sourceOrdinal = 0,
                            subdivisionRole = DiffusionComponentRole.DIFFUSION_MODEL,
                            ordinal = 0,
                            parameterBytes = 1_024,
                            runtimePlacement = DiffusionRuntimePlacement.CPU,
                            runtimeBackendMask = 0,
                            parameterPlacement = DiffusionParameterPlacement.CPU,
                        ),
                    ),
                    backends = listOf(
                        DiffusionPreflightBackend(
                            kind = DiffusionBackendKind.CPU,
                            deviceType = DiffusionBackendDeviceType.CPU,
                            ordinal = 0,
                            budgetBytes = 0,
                            freeBytes = 0,
                            totalBytes = 0,
                        ),
                    ),
                ),
            )
        }
        override fun load(config: DiffusionModelConfig): Boolean { check(!failLoad); return true }
        override fun generate(params: ImageGenParams) = byteArrayOf(1, 2, 3, 4)
        override fun progress() = intArrayOf(1, 1)
        override fun cancel(): Boolean { cancelCalls++; return true }
        override fun release() { releaseCalls++; check(!failRelease) }
    }

    private class FakeSink : GeneratedOutputSink {
        var commits = 0
        var aborts = 0
        var byteCount = 0
        var failCommit = false
        override suspend fun begin(operationId: OperationId, expectedBytes: Long?): StagedOutput = object : StagedOutput {
            override suspend fun write(bytes: ByteArray) { byteCount += bytes.size }
            override suspend fun commit(metadata: GeneratedMediaMetadata): GeneratedMediaResult {
                if (failCommit) error("sink commit failed")
                commits++
                return GeneratedMediaResult(
                    id = "result-1",
                    metadata = metadata,
                    sizeBytes = byteCount.toLong(),
                    location = ArtifactLocation.LocalFile("/outputs/image.png"),
                )
            }
            override suspend fun abort() { aborts++ }
            override suspend fun close() = Unit
        }
    }
}
