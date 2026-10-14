package com.debanshu777.caraml.core.recommendation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.MemoryTopology
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.TestScope
import okio.Path.Companion.toPath
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class LoadRecoveryRepositoryTest {
    @Test
    fun repeatedPendingMarkerQuarantinesOnlyExactConfigurationAndEngine() = runTest {
        var now = 1_000L
        val repository = repository(now = { now })
        val first = repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)
        repository.recordSuspectedFailure(first)
        now++
        val second = repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)
        repository.recordSuspectedFailure(second)

        assertEquals(LoadQuarantine.KNOWN_UNSTABLE, repository.quarantine(first.modelDigest, first.configDigest, ENGINE_VERSION))
        assertEquals(LoadQuarantine.NONE, repository.quarantine(first.modelDigest, "f".repeat(64), ENGINE_VERSION))
        assertEquals(
            LoadQuarantine.NONE,
            repository.quarantine(first.modelDigest, first.configDigest, "native-engine-v1"),
        )
    }

    @Test
    fun oneSuspectedFailureIsTemporaryAndExplicitRetryClearsExactKey() = runTest {
        val repository = repository()
        val marker = repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)
        repository.recordSuspectedFailure(marker)

        assertEquals(LoadQuarantine.TEMPORARY, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))
        repository.allowExplicitRetry(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION)
        assertEquals(LoadQuarantine.NONE, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))
    }

    @Test
    fun pendingRecoveryIsAtomicAndIdempotent() = runTest {
        val repository = repository()
        val marker = repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)

        val recovered = assertIs<SuspectedLoadFailure>(repository.recoverPendingLoad(2_000L))
        assertEquals(marker.modelDigest, recovered.modelDigest)
        assertNull(repository.recoverPendingLoad(2_001L))
        assertEquals(LoadQuarantine.TEMPORARY, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))
    }

    @Test
    fun successFailureAndCancellationClearPendingWithoutCreatingSuspectedCrash() = runTest {
        val repository = repository()
        repository.markLoadSucceeded(repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan))
        assertNull(repository.recoverPendingLoad(2_000L))

        repository.markLoadFailed(
            repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan),
            StableLoadFailure.ALLOCATION,
        )
        assertNull(repository.recoverPendingLoad(2_000L))

        repository.markLoadCancelled(repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan))
        assertNull(repository.recoverPendingLoad(2_000L))
        assertEquals(LoadQuarantine.NONE, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))
    }

    @Test
    fun successfulExactConfigurationClearsItsPriorRecoveryState() = runTest {
        val repository = repository()
        val first = repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)
        repository.recordSuspectedFailure(first)
        assertEquals(LoadQuarantine.TEMPORARY, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))

        repository.markLoadSucceeded(repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan))

        assertEquals(LoadQuarantine.NONE, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))
    }

    @Test
    fun sevenDayExpiryAndBoundedHistoryDiscardStaleState() = runTest {
        var now = 10L
        val repository = repository(now = { now })
        repeat(70) { index ->
            val plan = RecoveryFixtures.plan(contextTokens = 1_024 + index)
            repository.recordSuspectedFailure(repository.beginLoad(RecoveryFixtures.identity, plan))
            now++
        }
        assertTrue(repository.recoveryRecordCount() <= LoadRecoveryRepository.MAX_RECOVERY_RECORDS)

        now += LoadRecoveryRepository.RECOVERY_WINDOW_MS + 1L
        assertEquals(LoadQuarantine.NONE, repository.quarantine(RecoveryFixtures.identity, RecoveryFixtures.plan, ENGINE_VERSION))
        assertEquals(0, repository.recoveryRecordCount())
    }

    @Test
    fun digestsNeverContainRawModelPath() = runTest {
        val repository = repository()
        val marker = repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)

        assertFalse(marker.modelDigest.contains("private"))
        assertFalse(marker.configDigest.contains("private"))
        assertEquals(64, marker.modelDigest.length)
        assertEquals(64, marker.configDigest.length)
        assertTrue(marker.modelDigest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun beginningAnotherLoadNeverCountsOrOverwritesAnUnrecoveredMarker() = runTest {
        val repository = repository()
        val original = repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)

        try {
            repository.beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan(contextTokens = 2_048))
            fail("Expected the unrecovered marker to block a second load")
        } catch (_: PendingLoadMarkerExistsException) {
            // Startup recovery owns conversion of the abandoned marker.
        }

        assertEquals(0, repository.recoveryRecordCount())
        val recovered = assertIs<SuspectedLoadFailure>(repository.recoverPendingLoad())
        assertEquals(original.modelDigest, recovered.modelDigest)
        assertEquals(original.configDigest, recovered.configDigest)
    }

    private fun TestScope.repository(
        now: () -> Long = { 1_000L },
    ): LoadRecoveryRepository {
        val path = Files.createTempDirectory("caraml-load-recovery-").resolve("state.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = backgroundScope) { path.toString().toPath() }
        return LoadRecoveryRepository(dataStore, ENGINE_VERSION, now)
    }

    private companion object {
        const val ENGINE_VERSION = NATIVE_LOAD_ENGINE_VERSION
    }
}

internal object RecoveryFixtures {
    val identity = ModelFileIdentity(
        repositoryId = "owner/model",
        revision = "a".repeat(64),
        path = "model.gguf",
        sizeBytes = 4,
        gitOid = null,
        lfsOid = "sha256:${"b".repeat(64)}",
        xetHash = null,
        evidence = emptyList(),
    )

    val plan = plan()

    fun plan(contextTokens: Int = 4_096) = LlmRunPlan(
        contextTokens = contextTokens,
        batchSize = 256,
        microBatchSize = 128,
        sequenceCount = 1,
        keyCacheType = KvCacheType.Q8_0,
        valueCacheType = KvCacheType.Q8_0,
        backend = BackendKind.CPU,
        memoryTopology = MemoryTopology.UNKNOWN,
        gpuLayerCount = 0,
        compromises = emptyList(),
    )
}
