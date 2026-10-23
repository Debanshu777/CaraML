package com.debanshu777.caraml.core.performance

import androidx.compose.ui.Modifier

/** Dialogs create a separate semantics root from the platform activity content. */
internal expect fun Modifier.exposeTestTagsForUiAutomation(): Modifier
