package com.debanshu777.caraml.core.rating

import com.debanshu777.diffusionrunner.SampleMethod
import kotlin.test.Test
import kotlin.test.assertEquals

class DiffusionStepPolicyTest {

    // --- Distilled models (DistilledHint.YES) ---

    @Test
    fun distilledWithLcmSampler_returns6Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.UNKNOWN,
            sampler = SampleMethod.LCM,
            distilledHint = DistilledHint.YES,
        )
        assertEquals(6, profile.steps)
    }

    @Test
    fun distilledWithTcdSampler_returns8Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.UNKNOWN,
            sampler = SampleMethod.TCD,
            distilledHint = DistilledHint.YES,
        )
        assertEquals(8, profile.steps)
    }

    @Test
    fun distilledWithEulerASampler_returns4Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.UNKNOWN,
            sampler = SampleMethod.EULER_A,
            distilledHint = DistilledHint.YES,
        )
        assertEquals(4, profile.steps)
    }

    @Test
    fun distilledSd1WithEulerSampler_returns4Steps_distilledWinsOverArch() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SD1,
            sampler = SampleMethod.EULER,
            distilledHint = DistilledHint.YES,
        )
        assertEquals(4, profile.steps)
    }

    // --- Architecture baselines (DistilledHint.UNKNOWN) ---

    @Test
    fun fluxWithEulerAndUnknownHint_returns25Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.FLUX,
            sampler = SampleMethod.EULER,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(25, profile.steps)
    }

    @Test
    fun sd3WithEulerAndUnknownHint_returns28Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SD3,
            sampler = SampleMethod.EULER,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(28, profile.steps)
    }

    @Test
    fun wanSmallWithEulerAndUnknownHint_returns25Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.WAN_SMALL,
            sampler = SampleMethod.EULER,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(25, profile.steps)
    }

    @Test
    fun wanLargeWithEulerAndUnknownHint_returns25Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.WAN_LARGE,
            sampler = SampleMethod.EULER,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(25, profile.steps)
    }

    @Test
    fun sdxlWithEulerAAndUnknownHint_returns25Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SDXL,
            sampler = SampleMethod.EULER_A,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(25, profile.steps)
    }

    @Test
    fun sdxlWithDpmpp2mAndUnknownHint_returns20Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SDXL,
            sampler = SampleMethod.DPMPP2M,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(20, profile.steps)
    }

    @Test
    fun sdxlWithHeunAndUnknownHint_returns20Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SDXL,
            sampler = SampleMethod.HEUN,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(20, profile.steps)
    }

    @Test
    fun sd1WithEulerAAndUnknownHint_returns20Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SD1,
            sampler = SampleMethod.EULER_A,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(20, profile.steps)
    }

    @Test
    fun sd1WithDpmpp2mAndUnknownHint_returns16Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SD1,
            sampler = SampleMethod.DPMPP2M,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(16, profile.steps)
    }

    @Test
    fun sd1WithHeunAndUnknownHint_returns16Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SD1,
            sampler = SampleMethod.HEUN,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(16, profile.steps)
    }

    @Test
    fun unknownArchWithEulerAAndUnknownHint_returns20Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.UNKNOWN,
            sampler = SampleMethod.EULER_A,
            distilledHint = DistilledHint.UNKNOWN,
        )
        assertEquals(20, profile.steps)
    }

    // --- DistilledHint.NO behaves same as UNKNOWN ---

    @Test
    fun sd1WithEulerAAndNoHint_returns20Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.SD1,
            sampler = SampleMethod.EULER_A,
            distilledHint = DistilledHint.NO,
        )
        assertEquals(20, profile.steps)
    }

    @Test
    fun fluxWithEulerAndNoHint_returns25Steps() {
        val profile = DiffusionStepPolicy.recommend(
            architecture = SdArchitecture.FLUX,
            sampler = SampleMethod.EULER,
            distilledHint = DistilledHint.NO,
        )
        assertEquals(25, profile.steps)
    }
}
