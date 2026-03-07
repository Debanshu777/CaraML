package com.debanshu777.caraml.features.modelhub.presentation.details.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.debanshu777.huggingfacemanager.download.DownloadMetadataDTO
import com.debanshu777.huggingfacemanager.model.ModelDetailResponse
import com.debanshu777.caraml.features.modelhub.presentation.search.GgufFileUiState

@Composable
fun ModelDetailContent(
    model: ModelDetailResponse?,
    ggufFiles: List<GgufFileUiState>,
    isDownloading: Boolean,
    onDownloadClick: (String, String, DownloadMetadataDTO) -> Unit,
    modifier: Modifier = Modifier
) {
    if (model == null) return
    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = model.modelId ?: model.id ?: "Unknown",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface
        )
        DetailRow("Author", model.author)
        DetailRow("Library", model.libraryName)
        DetailRow("Pipeline", model.pipelineTag)
        DetailRow("Downloads", model.downloads?.toString())
        DetailRow("Likes", model.likes?.toString())
        DetailRow("Created", model.createdAt)
        DetailRow("Last modified", model.lastModified)
        model.tags?.filterNotNull()?.takeIf { it.isNotEmpty() }?.let { tags ->
            Text(
                text = "Tags",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                tags.forEach { tag ->
                    Surface(
                        shape = RoundedCornerShape(8.dp),
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
        model.config?.let { config ->
            DetailRow("Model type", config.modelType)
            config.architectures?.filterNotNull()?.joinToString()?.let { arch ->
                DetailRow("Architectures", arch)
            }
        }
        model.cardData?.let { card ->
            DetailRow("License", card.license)
            DetailRow("Base model", card.baseModel)
        }
        
        if (ggufFiles.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                text = "GGUF Files",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            ggufFiles.forEach { item ->
                GgufFileListItem(
                    filename = item.filename,
                    sizeBytes = item.sizeBytes,
                    isDownloaded = item.isDownloaded,
                    progress = item.progress,
                    isDownloading = isDownloading,
                    onDownloadClick = {
                        onDownloadClick(
                            model.modelId ?: model.id ?: "",
                            item.path,
                            DownloadMetadataDTO(
                                item.sizeBytes,
                                model.author,
                                model.libraryName,
                                model.pipelineTag
                            )
                        )
                    }
                )
            }
        } else if (model != null) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp))
            Text(
                text = "No GGUF files found",
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
