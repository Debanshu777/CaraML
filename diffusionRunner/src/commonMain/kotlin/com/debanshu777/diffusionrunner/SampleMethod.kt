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
    }
}