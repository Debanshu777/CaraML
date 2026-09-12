package com.debanshu777.caraml.core.ui.layout

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class AdaptiveLayoutPolicyTest {
    @Test
    fun navigationChangesAtMaterialWidthBoundaries() {
        assertEquals(AppNavigationLayout.ModalDrawer, adaptiveLayoutPolicy(599.dp, AppContentKind.Chat).navigation)
        assertEquals(AppNavigationLayout.Rail, adaptiveLayoutPolicy(600.dp, AppContentKind.Chat).navigation)
        assertEquals(AppNavigationLayout.Rail, adaptiveLayoutPolicy(839.dp, AppContentKind.Chat).navigation)
        assertEquals(AppNavigationLayout.Sidebar, adaptiveLayoutPolicy(840.dp, AppContentKind.Chat).navigation)
    }

    @Test
    fun destinationWidthsMatchTheApprovedSpec() {
        assertEquals(840.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.Chat).maxContentWidth)
        assertEquals(1040.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.ModelHub).maxContentWidth)
        assertEquals(760.dp, adaptiveLayoutPolicy(1200.dp, AppContentKind.Settings).maxContentWidth)
    }
}
