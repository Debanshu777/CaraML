package com.debanshu777.caraml.features.chat.presentation.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu

@Preview
@Composable
private fun ModelSelectorTopBarPreview() {
    MaterialTheme {
        Surface {
            ModelSelectorTopBar(modifier = Modifier.fillMaxWidth())
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelSelectorTopBar(
    modifier: Modifier = Modifier,
    onMenuClick: () -> Unit = {},
) {
    TopAppBar(
        modifier = modifier,
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Open menu")
            }
        },
        title = {
            Text(
                text = "Chat",
                style = MaterialTheme.typography.titleLarge
            )
        }
    )
}
