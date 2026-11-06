package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.download.transfer.ArtifactManifest

/** Reads published identities and checks file shape; does not rehash model payloads. */
fun interface InstalledModelManifestSource {
    suspend operator fun invoke(ownerModelId: String): ArtifactManifest?
}
