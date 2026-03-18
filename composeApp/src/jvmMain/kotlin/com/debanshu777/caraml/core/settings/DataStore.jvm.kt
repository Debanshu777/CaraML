package com.debanshu777.caraml.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import java.io.File

private fun getPreferencesDataStorePath(): String {
    val baseDir = File(System.getProperty("user.home"), ".caraml")
    if (!baseDir.exists()) {
        baseDir.mkdirs()
    }
    return File(baseDir, dataStoreFileName).absolutePath
}

actual fun createPreferencesDataStore(): DataStore<Preferences> {
    return getPreferencesDataStore(getPreferencesDataStorePath())
}
