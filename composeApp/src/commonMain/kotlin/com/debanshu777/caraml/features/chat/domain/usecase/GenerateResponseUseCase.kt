package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.domain.InferenceRepository
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.TokenTimer
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

        return GenerationResult(
            metrics = timer.buildMetrics(),
            stopReason = inferenceRepository.getStopReason(),
        )
    }
}
