package com.debanshu777.caraml.core.theme

import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
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

        val colors = scheme.toAuroraColors()

        assertEquals(scheme.surface, colors.canvas)
        assertEquals(scheme.primaryContainer.copy(alpha = 0.34f), colors.primaryGlow)
        assertEquals(scheme.tertiaryContainer.copy(alpha = 0.22f), colors.tertiaryGlow)
        assertEquals(scheme.outlineVariant.copy(alpha = 0.72f), colors.paneBorder)
    }

    @Test
    fun everySurfaceLevelMapsToOneMaterialRole() {
        val scheme = lightColorScheme()

        assertEquals(scheme.surface, AuroraSurfaceLevel.Canvas.containerColor(scheme))
        assertEquals(scheme.surfaceContainerLow, AuroraSurfaceLevel.Recessed.containerColor(scheme))
        assertEquals(scheme.surfaceContainer, AuroraSurfaceLevel.Pane.containerColor(scheme))
        assertEquals(scheme.surfaceContainerHigh, AuroraSurfaceLevel.Floating.containerColor(scheme))
    }
}
