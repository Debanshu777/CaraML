package com.debanshu777.caraml.core.recommendation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class LoadRecoveryProcessTest {
    @Test
    fun processDeathLeavesOneRecoverableMarkerAndSaferSuccessClearsIt() = runTest {
        val directory = Files.createTempDirectory("caraml-load-process-")
        val statePath = directory.resolve("state.preferences_pb")
        val java = File(System.getProperty("java.home"), "bin/java").absolutePath
        val classpath = runtimeClasspath()
        val process = ProcessBuilder(
            java,
            "-cp",
            classpath,
            LoadCrashProbeMain::class.java.name,
            statePath.toString(),
            ENGINE_VERSION,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), output)

        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) {
            statePath.toString().toPath()
        }
        val repository = LoadRecoveryRepository(dataStore, ENGINE_VERSION) { 2_000L }
        val suspected = assertIs<SuspectedLoadFailure>(repository.recoverPendingLoad(2_000L))
        assertEquals(LoadQuarantine.TEMPORARY, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))
        assertNull(repository.recoverPendingLoad(2_001L))

        val safer = RecoveryFixtures.plan(contextTokens = 2_048)
        val saferMarker = repository.beginLoad(RecoveryFixtures.identity, safer)
        repository.markLoadSucceeded(saferMarker)
        assertEquals(LoadQuarantine.NONE, repository.quarantine(RecoveryFixtures.identity, safer, ENGINE_VERSION))
        assertEquals(suspected.modelDigest, saferMarker.modelDigest)
    }

    private fun runtimeClasspath(): String {
        val loader = LoadRecoveryProcessTest::class.java.classLoader
        val urls = generateSequence(loader) { it.parent }
            .filterIsInstance<URLClassLoader>()
            .flatMap { it.urLs.asSequence() }
            .filter { it.protocol == "file" }
            .map { File(it.toURI()).absolutePath }
            .distinct()
            .toList()
        return if (urls.isNotEmpty()) urls.joinToString(File.pathSeparator)
        else System.getProperty("java.class.path")
    }

    private companion object {
        const val ENGINE_VERSION = "engine-1"
    }
}
