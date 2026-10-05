package com.debanshu777.caraml.core.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.test.Test
import kotlin.test.assertEquals

class AuroraColorsTest {
    @Test
    fun customSeedPreservesTheApprovedLilacAndMintCompanions() {
        val seed = Color(0xFFEFD04B)
        val scheme = darkColorScheme(
            primary = Color(0xFFDDB8F7),
            secondary = Color(0xFFD0C86F),
            tertiary = Color(0xFFB9CB7B),
        )

        val colors = scheme.toAuroraColors(isDark = true, focalSeed = seed)
        assertEquals(seed.copy(alpha = 0.78f), colors.focusPrimary)
        assertEquals(seed.copy(alpha = 0.40f), colors.primaryGlow)
        assertEquals(Color.Black, colors.onFocusPrimary)
        assertEquals(Color(0xFFC7BAFF).copy(alpha = 0.74f), colors.focusSecondary)
        assertEquals(Color(0xFFB7E1C4).copy(alpha = 0.70f), colors.focusTertiary)
    }

    @Test
    fun auroraUsesTheApprovedPaletteAndSeparatesCanvasFromSurfaces() {
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

        assertEquals(scheme.background, colors.canvas)
        assertEquals(scheme.primary.copy(alpha = 0.40f), colors.primaryGlow)
        assertEquals(Color(0xFFC7BAFF).copy(alpha = 0.40f), colors.secondaryGlow)
        assertEquals(Color(0xFFB7E1C4).copy(alpha = 0.32f), colors.tertiaryGlow)
        assertEquals(scheme.onSurface.copy(alpha = 0.022f), colors.grainTint)
        assertEquals(Color(0x0A242020), colors.edgeVignette)
        assertEquals(scheme.outlineVariant, colors.paneBorder)
        assertEquals(scheme.surface, colors.commandSurface)
        assertEquals(scheme.surfaceContainerHigh, colors.selectedSurface)
        assertEquals(scheme.outlineVariant, colors.divider)
        assertEquals(scheme.primary.copy(alpha = 0.78f), colors.focusPrimary)
        assertEquals(0.74f, colors.focusSecondary.alpha, 0.005f)
        assertEquals(0.70f, colors.focusTertiary.alpha, 0.005f)
    }

    @Test
    fun darkAuroraKeepsTheSameBrandColorsAndAddsTheDarkVignette() {
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

        assertEquals(scheme.primary.copy(alpha = 0.40f), colors.primaryGlow)
        assertEquals(Color(0xFFC7BAFF).copy(alpha = 0.40f), colors.secondaryGlow)
        assertEquals(Color(0xFFB7E1C4).copy(alpha = 0.32f), colors.tertiaryGlow)
        assertEquals(scheme.onSurface.copy(alpha = 0.022f), colors.grainTint)
        assertEquals(Color(0x20000000), colors.edgeVignette)
    }

    @Test
    fun everySurfaceLevelMapsToOneMaterialRole() {
        val scheme = lightColorScheme()

        assertEquals(scheme.background, AuroraSurfaceLevel.Canvas.containerColor(scheme))
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
        val typography = AppTypeScale(appTypography(FontFamily.Serif, FontFamily.SansSerif))
        assertEquals(RoundedCornerShape(8.dp), AppShapes.extraSmall)
        assertEquals(RoundedCornerShape(12.dp), AppShapes.small)
        assertEquals(RoundedCornerShape(18.dp), AppShapes.medium)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.large)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.extraLarge)
        assertEquals(8.dp, AppCornerRadii.radius8)
        assertEquals(12.dp, AppCornerRadii.radius12)
        assertEquals(18.dp, AppCornerRadii.radius18)
        assertEquals(24.dp, AppCornerRadii.radius24)
        assertEquals(FontFamily.Monospace, typography.technical12.fontFamily)
        assertEquals(FontWeight.Medium, typography.technical12.fontWeight)
        assertEquals(12.sp, typography.technical12.fontSize)
        assertEquals(17.sp, typography.technical12.lineHeight)

        assertEquals(28.sp, typography.heading28.fontSize)
        assertEquals(34.sp, typography.heading28.lineHeight)
        assertEquals(FontWeight.Bold, typography.heading28.fontWeight)
        assertEquals(FontFamily.Serif, typography.heading28.fontFamily)
        assertEquals(16.sp, typography.heading16.fontSize)
        assertEquals(22.sp, typography.heading16.lineHeight)
        assertEquals(FontWeight.SemiBold, typography.heading16.fontWeight)
        assertEquals(FontFamily.Serif, typography.heading16.fontFamily)
        assertEquals(17.sp, typography.body17.fontSize)
        assertEquals(22.sp, typography.body17.lineHeight)
        assertEquals(FontWeight.Medium, typography.body17.fontWeight)
        assertEquals(FontFamily.SansSerif, typography.body17.fontFamily)
        assertEquals(14.sp, typography.body14.fontSize)
        assertEquals(20.sp, typography.body14.lineHeight)
        assertEquals(FontWeight.Normal, typography.body14.fontWeight)
        assertEquals(FontFamily.SansSerif, typography.body14.fontFamily)
        assertEquals(24.sp, typography.heading24.fontSize)
        assertEquals(30.sp, typography.heading24.lineHeight)
        assertEquals(FontFamily.Serif, typography.heading24.fontFamily)
        assertEquals(32.sp, typography.heading32.fontSize)
        assertEquals(38.sp, typography.heading32.lineHeight)
        assertEquals(FontFamily.Serif, typography.heading32.fontFamily)
        assertEquals(42.sp, typography.hero42.fontSize)
        assertEquals(42.84.sp, typography.hero42.lineHeight)
        assertEquals(FontWeight.ExtraBold, typography.hero42.fontWeight)
        assertEquals(36.sp, typography.hero36Compact.fontSize)
        assertEquals(32.sp, typography.pageTitle32.fontSize)
        assertEquals(44.sp, typography.pageTitle32.lineHeight)
        assertEquals(21.sp, typography.modelTitle21.fontSize)
        assertEquals(25.2.sp, typography.modelTitle21.lineHeight)
        assertEquals(FontFamily.Serif, typography.modelTitle21.fontFamily)
    }
}
