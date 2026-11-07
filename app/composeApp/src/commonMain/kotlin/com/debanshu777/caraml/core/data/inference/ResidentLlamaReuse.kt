package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.plugin.llama.bridge.NativeRunnerConfig

/** Pure candidate matching only; does not admit a load or establish current filesystem validity.
 *
 * Integration must compare the current manifest generation/path/size while holding the existing
 * artifact root lease and native-session ownership. A match never substitutes for that lease.
 * Store a key only after a successful load, and clear it on release or native-session failure.
 */
@ConsistentCopyVisibility
internal data class ResidentLlamaReuseKey private constructor(
    private val model: LocalModelEntity,
    private val artifact: ResolvedLocalArtifact,
    private val plan: LlmRunPlan,
    private val settings: AppSettings,
    val config: NativeRunnerConfig,
    private val engineVersion: String,
) {
    companion object {
        fun from(
            request: LoadRequest,
            settings: AppSettings,
            config: NativeRunnerConfig,
            engineVersion: String,
        ): ResidentLlamaReuseKey? {
            val plan = request.plan as? LlmRunPlan ?: return null
            val artifact = request.artifact ?: return null
            val target = artifact.loadTarget as? VerifiedArtifactLoadTarget.File ?: return null
            if (artifact.identity != request.identity || target.path != request.model.localPath ||
                artifact.components.isEmpty() || config.nCtx <= 0 || !settings.temperature.isFinite() ||
                engineVersion.isBlank()
            ) return null
            return ResidentLlamaReuseKey(
                // Selecting/using a model updates this counter without changing its native state.
                model = request.model.copy(usageCount = 0),
                artifact = artifact.copy(
                    components = artifact.components.toList(),
                    revisionIdentity = artifact.revisionIdentity.copy(commits = artifact.revisionIdentity.commits.toList()),
                ),
                plan = plan,
                // These UI flags do not affect native configuration, prompt or admission policy.
                // Retaining the value type makes future settings fail closed by default.
                settings = settings.copy(
                    benchmarkMode = false,
                    modelProfileOnboardingComplete = false,
                    recommendationCalibrationOfferComplete = false,
                ),
                config = config,
                engineVersion = engineVersion,
            )
        }
    }
}

internal enum class ResidentLlamaState { READY, LOADING, GENERATING, FAILED, UNLOADED }
internal enum class ResidentLlamaIntent { SELECT, RETRY, RELOAD }

internal fun canReuseResidentLlama(
    resident: ResidentLlamaReuseKey?,
    candidate: ResidentLlamaReuseKey?,
    state: ResidentLlamaState,
    contextLimit: Int,
    intent: ResidentLlamaIntent,
): Boolean = intent == ResidentLlamaIntent.SELECT &&
    state == ResidentLlamaState.READY &&
    resident != null && candidate != null && resident == candidate &&
    contextLimit > 0 && contextLimit == resident.config.nCtx
