package com.debanshu777.caraml.core.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
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
 * - `titleMedium` and `labelLarge` lifted to `SemiBold` to give cards/buttons
 *   a clearer hierarchy in dense screens (chat, model lists). This replaces
 *   ad-hoc `FontWeight.SemiBold` / `Medium` overrides scattered through the UI.
 */
val AppTypography: Typography = Typography().run {
    copy(
        titleMedium = titleMedium.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        labelLarge = labelLarge.copy(
            fontWeight = FontWeight.SemiBold,
        ),
        // Slightly tighter body for chat-dense layouts; keeps readability while
        // letting more content fit on small screens.
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
)
