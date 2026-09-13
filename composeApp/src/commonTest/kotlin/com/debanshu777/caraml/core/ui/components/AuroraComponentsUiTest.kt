@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.rating.ui.RecommendationStatusChip
import com.debanshu777.caraml.core.recommendation.PersonalizedRecommendation
import com.debanshu777.caraml.core.recommendation.RecommendationCategory
import com.debanshu777.caraml.core.recommendation.RecommendationProfile
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.features.chat.data.ChatMessage
import com.debanshu777.caraml.features.chat.data.MessageRole
import com.debanshu777.caraml.features.chat.presentation.components.MessageBubble
import com.debanshu777.caraml.features.chat.presentation.components.ModelErrorScreen
import com.debanshu777.caraml.features.modelhub.domain.DescriptorState
import com.debanshu777.caraml.features.modelhub.presentation.details.components.GgufFileListItem
import com.debanshu777.caraml.features.modelhub.presentation.details.components.InstallBundleCard
import com.debanshu777.caraml.features.modelhub.presentation.downloaded.components.LocalModelListItem
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.InstallBundleUiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AuroraComponentsUiTest {

    @Test
    fun emptyStateIconAndTitleMeetContrastOnTransparentDarkHost() {
        val background = Color(0xFF111318)
        assertEmptyStateIconAndTitleContrast(
            background = background,
            colorScheme = darkColorScheme(
                surface = background,
                onSurface = Color.White,
                onSurfaceVariant = Color.LightGray,
            ),
        )
    }

    @Test
    fun emptyStateIconAndTitleMeetContrastOnTransparentLightHost() {
        val background = Color(0xFFF9F9FF)
        assertEmptyStateIconAndTitleContrast(
            background = background,
            colorScheme = lightColorScheme(
                surface = background,
                onSurface = Color.Black,
                onSurfaceVariant = Color.DarkGray,
            ),
        )
    }

    @Test
    fun emptyStateExposesItsActionAndInvokesIt() = runComposeUiTest {
        var clicks = 0
        setContent {
            MaterialTheme {
                CaraMLEmptyState(
                    icon = Icons.Default.AutoAwesome,
                    title = "Think locally. Stay private.",
                    supportingText = "Your prompt and model stay on this device.",
                    actionLabel = "Browse models",
                    onAction = { clicks += 1 },
                )
            }
        }

        onNodeWithText("Think locally. Stay private.").assertIsDisplayed()
        onNodeWithText("Browse models")
            .assertWidthIsAtLeast(48.dp)
            .assertHeightIsAtLeast(48.dp)
            .performClick()
        runOnIdle { assertEquals(1, clicks) }
    }

    @Test
    fun statusPillMeaningDoesNotDependOnColor() = runComposeUiTest {
        setContent {
            MaterialTheme {
                CaraMLStatusPill(
                    label = "Ready",
                    contentDescription = "Model ready for chat",
                    tone = StatusTone.Success,
                    icon = Icons.Default.CheckCircle,
                )
            }
        }

        onNodeWithContentDescription("Model ready for chat")
            .assertIsDisplayed()
            .assertTextEquals("Ready")
    }

    @Test
    fun emptyStateTitleAndActionRemainVisibleAtTwoHundredPercentFontScale() = runComposeUiTest {
        setContent {
            AtTwoHundredPercentFontScale {
                MaterialTheme {
                    Box(Modifier.width(320.dp).height(360.dp)) {
                        CaraMLEmptyState(
                            icon = Icons.Default.AutoAwesome,
                            title = "Think locally. Stay private.",
                            supportingText = "Your prompt and model stay on this device.",
                            actionLabel = "Browse models",
                            onAction = {},
                        )
                    }
                }
            }
        }

        onNodeWithText("Think locally. Stay private.").assertIsDisplayed()
        onNodeWithText("Browse models").assertIsDisplayed()
    }

    @Test
    fun localModelStateMappingsExposeNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    LocalModelStatusFixture(
                        id = "ready-model",
                        filename = "ready.gguf",
                        componentStatus = LocalModelEntity.STATUS_READY,
                    )
                    LocalModelStatusFixture(
                        id = "partial-model",
                        filename = "partial.gguf",
                        componentStatus = LocalModelEntity.STATUS_PARTIAL,
                    )
                    LocalModelStatusFixture(
                        id = "unsupported-model",
                        filename = "unsupported.bin",
                        componentStatus = null,
                    )
                }
            }
        }

        listOf(
            "Ready" to "Ready for chat.",
            "Needs setup" to "Partial download. Missing components need setup.",
            "Unsupported" to "Unsupported. Chat is not available for this model type.",
        ).forEach { (label, description) ->
            onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithContentDescription(description).assertIsDisplayed()
        }
    }

    @Test
    fun recommendationStateMappingsExposeNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                Column {
                    RecommendationStatusChip(
                        state = DescriptorState.ASSESSED,
                        recommendation = recommendation(RecommendationCategory.RECOMMENDED),
                    )
                    RecommendationStatusChip(
                        state = DescriptorState.ASSESSED,
                        recommendation = recommendation(RecommendationCategory.RISKY),
                    )
                }
            }
        }

        listOf("Recommended", "Risky").forEach { label ->
            onNodeWithText(label, useUnmergedTree = true).assertIsDisplayed()
            onNodeWithContentDescription(
                "$label. Unavailable confidence. No reason available.",
            ).assertIsDisplayed()
        }
    }

    @Test
    fun downloadingStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                GgufFileListItem(
                    filename = "weights.gguf",
                    sizeBytes = 1_024L,
                    isDownloaded = false,
                    progress = 42f,
                    isDownloading = true,
                    onDownloadClick = {},
                )
            }
        }

        onNodeWithText("42%", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasStateDescription("Downloading") and hasText("42%")).assertIsDisplayed()
    }

    @Test
    fun generatingStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                MessageBubble(
                    message = ChatMessage(
                        id = "generating-message",
                        role = MessageRole.Assistant,
                        text = "",
                    ),
                    showMediaPending = true,
                    imageGenStep = 3,
                    imageGenTotalSteps = 10,
                    imageGenElapsedSeconds = 2,
                )
            }
        }

        onNodeWithText("Step 3 / 10  ·  2s", useUnmergedTree = true).assertIsDisplayed()
        onNode(
            hasStateDescription("Generating") and hasText("Step 3 / 10  ·  2s"),
        ).assertIsDisplayed()
    }

    @Test
    fun successStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                InstallBundleCard(
                    modelId = "org/downloaded-model",
                    state = InstallBundleUiState(
                        variants = listOf(
                            GgufFileUiState(
                                path = "weights.gguf",
                                filename = "weights.gguf",
                                sizeBytes = 1_024L,
                                isDownloaded = true,
                                progress = null,
                            ),
                        ),
                        isReady = true,
                    ),
                    familyLabel = null,
                    modelDescription = null,
                    onVariantSelected = {},
                    onInstall = {},
                )
            }
        }

        onNodeWithText("Model downloaded", useUnmergedTree = true).assertIsDisplayed()
        onNodeWithContentDescription("Model downloaded").assertIsDisplayed()
    }

    @Test
    fun errorStateMappingExposesNonColorMeaning() = runComposeUiTest {
        setContent {
            MaterialTheme {
                ModelErrorScreen(
                    errorMessage = "Runtime rejected this model.",
                    onTryAnotherModelClick = {},
                )
            }
        }

        onNodeWithText("Unable to load model", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasStateDescription("Error") and hasText("Unable to load model")).assertIsDisplayed()
    }
}

