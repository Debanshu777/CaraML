@file:OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)

package com.debanshu777.caraml.core.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.ui.motion.LocalAuroraMotionPolicy
import com.debanshu777.caraml.core.ui.motion.auroraMotionPolicy
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AuroraBackdropUiTest {
    @Test
    fun focalSurfaceAtmosphereEntersOnceThenBecomesPixelStable() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = Color(0xFF0E1018),
                        primary = Color(0xFF276BFF),
                        secondary = Color(0xFF00D59C),
                        tertiary = Color(0xFFFF3F92),
                    ),
                ) {
                    FocalFixture(
                        tag = "entering-focal",
                        entrance = FocalEntrance.OneShot,
                    )
                }
            }
        }

        val initial = onNodeWithTag("entering-focal").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(450)
        val midpoint = onNodeWithTag("entering-focal").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(550)
        val settled = onNodeWithTag("entering-focal").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_000)
        val idle = onNodeWithTag("entering-focal").captureToImage().toPixelMap()

        assertTrue(
            initial.maxChannelDifference(midpoint) >= 0.01f,
            "focal atmosphere must visibly reveal during its first 900ms",
        )
        assertTrue(
            midpoint.maxChannelDifference(settled) >= 0.005f,
            "focal atmosphere must continue resolving after the midpoint",
        )
        assertTrue(
            settled.maxChannelDifference(idle) <= (1f / 255f) + 0.0001f,
            "focal atmosphere must be completely static after the one-shot entrance",
        )
    }

    @Test
    fun reducedMotionShowsTheSettledFocalAtmosphereWithoutSpatialOrIdleMotion() =
        runComposeUiTest {
            mainClock.autoAdvance = false
            setContent {
                CompositionLocalProvider(
                    LocalDensity provides Density(1f),
                    LocalAuroraMotionPolicy provides auroraMotionPolicy(durationScale = 0f),
                ) {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            surface = Color(0xFF0E1018),
                            primary = Color(0xFF276BFF),
                            secondary = Color(0xFF00D59C),
                            tertiary = Color(0xFFFF3F92),
                        ),
                    ) {
                        FocalFixture(
                            tag = "reduced-focal",
                            entrance = FocalEntrance.OneShot,
                        )
                    }
                }
            }

            val initial = onNodeWithTag("reduced-focal").captureToImage().toPixelMap()
            mainClock.advanceTimeBy(1_000)
            val later = onNodeWithTag("reduced-focal").captureToImage().toPixelMap()

            assertTrue(
                initial.maxChannelDifference(later) <= (1f / 255f) + 0.0001f,
                "reduced motion must render the settled focal atmosphere immediately",
            )
        }

    @Test
    fun ambientMeshKeepsThreeRestrainedColorRegionsAtCompactAndWideAspectRatios() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            surface = Color(0xFF0E1018),
                            primary = Color(0xFF276BFF),
                            secondary = Color(0xFF00D59C),
                            tertiary = Color(0xFFFF3F92),
                        ),
                    ) {
                        Column {
                            BackdropFixture(
                                tag = "compact-mesh",
                                width = 360,
                                height = 360,
                            )
                            BackdropFixture(
                                tag = "wide-mesh",
                                width = 720,
                                height = 360,
                            )
                        }
                    }
                }
            }

            listOf("compact-mesh", "wide-mesh").forEach { tag ->
                val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
                val primary = pixels.averagePatch(xFraction = 0.12f, yFraction = 0.12f)
                val secondary = pixels.averagePatch(xFraction = 0.82f, yFraction = 0.18f)
                val tertiary = pixels.averagePatch(xFraction = 0.78f, yFraction = 0.84f)

                assertTrue(
                    primary.blue - max(primary.red, primary.green) >= 0.004f,
                    "$tag must keep a blue primary region; sampled $primary",
                )
                assertTrue(
                    secondary.green > primary.green && secondary.green > tertiary.green,
                    "$tag secondary anchor must lift green relative to the other regions; " +
                        "primary=$primary secondary=$secondary tertiary=$tertiary",
                )
                assertTrue(
                    tertiary.red - tertiary.green >= 0.004f,
                    "$tag must keep a magenta tertiary region; sampled $tertiary",
                )

                val separation = primary.distanceTo(secondary) +
                    secondary.distanceTo(tertiary) +
                    tertiary.distanceTo(primary)
                assertTrue(
                    separation in 0.05f..0.22f,
                    "$tag ambient mesh must read as faint atmosphere rather than a full-screen gradient; delta was $separation",
                )
            }
        }

    @Test
    fun grainIsDeterministicAndCreatesSubtleHighFrequencyTexture() = runComposeUiTest {
        val neutral = Color(0xFF4C4C4C)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = neutral,
                        primary = neutral,
                        secondary = neutral,
                        tertiary = neutral,
                    ),
                ) {
                    Column {
                        BackdropFixture(tag = "grain-a", width = 192, height = 192)
                        BackdropFixture(tag = "grain-b", width = 192, height = 192)
                    }
                }
            }
        }

        val first = onNodeWithTag("grain-a").captureToImage().toPixelMap()
        val second = onNodeWithTag("grain-b").captureToImage().toPixelMap()
        val energy = first.highFrequencyEnergy()

        assertTrue(
            energy >= 0.0007f,
            "ambient grain must remain perceptible at close inspection; energy was $energy",
        )
        assertTrue(
            energy <= 0.012f,
            "ambient grain must stay quieter than the focal treatment; energy was $energy",
        )
        assertTrue(
            first.maxChannelDifference(second) <= (1f / 255f) + 0.0001f,
            "grain must be deterministic across equivalent backdrops",
        )
    }

    @Test
    fun vignetteAddsControlledEdgeDepthWithoutCrushingTheCanvas() = runComposeUiTest {
        val neutral = Color(0xFF707070)
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = neutral,
                        primary = neutral,
                        secondary = neutral,
                        tertiary = neutral,
                    ),
                ) {
                    BackdropFixture(tag = "vignette", width = 240, height = 240)
                }
            }
        }

        val pixels = onNodeWithTag("vignette").captureToImage().toPixelMap()
        val center = pixels.averagePatch(0.5f, 0.5f, radius = 8).signal()
        val corners = listOf(
            pixels.averagePatch(0.04f, 0.04f, radius = 8),
            pixels.averagePatch(0.96f, 0.04f, radius = 8),
            pixels.averagePatch(0.04f, 0.96f, radius = 8),
            pixels.averagePatch(0.96f, 0.96f, radius = 8),
        ).map(Color::signal).average().toFloat()
        val depth = center - corners

        assertTrue(depth >= 0.01f, "vignette must gently frame the canvas; depth was $depth")
        assertTrue(depth <= 0.07f, "vignette must not turn the canvas into a hero surface; depth was $depth")
    }

    @Test
    fun backdropIsPixelStableWhileIdle() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = Color(0xFF0E1018),
                        primary = Color(0xFF276BFF),
                        secondary = Color(0xFF00D59C),
                        tertiary = Color(0xFFFF3F92),
                    ),
                ) {
                    BackdropFixture(tag = "stable-backdrop", width = 240, height = 240)
                }
            }
        }

        val first = onNodeWithTag("stable-backdrop").captureToImage().toPixelMap()
        mainClock.advanceTimeBy(1_111)
        val second = onNodeWithTag("stable-backdrop").captureToImage().toPixelMap()

        assertTrue(
            first.maxChannelDifference(second) <= (1f / 255f) + 0.0001f,
            "ambient mesh and grain must not shimmer while idle",
        )
    }

    @Test
    fun grainMaskIsReusedWhenTheThemeTintChanges() = runComposeUiTest {
        var tint by mutableStateOf(Color.White)
        var firstBrush: Any? = null
        var latestBrush: Any? = null

        setContent {
            val brush = rememberAuroraGrainBrush(tint)
            if (firstBrush == null) firstBrush = brush
            latestBrush = brush
        }

        runOnIdle { tint = Color.Black }
        runOnIdle {
            assertSame(
                firstBrush,
                latestBrush,
                "theme interpolation must tint one cached grain mask rather than rebuild its bitmap",
            )
        }
    }

    @Test
    fun focalSurfaceMaintainsTextContrastInLightAndDarkSchemes() = runComposeUiTest {
        val darkOnSurface = Color(0xFFF3F4FA)
        val darkOnSurfaceVariant = Color(0xFFE0E2EA)
        val lightOnSurface = Color(0xFF11131A)
        val lightOnSurfaceVariant = Color(0xFF323640)

        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                Column {
                    MaterialTheme(
                        colorScheme = darkColorScheme(
                            surface = Color(0xFF0E1018),
                            onSurface = darkOnSurface,
                            onSurfaceVariant = darkOnSurfaceVariant,
                            primary = Color(0xFF7FA4FF),
                            secondary = Color(0xFF48DDB4),
                            tertiary = Color(0xFFFF79B5),
                        ),
                    ) {
                        FocalFixture("dark-focal-contrast")
                    }
                    MaterialTheme(
                        colorScheme = lightColorScheme(
                            surface = Color(0xFFF9FAFF),
                            onSurface = lightOnSurface,
                            onSurfaceVariant = lightOnSurfaceVariant,
                            primary = Color(0xFF315FC3),
                            primaryContainer = Color(0xFFB8CAFF),
                            secondaryContainer = Color(0xFFA8EBD4),
                            tertiary = Color(0xFFA33B70),
                            tertiaryContainer = Color(0xFFFFC0DC),
                        ),
                    ) {
                        FocalFixture("light-focal-contrast")
                    }
                }
            }
        }

        listOf(
            Triple("dark-focal-contrast", darkOnSurface, darkOnSurfaceVariant),
            Triple("light-focal-contrast", lightOnSurface, lightOnSurfaceVariant),
        ).forEach { (tag, onSurface, onSurfaceVariant) ->
            val pixels = onNodeWithTag(tag).captureToImage().toPixelMap()
            assertTrue(
                pixels.minimumContrastAgainst(onSurface) >= 4.5f,
                "$tag must keep onSurface text at or above 4.5:1 everywhere",
            )
            assertTrue(
                pixels.minimumContrastAgainst(onSurfaceVariant) >= 4.5f,
                "$tag must keep onSurfaceVariant text at or above 4.5:1 everywhere",
            )
        }
    }

    @Test
    fun focalSurfaceSectionTitleRendersWithReadableDarkAndLightContentColor() =
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f)) {
                    Column {
                        MaterialTheme(
                            colorScheme = darkColorScheme(
                                surface = Color(0xFF0E1018),
                                onSurface = Color(0xFFF3F4FA),
                                primary = Color(0xFF7FA4FF),
                                secondary = Color(0xFF48DDB4),
                                tertiary = Color(0xFFFF79B5),
                            ),
                        ) {
                            FocalSectionFixture("Dark focal heading")
                        }
                        MaterialTheme(
                            colorScheme = lightColorScheme(
                                surface = Color(0xFFF9FAFF),
                                onSurface = Color(0xFF11131A),
                                primary = Color(0xFF315FC3),
                                primaryContainer = Color(0xFFB8CAFF),
                                secondaryContainer = Color(0xFFA8EBD4),
                                tertiary = Color(0xFFA33B70),
                                tertiaryContainer = Color(0xFFFFC0DC),
                            ),
                        ) {
                            FocalSectionFixture("Light focal heading")
                        }
                    }
                }
            }

            listOf("Dark focal heading", "Light focal heading").forEach { label ->
                val contrast = onNodeWithText(label)
                    .captureToImage()
                    .toPixelMap()
                    .internalContrast()
                assertTrue(
                    contrast >= 4.5f,
                    "$label must use the semantic on-surface color; contrast was $contrast",
                )
            }
        }

    @Test
    fun focalSurfaceUsesTheSameThreeColorGrainLanguage() = runComposeUiTest {
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f)) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        surface = Color(0xFF0E1018),
                        primary = Color(0xFF276BFF),
                        secondary = Color(0xFF00D59C),
                        tertiary = Color(0xFFFF3F92),
                    ),
                ) {
                    AuroraFocalSurface(
                        modifier = Modifier
                            .requiredSize(width = 360.dp, height = 180.dp)
                            .testTag("focal-field"),
                        shape = RectangleShape,
                    ) {}
                }
            }
        }

        val pixels = onNodeWithTag("focal-field").captureToImage().toPixelMap()
        val primary = pixels.averagePatch(0.12f, 0.16f)
        val secondary = pixels.averagePatch(0.88f, 0.18f)
        val tertiary = pixels.averagePatch(0.74f, 0.82f)

        assertTrue(primary.blue > primary.red && primary.blue > primary.green)
        assertTrue(secondary.green > secondary.red && secondary.green > secondary.blue)
        assertTrue(tertiary.red > tertiary.green)
        assertTrue(
            pixels.highFrequencyEnergy() >= 0.0025f,
            "focal field must share the deterministic grain treatment",
        )
    }
}

