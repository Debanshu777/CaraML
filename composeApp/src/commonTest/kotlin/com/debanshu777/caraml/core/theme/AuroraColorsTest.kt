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
import kotlin.test.assertNull

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
        assertEquals(RoundedCornerShape(24.dp), AppShapes.large)
        assertEquals(RoundedCornerShape(24.dp), AppShapes.extraLarge)
        assertEquals(FontFamily.Monospace, AppTechnicalLabel.fontFamily)
        assertEquals(FontWeight.Medium, AppTechnicalLabel.fontWeight)
        assertEquals(12.sp, AppTechnicalLabel.fontSize)
        assertEquals(16.sp, AppTechnicalLabel.lineHeight)

        assertEquals(28.sp, AppPrismTypography.screenTitle.fontSize)
        assertEquals(34.sp, AppPrismTypography.screenTitle.lineHeight)
        assertEquals(FontWeight.SemiBold, AppPrismTypography.screenTitle.fontWeight)
        assertNull(AppPrismTypography.screenTitle.fontFamily)
        assertEquals(17.sp, AppPrismTypography.modelTitle.fontSize)
        assertEquals(22.sp, AppPrismTypography.modelTitle.lineHeight)
        assertEquals(FontWeight.Medium, AppPrismTypography.modelTitle.fontWeight)
        assertNull(AppPrismTypography.modelTitle.fontFamily)
        assertEquals(13.sp, AppPrismTypography.denseMetadata.fontSize)
        assertEquals(18.sp, AppPrismTypography.denseMetadata.lineHeight)
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
