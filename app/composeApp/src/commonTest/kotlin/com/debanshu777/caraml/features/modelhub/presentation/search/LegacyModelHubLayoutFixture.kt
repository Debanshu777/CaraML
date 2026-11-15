package com.debanshu777.caraml.features.modelhub.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandPageHeader

internal data class ModelHubFixtureChrome(
    val header: @Composable () -> Unit,
    val context: @Composable () -> Unit,
    val snackbarHostState: SnackbarHostState?,
)

@Composable
internal fun ModelHubScreenLayout(
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    sharedContext: (@Composable () -> Unit)? = null,
    modifier: Modifier = Modifier,
    snackbarHostState: SnackbarHostState? = null,
    discoverContent: @Composable (ModelHubFixtureChrome) -> Unit,
    libraryContent: @Composable (ModelHubFixtureChrome) -> Unit,
    downloadQueueEntry: (@Composable () -> Unit)? = null,
) {
    val tabs = listOf("Discover", "Library")
    val tabStateHolder = rememberSaveableStateHolder()
    val chrome = ModelHubFixtureChrome(
        header = {
            BrandPageHeader(title = "Models", modifier = Modifier.padding(bottom = AppTheme.spacing.spacing12))
            ModelHubTabRow(tabs = tabs, selectedTabIndex = selectedTabIndex, onTabSelected = onTabSelected)
        },
        context = {
            sharedContext?.invoke()
            downloadQueueEntry?.invoke()
        },
        snackbarHostState = snackbarHostState,
    )
    Box(modifier.fillMaxSize()) {
        tabStateHolder.SaveableStateProvider(selectedTabIndex) {
            when (selectedTabIndex) {
                0 -> discoverContent(chrome)
                1 -> libraryContent(chrome)
            }
        }
    }
}

@Composable
private fun ModelHubTabRow(
    tabs: List<String>,
    selectedTabIndex: Int,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(bottom = AppTheme.spacing.spacing12)
            .testTag("model-tabs")
            .clip(AppTheme.shapes.medium)
            .background(AppTheme.colors.surfaceContainerLow.copy(
                alpha = if (AppTheme.softEffects) AppTheme.effects.contextStripSurface else 1f,
            ))
            .padding(5.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        tabs.forEachIndexed { index, title ->
            val selected = selectedTabIndex == index
            Box(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = AppTheme.spacing.spacing48)
                    .clip(AppTheme.shapes.small)
                    .background(if (selected) AppTheme.colors.surfaceContainerLowest else Color.Transparent)
                    .selectable(
                        selected = selected,
                        onClick = { onTabSelected(index) },
                        role = Role.Tab,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = title,
                    style = AppTheme.typography.labelLarge,
                    color = if (selected) {
                        AppTheme.colors.onSurface
                    } else {
                        AppTheme.colors.onSurfaceVariant
                    },
                )
            }
        }
    }
}
