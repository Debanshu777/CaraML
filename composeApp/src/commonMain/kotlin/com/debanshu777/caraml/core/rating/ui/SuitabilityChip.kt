package com.debanshu777.caraml.core.rating.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.rating.SuitabilityRating

/**
 * Compact chip showing a model's [SuitabilityRating]. Optional info button
 * opens the bottom-sheet explainer.
 *
 * Layout: [colored dot] [label] [optional info icon]
 *
 * Pass null `onInfoClick` for a non-interactive chip (e.g. inside the variant
 * picker where a single shared sheet is opened elsewhere).
 */
@Composable
fun SuitabilityChip(
    rating: SuitabilityRating,
    modifier: Modifier = Modifier,
    onInfoClick: (() -> Unit)? = null,
) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.small,
        color = rating.containerColor(),
        tonalElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier
                .let { if (onInfoClick != null) it.clickable { onInfoClick() } else it }
                .padding(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(rating.foregroundColor()),
            )
            Text(
                text = rating.shortLabel(),
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = rating.onContainerColor(),
            )
            if (onInfoClick != null) {
                Icon(
                    imageVector = Icons.Outlined.Info,
                    contentDescription = "About this rating",
                    tint = rating.onContainerColor(),
                    modifier = Modifier.size(14.dp),
                )
            }
        }
    }
}
