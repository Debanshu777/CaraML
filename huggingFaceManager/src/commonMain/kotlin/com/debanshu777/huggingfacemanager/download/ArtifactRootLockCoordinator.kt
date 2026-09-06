package com.debanshu777.huggingfacemanager.download

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
