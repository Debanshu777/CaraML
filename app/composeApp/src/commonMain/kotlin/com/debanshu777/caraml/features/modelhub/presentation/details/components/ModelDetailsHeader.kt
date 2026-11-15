package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandNavigationButton
import com.debanshu777.caraml.core.ui.icons.AppIcons

@Composable
internal fun ModelDetailsHeader(
    modelId: String,
    showTitle: Boolean,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().heightIn(min = AppTheme.spacing.spacing48),
        horizontalArrangement = Arrangement.spacedBy(AppTheme.spacing.spacing12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BrandNavigationButton(onClick = onBack, navigationIcon = AppIcons.Back,
            contentDescription = "Navigate back")
        // Reserve the same line at every scroll position, including large font scales.
        // Revealing it must not resize the chrome and move the overview back into view.
        Text(
            text = modelId.substringAfter('/'),
            style = AppTheme.typography.pageTitle,
            color = AppTheme.colors.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).alpha(if (showTitle) 1f else 0f).then(
                if (showTitle) Modifier.testTag("detail-collapsed-title").semantics {
                    heading()
                    contentDescription = modelId
                } else Modifier.clearAndSetSemantics { },
            ),
        )
    }
}
