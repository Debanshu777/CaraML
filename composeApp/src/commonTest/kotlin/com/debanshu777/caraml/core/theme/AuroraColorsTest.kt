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
    fun auroraColorsComeFromSemanticSchemeRoles() {
        val scheme = lightColorScheme(
            surface = Color(0xFF101010),
            primaryContainer = Color(0xFF223344),
            tertiaryContainer = Color(0xFF556677),
            outlineVariant = Color(0xFF8899AA),
        )

        val colors = scheme.toAuroraColors(isDark = false)

        assertEquals(scheme.surface, colors.canvas)
        assertEquals(scheme.primaryContainer.copy(alpha = 0.46f), colors.primaryGlow)
        assertEquals(scheme.tertiaryContainer.copy(alpha = 0.34f), colors.tertiaryGlow)
        assertEquals(scheme.outlineVariant.copy(alpha = 0.72f), colors.paneBorder)
        assertEquals(scheme.surfaceContainer, colors.commandSurface)
        assertEquals(scheme.surfaceContainerHigh, colors.selectedSurface)
        assertEquals(scheme.outlineVariant.copy(alpha = 0.48f), colors.divider)
        assertEquals(scheme.primary.copy(alpha = 0.78f), colors.focusPrimary)
        assertEquals(scheme.tertiary.copy(alpha = 0.70f), colors.focusTertiary)
    }

    @Test
    fun darkAuroraUsesVisibleSeedRolesInsteadOfDarkContainerRoles() {
        val scheme = darkColorScheme(
            surface = Color(0xFF101010),
            primary = Color(0xFF99BBFF),
            tertiary = Color(0xFFFFAADD),
            primaryContainer = Color(0xFF182030),
            tertiaryContainer = Color(0xFF301824),
        )

        val colors = scheme.toAuroraColors(isDark = true)

        assertEquals(scheme.primary.copy(alpha = 0.38f), colors.primaryGlow)
        assertEquals(scheme.tertiary.copy(alpha = 0.28f), colors.tertiaryGlow)
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
        assertEquals(FontFamily.Monospace, AppTechnicalLabel.fontFamily)
        assertEquals(FontWeight.Medium, AppTechnicalLabel.fontWeight)
        assertEquals(12.sp, AppTechnicalLabel.fontSize)
        assertEquals(17.sp, AppTechnicalLabel.lineHeight)
    }
}
