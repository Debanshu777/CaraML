package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.plugin.llama.bridge.InferenceChunk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RoutingInferenceRepositoryTest {
    @Test fun residentReuseDoesNotCancelGenerationOrSwitchEngines() = runTest {
        val events = mutableListOf<String>()
        val router = RoutingInferenceRepository(FakeEngine("gguf", events), FakeEngine("onnx", events))
        val installedRequest = request("model.gguf")
        val artifact = ResolvedLocalArtifact(
            installedRequest.identity, RevisionIdentity.HubCommit(emptyList()), emptyList(),
            VerifiedArtifactLoadTarget.File(installedRequest.model.localPath, "model", installedRequest.model.modelId, installedRequest.model.filename),
        )
        router.loadModel(installedRequest)
        events.clear()
        assertEquals(ModelLoadResult.Success(2048), router.tryReuseInstalledModel(installedRequest.model, artifact))
        assertEquals(listOf("gguf.reuse"), events)
        val generation = launch { router.generateResponse("synthetic prompt").toList() }
        runCurrent()
        events.clear()
        assertEquals(null, router.tryReuseInstalledModel(installedRequest.model, artifact))
        assertEquals(emptyList(), events, "Reuse must not interrupt an active generation")
        router.cancelGeneration()
        generation.join()
        router.loadModel(request("model.onnx"))
        events.clear()
        assertEquals(null, router.tryReuseInstalledModel(installedRequest.model, artifact))
        assertEquals(emptyList(), events, "Reuse must not replace a different active engine")
    }

    @Test fun switchingEnginesCancelsAndReleasesThePreviousSessionBeforeLoading() = runTest {
        val events = mutableListOf<String>()
        val llama = FakeEngine("gguf", events)
        val onnx = FakeEngine("onnx", events)
        val router = RoutingInferenceRepository(llama, onnx)
        router.loadModel(request("model.gguf"))
        val generation = launch { router.generateResponse("synthetic prompt").toList() }
        runCurrent()
        router.loadModel(request("model.onnx"))
        generation.join()
        assertEquals(listOf("gguf.cancel", "gguf.load", "gguf.generate", "gguf.cancel",
            "gguf.generation-finished", "gguf.unload", "onnx.load"), events)
        assertEquals("onnx", router.getRuntimeConfigString())
        router.loadModel(request("other.gguf"))
        assertEquals(listOf("onnx.cancel", "onnx.unload", "gguf.load"), events.takeLast(3))
    }

    @Test fun onnxReuseOnlyRoutesToTheIdleActiveOnnxEngine() = runTest {
        val events = mutableListOf<String>()
        val router = RoutingInferenceRepository(FakeEngine("gguf", events), FakeEngine("onnx", events))
        val installed = request("model.onnx")
        val artifact = ResolvedLocalArtifact(installed.identity, RevisionIdentity.HubCommit(emptyList()), emptyList(),
            VerifiedArtifactLoadTarget.File(installed.model.localPath, "model", installed.model.modelId, installed.model.filename))
        assertEquals(null, router.tryReuseInstalledModel(installed.model, artifact))
        assertEquals(emptyList(), events)
        router.loadModel(installed)
        events.clear()
        assertEquals(ModelLoadResult.Success(2048), router.tryReuseInstalledModel(installed.model, artifact))
        assertEquals(listOf("onnx.reuse"), events)
        val generation = launch { router.generateResponse("synthetic prompt").toList() }
        runCurrent()
        events.clear()
        assertEquals(null, router.tryReuseInstalledModel(installed.model, artifact))
        assertEquals(emptyList(), events)
        router.cancelGeneration()
        generation.join()
        router.loadModel(request("other.gguf"))
        events.clear()
        assertEquals(null, router.tryReuseInstalledModel(installed.model, artifact))
        assertEquals(emptyList(), events)
    }

    private class FakeEngine(private val name: String, private val events: MutableList<String>) : InferenceRepository {
        private var stopped = CompletableDeferred<Unit>()
        override suspend fun loadModel(request: LoadRequest): ModelLoadResult {
            events += "$name.load"
            stopped = CompletableDeferred()
            return ModelLoadResult.Success(2048)
        }
        override suspend fun tryReuseInstalledModel(model: LocalModelEntity, artifact: ResolvedLocalArtifact): ModelLoadResult.Success {
            events += "$name.reuse"
            return ModelLoadResult.Success(2048)
        }
        override fun generateResponse(userPrompt: String) = flow<InferenceChunk> {
            events += "$name.generate"
            stopped.await()
            events += "$name.generation-finished"
        }
        override fun cancelGeneration() { events += "$name.cancel"; stopped.complete(Unit) }
        override suspend fun unloadModel() { events += "$name.unload" }
        override fun getContextUsed() = 0
        override fun getContextLimit() = 2048
        override fun getStopReason() = 0
        override fun isContextAboveThreshold() = false
        override fun summarizeConversation(transcript: String) = flowOf("")
        override suspend fun resetContextWithSummary(summary: String, lastExchange: String) = true
        override suspend fun resetContext() = Unit
        override fun getRuntimeConfigString() = name
    }

    private fun request(filename: String): LoadRequest {
        val descriptor = task6LlmDescriptor()
        return LoadRequest(
            model = LocalModelEntity(modelId = "test/model", filename = filename, localPath = "/test/$filename",
                sizeBytes = 4, downloadedAt = 1, author = null, libraryName = null, pipelineTag = "text-generation"),
            identity = ModelFileIdentity("test/model", "a".repeat(40), filename, 4,
                "b".repeat(40), null, null, emptyList()),
            observationIdentity = requireNotNull(ObservationModelIdentity.fromDescriptor(descriptor)),
            plan = task6LlmPlan(),
            assessmentKey = "routing-test",
        )
    }
}
