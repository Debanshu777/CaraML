package com.debanshu777.caraml.core.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * App typography aligned to the Material 3 type scale.
 *
 * We do **not** override the M3 default font family — staying on the platform
 * default (Roboto on Android, System on iOS, default on Desktop) keeps text
 * crisp without bundling extra fonts and aligns with the MD3 spec
 * (Roboto / Roboto Flex is the recommended default).
 *
 * Customizations we *do* make:
 * - `titleLarge`, `titleMedium`, and `labelLarge` lifted to `SemiBold` to give cards/buttons
 *   a clearer hierarchy in dense screens (chat, model lists). This replaces
 *   ad-hoc `FontWeight.SemiBold` / `Medium` overrides scattered through the UI.
 */
val AppTypography: Typography = Typography().run {
    copy(
        titleLarge = titleLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        titleMedium = titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        labelLarge = labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        bodyLarge = bodyLarge.copy(
            lineHeight = 24.sp,
        ),
        bodyMedium = bodyMedium.copy(
            lineHeight = 20.sp,
        ),
    )
}

/** Helper for callers that need a numeric-emphasis style (stats, counters). */
val AppNumericLabel: TextStyle = TextStyle(
    fontWeight = FontWeight.Medium,
    fontSize = 12.sp,
    lineHeight = 16.sp,
    fontFeatureSettings = "tnum",
)

/** Semantic type roles for the dense Prism workbench hierarchy. */
@Immutable
data class PrismTypography(
    val screenTitle: TextStyle,
    val modelTitle: TextStyle,
    val denseMetadata: TextStyle,
    val technicalLabel: TextStyle,
    val detailTitleCompact: TextStyle,
    val detailTitleExpanded: TextStyle,
)

val AppPrismTypography: PrismTypography = PrismTypography(
    screenTitle = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 28.sp,
        lineHeight = 34.sp,
    ),
    modelTitle = TextStyle(
        fontWeight = FontWeight.Medium,
        fontSize = 17.sp,
        lineHeight = 22.sp,
    ),
    denseMetadata = TextStyle(
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
    ),
    technicalLabel = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp,
    ),
    detailTitleCompact = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 24.sp,
        lineHeight = 30.sp,
    ),
    detailTitleExpanded = TextStyle(
        fontWeight = FontWeight.SemiBold,
        fontSize = 32.sp,
        lineHeight = 38.sp,
    ),
)

/** Semantic Prism roles layered on top of the platform-default Material family. */
val Typography.prism: PrismTypography
    get() = AppPrismTypography

/** Source-compatible alias for machine data outside the shared workbench rows. */
val AppTechnicalLabel: TextStyle = AppPrismTypography.technicalLabel
