package com.debanshu777.caraml.core.data.inference

import com.debanshu777.huggingfacemanager.download.ArtifactManifest
import com.debanshu777.huggingfacemanager.model.normalizedDiffusersRelativePath
import com.debanshu777.huggingfacemanager.sdcpp.SdCppModelSetup

internal sealed interface VerifiedDiffusionLoadTarget {
    data class File(val relativePath: String) : VerifiedDiffusionLoadTarget
    data object Directory : VerifiedDiffusionLoadTarget
}

/** Fixed paths opened by stable-diffusion.cpp's directory loader. */
internal val NATIVE_DIFFUSERS_CONSUMED_PATHS: Set<String> = setOf(
    "unet/diffusion_pytorch_model.safetensors",
    "vae/diffusion_pytorch_model.safetensors",
    "text_encoder/model.safetensors",
    "text_encoder_2/model.safetensors",
)

internal fun ArtifactManifest.verifiedDiffusionLoadTarget(
    ownerModelId: String,
): VerifiedDiffusionLoadTarget? {
    val primary = entries.singleOrNull {
        it.logicalRole == "model" && it.identity.repositoryId == ownerModelId
    } ?: return null

    if (primary.layoutRelativePath !in NATIVE_DIFFUSERS_CONSUMED_PATHS) {
        return VerifiedDiffusionLoadTarget.File(primary.layoutRelativePath)
    }

    val coveredOwnerPaths = entries.asSequence()
        .filter { it.identity.repositoryId == ownerModelId }
        .map { it.layoutRelativePath }
        .toSet()
    return if (coveredOwnerPaths.containsAll(NATIVE_DIFFUSERS_CONSUMED_PATHS)) {
        VerifiedDiffusionLoadTarget.Directory
    } else {
        null
    }
}

internal fun ArtifactManifest.isCompleteDiffusionInstallation(
    ownerModelId: String,
    setup: SdCppModelSetup?,
): Boolean {
    if (verifiedDiffusionLoadTarget(ownerModelId) == null) return false
    return setup?.components.orEmpty().filter { it.required }.all { component ->
        entries.singleOrNull { entry ->
            entry.logicalRole == component.role.name.lowercase() &&
                entry.identity.repositoryId == component.repoId &&
                entry.identity.relativePath == component.filePath &&
                entry.layoutRelativePath == normalizedDiffusersRelativePath(component.filePath)
        } != null
    }
}
