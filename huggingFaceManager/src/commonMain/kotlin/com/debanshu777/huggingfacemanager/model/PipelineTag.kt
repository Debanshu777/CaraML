package com.debanshu777.huggingfacemanager.model

enum class PipelineTag(val apiValue: String) {
    TEXT_GENERATION("text-generation"),
    TEXT_TO_IMAGE("text-to-image"),
    IMAGE_TO_IMAGE("image-to-image"),
    TEXT_TO_VIDEO("text-to-video"),
    IMAGE_TO_VIDEO("image-to-video"),
    ;

    companion object {
        fun fromString(tag: String?): PipelineTag? =
            tag?.lowercase()?.let { normalized ->
                entries.firstOrNull { it.apiValue == normalized }
            }

        /**
         * Language-model hubs suitable for chat.
         * Matches `text-generation` and HF variants (e.g. `text-generation-instruct`),
         * but never `text-to-image` / `text-to-video` (those start with `text-to-`).
         */
        fun isTextGenerationTag(tag: String?): Boolean {
            val t = tag?.lowercase() ?: return false
            return t.startsWith(TEXT_GENERATION.apiValue) && !t.startsWith("text-to-")
        }

        /** Diffusion / video checkpoint hubs (HF pipeline tags). */
        fun isDiffusionPipelineTag(tag: String?): Boolean =
            when (tag?.lowercase()) {
                TEXT_TO_IMAGE.apiValue,
                IMAGE_TO_IMAGE.apiValue,
                TEXT_TO_VIDEO.apiValue,
                IMAGE_TO_VIDEO.apiValue,
                -> true
                else -> false
            }

        fun isVideoPipelineTag(tag: String?): Boolean =
            when (tag?.lowercase()) {
                TEXT_TO_VIDEO.apiValue,
                IMAGE_TO_VIDEO.apiValue,
                -> true
                else -> false
            }

        /**
         * Model hub is a type we surface in the app (chat and/or media).
         * Unknown tags still allowed when [LocalModelEntity] classification uses file extensions.
         */
        fun isSupported(tag: String?): Boolean =
            tag.isNullOrBlank() ||
                isTextGenerationTag(tag) ||
                isDiffusionPipelineTag(tag)
    }
}
