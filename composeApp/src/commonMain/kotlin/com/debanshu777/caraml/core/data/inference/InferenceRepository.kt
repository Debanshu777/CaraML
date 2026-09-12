package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.recommendation.LoadAdmission
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.InferenceObservationPlan
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.runner.InferenceChunk
import kotlinx.coroutines.flow.Flow

sealed interface ModelLoadResult {
    data class Success(val contextSize: Int) : ModelLoadResult
    data class AdmissionRequired(val admission: LoadAdmission) : ModelLoadResult
    data class Error(val message: String) : ModelLoadResult
}

interface InferenceRepository {
    suspend fun loadModel(model: LocalModelEntity): ModelLoadResult
    suspend fun loadModel(request: LoadRequest): ModelLoadResult =
        ModelLoadResult.Error("The selected model configuration could not be verified.")
    suspend fun allowExplicitRetry(request: LoadRequest) = Unit
    fun currentGenerationObservation(): InferenceObservationPlan? = null
    suspend fun unloadModel()
    fun generateResponse(userPrompt: String): Flow<InferenceChunk>
    fun cancelGeneration()
    fun getContextUsed(): Int
    fun getContextLimit(): Int
    fun getStopReason(): Int
    fun isContextAboveThreshold(): Boolean
    fun summarizeConversation(transcript: String): Flow<String>
    suspend fun resetContextWithSummary(summary: String, lastExchange: String = ""): Boolean
    suspend fun resetContext()
    fun getRuntimeConfigString(): String
}
