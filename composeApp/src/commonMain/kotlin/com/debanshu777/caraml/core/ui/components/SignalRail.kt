package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

enum class SignalTone {
    Accent,
    Positive,
    Warning,
    Error,
    Neutral,
}

@Composable
fun SignalRail(
    tone: SignalTone,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .width(3.dp)
            .fillMaxHeight()
            .background(signalColor(tone)),
    )
}

@Composable
private fun signalColor(tone: SignalTone): Color = MaterialTheme.colorScheme.run {
    when (tone) {
        SignalTone.Accent -> primary
        SignalTone.Positive -> tertiary
        SignalTone.Warning -> secondary
        SignalTone.Error -> error
        SignalTone.Neutral -> outline
    }
}
