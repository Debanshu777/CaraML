package com.debanshu777.caraml.core.rating

import com.debanshu777.diffusionrunner.SampleMethod

/** Carries the recommended step count and a human-readable rationale. */
data class DiffusionStepProfile(val steps: Int, val reason: String)

/** Caller's knowledge of whether the model has been distilled. */
enum class DistilledHint { UNKNOWN, YES, NO }

/** Stateless policy object that maps (architecture, sampler, distillation) → optimal step count. */
object DiffusionStepPolicy {

    /** Returns true for samplers that converge faster and need fewer steps. */
    private fun isHigherOrder(sampler: SampleMethod): Boolean = sampler in setOf(
        SampleMethod.HEUN,
        SampleMethod.DPMPP2M,
        SampleMethod.DPMPP2M_V2,
    )

    /** Returns the recommended [DiffusionStepProfile] for the given inputs. */
    fun recommend(
        architecture: SdArchitecture,
        sampler: SampleMethod,
        distilledHint: DistilledHint,
    ): DiffusionStepProfile {
        if (distilledHint == DistilledHint.YES) {
            return when (sampler) {
                SampleMethod.LCM -> DiffusionStepProfile(6, "distilled model with LCM sampler")
                SampleMethod.TCD -> DiffusionStepProfile(8, "distilled model with TCD sampler")
                else -> DiffusionStepProfile(4, "distilled model")
            }
        }

        return when (architecture) {
            SdArchitecture.FLUX -> DiffusionStepProfile(25, "flow-matching (FLUX)")
            SdArchitecture.SD3 -> DiffusionStepProfile(28, "flow-matching (SD3)")
            SdArchitecture.WAN_SMALL,
            SdArchitecture.WAN_LARGE -> DiffusionStepProfile(25, "flow-matching (Wan)")
            SdArchitecture.SDXL -> if (isHigherOrder(sampler)) {
                DiffusionStepProfile(20, "SDXL with higher-order sampler")
            } else {
                DiffusionStepProfile(25, "SDXL")
            }
            SdArchitecture.SD1 -> if (isHigherOrder(sampler)) {
                DiffusionStepProfile(16, "SD1/2 with higher-order sampler")
            } else {
                DiffusionStepProfile(20, "SD1/2")
            }
            SdArchitecture.UNKNOWN -> DiffusionStepProfile(20, "unknown architecture fallback")
        }
    }
}
