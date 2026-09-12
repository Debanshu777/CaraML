package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.data.inference.ModelLoadResult
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import com.debanshu777.runner.InferenceChunk
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ManageContextUseCaseTest {
    @Test
    fun returnsFailureWhenBothNativeResetAttemptsFail() = runTest {
        val repository = FakeInferenceRepository(resetResults = ArrayDeque(listOf(false, false)))
        val useCase = ManageContextUseCase(repository, ChatConfig(lastExchangeCount = 1))

        val result = useCase.resetContext(
            listOf(
                ChatMessage(role = MessageRole.User, text = "older"),
                ChatMessage(role = MessageRole.Assistant, text = "latest"),
            )
        )

        assertEquals(ContextResetResult.Failure, result)
        assertEquals(2, repository.resetCalls)
    }

    @Test
    fun cancellationDuringSummaryIsRethrownWithoutResettingContext() = runTest {
        val repository = FakeInferenceRepository(
            summary = flow { throw CancellationException("cancelled") },
        )
        val useCase = ManageContextUseCase(repository, ChatConfig(lastExchangeCount = 1))

        assertFailsWith<CancellationException> {
            useCase.resetContext(
                listOf(
                    ChatMessage(role = MessageRole.User, text = "older"),
                    ChatMessage(role = MessageRole.Assistant, text = "latest"),
                )
            )
        }
        assertEquals(0, repository.resetCalls)
    }
}

private class FakeInferenceRepository(
    private val resetResults: ArrayDeque<Boolean> = ArrayDeque(listOf(true)),
    private val summary: Flow<String> = emptyFlow(),
) : InferenceRepository {
    var resetCalls: Int = 0

    override suspend fun loadModel(model: LocalModelEntity): ModelLoadResult =
        error("Not used")

    override suspend fun unloadModel() = Unit
    override fun generateResponse(userPrompt: String): Flow<InferenceChunk> = emptyFlow()
    override fun cancelGeneration() = Unit
    override fun getContextUsed(): Int = 0
    override fun getContextLimit(): Int = 1
    override fun getStopReason(): Int = 0
    override fun isContextAboveThreshold(): Boolean = false
    override fun summarizeConversation(transcript: String): Flow<String> = summary

    override suspend fun resetContextWithSummary(summary: String, lastExchange: String): Boolean {
        resetCalls++
        return resetResults.removeFirstOrNull() ?: false
    }

    override suspend fun resetContext() = Unit
    override fun getRuntimeConfigString(): String = ""
}
