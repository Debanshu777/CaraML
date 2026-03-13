package com.debanshu777.caraml.features.chat.presentation.components.preview.providers

import androidx.compose.ui.tooling.preview.PreviewParameterProvider
import com.debanshu777.caraml.features.chat.data.LiveGenerationStats

class LiveGenerationStatsPreviewProvider : PreviewParameterProvider<LiveGenerationStats> {
    override val values: Sequence<LiveGenerationStats> = sequenceOf(
        LiveGenerationStats(
            contextUsed = 512,
            contextLimit = 4096,
            outputTokenCount = 128,
            tokensPerSecond = 42.5
        ),
        LiveGenerationStats(
            contextUsed = 0,
            contextLimit = 0,
            outputTokenCount = 0,
            tokensPerSecond = 0.0
        ),
        LiveGenerationStats(
            contextUsed = 8192,
            contextLimit = 8192,
            outputTokenCount = 2048,
            tokensPerSecond = 125.3
        ),
        LiveGenerationStats(
            contextUsed = 0,
            contextLimit = 32768,
            outputTokenCount = 1,
            tokensPerSecond = 0.5
        ),
        LiveGenerationStats(
            contextUsed = 16384,
            contextLimit = 16384,
            outputTokenCount = 10000,
            tokensPerSecond = 999.9
        ),
    )
}
