package com.debanshu777.caraml.core.data.inference

import com.debanshu777.caraml.core.platform.BackendKind
import com.debanshu777.caraml.core.recommendation.KvCacheType
import com.debanshu777.caraml.core.recommendation.LlmRunPlan
import com.debanshu777.caraml.core.recommendation.LoadRequest
import com.debanshu777.caraml.core.recommendation.ResolvedLocalArtifact
import com.debanshu777.caraml.core.recommendation.VerifiedArtifactLoadTarget
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.localmodel.LocalModelEntity
import com.debanshu777.caraml.sdk.plugin.onnx.OnnxExecutionProvider
import com.debanshu777.caraml.sdk.plugin.onnx.OnnxRuntimeConfig

/** Equality is necessary, but reuse still requires idle session ownership and a current artifact lease. */
@ConsistentCopyVisibility
internal data class ResidentOnnxReuseKey private constructor(
    private val model: LocalModelEntity,
    private val artifact: ResolvedLocalArtifact,
    private val plan: LlmRunPlan,
    private val settings: AppSettings,
    val config: OnnxRuntimeConfig,
    private val engineVersion: String,
) {
    companion object {
        fun from(
            request: LoadRequest,
            settings: AppSettings,
            config: OnnxRuntimeConfig,
            engineVersion: String,
        ): ResidentOnnxReuseKey? {
            val plan = request.plan as? LlmRunPlan ?: return null
            val artifact = request.artifact ?: return null
            val target = artifact.loadTarget as? VerifiedArtifactLoadTarget.File ?: return null
            if (!request.model.filename.endsWith(".onnx", true) || artifact.identity != request.identity ||
                target.path != request.model.localPath || artifact.components.isEmpty() ||
                plan.backend != BackendKind.CPU || plan.keyCacheType != KvCacheType.F32 ||
                plan.valueCacheType != KvCacheType.F32 || plan.contextTokens != config.contextTokens ||
                config.provider != OnnxExecutionProvider.CPU || config.temperature != settings.temperature ||
                engineVersion.isBlank() || request.nativeMetadataFallback
            ) return null
            return ResidentOnnxReuseKey(
                model = request.model.copy(usageCount = 0),
                artifact = artifact.copy(
                    components = artifact.components.toList(),
                    revisionIdentity = artifact.revisionIdentity.copy(commits = artifact.revisionIdentity.commits.toList()),
                ),
                plan = plan,
                // Preserve the value type so future runtime or admission settings invalidate by default.
                settings = settings.copy(benchmarkMode = false, modelProfileOnboardingComplete = false,
                    recommendationCalibrationOfferComplete = false),
                config = config,
                engineVersion = engineVersion,
            )
        }
    }
}
