package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun SettingFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    selectedIndicatorContentDescription: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = selectedIndicatorContentDescription,
                )
            }
        } else {
            null
        },
        enabled = enabled,
        modifier = modifier.heightIn(min = 48.dp),
    )
}
