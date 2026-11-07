package com.debanshu777.caraml.core.download

import com.debanshu777.caraml.core.download.transfer.DownloadMetadataDTO
import com.debanshu777.caraml.core.download.transfer.artifactBundleId
import com.debanshu777.caraml.core.download.transfer.immutableArtifactStorageLocation
import com.debanshu777.caraml.sdk.discovery.huggingface.model.OnnxModelBundles

/** One verified storage generation: the graph is the primary, all sidecars are owned components. */
fun onnxDownloadRequests(metadata: List<DownloadMetadataDTO>): List<DownloadArtifactRequest>? {
    if (!OnnxModelBundles.isComplete(metadata.map { it.artifact.relativePath })) return null
    if (metadata.map { it.artifact.repositoryId to it.artifact.immutableRevision }.distinct().size != 1) return null
    val bundleId = artifactBundleId(metadata.map { it.artifact }) ?: return null
    return metadata.sortedWith(compareBy<DownloadMetadataDTO> { !it.artifact.relativePath.endsWith(".onnx") }.thenBy { it.artifact.relativePath })
        .mapIndexed { index, item ->
            val primary = item.artifact.relativePath.endsWith(".onnx")
            DownloadArtifactRequest(
                metadata = item.copy(
                    logicalRole = if (primary) "model" else "onnx-companion-$index",
                    libraryName = "onnx",
                    bundleId = bundleId,
                    destinationRelativePath = immutableArtifactStorageLocation(item.artifact, bundleId).localRelativePath,
                ),
                primary = primary,
            )
        }
}
