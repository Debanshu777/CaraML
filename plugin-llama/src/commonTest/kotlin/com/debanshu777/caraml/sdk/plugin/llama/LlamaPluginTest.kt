@file:OptIn(com.debanshu777.caraml.sdk.api.ExperimentalCaraMLApi::class)

package com.debanshu777.caraml.sdk.plugin.llama

import com.debanshu777.caraml.sdk.plugin.llama.bridge.InferenceChunk
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaFitReport
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaMemoryPoolKind
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaPreflightMemoryPool
import com.debanshu777.caraml.sdk.plugin.llama.bridge.LlamaPreflightResult
import com.debanshu777.caraml.sdk.plugin.llama.bridge.NativeRunnerConfig
import com.debanshu777.caraml.sdk.plugin.llama.bridge.StopReason
import com.debanshu777.caraml.sdk.core.InferenceClient
import com.debanshu777.caraml.sdk.core.PlanAssessment
import com.debanshu777.caraml.sdk.api.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

@OptIn(ExperimentalCaraMLApi::class)
class LlamaPluginTest {
    @Test
    fun pluginIsLazyAndRunsTypedTurnsThroughCore() = runTest {
        val bridge = FakeBridge()
        val plugin = LlamaPlugin("", bridge)
        assertEquals(0, bridge.initializeCalls)

        val artifacts = FakeArtifacts()
        val client = InferenceClient.Builder().addEngine(plugin).artifactAccess(artifacts)
            .resourceSource(DeviceResourceSource { ResourceSnapshot(availableMemoryBytes = 10_000, capturedAtEpochMillis = 1) })
            .build()
        val first = request(artifacts.ref, "first prompt")
        val assessed = assertIs<PlanAssessment.Ready>(client.assess(first)).plan
        assertEquals(1, bridge.preflightCalls)
        assertEquals(1024L, assessed.summary.resources.residentBytes)

        val session = client.load(assessed)
        val events = session.generate(first).toList()
        assertEquals("reason", (events[0] as GenerationEvent.TextDelta).text)
        assertEquals(TextKind.REASONING, (events[0] as GenerationEvent.TextDelta).kind)
        assertEquals("answer", (events[1] as GenerationEvent.TextDelta).text)
        assertEquals(GenerationEvent.TextResync("reclassified", TextKind.REASONING), events[2])
        assertIs<GenerationEvent.Completed>(events.last())

        val second = first.nextTurn(OperationId("turn-2"), "second prompt")
        assertIs<GenerationEvent.Completed>(session.generate(second).toList().last())
        session.close()
        client.close()
        assertEquals(1, bridge.loadCalls)
        assertEquals(1, bridge.unloadCalls)
        assertEquals(listOf("first prompt", "second prompt"), bridge.prompts)
    }

    @Test
    fun secondPluginCannotProbeWhileLlamaOwnsTheProcessRuntime() = runTest {
        val artifacts = FakeArtifacts()
        val firstBridge = FakeBridge()
        val secondBridge = FakeBridge()
        val firstClient = client(LlamaPlugin("", firstBridge), artifacts)
        val secondClient = client(LlamaPlugin("", secondBridge), artifacts)
        val request = request(artifacts.ref, "hello")
        val session = firstClient.load(assertIs<PlanAssessment.Ready>(firstClient.assess(request)).plan)

        assertFailsWith<InferenceException.Busy> { secondClient.assess(request.copy(operationId = OperationId("other"))) }
        assertEquals(0, secondBridge.preflightCalls)

        session.close()
        firstClient.close()
        secondClient.close()
    }

    @Test
    fun cancellationIsOperationScopedAndFinalizesBeforeUnload() = runTest {
        val artifacts = FakeArtifacts()
        val bridge = FakeBridge().apply { generationGate = CompletableDeferred() }
        val client = client(LlamaPlugin("", bridge), artifacts)
        val request = request(artifacts.ref, "wait")
        val session = client.load(assertIs<PlanAssessment.Ready>(client.assess(request)).plan)
        val collecting = launch { session.generate(request).toList() }
        bridge.generationStarted.await()
        val cancelling = async { session.cancel(OperationId("wrong-operation")) }
        cancelling.await()
        assertEquals(0, bridge.cancelCalls)
        session.cancel(request.operationId)
        bridge.generationGate?.complete(Unit)
        collecting.join()
        assertEquals(1, bridge.cancelCalls)
        session.close()
        client.close()
        assertEquals(1, bridge.finalizeCalls)
        assertEquals(1, bridge.unloadCalls)
    }

