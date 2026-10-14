package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.download.transfer.ArtifactManifest

fun interface InstalledModelManifestSource {
    suspend operator fun invoke(ownerModelId: String): ArtifactManifest?
}
