package com.debanshu777.caraml.core.performance

import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId

internal actual fun Modifier.exposeTestTagsForUiAutomation(): Modifier =
    semantics { testTagsAsResourceId = true }
