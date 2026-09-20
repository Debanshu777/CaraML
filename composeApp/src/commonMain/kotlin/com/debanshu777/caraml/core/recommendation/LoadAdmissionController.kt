package com.debanshu777.caraml.core.recommendation

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.platform.DeviceSnapshot
import com.debanshu777.caraml.core.platform.ThermalState
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity

data class LoadRequest(
    val model: LocalModelEntity,
    val identity: ModelFileIdentity,
    val observationIdentity: ObservationModelIdentity,
    val plan: RunPlan,
    val assessmentKey: String,
    val artifact: ResolvedLocalArtifact? = null,
    val assessedPlans: AssessedPlans? = null,
    val profile: RecommendationProfile = RecommendationProfile(),
    val riskAcknowledgement: RiskAcknowledgement? = null,
    val backendAlternative: LoadRequest? = null,
)

data class RiskAcknowledgement(
    val assessmentKey: String,
    val planKey: String,
    val acknowledgedAtEpochMs: Long,
)

enum class LoadAdmissionReason {
    CURRENT_MEMORY_PRESSURE,
    CURRENT_THERMAL_PRESSURE,
    RESOURCE_SNAPSHOT_UNAVAILABLE,
    INCOMPATIBLE_MODEL,
    INSUFFICIENT_INFORMATION,
    NO_SAFE_CONFIGURATION,
    INVALID_MODEL,
    NATIVE_PREFLIGHT_INVALID,
    NATIVE_BACKEND_INCOMPATIBLE,
    NATIVE_PREFLIGHT_UNAVAILABLE,
    RISK_ACKNOWLEDGEMENT_REQUIRED,
    SUSPECTED_PREVIOUS_CRASH,
    KNOWN_UNSTABLE_CONFIGURATION,
}

sealed interface LoadAdmission {
    data class Ready(val request: LoadRequest) : LoadAdmission

    data class ConfirmationRequired(
        val request: LoadRequest,
        val reason: LoadAdmissionReason = LoadAdmissionReason.RISK_ACKNOWLEDGEMENT_REQUIRED,
        val explicitRetryRequired: Boolean = false,
    ) : LoadAdmission

    data class AlternativeAvailable(
        val original: LoadRequest,
        val saferPlan: RunPlan,
        val reason: LoadAdmissionReason = LoadAdmissionReason.NO_SAFE_CONFIGURATION,
        val saferRequest: LoadRequest = original.copy(
            plan = saferPlan,
            riskAcknowledgement = null,
            backendAlternative = null,
        ),
    ) : LoadAdmission

    data class SafeAlternativeAvailable(
        val saferRequest: LoadRequest,
        val reason: LoadAdmissionReason = LoadAdmissionReason.NO_SAFE_CONFIGURATION,
    ) : LoadAdmission {
        init {
            require(saferRequest.plan.backend == BackendKind.CPU)
            require(saferRequest.backendAlternative == null)
            require(saferRequest.riskAcknowledgement == null)
            require(saferRequest.assessmentKey.isNotBlank())
            require(saferRequest.assessedPlans?.assessmentKey == saferRequest.assessmentKey)
            require(saferRequest.artifact?.identity == saferRequest.identity)
        }
    }

    data class TemporarilyUnavailable(
        val request: LoadRequest,
        val reason: LoadAdmissionReason,
    ) : LoadAdmission

    data class Blocked(
        val request: LoadRequest,
        val reason: LoadAdmissionReason,
    ) : LoadAdmission
}

sealed interface NativeLoadPreflight {
    data object Fit : NativeLoadPreflight
    data object NoFit : NativeLoadPreflight
    data class BackendIncompatible(val saferRequest: LoadRequest) : NativeLoadPreflight
    data object Invalid : NativeLoadPreflight
    data object Unavailable : NativeLoadPreflight
}

enum class LoadQuarantine {
    NONE,
    TEMPORARY,
    KNOWN_UNSTABLE,
}

interface LoadRecoveryState {
    suspend fun quarantine(
        identity: ModelFileIdentity,
        plan: RunPlan,
        engineVersion: String,
    ): LoadQuarantine

    suspend fun allowExplicitRetry(
        identity: ModelFileIdentity,
        plan: RunPlan,
        engineVersion: String,
    )
}

