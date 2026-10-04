package com.debanshu777.caraml.core.ui.layout

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

enum class AppNavigationLayout {
    ModalSidebar,
    Rail,
    Sidebar,
}

/** Presentation-only shell mode used by primary destination chrome. */
val LocalAppNavigationLayout = staticCompositionLocalOf {
    AppNavigationLayout.ModalSidebar
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
        width < AppTheme.dimensions.size600 -> AppNavigationLayout.ModalSidebar
        width < AppTheme.dimensions.size840 -> AppNavigationLayout.Rail
        else -> AppNavigationLayout.Sidebar
    }
    val horizontalMargin = if (width < AppTheme.dimensions.size600) AppTheme.spacing.spacing16 else AppTheme.spacing.spacing24
    val maxContentWidth = when (contentKind) {
        AppContentKind.Chat -> AppTheme.dimensions.size840
        AppContentKind.ModelHub,
        AppContentKind.Details,
        -> AppTheme.dimensions.size1040
        AppContentKind.Settings -> AppTheme.dimensions.size760
    }
    return AdaptiveLayoutPolicy(
        navigation = navigation,
        horizontalMargin = horizontalMargin,
        maxContentWidth = maxContentWidth,
    )
}
