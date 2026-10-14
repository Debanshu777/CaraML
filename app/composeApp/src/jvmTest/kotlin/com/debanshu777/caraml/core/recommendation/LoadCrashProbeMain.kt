package com.debanshu777.caraml.core.recommendation

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath

object LoadCrashProbeMain {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 2)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val dataStore = PreferenceDataStoreFactory.createWithPath(scope = scope) { args[0].toPath() }
        runBlocking {
            LoadRecoveryRepository(
                dataStore = dataStore,
                engineVersion = args[1],
                clock = { 1_000L },
            ).beginLoad(RecoveryFixtures.identity, RecoveryFixtures.plan)
        }
        scope.cancel()
        // Deliberately exit without a terminal marker transition, simulating process death.
    }
}
