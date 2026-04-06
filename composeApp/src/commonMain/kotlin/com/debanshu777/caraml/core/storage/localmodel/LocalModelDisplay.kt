package com.debanshu777.caraml.core.storage.localmodel

import com.debanshu777.huggingfacemanager.model.DIFFUSERS_BUNDLE_DB_FILENAME

/** User-visible label for the downloaded weight(s). */
fun LocalModelEntity.displayFilename(): String =
    if (filename == DIFFUSERS_BUNDLE_DB_FILENAME) {
        "Full model (diffusers)"
    } else {
        filename
    }
