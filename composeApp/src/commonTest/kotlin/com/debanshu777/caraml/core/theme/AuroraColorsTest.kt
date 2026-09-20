package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.materialkolor.hct.Hct
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AuroraColorsTest {
    @Test
    fun warmSeedBuildsDistinctVioletAndGreenFocalHarmonies() {
        val seed = Color(0xFFEFD04B)
        val scheme = darkColorScheme(
            primary = Color(0xFFDDB8F7),
            secondary = Color(0xFFD0C86F),
            tertiary = Color(0xFFB9CB7B),
        )

        val colors = scheme.toAuroraColors(isDark = true, focalSeed = seed)
        val primaryHue = Hct.fromInt(seed.toArgb()).hue
        val violetHue = Hct.fromInt(colors.focusSecondary.toArgb()).hue
        val greenHue = Hct.fromInt(colors.focusTertiary.toArgb()).hue

        assertEquals(seed.copy(alpha = 0.78f), colors.focusPrimary)
        assertEquals(Color.Black, colors.onFocusPrimary)
        assertEquals(220.0, clockwiseHueDistance(primaryHue, violetHue), 10.0)
        assertEquals(75.0, clockwiseHueDistance(primaryHue, greenHue), 10.0)
    }

    @Test
    fun auroraColorsComeFromSemanticSchemeRoles() {
        val scheme = lightColorScheme(
            surface = Color(0xFF101010),
            onSurface = Color(0xFFEFEFEF),
            scrim = Color(0xFF080808),
            primaryContainer = Color(0xFF223344),
            secondaryContainer = Color(0xFF334455),
            tertiaryContainer = Color(0xFF556677),
            outlineVariant = Color(0xFF8899AA),
        )

        val colors = scheme.toAuroraColors(isDark = false)

        assertEquals(scheme.surface, colors.canvas)
        assertEquals(scheme.primaryContainer.copy(alpha = 0.38f), colors.primaryGlow)
        assertEquals(scheme.secondaryContainer.copy(alpha = 0.32f), colors.secondaryGlow)
        assertEquals(scheme.tertiaryContainer.copy(alpha = 0.34f), colors.tertiaryGlow)
        assertEquals(scheme.onSurface.copy(alpha = 0.035f), colors.grainTint)
        assertEquals(scheme.scrim.copy(alpha = 0.06f), colors.edgeVignette)
        assertEquals(scheme.outlineVariant.copy(alpha = 0.72f), colors.paneBorder)
        assertEquals(scheme.surfaceContainer, colors.commandSurface)
        assertEquals(scheme.surfaceContainerHigh, colors.selectedSurface)
        assertEquals(scheme.outlineVariant.copy(alpha = 0.48f), colors.divider)
        assertEquals(scheme.primary.copy(alpha = 0.78f), colors.focusPrimary)
        assertEquals(0.74f, colors.focusSecondary.alpha, 0.005f)
        assertEquals(0.70f, colors.focusTertiary.alpha, 0.005f)
    }

    @Test
    fun darkAuroraUsesVisibleSeedRolesInsteadOfDarkContainerRoles() {
        val scheme = darkColorScheme(
            surface = Color(0xFF101010),
            onSurface = Color(0xFFEFEFEF),
            scrim = Color(0xFF080808),
            primary = Color(0xFF99BBFF),
            secondary = Color(0xFF99FFCC),
            tertiary = Color(0xFFFFAADD),
            primaryContainer = Color(0xFF182030),
            tertiaryContainer = Color(0xFF301824),
        )

        val colors = scheme.toAuroraColors(isDark = true)

        assertEquals(scheme.primary.copy(alpha = 0.28f), colors.primaryGlow)
        assertEquals(scheme.secondary.copy(alpha = 0.26f), colors.secondaryGlow)
        assertEquals(scheme.tertiary.copy(alpha = 0.24f), colors.tertiaryGlow)
        assertEquals(scheme.onSurface.copy(alpha = 0.05f), colors.grainTint)
        assertEquals(scheme.scrim.copy(alpha = 0.18f), colors.edgeVignette)
    }

    @Test
    fun everySurfaceLevelMapsToOneMaterialRole() {
        val scheme = lightColorScheme()

        assertEquals(scheme.surface, AuroraSurfaceLevel.Canvas.containerColor(scheme))
        assertEquals(scheme.surfaceContainerLow, AuroraSurfaceLevel.Recessed.containerColor(scheme))
        assertEquals(scheme.surfaceContainer, AuroraSurfaceLevel.Pane.containerColor(scheme))
        assertEquals(scheme.surfaceContainerHigh, AuroraSurfaceLevel.Floating.containerColor(scheme))
    }

    @Test
    fun surfaceLevelsKeepApprovedBackdropTransparency() {
        assertEquals(1f, AuroraSurfaceLevel.Canvas.containerAlpha)
        assertEquals(0.76f, AuroraSurfaceLevel.Recessed.containerAlpha)
        assertEquals(0.82f, AuroraSurfaceLevel.Pane.containerAlpha)
        assertEquals(0.94f, AuroraSurfaceLevel.Floating.containerAlpha)
    }

    @Test
    fun prismShapeAndTechnicalTypeTokensMatchTheSharedVocabulary() {
        assertEquals(RoundedCornerShape(8.dp), AppShapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), AppShapes.small)
        assertEquals(RoundedCornerShape(18.dp), AppShapes.medium)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.large)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.extraLarge)
        assertEquals(FontFamily.Monospace, AppTechnicalLabel.fontFamily)
        assertEquals(FontWeight.Medium, AppTechnicalLabel.fontWeight)
        assertEquals(12.sp, AppTechnicalLabel.fontSize)
        assertEquals(17.sp, AppTechnicalLabel.lineHeight)

        assertEquals(28.sp, AppPrismTypography.screenTitle.fontSize)
        assertEquals(34.sp, AppPrismTypography.screenTitle.lineHeight)
        assertEquals(FontWeight.SemiBold, AppPrismTypography.screenTitle.fontWeight)
        assertNull(AppPrismTypography.screenTitle.fontFamily)
        assertEquals(16.sp, AppPrismTypography.sectionTitle.fontSize)
        assertEquals(22.sp, AppPrismTypography.sectionTitle.lineHeight)
        assertEquals(FontWeight.SemiBold, AppPrismTypography.sectionTitle.fontWeight)
        assertNull(AppPrismTypography.sectionTitle.fontFamily)
        assertEquals(17.sp, AppPrismTypography.modelTitle.fontSize)
        assertEquals(22.sp, AppPrismTypography.modelTitle.lineHeight)
        assertEquals(FontWeight.Medium, AppPrismTypography.modelTitle.fontWeight)
        assertNull(AppPrismTypography.modelTitle.fontFamily)
        assertEquals(14.sp, AppPrismTypography.denseMetadata.fontSize)
        assertEquals(20.sp, AppPrismTypography.denseMetadata.lineHeight)
        assertEquals(FontWeight.Normal, AppPrismTypography.denseMetadata.fontWeight)
        assertNull(AppPrismTypography.denseMetadata.fontFamily)
        assertEquals(24.sp, AppPrismTypography.detailTitleCompact.fontSize)
        assertEquals(30.sp, AppPrismTypography.detailTitleCompact.lineHeight)
        assertNull(AppPrismTypography.detailTitleCompact.fontFamily)
        assertEquals(32.sp, AppPrismTypography.detailTitleExpanded.fontSize)
        assertEquals(38.sp, AppPrismTypography.detailTitleExpanded.lineHeight)
        assertNull(AppPrismTypography.detailTitleExpanded.fontFamily)
    }
}

private fun clockwiseHueDistance(from: Double, to: Double): Double = (to - from + 360.0) % 360.0
