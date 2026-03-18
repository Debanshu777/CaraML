package com.debanshu777.caraml.core.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSUserDomainMask

private fun getPreferencesDataStorePath(): String {
    val documentDirectory = NSFileManager.defaultManager.URLForDirectory(
        directory = NSDocumentDirectory,
        inDomain = NSUserDomainMask,
        appropriateForURL = null,
        create = false,
        error = null
    )
    val path = documentDirectory?.path
    require(!path.isNullOrBlank()) { "Unable to resolve documents directory for DataStore" }
    return "$path/$dataStoreFileName"
}

actual fun createPreferencesDataStore(): DataStore<Preferences> {
    return getPreferencesDataStore(getPreferencesDataStorePath())
}
