package com.debanshu777.caraml.core.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Material 3 roles use the platform font family with a slightly stronger title hierarchy. */
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

/** Explicit size and weight scale for styles outside Material 3's standard roles. */
@Immutable
data class AppTypeScale(
    val displayLarge: TextStyle = AppTypography.displayLarge,
    val displayMedium: TextStyle = AppTypography.displayMedium,
    val displaySmall: TextStyle = AppTypography.displaySmall,
    val headingLarge: TextStyle = AppTypography.headlineSmall,
    val headingBase: TextStyle = AppTypography.titleLarge,
    val headingSmall: TextStyle = AppTypography.titleMedium,
    val headingXSmall: TextStyle = AppTypography.titleSmall,
    val bodyLarge: TextStyle = AppTypography.bodyLarge,
    val bodyBase: TextStyle = AppTypography.bodyMedium,
    val bodySmall: TextStyle = AppTypography.bodySmall,
    val labelLarge: TextStyle = AppTypography.labelLarge,
    val labelBase: TextStyle = AppTypography.labelMedium,
    val labelSmall: TextStyle = AppTypography.labelSmall,
    val heading32: TextStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 32.sp, lineHeight = 38.sp),
    val heading28: TextStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 34.sp),
    val heading24: TextStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 30.sp),
    val heading16: TextStyle = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp, lineHeight = 22.sp),
    val body17: TextStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    val body14: TextStyle = TextStyle(fontWeight = FontWeight.Normal, fontSize = 14.sp, lineHeight = 20.sp),
    val label12: TextStyle = TextStyle(fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    val numeric12: TextStyle = label12.copy(fontFeatureSettings = "tnum"),
    val technical12: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    val bodySmallMedium: TextStyle = AppTypography.bodySmall.copy(fontWeight = FontWeight.Medium),
    val bodySmallItalic: TextStyle = AppTypography.bodySmall.copy(fontStyle = FontStyle.Italic),
    val bodyLargeCode: TextStyle = AppTypography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    val bodyLargeQuote: TextStyle = AppTypography.bodyLarge.copy(fontStyle = FontStyle.Italic),
    val headingLargeMono: TextStyle = AppTypography.headlineSmall.copy(fontFamily = FontFamily.Monospace),
)
