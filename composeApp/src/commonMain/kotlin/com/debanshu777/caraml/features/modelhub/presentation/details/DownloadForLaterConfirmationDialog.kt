package com.debanshu777.caraml.features.modelhub.presentation.details

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import com.debanshu777.caraml.core.theme.prismShapes
import com.debanshu777.caraml.core.theme.AuroraSurfaceLevel

@Composable
fun DownloadForLaterConfirmationDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.prismShapes.modal,
        containerColor = AuroraSurfaceLevel.Floating.containerColor(MaterialTheme.colorScheme),
        title = { Text("Download for later") },
        text = {
            Text("This model is not expected to run on this device. You can still download it for later use.")
        },
        confirmButton = {
            Button(onClick = onConfirm) { Text("Download anyway") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}
