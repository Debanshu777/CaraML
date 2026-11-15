package com.debanshu777.caraml.features.modelhub.presentation.search.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
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
import androidx.compose.material3.Icon
import com.debanshu777.caraml.core.ui.components.BrandIconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
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
    placeholder: String = "Find your next little brain",
    focusRequester: FocusRequester? = null,
    compact: Boolean = false,
    trailingAction: (@Composable () -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }

    Column(modifier = modifier.fillMaxWidth()) {
        CommandSurface(
            focused = focused,
            active = query.isNotBlank(),
            modifier = Modifier
                .fillMaxWidth()
                .testTag("model-command"),
            contentPadding = PaddingValues(
                start = AppTheme.spacing.spacing12,
                end = if (trailingAction != null) AppTheme.spacing.spacing4 else AppTheme.spacing.spacing12,
                top = if (compact) 0.dp else AppTheme.spacing.spacing4,
                bottom = if (compact) 0.dp else AppTheme.spacing.spacing4,
            ),
            idleContainerColor = AppTheme.colors.surface.copy(
                alpha = if (AppTheme.softEffects) AppTheme.effects.workspaceGlyphSurface else 1f,
            ),
            idleBorderColor = AppTheme.auroraColors.paneBorder,
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = AppTheme.spacing.spacing48),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = AppIcons.Search,
                    contentDescription = null,
                    modifier = Modifier.size(if (compact) 20.dp else AppTheme.spacing.spacing24),
                    tint = AppTheme.colors.onSurfaceVariant,
                )
                Spacer(Modifier.width(if (compact) AppTheme.spacing.spacing8 else AppTheme.spacing.spacing12))
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    modifier = Modifier
                        .weight(1f)
                        .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                        .semantics { contentDescription = placeholder }
                        .onFocusChanged { focused = it.isFocused },
                    textStyle = (if (compact) AppTheme.typography.bodyBase else AppTheme.typography.bodyLarge).copy(
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
                                    text = placeholder,
                                    style = if (compact) AppTheme.typography.bodyBase else AppTheme.typography.bodyLarge,
                                    maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    color = AppTheme.colors.onSurfaceVariant,
                                )
                            }
                            innerTextField()
                        }
                    },
                )
                if (query.isNotEmpty()) {
                    BrandIconButton(onClick = onClear, modifier = Modifier.size(AppTheme.spacing.spacing48)) {
                        Icon(
                            imageVector = AppIcons.Close,
                            contentDescription = "Clear model search",
                            tint = AppTheme.colors.onSurfaceVariant,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                trailingAction?.invoke()
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
