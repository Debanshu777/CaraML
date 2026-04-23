package com.debanshu777.caraml.features.chat.domain.usecase

import com.debanshu777.caraml.core.data.Inference.InferenceRepository
import com.debanshu777.caraml.core.platform.AppLogger
import com.debanshu777.caraml.features.chat.data.InferenceMetrics
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats
import com.debanshu777.caraml.features.chat.data.TokenTimer
import com.debanshu777.runner.StopReason
import com.debanshu777.runner.StructuredOutputParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onEach

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
        onToken: (thinking: String, output: String, liveStats: LiveGenerationStats) -> Unit,
    ): GenerationResult {
        val contextLimit = inferenceRepository.getContextLimit()
        val timer = TokenTimer()
        val parser = StructuredOutputParser()

        fun emitSnapshot(snapshot: StructuredOutputParser.Snapshot) {
            val (tokenCount, tokensPerSecond) = timer.buildLiveMetrics()
            onToken(
                snapshot.thinking,
                snapshot.output,
                LiveGenerationStats(
                    contextUsed = inferenceRepository.getContextUsed(),
                    contextLimit = contextLimit,
                    outputTokenCount = tokenCount,
                    tokensPerSecond = tokensPerSecond,
                ),
            )
        }

        try {
            inferenceRepository.generateResponse(userPrompt)
                .onEach { timer.onToken() }
                .flowOn(Dispatchers.IO)
                .collect { token ->
                    val snapshot = parser.accept(token)
                    emitSnapshot(snapshot)
                }
        } finally {
            // Flush any buffered tail (e.g. mid-tag fragment) and emit a final
            // snapshot so the UI sees the closed-out state even on cancellation.
            emitSnapshot(parser.finish())
        }

        if (parser.isInFallback) {
            AppLogger.w(TAG, "structured output: fell back to raw stream")
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
