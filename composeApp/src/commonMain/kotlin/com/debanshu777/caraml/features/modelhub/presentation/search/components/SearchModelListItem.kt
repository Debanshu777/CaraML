package com.debanshu777.caraml.features.modelhub.presentation.search.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.model.SearchModelsResponse

@Composable
fun SearchModelListItem(
    model: SearchModelsResponse.Model?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (model == null) return
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = model.id ?: "Unknown",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = buildString {
                    append("Trending weight: ${model.trendingWeight ?: 0}")
                    if (model.`private` == true) {
                        append(" • Private")
                    }
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
    HorizontalDivider()
}
