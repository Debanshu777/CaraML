package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.debanshu777.caraml.core.ui.components.CaraMLTopBar
import com.debanshu777.caraml.core.ui.components.TopBarNavigation

@Preview
@Composable
private fun ModelSelectorTopBarPreview() {
    MaterialTheme {
        Surface {
            ModelSelectorTopBar(modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ModelSelectorTopBar(
    title: String = "Assistant",
    modifier: Modifier = Modifier,
    onMenuClick: () -> Unit = {},
) {
    CaraMLTopBar(
        title = title,
        navigation = TopBarNavigation.Menu,
        onNavigationClick = onMenuClick,
        modifier = modifier,
    )
}