class LoadAdmissionController(
    private val snapshotSource: suspend () -> DeviceSnapshot,
    private val recommendationSource: suspend (LoadRequest, DeviceSnapshot) -> PersonalizedRecommendation,
    private val artifactValidator: suspend (LoadRequest) -> Boolean,
    private val nativePreflight: suspend (LoadRequest) -> NativeLoadPreflight,
    private val recoveryState: LoadRecoveryState,
    private val engineVersion: String,
    private val clock: () -> Long,
) {
    init {
        require(engineVersion.isNotBlank() && engineVersion.length <= MAX_ENGINE_VERSION_LENGTH)
        require(engineVersion.none(Char::isISOControl))
    }

    suspend fun evaluate(
        request: LoadRequest,
        acknowledgement: RiskAcknowledgement?,
    ): LoadAdmission {
        if (validateRunPlan(request.plan) != null || request.assessmentKey.isBlank()) {
            return LoadAdmission.Blocked(request, LoadAdmissionReason.INSUFFICIENT_INFORMATION)
        }

        val snapshot = snapshotSource()
        if (!snapshot.isFresh) {
            return LoadAdmission.TemporarilyUnavailable(request, LoadAdmissionReason.RESOURCE_SNAPSHOT_UNAVAILABLE)
        }
        if (snapshot.resources.lowMemory == true) {
            return LoadAdmission.TemporarilyUnavailable(request, LoadAdmissionReason.CURRENT_MEMORY_PRESSURE)
        }
        if (snapshot.resources.thermalState in setOf(ThermalState.SERIOUS, ThermalState.CRITICAL)) {
            return LoadAdmission.TemporarilyUnavailable(request, LoadAdmissionReason.CURRENT_THERMAL_PRESSURE)
        }

        val recommendation = recommendationSource(request, snapshot)
        val fallback = recommendation.fallbackPlan as? RunPlan
        val selected = recommendation.selectedPlan as? RunPlan
        when (recommendation.category) {
            RecommendationCategory.INCOMPATIBLE ->
                return LoadAdmission.Blocked(request, LoadAdmissionReason.INCOMPATIBLE_MODEL)
            RecommendationCategory.NEEDS_INFORMATION ->
                return LoadAdmission.Blocked(request, LoadAdmissionReason.INSUFFICIENT_INFORMATION)
            RecommendationCategory.NOT_SUITABLE -> if (!request.matches(fallback)) {
                return fallback?.let { request.alternative(it, LoadAdmissionReason.NO_SAFE_CONFIGURATION) }
                    ?: LoadAdmission.Blocked(request, LoadAdmissionReason.NO_SAFE_CONFIGURATION)
            }
            RecommendationCategory.RECOMMENDED,
            RecommendationCategory.USABLE,
            RecommendationCategory.RISKY,
            -> Unit
        }

        if (!request.matches(selected) && !request.matches(fallback)) {
            return (selected ?: fallback)?.let {
                request.alternative(it, LoadAdmissionReason.NO_SAFE_CONFIGURATION)
            } ?: LoadAdmission.Blocked(request, LoadAdmissionReason.NO_SAFE_CONFIGURATION)
        }

        if (recommendation.category == RecommendationCategory.RISKY &&
            !acknowledgement.isValidFor(request, recommendation.assessmentKey, clock())
        ) {
            return LoadAdmission.ConfirmationRequired(request)
        }

        when (recoveryState.quarantine(request.identity, request.plan, engineVersion)) {
            LoadQuarantine.NONE -> Unit
            LoadQuarantine.TEMPORARY -> return fallback
                ?.takeUnless { request.matches(it) }
                ?.let { request.alternative(it, LoadAdmissionReason.SUSPECTED_PREVIOUS_CRASH) }
                ?: LoadAdmission.ConfirmationRequired(
                    request,
                    LoadAdmissionReason.SUSPECTED_PREVIOUS_CRASH,
                    explicitRetryRequired = true,
                )
            LoadQuarantine.KNOWN_UNSTABLE -> return fallback
                ?.takeUnless { request.matches(it) }
                ?.let { request.alternative(it, LoadAdmissionReason.KNOWN_UNSTABLE_CONFIGURATION) }
                ?: LoadAdmission.ConfirmationRequired(
                    request,
                    LoadAdmissionReason.KNOWN_UNSTABLE_CONFIGURATION,
                    explicitRetryRequired = true,
                )
        }

        if (!artifactValidator(request)) {
            return LoadAdmission.Blocked(request, LoadAdmissionReason.INVALID_MODEL)
        }

        return when (val preflight = classifyNativePreflight(request, snapshot)) {
            NativeLoadPreflight.Fit -> LoadAdmission.Ready(request)
            NativeLoadPreflight.NoFit -> fallback
                ?.takeUnless { request.matches(it) }
                ?.let { request.alternative(it, LoadAdmissionReason.NO_SAFE_CONFIGURATION) }
                ?: LoadAdmission.Blocked(request, LoadAdmissionReason.NO_SAFE_CONFIGURATION)
            is NativeLoadPreflight.BackendIncompatible -> request.alternative(
                preflight.saferRequest,
                LoadAdmissionReason.NATIVE_BACKEND_INCOMPATIBLE,
            )
            NativeLoadPreflight.Invalid -> LoadAdmission.Blocked(
                request,
                LoadAdmissionReason.NATIVE_PREFLIGHT_INVALID,
            )
            NativeLoadPreflight.Unavailable -> LoadAdmission.TemporarilyUnavailable(
                request,
                LoadAdmissionReason.NATIVE_PREFLIGHT_UNAVAILABLE,
            )
        }
    }

    private suspend fun classifyNativePreflight(
        request: LoadRequest,
        snapshot: DeviceSnapshot,
    ): NativeLoadPreflight {
        val requestedResult = nativePreflight(request)
        if (requestedResult != NativeLoadPreflight.Invalid) return requestedResult
        val requestedPlan = request.plan as? LlmRunPlan ?: return requestedResult
        if (requestedPlan.backend == BackendKind.CPU) return requestedResult
        val alternative = request.backendAlternative ?: return requestedResult
        val alternativePlan = alternative.plan as? LlmRunPlan ?: return requestedResult
        if (alternativePlan.backend != BackendKind.CPU ||
            validateRunPlan(alternativePlan) != null ||
            request.matches(alternativePlan) ||
            !alternative.isExactBackendAlternativeFor(request) ||
            !artifactValidator(alternative)
        ) {
            return requestedResult
        }
        val recommendation = recommendationSource(alternative, snapshot)
        if (!alternative.matches(recommendation.admissiblePlan() as? RunPlan)) return requestedResult
        return if (nativePreflight(alternative) == NativeLoadPreflight.Fit) {
            NativeLoadPreflight.BackendIncompatible(alternative)
        } else {
            requestedResult
        }
    }

    private fun PersonalizedRecommendation.admissiblePlan(): PlanReference? = when (category) {
        RecommendationCategory.RECOMMENDED,
        RecommendationCategory.USABLE,
        RecommendationCategory.RISKY,
        -> selectedPlan
        RecommendationCategory.NOT_SUITABLE -> fallbackPlan
        RecommendationCategory.INCOMPATIBLE,
        RecommendationCategory.NEEDS_INFORMATION,
        -> null
    }

    suspend fun allowExplicitRetry(request: LoadRequest) {
        recoveryState.allowExplicitRetry(request.identity, request.plan, engineVersion)
    }

    private fun LoadRequest.matches(other: RunPlan?): Boolean = other?.stableKey == plan.stableKey

    private fun LoadRequest.alternative(plan: RunPlan, reason: LoadAdmissionReason) =
        LoadAdmission.AlternativeAvailable(this, plan, reason)

    private fun LoadRequest.alternative(request: LoadRequest, reason: LoadAdmissionReason) =
        LoadAdmission.AlternativeAvailable(this, request.plan, reason, request)

    private fun LoadRequest.isExactBackendAlternativeFor(original: LoadRequest): Boolean =
        backendAlternative == null &&
            riskAcknowledgement == null &&
            model == original.model &&
            identity == original.identity &&
            observationIdentity == original.observationIdentity &&
            artifact == original.artifact &&
            profile == original.profile &&
            assessmentKey.isNotBlank() &&
            assessedPlans?.assessmentKey == assessmentKey

    private fun RiskAcknowledgement?.isValidFor(
        request: LoadRequest,
        currentAssessmentKey: String,
        now: Long,
    ): Boolean {
        if (this == null) return false
        val age = now - acknowledgedAtEpochMs
        return assessmentKey == request.assessmentKey &&
            assessmentKey == currentAssessmentKey &&
            planKey == request.plan.stableKey &&
            age in 0..RecommendationPolicyV1.RESOURCE_SNAPSHOT_MAX_AGE_MS
    }

    private companion object {
        const val MAX_ENGINE_VERSION_LENGTH = 128
    }
}
