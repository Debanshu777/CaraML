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
    fun appThemeShapeAndTypeTokensMatchTheSharedVocabulary() {
        assertEquals(RoundedCornerShape(8.dp), AppShapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), AppShapes.small)
        assertEquals(RoundedCornerShape(18.dp), AppShapes.medium)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.large)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.extraLarge)
        assertEquals(8.dp, AppCornerRadii.radius8)
        assertEquals(12.dp, AppCornerRadii.radius12)
        assertEquals(18.dp, AppCornerRadii.radius18)
        assertEquals(24.dp, AppCornerRadii.radius24)
        assertEquals(FontFamily.Monospace, AppTheme.typography.technical12.fontFamily)
        assertEquals(FontWeight.Medium, AppTheme.typography.technical12.fontWeight)
        assertEquals(12.sp, AppTheme.typography.technical12.fontSize)
        assertEquals(17.sp, AppTheme.typography.technical12.lineHeight)

        assertEquals(28.sp, AppTheme.typography.heading28.fontSize)
        assertEquals(34.sp, AppTheme.typography.heading28.lineHeight)
        assertEquals(FontWeight.SemiBold, AppTheme.typography.heading28.fontWeight)
        assertNull(AppTheme.typography.heading28.fontFamily)
        assertEquals(16.sp, AppTheme.typography.heading16.fontSize)
        assertEquals(22.sp, AppTheme.typography.heading16.lineHeight)
        assertEquals(FontWeight.SemiBold, AppTheme.typography.heading16.fontWeight)
        assertNull(AppTheme.typography.heading16.fontFamily)
        assertEquals(17.sp, AppTheme.typography.body17.fontSize)
        assertEquals(22.sp, AppTheme.typography.body17.lineHeight)
        assertEquals(FontWeight.Medium, AppTheme.typography.body17.fontWeight)
        assertNull(AppTheme.typography.body17.fontFamily)
        assertEquals(14.sp, AppTheme.typography.body14.fontSize)
        assertEquals(20.sp, AppTheme.typography.body14.lineHeight)
        assertEquals(FontWeight.Normal, AppTheme.typography.body14.fontWeight)
        assertNull(AppTheme.typography.body14.fontFamily)
        assertEquals(24.sp, AppTheme.typography.heading24.fontSize)
        assertEquals(30.sp, AppTheme.typography.heading24.lineHeight)
        assertNull(AppTheme.typography.heading24.fontFamily)
        assertEquals(32.sp, AppTheme.typography.heading32.fontSize)
        assertEquals(38.sp, AppTheme.typography.heading32.lineHeight)
        assertNull(AppTheme.typography.heading32.fontFamily)
    }
}

private fun clockwiseHueDistance(from: Double, to: Double): Double = (to - from + 360.0) % 360.0
