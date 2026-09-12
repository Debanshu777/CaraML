package com.debanshu777.caraml.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun ResponsiveContentPane(
    kind: AppContentKind,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        val policy = adaptiveLayoutPolicy(maxWidth, kind)
        Box(
            modifier = Modifier
                .widthIn(max = policy.maxContentWidth)
                .fillMaxSize()
                .padding(horizontal = policy.horizontalMargin),
            content = content,
        )
    }
}
