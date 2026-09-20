package com.debanshu777.caraml.features.modelhub.domain

import com.debanshu777.caraml.core.recommendation.DiffusionModelDescriptor
import com.debanshu777.caraml.core.recommendation.LlmModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelDescriptor
import com.debanshu777.caraml.core.recommendation.ModelFileIdentity
import com.debanshu777.caraml.core.recommendation.canonicalDownloadRemoteObjectId
import com.debanshu777.huggingfacemanager.download.DownloadArtifactIdentity
import com.debanshu777.caraml.core.download.DownloadArtifactRequest

data class ArtifactStorageKey(val repositoryId: String, val relativePath: String)

data class ArtifactStorageLocation(val finalVolume: String, val temporaryVolume: String)

data class StorageVolume(val freeBytes: Long?)

data class DownloadStorageLayout(
    val volumes: Map<String, StorageVolume>,
    val locations: Map<ArtifactStorageKey, ArtifactStorageLocation>,
)

data class LocalDownloadArtifact(
    val repositoryId: String,
    val relativePath: String,
    val finalBytes: Long? = null,
    val partBytes: Long? = null,
    val exactPublished: Boolean = false,
)

data class LocalDownloadInventory(val artifacts: List<LocalDownloadArtifact>) {
    companion object {
        val Empty = LocalDownloadInventory(emptyList())
    }
}

data class VolumeStorageRequirement(
    val additionalBytes: Long,
    val freeBytes: Long,
    val reserveBytes: Long,
)

sealed interface StorageRequirement {
    val perVolume: Map<String, VolumeStorageRequirement>

    data class Ready(
        override val perVolume: Map<String, VolumeStorageRequirement>,
    ) : StorageRequirement

    data class Blocked(
        val volumeId: String,
        override val perVolume: Map<String, VolumeStorageRequirement>,
    ) : StorageRequirement

    data class NeedsInformation(
        override val perVolume: Map<String, VolumeStorageRequirement> = emptyMap(),
    ) : StorageRequirement
}

class DownloadStorageEstimator {
    fun estimate(
        requests: List<DownloadArtifactRequest>,
        localInventory: LocalDownloadInventory,
        layout: DownloadStorageLayout,
    ): StorageRequirement {
        if (requests.isEmpty() || requests.size > MAX_COMPONENTS || !inventoryIsValid(localInventory)) {
            return StorageRequirement.NeedsInformation()
        }
        val inventory = localInventory.artifacts.associateBy {
            ArtifactStorageKey(it.repositoryId, it.relativePath)
        }
        if (inventory.size != localInventory.artifacts.size) return StorageRequirement.NeedsInformation()
        val finalGrowth = mutableMapOf<String, Long>()
        val temporaryGrowth = mutableMapOf<String, Long>()
        val seenTargets = mutableSetOf<ArtifactStorageKey>()
        requests.forEach { request ->
            val metadata = request.metadata
            if (!metadata.usesImmutableStorageLayout || metadata.sizeBytes != metadata.artifact.expectedBytes) {
                return StorageRequirement.NeedsInformation()
            }
            val key = ArtifactStorageKey(metadata.artifact.repositoryId, metadata.destinationRelativePath)
            if (!seenTargets.add(key)) return StorageRequirement.NeedsInformation()
            val location = layout.locations[key] ?: return StorageRequirement.NeedsInformation()
            if (layout.volumes[location.finalVolume] == null || layout.volumes[location.temporaryVolume] == null) {
                return StorageRequirement.NeedsInformation()
            }
            val local = inventory[key]
            val expectedBytes = metadata.artifact.expectedBytes
            if (local?.exactPublished == true && local.finalBytes != expectedBytes) {
                return StorageRequirement.NeedsInformation()
            }
            val missingFinal = if (local?.finalBytes != null) 0L else expectedBytes
            val missingTemporary = if (local?.exactPublished == true) 0L else {
                checkedSubtract(expectedBytes, local?.partBytes ?: 0L)
                    ?: return StorageRequirement.NeedsInformation()
            }
            finalGrowth.addChecked(location.finalVolume, missingFinal) ?: return StorageRequirement.NeedsInformation()
            temporaryGrowth[location.temporaryVolume] = maxOf(
                temporaryGrowth[location.temporaryVolume] ?: 0L,
                missingTemporary,
            )
        }
        return requirements(finalGrowth, temporaryGrowth, layout)
    }

