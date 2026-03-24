package com.debanshu777.caraml.core.drawer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun CustomDrawer(
    items: List<DrawerItem>,
    selectedItemId: String?,
    onItemClick: (DrawerItem) -> Unit,
    modifier: Modifier = Modifier,
    header: @Composable (() -> Unit)? = null,
    footer: @Composable (() -> Unit)? = null,
) {
    Column(
        modifier = modifier
            .fillMaxHeight()
            .fillMaxWidth(0.6f)
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        header?.let { headerContent ->
            Spacer(modifier = Modifier.height(24.dp))
            headerContent()
        }

        Spacer(modifier = Modifier.height(24.dp))

        items.forEach { item ->
            DrawerItemView(
                item = item,
                selected = item.id == selectedItemId,
                onClick = { onItemClick(item) },
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        Spacer(modifier = Modifier.weight(1f))

        footer?.let { footerContent ->
            footerContent()
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
