package com.debanshu777.caraml.core.ui.layout

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class AppNavigationLayout {
    BottomBar,
    Rail,
    Sidebar,
}

/** Presentation-only shell mode used by primary destination chrome. */
val LocalAppNavigationLayout = staticCompositionLocalOf {
    AppNavigationLayout.BottomBar
}

enum class AppContentKind {
    Chat,
    ModelHub,
    Settings,
    Details,
}

@Immutable
data class AdaptiveLayoutPolicy(
    val navigation: AppNavigationLayout,
    val horizontalMargin: Dp,
    val maxContentWidth: Dp,
)

fun adaptiveLayoutPolicy(width: Dp, contentKind: AppContentKind): AdaptiveLayoutPolicy {
    val navigation = when {
        width < 600.dp -> AppNavigationLayout.BottomBar
        width < 1200.dp -> AppNavigationLayout.Rail
        else -> AppNavigationLayout.Sidebar
    }
    val horizontalMargin = if (width < 600.dp) 16.dp else 24.dp
    val maxContentWidth = when (contentKind) {
        AppContentKind.Chat -> 840.dp
        AppContentKind.ModelHub,
        AppContentKind.Details,
        -> 1040.dp
        AppContentKind.Settings -> 760.dp
    }
    return AdaptiveLayoutPolicy(
        navigation = navigation,
        horizontalMargin = horizontalMargin,
        maxContentWidth = maxContentWidth,
    )
}
