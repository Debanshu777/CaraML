package com.debanshu777.caraml.benchmark

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import com.debanshu777.caraml.core.data.theme.ThemeRepository
import com.debanshu777.caraml.core.settings.initPreferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform

/** Opt-in setup for the isolated benchmark package; no production manifest entry. */
class BenchmarkPreferencesActivity : ComponentActivity() {
    private val setupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        require(intent.hasExtra("softEffects")) { "An explicit effects experiment is required" }
        initPreferencesDataStore(applicationContext)
        val status = TextView(this).apply { text = "Preparing benchmark preferences" }
        setContentView(status)
        setupScope.launch {
            val repository = KoinPlatform.getKoin().get<ThemeRepository>()
            val enabled = intent.getBooleanExtra("softEffects", true)
            repository.updatePreferences(repository.getPreferences().first().copy(softEffects = enabled))
            check(repository.getPreferences().first().softEffects == enabled)
            status.text = "Benchmark preferences ready: soft effects $enabled"
            status.contentDescription = "benchmark-preferences-ready"
        }
    }

    override fun onDestroy() {
        setupScope.cancel()
        super.onDestroy()
    }
}
