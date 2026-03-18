package com.debanshu777.caraml.core.data.Inference

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import kotlinx.coroutines.flow.Flow

sealed interface ModelLoadResult {
    data class Success(val contextSize: Int) : ModelLoadResult
    data class Error(val message: String) : ModelLoadResult
}

interface InferenceRepository {
    suspend fun loadModel(model: LocalModelEntity): ModelLoadResult
    fun unloadModel()
    fun generateResponse(userPrompt: String): Flow<String>
    fun cancelGeneration()
    fun getContextUsed(): Int
    fun getContextLimit(): Int
    fun getStopReason(): Int
    fun isContextAboveThreshold(): Boolean
    fun summarizeConversation(transcript: String): Flow<String>
    suspend fun resetContextWithSummary(summary: String, lastExchange: String = ""): Boolean
}
