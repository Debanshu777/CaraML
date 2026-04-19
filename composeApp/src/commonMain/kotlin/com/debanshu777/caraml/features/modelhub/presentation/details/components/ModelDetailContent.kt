package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState
import com.debanshu777.caraml.features.modelhub.presentation.search.SetupComponentUiState
import com.debanshu777.huggingfacemanager.sdcpp.getModelSetup

@Composable
fun ModelDetailContent(
    model: ModelDetailResponse?,
    ggufFiles: List<GgufFileUiState>,
    isDownloading: Boolean,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    weightFilesHeading: String = "GGUF files",
    weightFilesEmptyLabel: String = "No GGUF files found",
    showDiffusionHint: Boolean = false,
    setupComponents: List<SetupComponentUiState> = emptyList(),
    isDownloadingSetupComponents: Boolean = false,
    onDownloadAllComponents: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    if (model == null) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = model.modelId ?: model.id ?: "Unknown",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        model.author?.let { author ->
            Text(
                text = author,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (model.downloads != null || model.likes != null) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                model.downloads?.let { count ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.Download,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                model.likes?.let { count ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            Icons.Default.FavoriteBorder,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = count.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        val hasInfo = model.libraryName != null || model.pipelineTag != null ||
            model.config?.modelType != null || model.config?.architectures?.filterNotNull()?.isNotEmpty() == true ||
            model.cardData?.license != null || model.cardData?.baseModel?.takeIf { it.isNotEmpty() } != null ||
            model.createdAt != null || model.lastModified != null
        if (hasInfo) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                tonalElevation = 1.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    DetailRow("Library", model.libraryName)
                    DetailRow("Pipeline", model.pipelineTag)
                    model.config?.let { config ->
                        DetailRow("Model type", config.modelType)
                        config.architectures?.filterNotNull()?.joinToString()?.let { arch ->
                            DetailRow("Architectures", arch)
                        }
                    }
                    model.cardData?.let { card ->
                        DetailRow("License", card.license)
                        card.baseModel?.takeIf { it.isNotEmpty() }?.let { models ->
                            DetailRow("Base model", models.joinToString(", "))
                        }
                    }
                    DetailRow("Created", model.createdAt)
                    DetailRow("Last modified", model.lastModified)
                }
            }
        }
        model.tags?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { tags ->
            Text(
                text = "Tags",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        tonalElevation = 1.dp
                    ) {
                        Text(
                            text = tag,
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        
        // Required Components section for diffusion models
        val modelId = model.modelId ?: model.id ?: ""
        val modelSetup = getModelSetup(modelId)
        
        if (modelSetup != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            
            if (modelSetup.selfContained) {
                // Self-contained model banner
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.CheckCircle,
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                            tint = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Column {
                            Text(
                                text = "Self-Contained Model",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "No additional downloads required. This model includes all necessary components.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            } else if (setupComponents.isNotEmpty()) {
                // Multi-component model with required downloads
                Text(
                    text = "Required Components",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
                
                if (modelSetup.description.isNotBlank()) {
                    Text(
                        text = modelSetup.description,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                }
                
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        setupComponents.forEach { component ->
                            RequiredComponentItem(
                                component = component,
                                isDownloading = isDownloadingSetupComponents
                            )
                        }
                        
                        val allDownloaded = setupComponents.filter { it.required }.all { it.isDownloaded }
                        val hasRequiredComponents = setupComponents.any { it.required }
                        
                        if (hasRequiredComponents) {
                            Button(
                                onClick = onDownloadAllComponents,
                                enabled = !allDownloaded && !isDownloadingSetupComponents,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                when {
                                    allDownloaded -> {
                                        Icon(
                                            Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(" All Components Ready")
                                    }
                                    isDownloadingSetupComponents -> {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(18.dp),
                                            strokeWidth = 2.dp
                                        )
                                        Text(" Downloading Components...")
                                    }
                                    else -> {
                                        Icon(
                                            Icons.Default.Download,
                                            contentDescription = null,
                                            modifier = Modifier.size(18.dp)
                                        )
                                        Text(" Download All Components")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        
        if (ggufFiles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = weightFilesHeading,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            if (showDiffusionHint) {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Text(
                            text = "Tap any file to download all required components automatically. " +
                                "fp16 variants are smaller with minimal quality loss.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            ggufFiles.forEach { item ->
                GgufFileListItem(
                    filename = item.path.ifEmpty { item.filename },
                    sizeBytes = item.sizeBytes,
                    isDownloaded = item.isDownloaded,
                    progress = item.progress,
                    isDownloading = isDownloading,
                    onDownloadClick = {
                        onDownloadClick(
                            model.modelId ?: model.id ?: "",
                            item.path,
                            DownloadMetadataDTO(
                                sizeBytes = item.sizeBytes,
                                author = model.author,
                                libraryName = model.libraryName,
                                pipelineTag = model.pipelineTag,
                                contextLength = model.gguf?.contextLength
                            )
                        )
                    },
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        } else {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            Text(
                text = weightFilesEmptyLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun DetailRow(
    label: String,
    value: String?,
    modifier: Modifier = Modifier
) {
    if (value == null) return
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun RequiredComponentItem(
    component: SetupComponentUiState,
    isDownloading: Boolean,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Status icon
        when {
            component.isDownloaded -> {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Downloaded",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            component.progress != null -> {
                CircularProgressIndicator(
                    progress = { component.progress },
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
            }
            else -> {
                Icon(
                    Icons.Default.Circle,
                    contentDescription = "Not downloaded",
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
                )
            }
        }
        
        // Component info
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = component.role.displayLabel,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Repo source
                Text(
                    text = component.repoId.substringAfterLast('/'),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                
                // Size hint
                component.sizeHint?.let { size ->
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = size,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Required badge
                if (component.required) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = MaterialTheme.colorScheme.errorContainer,
                        modifier = Modifier
                    ) {
                        Text(
                            text = "Required",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }
            }
            
            // Progress bar for downloading
            if (component.progress != null) {
                LinearProgressIndicator(
                    progress = { component.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
        }
    }
}
