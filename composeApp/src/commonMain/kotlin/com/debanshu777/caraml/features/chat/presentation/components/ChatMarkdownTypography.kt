package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.m3.markdownTypography
import com.debanshu777.caraml.core.theme.AppTheme

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
    val t = AppTheme.typography
    return markdownTypography(
        h1 = t.headingLarge,
        h2 = t.headingBase,
        h3 = t.headingSmall,
        h4 = t.headingXSmall,
        h5 = t.headingXSmall,
        h6 = t.headingXSmall,
        text = t.conversationBody,
        paragraph = t.conversationBody,
        list = t.conversationBody,
        ordered = t.conversationBody,
        bullet = t.conversationBody,
        code = AppTheme.typography.bodyLargeCode,
        inlineCode = AppTheme.typography.bodyLargeCode,
        quote = AppTheme.typography.bodyLargeQuote,
    )
}
