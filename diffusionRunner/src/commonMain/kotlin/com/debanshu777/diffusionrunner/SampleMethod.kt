package com.debanshu777.diffusionrunner

enum class SampleMethod(val value: Int) {
    EULER(0),
    EULER_A(1),
    HEUN(2),
    DPM2(3),
    DPMPP2S_A(4),
    DPMPP2M(5),
    DPMPP2M_V2(6),
    IPNDM(7),
    IPNDM_V(8),
    LCM(9),
    DDIM_TRAILING(10),
    TCD(11);

    companion object {
        fun fromValue(value: Int): SampleMethod =
            entries.firstOrNull { it.value == value } ?: EULER_A

        /**
         * Parse a stable-diffusion.cpp sampler name (e.g. "euler", "euler_a", "lcm")
         * into the corresponding enum. Falls back to EULER_A for unknown names.
         */
        fun fromName(name: String?): SampleMethod {
            if (name.isNullOrBlank()) return EULER_A
            return when (name.lowercase()) {
                "euler" -> EULER
                "euler_a" -> EULER_A
                "heun" -> HEUN
                "dpm2" -> DPM2
                "dpm++2s_a" -> DPMPP2S_A
                "dpm++2m" -> DPMPP2M
                "dpm++2mv2" -> DPMPP2M_V2
                "ipndm" -> IPNDM
                "ipndm_v" -> IPNDM_V
                "lcm" -> LCM
                "ddim_trailing" -> DDIM_TRAILING
                "tcd" -> TCD
                else -> EULER_A
            }
        }
    }
}