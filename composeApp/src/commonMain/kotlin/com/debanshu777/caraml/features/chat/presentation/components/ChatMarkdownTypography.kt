package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.m3.markdownTypography

/**
 * Typography for assistant chat bubbles rendered as Markdown.
 *
 * Maps Markdown semantic roles onto Material 3 type roles:
 *   h1 -> headlineSmall
 *   h2 -> titleLarge
 *   h3 -> titleMedium
 *   h4/h5/h6 -> titleSmall
 *   paragraph / text / lists -> bodyLarge
 *   inline & block code -> bodyLarge with monospace
 *   quote -> bodyLarge italic
 */
@Composable
fun chatMarkdownTypography(): MarkdownTypography {
    val t = MaterialTheme.typography
    return markdownTypography(
        h1 = t.headlineSmall,
        h2 = t.titleLarge,
        h3 = t.titleMedium,
        h4 = t.titleSmall,
        h5 = t.titleSmall,
        h6 = t.titleSmall,
        text = t.bodyLarge,
        paragraph = t.bodyLarge,
        list = t.bodyLarge,
        ordered = t.bodyLarge,
        bullet = t.bodyLarge,
        code = t.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        inlineCode = t.bodyLarge.copy(fontFamily = FontFamily.Monospace),
        quote = t.bodyLarge.copy(fontStyle = FontStyle.Italic),
    )
}