    fun estimate(
        descriptor: ModelDescriptor,
        localInventory: LocalDownloadInventory,
        layout: DownloadStorageLayout,
    ): StorageRequirement {
        val files = when (descriptor) {
            is LlmModelDescriptor -> descriptor.files
            is DiffusionModelDescriptor -> descriptor.components
                .filter { it.required || it.isPrimary }
                .map { it.file }
        }
        if (files.isEmpty() || files.size > MAX_COMPONENTS || !inventoryIsValid(localInventory)) {
            return StorageRequirement.NeedsInformation()
        }

        val inventory = localInventory.artifacts.associateBy {
            ArtifactStorageKey(it.repositoryId, it.relativePath)
        }
        if (inventory.size != localInventory.artifacts.size) return StorageRequirement.NeedsInformation()

        val finalGrowth = mutableMapOf<String, Long>()
        val temporaryGrowth = mutableMapOf<String, Long>()
        val seenTargets = mutableSetOf<ArtifactStorageKey>()
        for (file in files) {
            if (!fileIsValid(descriptor, file)) return StorageRequirement.NeedsInformation()
            val key = ArtifactStorageKey(file.repositoryId, file.path)
            if (!seenTargets.add(key)) return StorageRequirement.NeedsInformation()
            val location = layout.locations[key] ?: return StorageRequirement.NeedsInformation()
            if (layout.volumes[location.finalVolume] == null || layout.volumes[location.temporaryVolume] == null) {
                return StorageRequirement.NeedsInformation()
            }
            val local = inventory[key]
            val finalAlreadyPresent = local?.finalBytes != null
            val finalAlreadyExact = local?.finalBytes == file.sizeBytes
            val missingFinal = if (finalAlreadyPresent) 0L else file.sizeBytes
            val existingPart = local?.partBytes ?: 0L
            val missingTemporary = if (finalAlreadyExact) 0L else {
                checkedSubtract(file.sizeBytes, existingPart) ?: return StorageRequirement.NeedsInformation()
            }
            finalGrowth.addChecked(location.finalVolume, missingFinal) ?: return StorageRequirement.NeedsInformation()
            temporaryGrowth[location.temporaryVolume] = maxOf(
                temporaryGrowth[location.temporaryVolume] ?: 0L,
                missingTemporary,
            )
        }

        val requirements = linkedMapOf<String, VolumeStorageRequirement>()
        for (volumeId in (finalGrowth.keys + temporaryGrowth.keys).sorted()) {
            val free = layout.volumes[volumeId]?.freeBytes?.takeIf { it >= 0L }
                ?: return StorageRequirement.NeedsInformation(requirements)
            val additional = checkedAdd(finalGrowth[volumeId] ?: 0L, temporaryGrowth[volumeId] ?: 0L)
                ?: return StorageRequirement.NeedsInformation(requirements)
            val reserve = storageReserve(free) ?: return StorageRequirement.NeedsInformation(requirements)
            requirements[volumeId] = VolumeStorageRequirement(additional, free, reserve)
        }
        val blocked = requirements.entries.firstOrNull { (_, value) ->
            value.additionalBytes > (checkedSubtract(value.freeBytes, value.reserveBytes) ?: -1L)
        }
        return if (blocked == null) StorageRequirement.Ready(requirements)
        else StorageRequirement.Blocked(blocked.key, requirements)
    }

    private fun inventoryIsValid(inventory: LocalDownloadInventory): Boolean =
        inventory.artifacts.size <= MAX_COMPONENTS && inventory.artifacts.all {
            it.repositoryId.isNotBlank() && it.relativePath.isNotBlank() &&
                (it.finalBytes == null || it.finalBytes >= 0L) &&
                (it.partBytes == null || it.partBytes >= 0L) &&
                (!it.exactPublished || it.finalBytes != null)
        }

    private fun fileIsValid(descriptor: ModelDescriptor, file: ModelFileIdentity): Boolean {
        val remoteObjectId = file.canonicalDownloadRemoteObjectId()
        if (DownloadArtifactIdentity.create(
                repositoryId = file.repositoryId,
                immutableRevision = file.revision,
                relativePath = file.path,
                remoteObjectId = remoteObjectId,
                expectedBytes = file.sizeBytes,
            ) == null
        ) {
            return false
        }
        return descriptor !is LlmModelDescriptor ||
            (file.repositoryId == descriptor.repositoryId && file.revision == descriptor.revision)
    }

    private fun requirements(
        finalGrowth: Map<String, Long>,
        temporaryGrowth: Map<String, Long>,
        layout: DownloadStorageLayout,
    ): StorageRequirement {
        val requirements = linkedMapOf<String, VolumeStorageRequirement>()
        for (volumeId in (finalGrowth.keys + temporaryGrowth.keys).sorted()) {
            val free = layout.volumes[volumeId]?.freeBytes?.takeIf { it >= 0L }
                ?: return StorageRequirement.NeedsInformation(requirements)
            val additional = checkedAdd(finalGrowth[volumeId] ?: 0L, temporaryGrowth[volumeId] ?: 0L)
                ?: return StorageRequirement.NeedsInformation(requirements)
            val reserve = storageReserve(free) ?: return StorageRequirement.NeedsInformation(requirements)
            requirements[volumeId] = VolumeStorageRequirement(additional, free, reserve)
        }
        val blocked = requirements.entries.firstOrNull { (_, value) ->
            value.additionalBytes > (checkedSubtract(value.freeBytes, value.reserveBytes) ?: -1L)
        }
        return if (blocked == null) StorageRequirement.Ready(requirements)
        else StorageRequirement.Blocked(blocked.key, requirements)
    }
}

private const val MAX_COMPONENTS = 64
private const val MIB = 1024L * 1024L
private const val GIB = 1024L * MIB

private fun storageReserve(freeBytes: Long): Long? {
    val fivePercent = freeBytes / 20L
    return minOf(10L * GIB, maxOf(512L * MIB, fivePercent))
}

private fun checkedAdd(first: Long, second: Long): Long? =
    if (first < 0L || second < 0L || first > Long.MAX_VALUE - second) null else first + second

private fun checkedSubtract(first: Long, second: Long): Long? =
    if (first < 0L || second < 0L || second > first) null else first - second

private fun MutableMap<String, Long>.addChecked(key: String, value: Long): Long? {
    val sum = checkedAdd(this[key] ?: 0L, value) ?: return null
    this[key] = sum
    return sum
}
