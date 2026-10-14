package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.Path
import okio.Path.Companion.toPath

/**
 * Holds the same repository-root lifetime used by download commit, recovery, validation, and
 * cleanup. Consumers must pass roots re-derived from trusted storage bindings, never arbitrary
 * external paths.
 */
interface ArtifactRootLifetime {
    suspend fun <T> withCurrentBundle(
        ownerModelId: String,
        expectedRepositoryRoots: Map<String, String>,
        block: suspend (ArtifactManifest?) -> T,
    ): T
}

fun artifactRootLifetime(pathProvider: StoragePathProvider): ArtifactRootLifetime =
    SharedArtifactRootLifetime(pathProvider)

private class SharedArtifactRootLifetime(
    private val pathProvider: StoragePathProvider,
) : ArtifactRootLifetime {
    override suspend fun <T> withCurrentBundle(
        ownerModelId: String,
        expectedRepositoryRoots: Map<String, String>,
        block: suspend (ArtifactManifest?) -> T,
    ): T {
        var blockEntered = false
        suspend fun unavailable(): T {
            blockEntered = true
            return block(null)
        }
        return try {
            if (expectedRepositoryRoots.isEmpty() || expectedRepositoryRoots.size > MAX_EXPECTED_ROOTS) {
                return unavailable()
            }
            val expectedRoots = expectedRepositoryRoots.mapValues { (repositoryId, suppliedRoot) ->
                val currentRoot = runCatching { artifactMetadataRoot(pathProvider, repositoryId) }.getOrNull()
                    ?: return unavailable()
                if (!suppliedRoot.matchesCanonicalRoot(currentRoot)) return unavailable()
                currentRoot
            }
            val ownerRoot = expectedRoots[ownerModelId] ?: return unavailable()
            val candidates = readOwnerManifestCandidates(ownerRoot).takeIf { it.isNotEmpty() }
                ?: return unavailable()
            val candidateRoots = candidates.asSequence().flatMap { it.entries.asSequence() }.associate { entry ->
                val repositoryId = entry.identity.repositoryId
                repositoryId to (runCatching { artifactMetadataRoot(pathProvider, repositoryId) }.getOrNull()
                    ?: return unavailable())
            }
            val allRoots = (expectedRoots.values + candidateRoots.values).distinct()
            if (allRoots.isEmpty() || allRoots.size > MAX_LEASE_ROOTS) return unavailable()

            ArtifactRootLockCoordinator.withRoots(allRoots.map(Path::toString)) {
                val allowedRoots = allRoots.mapTo(mutableSetOf(), ::artifactRootKey)
                val store = artifactBundleStore(pathProvider, ownerRoot, allowedRoots)
                try {
                    if (store.readRecoveryCandidates() != candidates) return@withRoots unavailable()
                    if (!recoverArtifactStores(allRoots)) return@withRoots unavailable()
                    store.recover()
                    val validated = store.readValidated()
                    blockEntered = true
                    block(validated)
                } finally {
                    store.close()
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            if (blockEntered) throw failure
            unavailable()
        }
    }

    private fun String.matchesCanonicalRoot(expected: Path): Boolean = runCatching {
        val supplied = toPath(normalize = false)
        supplied.isAbsolute && supplied == supplied.normalized() && supplied == expected
    }.getOrDefault(false)

    private companion object {
        const val MAX_EXPECTED_ROOTS = 64
        const val MAX_LEASE_ROOTS = 256
    }
}

internal object ArtifactRootLockCoordinator {
    private data class Entry(val mutex: Mutex, var references: Int)

    private val guard = Mutex()
    private val entries = mutableMapOf<String, Entry>()

    suspend fun <T> withRoots(keys: Collection<String>, block: suspend () -> T): T {
        val orderedKeys = keys.asSequence()
            .map { it.trim().replace('\\', '/').trimEnd('/').lowercase() }
            .filter(String::isNotEmpty)
            .distinct()
            .sorted()
            .toList()
        require(orderedKeys.isNotEmpty()) { "Artifact root is unavailable" }
        val acquired = guard.withLock {
            orderedKeys.map { key -> key to entries.getOrPut(key) { Entry(Mutex(), 0) }.also { it.references++ } }
        }
        return try {
            lockRecursively(acquired.map { it.second }, 0, block)
        } finally {
            guard.withLock {
                acquired.forEach { (key, entry) ->
                    entry.references--
                    if (entry.references == 0 && entries[key] === entry) entries.remove(key)
                }
            }
        }
    }

    private suspend fun <T> lockRecursively(entries: List<Entry>, index: Int, block: suspend () -> T): T =
        if (index == entries.size) block()
        else entries[index].mutex.withLock { lockRecursively(entries, index + 1, block) }
}
