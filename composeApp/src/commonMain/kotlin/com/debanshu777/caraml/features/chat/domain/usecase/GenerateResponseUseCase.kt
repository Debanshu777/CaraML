package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.data.Inference.InferenceRepository
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.TokenTimer
import com.debanshu777.runner.StopReason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.runningFold

data class GenerationResult(
    val metrics: InferenceMetrics?,
    val stopReason: Int,
)

class GenerateResponseUseCase(
    private val inferenceRepository: InferenceRepository,
) {
    companion object {
        private const val TAG = "Inference"
    }

    private fun stopReasonLabel(code: Int): String = when (code) {
        StopReason.EOG -> "EOG"
        StopReason.MAX_TOKENS -> "MAX_TOKENS"
        StopReason.CONTEXT_FULL -> "CONTEXT_FULL"
        StopReason.CANCELLED -> "CANCELLED"
        StopReason.ERROR -> "ERROR"
        else -> "NONE"
    }
    suspend operator fun invoke(
        userPrompt: String,
        onToken: (accumulatedText: String, liveStats: LiveGenerationStats) -> Unit,
    ): GenerationResult {
        val contextLimit = inferenceRepository.getContextLimit()
        val timer = TokenTimer()

        inferenceRepository.generateResponse(userPrompt)
            .onEach { timer.onToken() }
            .runningFold(StringBuilder()) { acc, token ->
                acc.append(token)
                acc
            }
            .flowOn(Dispatchers.IO)
            .collect { accumulated ->
                val (tokenCount, tokensPerSecond) = timer.buildLiveMetrics()
                onToken(
                    accumulated.toString(),
                    LiveGenerationStats(
                        contextUsed = inferenceRepository.getContextUsed(),
                        contextLimit = contextLimit,
                        outputTokenCount = tokenCount,
                        tokensPerSecond = tokensPerSecond,
                    )
                )
            }

        val metrics = timer.buildMetrics()
        val stopReason = inferenceRepository.getStopReason()
        val tps = metrics?.tokensPerSecond ?: 0.0
        val tpsRounded = kotlin.math.round(tps * 10.0) / 10.0
        AppLogger.i(TAG) {
            "complete: tokens=${metrics?.tokenCount ?: 0}, " +
            "tps=$tpsRounded, stop=${stopReasonLabel(stopReason)}"
        }

        return GenerationResult(
            metrics = metrics,
            stopReason = stopReason,
        )
    }
}