    @Test
    fun failedPartialLoadExposesOwnerUntilNativeTeardownSucceeds() = runTest {
        val bridge = FakeBridge()
        val plugin = LlamaPlugin("", bridge)
        val artifacts = FakeArtifacts()
        val lease = artifacts.acquire(artifacts.ref)!!
        val plan = assertIs<PreflightResult.Ready>(plugin.preflight(request(artifacts.ref, "hello"), lease)).plan
        bridge.systemPromptResult = -1
        bridge.failUnload = true
        val failure = assertFailsWith<UnreleasedEngineLoad> { plugin.load(plan) }
        try {
            assertIs<InferenceException.Unsupported>(failure.cause)
            assertEquals(null, com.debanshu777.caraml.sdk.runtime.NativeRuntimeGate.tryAcquire())
        } finally {
            bridge.failUnload = false
            failure.owner.close()
            lease.close()
        }
        val released = kotlin.test.assertNotNull(com.debanshu777.caraml.sdk.runtime.NativeRuntimeGate.tryAcquire())
        released.close()
    }

    private fun client(plugin: LlamaPlugin, artifacts: FakeArtifacts) = InferenceClient.Builder()
        .addEngine(plugin)
        .artifactAccess(artifacts)
        .resourceSource(DeviceResourceSource { ResourceSnapshot(availableMemoryBytes = 10_000, capturedAtEpochMillis = 1) })
        .build()

    private fun request(artifact: ArtifactRef, prompt: String) = LlamaRequest(
        OperationId("turn-1"),
        ModelDescriptor(ModelId("gguf-model"), "Model", format = "gguf"),
        artifact,
        prompt,
    )
}

private class FakeBridge : LlamaBridge {
    var systemPromptResult = 0
    var failUnload = false
    var initializeCalls = 0
    var preflightCalls = 0
    var loadCalls = 0
    var unloadCalls = 0
    var cancelCalls = 0
    var finalizeCalls = 0
    var generationGate: CompletableDeferred<Unit>? = null
    val generationStarted = CompletableDeferred<Unit>()
    val prompts = mutableListOf<String>()

    override fun initialize(nativeLibraryDirectory: String) { initializeCalls++ }
    override fun engineVersion() = "test-native"
    override fun preflight(path: String, config: NativeRunnerConfig): LlamaPreflightResult {
        preflightCalls++
        return LlamaPreflightResult.Fit(
            LlamaFitReport(
                fittedContextTokens = 2048,
                fittedGpuLayers = 0,
                memoryPools = listOf(
                    LlamaPreflightMemoryPool(LlamaMemoryPoolKind.HOST, 0, 512, 256, 256, 4096, 8192),
                ),
            ),
        )
    }
    override fun loadModel(path: String, config: NativeRunnerConfig): Boolean { loadCalls++; return true }
    override fun processSystemPrompt(prompt: String) = systemPromptResult
    override fun processUserPrompt(prompt: String, maxTokens: Int): Int { prompts += prompt; return 0 }
    override fun generateChunks(): Flow<InferenceChunk> = flow {
        emit(InferenceChunk("reason", ""))
        emit(InferenceChunk("", "answer"))
        emit(InferenceChunk("reclassified", "", reasoningResync = true, isTokenEvent = false))
        generationStarted.complete(Unit)
        generationGate?.await()
    }
    override fun stopReason() = StopReason.EOG
    override fun cancelGenerate() { cancelCalls++ }
    override fun finalizeGeneration() { finalizeCalls++ }
    override fun unloadModel() { unloadCalls++; check(!failUnload) { "Native unload failed" } }
}

private class FakeArtifacts : ArtifactAccess {
    val ref = ArtifactRef(ArtifactId("local-gguf"))
    override suspend fun acquire(ref: ArtifactRef): ArtifactLease? = if (ref.id == this.ref.id) object : ArtifactLease {
        override val artifact = ref
        override val location = ArtifactLocation.LocalFile("/models/model.gguf")
        override suspend fun close() = Unit
    } else null
}
