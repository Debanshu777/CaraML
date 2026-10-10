package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.debanshu777.caraml.core.theme.AppTheme
import com.debanshu777.caraml.core.ui.components.BrandButton
import com.debanshu777.caraml.core.ui.components.BrandButtonStyle
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel

@Composable
fun DownloadForLaterConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = AppTheme.shapes.extraLarge,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(AppTheme.colors),
        title = { Text("Download for later") },
        text = {
            Text("This model is not expected to run on this device. You can still download it for later use.")
        },
        confirmButton = {
            BrandButton(onClick = onConfirm) { Text("Download anyway") }
        },
        dismissButton = {
            BrandButton(style = BrandButtonStyle.Secondary, onClick = onDismiss) { Text("Cancel") }
        },
    )
}
