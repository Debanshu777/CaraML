package com.debanshu777.caraml.core.rating.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.rating.SuitabilityRating

/**
 * Small filled circle indicator. Used inside variant picker chips where a
 * full [SuitabilityChip] would crowd the layout — the legend on the search
 * screen explains what each color means.
 */
@Composable
fun SuitabilityDot(
    rating: SuitabilityRating,
    modifier: Modifier = Modifier,
    size: Dp = 8.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(rating.foregroundColor()),
    )
}