@Composable
private fun LocalModelStatusFixture(
    id: String,
    filename: String,
    componentStatus: String?,
) {
    LocalModelListItem(
        model = LocalModelEntity(
            modelId = id,
            filename = filename,
            localPath = "/models/$filename",
            sizeBytes = 1_024L,
            downloadedAt = 0L,
            author = null,
            libraryName = null,
            pipelineTag = if (filename.endsWith(".bin")) "audio-classification" else null,
            componentStatus = componentStatus,
        ),
        selectionMode = false,
        isSelected = false,
        onOpenModel = {},
        onToggleSelect = {},
        onLongPress = {},
    )
}

private fun recommendation(category: RecommendationCategory) = PersonalizedRecommendation(
    assessmentKey = "${category.name.lowercase()}-assessment",
    category = category,
    selectedPlan = null,
    reasons = emptyList(),
    profile = RecommendationProfile(),
)

private fun hasStateDescription(description: String) =
    SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, description)

private fun assertEmptyStateIconAndTitleContrast(
    background: Color,
    colorScheme: androidx.compose.material3.ColorScheme,
) = runComposeUiTest {
    val title = "Aurora contrast"
    setContent {
        CompositionLocalProvider(LocalDensity provides Density(1f)) {
            MaterialTheme(colorScheme = colorScheme) {
                Box(
                    modifier = Modifier
                        .requiredSize(width = 320.dp, height = 240.dp)
                        .background(background)
                        .testTag("empty-state-contrast-host"),
                ) {
                    CaraMLEmptyState(
                        icon = Icons.Default.AutoAwesome,
                        title = title,
                        supportingText = "Readable in every theme.",
                    )
                }
            }
        }
    }

    val host = onNodeWithTag("empty-state-contrast-host").fetchSemanticsNode()
    val titleBounds = onNodeWithText(title).fetchSemanticsNode().boundsInRoot
    val relativeTitleBounds = Rect(
        left = titleBounds.left - host.boundsInRoot.left,
        top = titleBounds.top - host.boundsInRoot.top,
        right = titleBounds.right - host.boundsInRoot.left,
        bottom = titleBounds.bottom - host.boundsInRoot.top,
    )
    val pixels = onNodeWithTag("empty-state-contrast-host").captureToImage().toPixelMap()
    val iconBottom = relativeTitleBounds.top - 12f
    val iconBounds = Rect(
        left = 148f,
        top = iconBottom - 24f,
        right = 172f,
        bottom = iconBottom,
    )

    assertTrue(
        pixels.maximumContrastAgainst(background, iconBounds) >= 3f,
        "Empty-state icon must have at least 3:1 contrast against its host",
    )
    assertTrue(
        pixels.maximumContrastAgainst(background, relativeTitleBounds) >= 4.5f,
        "Empty-state title must have at least 4.5:1 contrast against its host",
    )
}

private fun PixelMap.maximumContrastAgainst(background: Color, bounds: Rect): Float {
    val left = bounds.left.toInt().coerceIn(0, width - 1)
    val top = bounds.top.toInt().coerceIn(0, height - 1)
    val right = bounds.right.toInt().coerceIn(left + 1, width)
    val bottom = bounds.bottom.toInt().coerceIn(top + 1, height)
    var maximum = 1f
    for (y in top until bottom) {
        for (x in left until right) {
            maximum = maxOf(maximum, contrastRatio(this[x, y], background))
        }
    }
    return maximum
}

private fun contrastRatio(first: Color, second: Color): Float {
    val firstLuminance = first.luminance()
    val secondLuminance = second.luminance()
    return (maxOf(firstLuminance, secondLuminance) + 0.05f) /
        (minOf(firstLuminance, secondLuminance) + 0.05f)
}

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
