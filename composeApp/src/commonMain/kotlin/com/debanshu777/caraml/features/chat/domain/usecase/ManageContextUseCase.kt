package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.data.Inference.InferenceRepository
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.ChatConfig

sealed interface ContextResetResult {
    data object Success : ContextResetResult
    data object Failure : ContextResetResult
}

class ManageContextUseCase(
    private val inferenceRepository: InferenceRepository,
    private val config: ChatConfig,
) {
    companion object {
        private const val TAG = "Inference"
    }

    fun needsReset(): Boolean = inferenceRepository.isContextAboveThreshold()

    suspend fun resetContext(messages: List<ChatMessage>): ContextResetResult {
        AppLogger.i(TAG) {
            "contextReset: used=${inferenceRepository.getContextUsed()}/${inferenceRepository.getContextLimit()}"
        }
        return try {
            val nonSystemMessages = messages.filter { it.role != MessageRole.System }
            if (nonSystemMessages.isEmpty()) {
                inferenceRepository.resetContextWithSummary("", "")
                return ContextResetResult.Success
            }

            val lastExchange = nonSystemMessages
                .takeLast(config.lastExchangeCount)
                .joinToString("\n") { "${it.role}: ${it.text}" }

            val olderMessages = nonSystemMessages.dropLast(config.lastExchangeCount)
            val summary = buildSummary(olderMessages)

            val resetOk = inferenceRepository.resetContextWithSummary(summary, lastExchange)
            if (!resetOk) {
                inferenceRepository.resetContextWithSummary("", lastExchange)
            }
            ContextResetResult.Success
        } catch (e: Exception) {
            try {
                inferenceRepository.resetContextWithSummary("", "")
            } catch (_: Exception) {
                // Give up silently
            }
            ContextResetResult.Success
        }
    }

    private suspend fun buildSummary(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        val transcript = messages.joinToString("\n") { "${it.role}: ${it.text}" }
        val sb = StringBuilder()
        try {
            inferenceRepository.summarizeConversation(transcript).collect { sb.append(it) }
        } catch (e: Exception) {
            val fallback = messages
                .takeLast(config.fallbackSummaryMessageCount)
                .joinToString("\n") { "${it.role}: ${it.text.take(config.fallbackSummaryCharLimit)}" }
            return "Previous messages:\n$fallback"
        }
        return sb.toString().trim()
    }
}
