package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.HorizontalDivider
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

@Composable
internal fun ModelDetailTabsBar(
    state: ModelDetailTabsState,
    onSelect: (Int) -> Unit,
    startInset: Dp,
    endInset: Dp,
    modifier: Modifier = Modifier,
    variantsLabel: String = "Variants",
) {
    Column(modifier.fillMaxWidth()) {
        HorizontalDivider(Modifier.fillMaxWidth().testTag("detail-section-divider"))
        ModelDetailTabs(listOf(variantsLabel, "Model card", "Technical details"), state, onSelect,
            Modifier.padding(start = startInset, end = endInset,
                top = AppTheme.spacing.spacing4, bottom = AppTheme.spacing.spacing8
            )
        )
    }
}

/** Flat labels wrap together at large text sizes so no destination is hidden offscreen. */
@Composable
internal fun ModelDetailTabs(
    labels: List<String>,
    state: ModelDetailTabsState,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val spacing = AppTheme.spacing
    val ink = AppTheme.colors.onSurface
    FlowRow(
        modifier.fillMaxWidth().selectableGroup().testTag("detail-tabs"),
        horizontalArrangement = Arrangement.spacedBy(spacing.spacing24),
        verticalArrangement = Arrangement.spacedBy(spacing.spacing4),
    ) {
        labels.forEachIndexed { index, label ->
            val selected = index == state.pager.currentPage
            Box(
                Modifier.focusRequester(state.focusRequesters[index]).onFocusChanged {
                    if (it.isFocused) state.focusedTab = index
                    else if (state.focusedTab == index) state.focusedTab = -1
                }.selectable(selected, role = Role.Tab, onClick = { onSelect(index) })
                    .heightIn(min = spacing.spacing48)
                    .drawWithContent {
                        drawContent()
                        if (selected) drawLine(ink,
                            Offset(1.dp.toPx(), size.height - 2.dp.toPx()),
                            Offset(size.width - 1.dp.toPx(), size.height - 2.dp.toPx()),
                            strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round
                        )
                    }
                    .padding(vertical = spacing.spacing12),
            ) {
                Text(label, style = AppTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    color = if (selected) ink else AppTheme.colors.onSurfaceVariant)
            }
        }
    }
}