@androidx.compose.runtime.Composable
private fun BackdropFixture(
    tag: String,
    width: Int,
    height: Int,
) {
    AuroraBackdrop(
        modifier = Modifier
            .requiredSize(width.dp, height.dp)
            .testTag(tag),
    ) {}
}

@androidx.compose.runtime.Composable
private fun FocalFixture(tag: String) {
    FocalFixture(tag = tag, entrance = FocalEntrance.None)
}

@androidx.compose.runtime.Composable
private fun FocalFixture(
    tag: String,
    entrance: FocalEntrance,
) {
    AuroraFocalSurface(
        modifier = Modifier
            .requiredSize(width = 360.dp, height = 180.dp)
            .testTag(tag),
        shape = RectangleShape,
        entrance = entrance,
    ) {}
}

@androidx.compose.runtime.Composable
private fun FocalSectionFixture(label: String) {
    AuroraFocalSurface(
        modifier = Modifier.requiredSize(width = 360.dp, height = 120.dp),
        shape = RectangleShape,
    ) {
        CaraMLSectionHeader(title = label)
    }
}

private fun PixelMap.averagePatch(
    xFraction: Float,
    yFraction: Float,
    radius: Int = 5,
): Color {
    val centerX = (width * xFraction).toInt().coerceIn(radius, width - radius - 1)
    val centerY = (height * yFraction).toInt().coerceIn(radius, height - radius - 1)
    var red = 0f
    var green = 0f
    var blue = 0f
    var count = 0
    for (y in centerY - radius..centerY + radius) {
        for (x in centerX - radius..centerX + radius) {
            val color = this[x, y]
            red += color.red
            green += color.green
            blue += color.blue
            count += 1
        }
    }
    return Color(red / count, green / count, blue / count)
}

