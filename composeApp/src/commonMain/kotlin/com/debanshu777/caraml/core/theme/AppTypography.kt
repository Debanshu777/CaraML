package com.debanshu777.caraml.core.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import caraml.composeapp.generated.resources.Res
import caraml.composeapp.generated.resources.bricolage_grotesque_600
import caraml.composeapp.generated.resources.bricolage_grotesque_700
import caraml.composeapp.generated.resources.bricolage_grotesque_800
import caraml.composeapp.generated.resources.dm_sans_400
import caraml.composeapp.generated.resources.dm_sans_500
import caraml.composeapp.generated.resources.dm_sans_600
import caraml.composeapp.generated.resources.dm_sans_700
import caraml.composeapp.generated.resources.dm_sans_italic_400
import org.jetbrains.compose.resources.Font

/** Bundled, offline typography: expressive headings with a quiet, readable interface. */
val AppTypography: Typography
    @Composable
    get() {
        val headingFamily = FontFamily(
            Font(Res.font.bricolage_grotesque_600, FontWeight.SemiBold),
            Font(Res.font.bricolage_grotesque_700, FontWeight.Bold),
            Font(Res.font.bricolage_grotesque_800, FontWeight.ExtraBold),
        )
        val bodyFamily = FontFamily(
            Font(Res.font.dm_sans_400, FontWeight.Normal),
            Font(Res.font.dm_sans_500, FontWeight.Medium),
            Font(Res.font.dm_sans_600, FontWeight.SemiBold),
            Font(Res.font.dm_sans_700, FontWeight.Bold),
            Font(Res.font.dm_sans_italic_400, FontWeight.Normal, FontStyle.Italic),
        )
        return remember(headingFamily, bodyFamily) { appTypography(headingFamily, bodyFamily) }
    }

internal fun appTypography(headingFamily: FontFamily, bodyFamily: FontFamily): Typography = Typography().run {
    copy(
        displayLarge = displayLarge.copy(fontFamily = headingFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp),
        displayMedium = displayMedium.copy(fontFamily = headingFamily, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp),
        displaySmall = displaySmall.copy(fontFamily = headingFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp),
        headlineLarge = headlineLarge.copy(fontFamily = headingFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.7).sp),
        headlineMedium = headlineMedium.copy(fontFamily = headingFamily, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp),
        headlineSmall = headlineSmall.copy(fontFamily = headingFamily, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleLarge = titleLarge.copy(fontFamily = headingFamily, fontWeight = FontWeight.SemiBold),
        titleMedium = titleMedium.copy(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleSmall = titleSmall.copy(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        bodyLarge = bodyLarge.copy(fontFamily = bodyFamily, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontFamily = bodyFamily, fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontFamily = bodyFamily, fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontFamily = bodyFamily, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontFamily = bodyFamily, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontFamily = bodyFamily, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
    )
}

/** All non-Material roles derive from the same families; technical text stays monospace. */
@Immutable
data class AppTypeScale internal constructor(
    private val material: Typography = Typography(),
    val displayLarge: TextStyle = material.displayLarge,
    val displayMedium: TextStyle = material.displayMedium,
    val displaySmall: TextStyle = material.displaySmall,
    val headingLarge: TextStyle = material.headlineSmall,
    val headingBase: TextStyle = material.titleLarge,
    val headingSmall: TextStyle = material.titleMedium,
    val headingXSmall: TextStyle = material.titleSmall,
    val bodyLarge: TextStyle = material.bodyLarge,
    val bodyBase: TextStyle = material.bodyMedium,
    val bodySmall: TextStyle = material.bodySmall,
    val labelLarge: TextStyle = material.labelLarge,
    val labelBase: TextStyle = material.labelMedium,
    val labelSmall: TextStyle = material.labelSmall,
    val sidebarLabel16: TextStyle = material.bodyLarge.copy(
        fontSize = 16.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    val sidebarWordmark25: TextStyle = material.titleLarge.copy(
        fontSize = 25.sp, lineHeight = 25.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.2).sp,
    ),
    val heroTitle: TextStyle = material.displayMedium.copy(
        fontSize = 34.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.8).sp,
    ),
    val heroTitleCompact: TextStyle = heroTitle.copy(fontSize = 30.sp, lineHeight = 34.sp),
    val pageTitle: TextStyle = material.headlineSmall.copy(
        fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp,
    ),
    val itemTitle: TextStyle = material.titleMedium.copy(
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    val activityTitle: TextStyle = material.titleMedium.copy(
        fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp,
    ),
    val stateTitle: TextStyle = material.titleLarge.copy(
        fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp,
    ),
    val sectionTitle: TextStyle = material.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
    val preferenceTitle: TextStyle = material.titleMedium,
    val conversationBody: TextStyle = material.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
    val heading32: TextStyle = material.headlineLarge.copy(fontSize = 32.sp, lineHeight = 38.sp),
    val heading28: TextStyle = material.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp),
    val heading24: TextStyle = material.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp),
    val heading16: TextStyle = material.titleMedium.copy(fontSize = 16.sp, lineHeight = 22.sp),
    val body17: TextStyle = material.bodyLarge.copy(fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 22.sp),
    val body14: TextStyle = material.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp),
    val label12: TextStyle = material.labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp),
    val numeric12: TextStyle = label12.copy(fontFeatureSettings = "tnum"),
    val technical12: TextStyle = TextStyle(
        fontFamily = FontFamily.Monospace,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 17.sp,
    ),
    val bodySmallMedium: TextStyle = material.bodySmall.copy(fontWeight = FontWeight.Medium),
    val bodySmallItalic: TextStyle = material.bodySmall.copy(fontStyle = FontStyle.Italic),
    val bodyLargeCode: TextStyle = material.bodyLarge.copy(fontFamily = FontFamily.Monospace),
    val bodyLargeQuote: TextStyle = material.bodyLarge.copy(fontStyle = FontStyle.Italic),
    val headingLargeMono: TextStyle = material.headlineSmall.copy(fontFamily = FontFamily.Monospace),
)

internal val LocalAppTypeScale = staticCompositionLocalOf<AppTypeScale?> { null }
