package com.debanshu777.caraml.features.chat.presentation.components.preview.providers

import androidx.compose.ui.tooling.preview.PreviewParameterProvider

class ErrorMessagePreviewProvider : PreviewParameterProvider<String> {
    override val values: Sequence<String> = sequenceOf(
        "Model failed to load",
        "Failed to load model.\nPlease check your connection and try again.",
        "An unexpected error occurred while initializing the model. " +
            "This could be due to insufficient memory, corrupted model files, " +
            "or compatibility issues with your device. Please try downloading " +
            "the model again or selecting a different model."
    )
}
