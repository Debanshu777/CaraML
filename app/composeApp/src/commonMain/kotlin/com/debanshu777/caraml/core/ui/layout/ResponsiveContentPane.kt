package com.debanshu777.caraml.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.debanshu777.caraml.core.drawer.LocalAppWindowWidth

val LocalContentEndInset = staticCompositionLocalOf { 0.dp }
val LocalContentStartInset = staticCompositionLocalOf { 0.dp }

@Composable
fun ResponsiveContentPane(
    kind: AppContentKind,
    modifier: Modifier = Modifier,
    fillMaxHeight: Boolean = true,
    edgeToEdgeEnd: Boolean = false,
    edgeToEdgeStart: Boolean = false,
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
        val policy = adaptiveLayoutPolicy(LocalAppWindowWidth.current ?: maxWidth, kind)
        val innerModifier = Modifier
            .widthIn(max = policy.maxContentWidth)
            .then(if (fillMaxHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth())
            .padding(start = if (edgeToEdgeStart) 0.dp else policy.horizontalMargin, end = if (edgeToEdgeEnd) 0.dp else policy.horizontalMargin)
        CompositionLocalProvider(
            LocalContentStartInset provides if (edgeToEdgeStart) policy.horizontalMargin else 0.dp,
            LocalContentEndInset provides if (edgeToEdgeEnd) policy.horizontalMargin else 0.dp,
        ) {
            Box(modifier = innerModifier, content = content)
        }
    }
}