private fun Color.distanceTo(other: Color): Float =
    kotlin.math.abs(red - other.red) +
        kotlin.math.abs(green - other.green) +
        kotlin.math.abs(blue - other.blue)

private fun PixelMap.highFrequencyEnergy(): Float {
    var total = 0f
    var count = 0
    for (y in 1 until height - 1) {
        for (x in 1 until width - 1) {
            val center = this[x, y].signal()
            val horizontal = kotlin.math.abs(
                (2f * center) - this[x - 1, y].signal() - this[x + 1, y].signal(),
            )
            val vertical = kotlin.math.abs(
                (2f * center) - this[x, y - 1].signal() - this[x, y + 1].signal(),
            )
            total += horizontal + vertical
            count += 1
        }
    }
    return total / count
}

private fun PixelMap.maxChannelDifference(other: PixelMap): Float {
    require(width == other.width && height == other.height)
    var maximum = 0f
    for (y in 0 until height) {
        for (x in 0 until width) {
            val first = this[x, y]
            val second = other[x, y]
            maximum = max(
                maximum,
                max(
                    kotlin.math.abs(first.red - second.red),
                    max(
                        kotlin.math.abs(first.green - second.green),
                        kotlin.math.abs(first.blue - second.blue),
                    ),
                ),
            )
        }
    }
    return maximum
}

private fun PixelMap.minimumContrastAgainst(foreground: Color): Float {
    var minimum = Float.POSITIVE_INFINITY
    for (y in 0 until height) {
        for (x in 0 until width) {
            val backgroundLuminance = this[x, y].luminance()
            val foregroundLuminance = foreground.luminance()
            val contrast = (max(foregroundLuminance, backgroundLuminance) + 0.05f) /
                (kotlin.math.min(foregroundLuminance, backgroundLuminance) + 0.05f)
            minimum = kotlin.math.min(minimum, contrast)
        }
    }
    return minimum
}

private fun PixelMap.internalContrast(): Float {
    var minimum = 1f
    var maximum = 0f
    for (y in 0 until height) {
        for (x in 0 until width) {
            val luminance = this[x, y].luminance()
            minimum = kotlin.math.min(minimum, luminance)
            maximum = max(maximum, luminance)
        }
    }
    return (maximum + 0.05f) / (minimum + 0.05f)
}

private fun Color.signal(): Float = (red + green + blue) / 3f
