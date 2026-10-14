package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.debanshu777.caraml.core.theme.AppTheme

internal fun selectedVariantLabel(variant: String, technicalStyle: SpanStyle): AnnotatedString =
    buildAnnotatedString {
        append("Selected variant: ")
        withStyle(technicalStyle) {
            append(variant)
        }
    }

@Composable
internal fun SelectedVariantLabel(variant: String) {
    Text(
        text = selectedVariantLabel(variant, AppTheme.typography.technical12.toSpanStyle()),
        style = AppTheme.typography.bodySmall,
    )
}
