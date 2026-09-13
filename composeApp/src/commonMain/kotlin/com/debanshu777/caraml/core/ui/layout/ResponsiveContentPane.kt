package com.debanshu777.caraml.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun ResponsiveContentPane(
    kind: AppContentKind,
    modifier: Modifier = Modifier,
    fillMaxHeight: Boolean = true,
    content: @Composable BoxScope.() -> Unit,
) {
    val outerModifier = if (fillMaxHeight) {
        modifier.fillMaxSize()
    } else {
        modifier.fillMaxWidth()
    }
    BoxWithConstraints(
        modifier = outerModifier,
        contentAlignment = Alignment.TopCenter,
    ) {
        val policy = adaptiveLayoutPolicy(maxWidth, kind)
        val innerModifier = Modifier
            .widthIn(max = policy.maxContentWidth)
            .then(if (fillMaxHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(horizontal = policy.horizontalMargin)
        Box(
            modifier = innerModifier,
            content = content,
        )
    }
}
