package com.debanshu777.caraml.core.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.materialkolor.hct.Hct

/** Character and illustration colors. Use [ink] on the pastels in either appearance. */
@Immutable
data class AppBrandColors(
    val accent: Color = Color(0xFFFF7854),
    val yellow: Color = Color(0xFFF6CD57),
    val lilac: Color = Color(0xFFC7BAFF),
    val mint: Color = Color(0xFFB7E1C4),
    val ink: Color = Color(0xFF262524),
) {
    fun positiveText(isDark: Boolean): Color = if (isDark) mint else Color(0xFF305B40)
    fun cautionText(isDark: Boolean): Color = if (isDark) Color(0xFFEFCC72) else Color(0xFF735518)
}

internal val LocalAppBrandColors = staticCompositionLocalOf { AppBrandColors() }
internal val LocalSoftEffects = staticCompositionLocalOf { true }
internal val LocalAppActionColor = staticCompositionLocalOf<Color?> { null }

/** Raw seed colors belong on filled controls; text actions need a contrast-safe tonal variant. */
internal fun actionColor(seed: Color, isDark: Boolean): Color {
    val hct = Hct.fromInt(seed.toArgb())
    return Color(Hct.from(hct.hue, hct.chroma, if (isDark) 80.0 else 35.0).toInt())
}

/** Warm neutral surfaces remain readable as the user changes their accent seed. */
internal fun ColorScheme.withBrandSurfaces(isDark: Boolean): ColorScheme = if (isDark) {
    copy(
        background = Color(0xFF222222),
        onBackground = Color(0xFFF8F5ED),
        surface = Color(0xFF303030),
        onSurface = Color(0xFFF8F5ED),
        onSurfaceVariant = Color(0xFFBFBAB1),
        surfaceDim = Color(0xFF222222),
        surfaceBright = Color(0xFF303030),
        surfaceContainerLowest = Color(0xFF191919),
        surfaceContainerLow = Color(0xFF191919),
        surfaceContainer = Color(0xFF303030),
        surfaceContainerHigh = Color(0xFF303030),
        surfaceContainerHighest = Color(0xFF383838),
        outline = Color(0xFFBFBAB1),
        outlineVariant = Color(0xFF504C46),
    )
} else {
    copy(
        background = Color(0xFFF8F6EF),
        onBackground = Color(0xFF262524),
        surface = Color.White,
        onSurface = Color(0xFF262524),
        onSurfaceVariant = Color(0xFF6C6861),
        surfaceDim = Color(0xFFF8F6EF),
        surfaceBright = Color.White,
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFECE9E1),
        surfaceContainer = Color.White,
        surfaceContainerHigh = Color.White,
        surfaceContainerHighest = Color(0xFFECE9E1),
        outline = Color(0xFF6C6861),
        outlineVariant = Color(0xFFDDD8CF),
    )
}
