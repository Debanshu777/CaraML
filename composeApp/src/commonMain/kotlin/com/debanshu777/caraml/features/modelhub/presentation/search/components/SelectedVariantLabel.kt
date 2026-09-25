package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import com.debanshu777.caraml.core.theme.AppTechnicalLabel

internal fun selectedVariantLabel(variant: String): AnnotatedString =
    buildAnnotatedString {
        append("Selected variant: ")
        withStyle(AppTechnicalLabel.toSpanStyle()) {
            append(variant)
        }
    }

@Composable
internal fun SelectedVariantLabel(variant: String) {
    Text(
        text = selectedVariantLabel(variant),
        style = MaterialTheme.typography.bodySmall,
    )
}
