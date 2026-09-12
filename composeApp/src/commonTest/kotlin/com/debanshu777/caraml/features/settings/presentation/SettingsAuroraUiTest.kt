@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test

class SettingsAuroraUiTest {
    @Test
    fun appearancePreviewIsIdentifiableWithoutDependingOnColor() = runComposeUiTest {
        setContent { MaterialTheme { AuroraThemePreview() } }

        onNodeWithContentDescription("Current Aurora theme preview").assertIsDisplayed()
    }

    @Test
    fun longDescriptionExpandsAndCollapsesAccessibly() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ExpandableSettingDescription(
                    summary = "Balanced memory and quality.",
                    details = "Uses Q8 keys and values for most devices.",
                )
            }
        }

        onNodeWithText("Show details").performClick()
        onNodeWithText("Uses Q8 keys and values for most devices.").assertIsDisplayed()
        onNodeWithText("Hide details").performClick()
        onNodeWithText("Uses Q8 keys and values for most devices.").assertDoesNotExist()
    }
}
