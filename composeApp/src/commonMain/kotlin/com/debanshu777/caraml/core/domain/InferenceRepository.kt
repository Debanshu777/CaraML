package com.debanshu777.caraml.core.domain

import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import kotlinx.coroutines.flow.Flow

sealed interface ModelLoadResult {
    data object Success : ModelLoadResult
    data class Error(val message: String) : ModelLoadResult
}

interface InferenceRepository {
    suspend fun loadModel(model: LocalModelEntity): ModelLoadResult
    suspend fun unloadCurrentModel()
    fun generateResponse(userPrompt: String, predictLength: Int = 1024): Flow<String>
    fun cancelGeneration()
    fun shutdown()
}
