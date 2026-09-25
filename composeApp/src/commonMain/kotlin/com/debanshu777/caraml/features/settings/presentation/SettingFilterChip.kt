package com.debanshu777.caraml.features.settings.presentation

import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.auroraColors
import com.debanshu777.caraml.core.theme.prismShapes

@Composable
internal fun SettingFilterChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    selectedIndicatorTestTag: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = MaterialTheme.auroraColors
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = if (selected) {
            {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    modifier = Modifier.testTag(selectedIndicatorTestTag),
                )
            }
        } else {
            null
        },
        enabled = enabled,
        modifier = modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .semantics { role = Role.RadioButton },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = colors.focusPrimary,
            selectedLabelColor = colors.onFocusPrimary,
            selectedLeadingIconColor = colors.onFocusPrimary,
        ),
        shape = MaterialTheme.prismShapes.control,
    )
}
