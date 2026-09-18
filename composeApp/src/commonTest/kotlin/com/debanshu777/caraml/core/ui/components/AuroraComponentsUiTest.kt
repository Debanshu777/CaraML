@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
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
    fun translucentPaneProvidesReadableContentColorInDarkTheme() = runComposeUiTest {
        val background = Color.Black
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = background,
                        surfaceContainer = background,
                        onSurface = Color.White,
                    ),
                ) {
                    Box(
                        Modifier
                            .requiredSize(width = 320.dp, height = 120.dp)
                            .background(background)
                            .testTag("pane-content-color-host"),
                    ) {
                        CaraMLPane(Modifier.fillMaxSize()) {
                            androidx.compose.material3.Text("Pane heading")
                        }
                    }
                }
            }
        }

        val bounds = onNodeWithText("Pane heading").fetchSemanticsNode().boundsInRoot
        val pixels = onNodeWithTag("pane-content-color-host").captureToImage().toPixelMap()
        assertTrue(
            pixels.maximumContrastAgainst(background, bounds) >= 4.5f,
            "Pane content must inherit onSurface in dark theme",
        )
    }

    @Test
    fun backdropKeepsFaintThreeAnchorAtmosphereAndReadableDarkAndLightContent() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            surface = Color(0xFF0E1118),
                            onSurface = Color.White,
                            primary = Color(0xFF4F83FF),
                            secondary = Color(0xFF00D59C),
                            tertiary = Color(0xFFFF5AA5),
                        ),
                    ) {
                        BackdropReadabilityFixture(
                            tag = "dark-backdrop-host",
                            label = "Dark readable",
                        )
                    }
                    MaterialTheme(
                        colorScheme = lightColorScheme(
                            surface = Color(0xFFF9FAFF),
                            onSurface = Color(0xFF11131A),
                            primaryContainer = Color(0xFFB8CAFF),
                            secondaryContainer = Color(0xFF9BEBD4),
                            tertiaryContainer = Color(0xFFFFC0DC),
                        ),
                    ) {
                        BackdropReadabilityFixture(
                            tag = "light-backdrop-host",
                            label = "Light readable",
                        )
                    }
                }
            }
        }

        listOf(
            "dark-backdrop-host" to "Dark readable",
            "light-backdrop-host" to "Light readable",
        ).forEach { (tag, label) ->
            val host = onNodeWithTag(tag).fetchSemanticsNode()
            val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
            val primary = pixels.averagePatch(36, 22)
            val secondary = pixels.averagePatch(324, 36)
            val tertiary = pixels.averagePatch(270, 158)
            val fieldDelta = primary.colorDistance(secondary) +
                secondary.colorDistance(tertiary) +
                tertiary.colorDistance(primary)

            assertTrue(
                fieldDelta in 0.05f..0.22f,
                "The ambient field must stay faint rather than become a full-screen hero; " +
                    "delta was $fieldDelta",
            )
            assertTrue(
                primary.colorDistance(secondary) >= 0.01f &&
                    secondary.colorDistance(tertiary) >= 0.01f &&
                    tertiary.colorDistance(primary) >= 0.01f,
                "The three faint anchors must remain spatially distinct without becoming " +
                    "saturated panels; primary=$primary secondary=$secondary tertiary=$tertiary",
            )

            val textBounds = onNodeWithText(label).fetchSemanticsNode().boundsInRoot
            val relativeTextBounds = Rect(
                left = textBounds.left - host.boundsInRoot.left,
                top = textBounds.top - host.boundsInRoot.top,
                right = textBounds.right - host.boundsInRoot.left,
                bottom = textBounds.bottom - host.boundsInRoot.top,
            )
            val localBackground = pixels[
                (relativeTextBounds.left - 6f).toInt().coerceIn(0, pixels.width - 1),
                relativeTextBounds.center.y.toInt().coerceIn(0, pixels.height - 1),
            ]
            assertTrue(
                pixels.maximumContrastAgainst(localBackground, relativeTextBounds) >= 4.5f,
                "$label content must keep at least 4.5:1 local contrast",
            )
        }
    }

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

        onNodeWithText("Step 3 / 10 · 2s", useUnmergedTree = true).assertIsDisplayed()
        onNode(hasStateDescription("Generating")).assertIsDisplayed()
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
        onNode(
            hasStateDescription("Model downloaded") and hasText("Model downloaded"),
        ).assertIsDisplayed()
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
private fun BackdropReadabilityFixture(
    tag: String,
    label: String,
) {
    AuroraBackdrop(
        modifier = Modifier
            .requiredSize(width = 360.dp, height = 180.dp)
            .testTag(tag),
    ) {
        androidx.compose.material3.Text(
            text = label,
            modifier = Modifier.align(Alignment.Center),
            color = MaterialTheme.colorScheme.onSurface,
        )
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

private fun PixelMap.averagePatch(
    centerX: Int,
    centerY: Int,
    radius: Int = 4,
): Color {
    var red = 0f
    var green = 0f
    var blue = 0f
    var count = 0
    for (y in (centerY - radius).coerceAtLeast(0)..(centerY + radius).coerceAtMost(height - 1)) {
        for (x in (centerX - radius).coerceAtLeast(0)..(centerX + radius).coerceAtMost(width - 1)) {
            val color = this[x, y]
            red += color.red
            green += color.green
            blue += color.blue
            count += 1
        }
    }
    return Color(red / count, green / count, blue / count)
}

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

private fun Color.colorDistance(other: Color): Float =
    kotlin.math.abs(red - other.red) +
        kotlin.math.abs(green - other.green) +
        kotlin.math.abs(blue - other.blue)

@Composable
private fun AtTwoHundredPercentFontScale(content: @Composable () -> Unit) {
    val current = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(current.density, fontScale = 2f),
        content = content,
    )
}
