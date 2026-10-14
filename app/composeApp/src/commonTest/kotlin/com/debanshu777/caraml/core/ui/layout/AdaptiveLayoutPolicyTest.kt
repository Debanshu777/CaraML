package com.debanshu777.caraml.core.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveLayoutPolicyTest {
    @Test
    fun compactGuttersMatchTheNarrowPhoneOverride() {
        assertEquals(16.dp, adaptiveLayoutPolicy(360.dp, AppContentKind.Settings).horizontalMargin)
        assertEquals(18.dp, adaptiveLayoutPolicy(361.dp, AppContentKind.Settings).horizontalMargin)
        assertEquals(18.dp, adaptiveLayoutPolicy(599.dp, AppContentKind.ModelHub).horizontalMargin)
        assertEquals(24.dp, adaptiveLayoutPolicy(600.dp, AppContentKind.Chat).horizontalMargin)
    }

    @Test
    fun navigationUsesModalSidebarBelow600RailThrough839AndSidebarFrom840() {
        assertEquals(
            AppNavigationLayout.ModalSidebar,
            adaptiveLayoutPolicy(599.dp, AppContentKind.Chat).navigation,
        )
        assertEquals(
            AppNavigationLayout.Rail,
            adaptiveLayoutPolicy(600.dp, AppContentKind.Chat).navigation,
        )
        assertEquals(
            AppNavigationLayout.Rail,
            adaptiveLayoutPolicy(839.dp, AppContentKind.Chat).navigation,
        )
        assertEquals(
            AppNavigationLayout.Sidebar,
            adaptiveLayoutPolicy(840.dp, AppContentKind.Chat).navigation,
        )
    }

    @Test
    fun destinationWidthsMatchTheApprovedSpec() {
        assertEquals(840.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.Chat).maxContentWidth)
        assertEquals(1040.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.ModelHub).maxContentWidth)
        assertEquals(760.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.Settings).maxContentWidth)
    }
}
