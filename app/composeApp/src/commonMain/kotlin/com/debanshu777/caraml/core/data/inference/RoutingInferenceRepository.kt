package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.recommendation.LoadRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps one text engine resident and routes on the installed primary artifact's format. */
class RoutingInferenceRepository(
    private val llama: InferenceRepository,
    private val onnx: InferenceRepository,
) : InferenceRepository {
    private val engineState = MutableStateFlow(llama)
    private var active: InferenceRepository
        get() = engineState.value
        set(value) { engineState.value = value }
    private val session = Mutex()
    private fun engine(request: LoadRequest): InferenceRepository =
        if (request.model.filename.endsWith(".onnx", true)) onnx else llama
    override suspend fun loadModel(request: LoadRequest): ModelLoadResult {
        active.cancelGeneration()
        return session.withLock {
            val next = engine(request)
            if (active !== next) { active.unloadModel(); active = next }
            active.loadModel(request)
        }
    }
    override suspend fun allowExplicitRetry(request: LoadRequest) = engine(request).allowExplicitRetry(request)
    override fun currentGenerationObservation() = active.currentGenerationObservation()
    override suspend fun unloadModel() {
        active.cancelGeneration()
        session.withLock { active.unloadModel() }
    }
    override fun generateResponse(userPrompt: String) = flow { session.withLock { emitAll(active.generateResponse(userPrompt)) } }
    override fun cancelGeneration() = active.cancelGeneration()
    override fun getContextUsed() = active.getContextUsed()
    override fun getContextLimit() = active.getContextLimit()
    override fun getStopReason() = active.getStopReason()
    override fun isContextAboveThreshold() = active.isContextAboveThreshold()
    override fun summarizeConversation(transcript: String) = flow { session.withLock { emitAll(active.summarizeConversation(transcript)) } }
    override suspend fun resetContextWithSummary(summary: String, lastExchange: String) = session.withLock { active.resetContextWithSummary(summary, lastExchange) }
    override suspend fun resetContext() = session.withLock { active.resetContext() }
    override fun getRuntimeConfigString() = active.getRuntimeConfigString()
}
