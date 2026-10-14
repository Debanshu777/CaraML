package com.debanshu777.caraml.core.rating

private const val MB = 1024L * 1024L

enum class SdArchitecture(
    val baseRamBytesQ4: Long,
    val baseRamBytesF16: Long,
    val hasT5Encoder: Boolean,
    val label: String,
) {
    SD1(
        baseRamBytesQ4  = 2_000L * MB,
        baseRamBytesF16 = 2_300L * MB,
        hasT5Encoder    = false,
        label           = "SD 1.x / 2.x",
    ),
    SDXL(
        baseRamBytesQ4  = 3_500L * MB,
        baseRamBytesF16 = 6_000L * MB,
        hasT5Encoder    = false,
        label           = "SDXL",
    ),
    SD3(
        baseRamBytesQ4  = 5_000L * MB,
        baseRamBytesF16 = 10_000L * MB,
        hasT5Encoder    = true,
        label           = "SD 3",
    ),
    FLUX(
        baseRamBytesQ4  = 6_400L * MB,
        baseRamBytesF16 = 12_000L * MB,
        hasT5Encoder    = true,
        label           = "FLUX",
    ),
    WAN_SMALL(
        baseRamBytesQ4  = 8_000L * MB,
        baseRamBytesF16 = 12_000L * MB,
        hasT5Encoder    = false,
        label           = "Wan 1.3B",
    ),
    WAN_LARGE(
        baseRamBytesQ4  = 16_000L * MB,
        baseRamBytesF16 = 24_000L * MB,
        hasT5Encoder    = false,
        label           = "Wan 14B",
    ),
    UNKNOWN(
        baseRamBytesQ4  = 0L,
        baseRamBytesF16 = 0L,
        hasT5Encoder    = false,
        label           = "Unknown",
    );

    companion object {
        /** RAM threshold (bytes) distinguishing WAN 1.3B from WAN 14B when native string is ambiguous. */
        private const val WAN_LARGE_THRESHOLD = 12L * 1024L * 1024L * 1024L  // 12 GB

        fun fromNativeString(s: String, estimatedRamBytes: Long = 0L): SdArchitecture {
            val u = s.uppercase()
            return when {
                "FLUX" in u || "FLEX" in u -> FLUX  // FLEX_2 is a FLUX variant in stable-diffusion.cpp
                "SD3"  in u                -> SD3
                "WAN"  in u -> if (estimatedRamBytes > WAN_LARGE_THRESHOLD) WAN_LARGE else WAN_SMALL
                "SDXL" in u                -> SDXL
                "SD1"  in u || "SD2" in u  -> SD1
                else                       -> UNKNOWN
            }
        }
    }
}
