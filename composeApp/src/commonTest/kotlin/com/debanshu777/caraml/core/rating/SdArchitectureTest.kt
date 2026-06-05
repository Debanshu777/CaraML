package com.debanshu777.caraml.core.rating

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SdArchitectureTest {

    @Test
    fun fromNativeString_mapsFlux() {
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX_FILL"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX_CONTROLS"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLUX2"))
        assertEquals(SdArchitecture.FLUX, SdArchitecture.fromNativeString("FLEX_2"))
    }

    @Test
    fun fromNativeString_mapsSD3() {
        assertEquals(SdArchitecture.SD3, SdArchitecture.fromNativeString("SD3"))
    }

    @Test
    fun fromNativeString_mapsSDXL() {
        assertEquals(SdArchitecture.SDXL, SdArchitecture.fromNativeString("SDXL"))
        assertEquals(SdArchitecture.SDXL, SdArchitecture.fromNativeString("SDXL_INPAINT"))
    }

    @Test
    fun fromNativeString_mapsSD1() {
        assertEquals(SdArchitecture.SD1, SdArchitecture.fromNativeString("SD1"))
        assertEquals(SdArchitecture.SD1, SdArchitecture.fromNativeString("SD2"))
    }

    @Test
    fun fromNativeString_mapsWanByRam() {
        val smallRam = 8L * 1024 * 1024 * 1024   // 8 GB → WAN_SMALL
        val largeRam = 20L * 1024 * 1024 * 1024  // 20 GB → WAN_LARGE
        assertEquals(SdArchitecture.WAN_SMALL, SdArchitecture.fromNativeString("WAN2_SMALL", smallRam))
        assertEquals(SdArchitecture.WAN_LARGE, SdArchitecture.fromNativeString("WAN2_LARGE", largeRam))
    }

    @Test
    fun fromNativeString_returnsUnknownForUnrecognized() {
        assertEquals(SdArchitecture.UNKNOWN, SdArchitecture.fromNativeString("CHROMA_RADIANCE"))
        assertEquals(SdArchitecture.UNKNOWN, SdArchitecture.fromNativeString(""))
    }

    @Test
    fun baseRamBytesQ4_areNonZeroForKnownArch() {
        SdArchitecture.entries.filter { it != SdArchitecture.UNKNOWN }.forEach { arch ->
            assertTrue(arch.baseRamBytesQ4 > 0, "${arch.name} has zero Q4 RAM")
        }
    }
}
