package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.CommandSurface

@Composable
fun SearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onClear: () -> Unit = { onQueryChange("") },
    modifier: Modifier = Modifier,
    errorMessage: String? = null,
) {
    var focused by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
    CommandSurface(
        focused = focused,
        active = query.isNotBlank(),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("model-command"),
        contentPadding = PaddingValues(horizontal = AppTheme.spacing.spacing12, vertical = AppTheme.spacing.spacing4),
        idleContainerColor = AppTheme.colors.surfaceContainerHigh,
        idleBorderColor = AppTheme.colors.outlineVariant,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = AppTheme.spacing.spacing48),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Default.Search,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.spacing.spacing24),
                tint = AppTheme.colors.onSurfaceVariant,
            )
            Spacer(Modifier.width(AppTheme.spacing.spacing12))
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { focused = it.isFocused },
                textStyle = AppTheme.typography.bodyLarge.copy(
                    color = AppTheme.colors.onSurface,
                ),
                singleLine = true,
                cursorBrush = SolidColor(AppTheme.colors.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                decorationBox = { innerTextField ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) {
                            Text(
                                text = "Search models",
                                style = AppTheme.typography.bodyLarge,
                                color = AppTheme.colors.onSurfaceVariant,
                            )
                        }
                        innerTextField()
                    }
                },
            )
            if (query.isNotEmpty()) {
                IconButton(onClick = onClear, modifier = Modifier.size(AppTheme.spacing.spacing48)) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Clear model search",
                    )
                }
            }
        }
    }
    if (errorMessage != null) {
        Text(
            text = errorMessage,
            style = AppTheme.typography.bodySmall,
            color = AppTheme.colors.error,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
    }
}
