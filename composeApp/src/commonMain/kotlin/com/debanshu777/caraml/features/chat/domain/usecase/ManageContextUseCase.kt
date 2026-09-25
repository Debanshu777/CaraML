package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.data.inference.InferenceRepository
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.domain.ChatConfig
import kotlinx.coroutines.CancellationException

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
                return if (inferenceRepository.resetContextWithSummary("", "")) {
                    ContextResetResult.Success
                } else {
                    ContextResetResult.Failure
                }
            }

            val lastExchange = nonSystemMessages
                .takeLast(config.lastExchangeCount)
                .joinToString("\n") { "${it.role}: ${it.text}" }

            val olderMessages = nonSystemMessages.dropLast(config.lastExchangeCount)
            val summary = buildSummary(olderMessages)

            val resetOk = inferenceRepository.resetContextWithSummary(summary, lastExchange)
            when {
                resetOk -> ContextResetResult.Success
                inferenceRepository.resetContextWithSummary("", lastExchange) -> ContextResetResult.Success
                else -> ContextResetResult.Failure
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val cleared = try {
                inferenceRepository.resetContextWithSummary("", "")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                false
            }
            if (cleared) ContextResetResult.Success else ContextResetResult.Failure
        }
    }

    private suspend fun buildSummary(messages: List<ChatMessage>): String {
        if (messages.isEmpty()) return ""
        val transcript = messages.joinToString("\n") { "${it.role}: ${it.text}" }
        val sb = StringBuilder()
        try {
            inferenceRepository.summarizeConversation(transcript).collect { sb.append(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            val fallback = messages
                .takeLast(config.fallbackSummaryMessageCount)
                .joinToString("\n") { "${it.role}: ${it.text.take(config.fallbackSummaryCharLimit)}" }
            return "Previous messages:\n$fallback"
        }
        return sb.toString().trim()
    }
}
