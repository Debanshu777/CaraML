package com.debanshu777.caraml.core.recommendation

import com.debanshu777.huggingfacemanager.download.ArtifactManifest

fun interface InstalledModelManifestSource {
    suspend operator fun invoke(ownerModelId: String): ArtifactManifest?
}
