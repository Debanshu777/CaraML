package com.debanshu777.caraml.core.ui.components

import com.debanshu777.caraml.core.ui.icons.AppIcons
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.debanshu777.caraml.core.theme.AppTheme

@Composable
fun StatusMark(
    label: String,
    contentDescription: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    containerColorOverride: Color? = null,
    contentColorOverride: Color? = null,
) {
    Surface(
        modifier = modifier.semantics(mergeDescendants = true) {
            stateDescription = contentDescription
        },
        shape = AppTheme.shapes.extraSmall,
        color = containerColorOverride ?: AppTheme.colors.secondaryContainer,
        contentColor = contentColorOverride ?: AppTheme.colors.onSecondaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = AppTheme.spacing.spacing8, vertical = AppTheme.spacing.spacing4),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(AppTheme.dimensions.size14),
            )
            Spacer(modifier = Modifier.width(AppTheme.spacing.spacing4))
            Text(
                text = label,
                style = AppTheme.typography.labelBase,
                modifier = Modifier.weight(1f, fill = false),
            )
        }
    }
}

@Preview(widthDp = 360, heightDp = 800)
@Composable
private fun StatusMarkPreview(){
    MaterialTheme {
        StatusMark(
            label = "Model downloaded",
            contentDescription = "Model downloaded",
            icon = AppIcons.CheckCircle,
        )
    }
}