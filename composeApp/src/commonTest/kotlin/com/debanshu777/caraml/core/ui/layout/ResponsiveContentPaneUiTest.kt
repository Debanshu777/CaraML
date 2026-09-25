@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.layout

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test

class ResponsiveContentPaneUiTest {

    @Test
    fun chatContentUsesItsMaximumWidthAtWideViewport() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Box(
                    modifier = Modifier
                        .requiredWidth(1_200.dp)
                        .height(480.dp),
                ) {
                    ResponsiveContentPane(kind = AppContentKind.Chat) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("responsive-content"),
                        )
                    }
                }
            }
        }

        onNodeWithTag("responsive-content").assertWidthIsEqualTo(792.dp)
    }
}
