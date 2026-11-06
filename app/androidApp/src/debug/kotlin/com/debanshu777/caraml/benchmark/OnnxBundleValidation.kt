package com.debanshu777.caraml.benchmark

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import com.debanshu777.caraml.core.data.inference.*
import com.debanshu777.caraml.core.data.settings.SettingsRepository
import com.debanshu777.caraml.core.download.*
import com.debanshu777.caraml.core.download.transfer.*
import com.debanshu777.caraml.core.recommendation.*
import com.debanshu777.caraml.core.recommendation.storage.PersistedModelEvidenceCodec
import com.debanshu777.caraml.core.settings.AppSettings
import com.debanshu777.caraml.core.storage.localmodel.*
import com.debanshu777.caraml.features.chat.domain.GenerationMode
import com.debanshu777.caraml.features.modelhub.domain.*
import com.debanshu777.caraml.features.modelhub.presentation.search.ModelHubBrowseMode
import com.debanshu777.caraml.sdk.plugin.llama.bridge.StopReason
import com.debanshu777.caraml.sdk.plugin.onnx.createOnnxTextRuntime
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import org.koin.mp.KoinPlatform

/** Exercises public HF discovery, real downloads, atomic publication, admission and chat.
 * Uses a fixed public model and synthetic prompts; does not alter personal settings. */
class OnnxBundleValidation : Instrumentation() {
    private var caseId = "smollm2-135m"
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        require(arguments == null || arguments.keySet().all { it == "case" })
        caseId = arguments?.getString("case") ?: caseId
        require(caseId in cases)
        start()
    }
    override fun onStart() = runBlocking {
        waitForIdleSync()
        com.debanshu777.caraml.core.settings.initPreferencesDataStore(targetContext)
        val koin = KoinPlatform.getKoin()
        var foreground: Activity? = null
        var ownedBatch: String? = null
        var inference: InferenceRepository? = null
        var code = Activity.RESULT_CANCELED
        fun report(stage: String, detail: String) = sendStatus(0, Bundle().apply {
            putString("stream", "CARAML_ONNX_VALIDATION ${JSONObject().put("case", caseId).put("stage", stage).put("detail", detail)}\n")
        })
        try {
            withTimeout(20 * 60_000L) {
                foreground = startActivitySync(Intent(targetContext, PerformanceForegroundActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                koin.get<DownloadRuntime>().awaitStartupReconciliation()
                val (repo, graph) = cases.getValue(caseId)
                val variants = koin.get<ModelMetadataSource>().describeVariants(repo, ModelHubBrowseMode.LanguageModels)
                val descriptor = (variants as? RepositoryVariantSet.Ready)?.variants?.map { it.descriptor }
                    ?.filterIsInstance<LlmModelDescriptor>()?.single { it.file.path == graph }
                    ?: error("ONNX discovery failed: $variants")
                check(descriptor.format == ModelFormat.ONNX)
                val requests = requireNotNull(onnxDownloadRequests(descriptor.files.map { file ->
                    val remoteId = file.lfsOid?.let { "sha256:" + it.removePrefix("sha256:") }
                        ?: file.gitOid ?: error("Missing remote hash")
                    DownloadMetadataDTO(requireNotNull(DownloadArtifactIdentity.create(repo, file.revision, file.path, remoteId, file.sizeBytes)),
                        "model", file.sizeBytes, "onnx-community", "onnx", "text-generation")
                }))
                report("discovery", "${requests.size} files, revision ${descriptor.files.first().revision}")
                val batchRequest = DownloadBatchRequest(repo, ModelType.TEXT, requests,
                    DownloadEvidenceFactory(PersistedModelEvidenceCodec()).create(requests, descriptor), true, "ONNX validation: $caseId")
                val store = koin.get<DownloadTaskStore>()
                val coordinator = koin.get<DownloadCoordinator>()
                val existing = store.getBatch(downloadBatchId(batchRequest))
                val batchId = existing?.batchId ?: coordinator.enqueue(batchRequest).also { ownedBatch = it }
                check(existing == null || existing.state == DownloadBatchState.COMPLETED || existing.userIntent == DownloadUserIntent.RUN) {
                    "Existing transfer is paused or cancelled; preserving user intent"
                }
                val completed = coordinator.observeForModel(repo).mapNotNull { all -> all.singleOrNull { it.batchId == batchId } }
                    .distinctUntilChangedBy { it.state }.onEach { report("download", "${it.state}: ${it.failureCode}") }
                    .first { it.state in setOf(DownloadBatchState.COMPLETED, DownloadBatchState.FAILED_TERMINAL, DownloadBatchState.FAILED_RETRYABLE, DownloadBatchState.CANCELLED) }
                check(completed.state == DownloadBatchState.COMPLETED)
                ownedBatch = null
                val model = koin.get<LocalModelRepository>().getMainModels().single { it.modelId == repo && it.filename == graph.substringAfterLast('/') }
                report("publication", model.filename)
                val resolver = koin.get<InstalledModelLoadRequestResolver>()
                val preparation = resolver.prepare(model, GenerationMode.Text)
                check(preparation is InstalledModelLoadPreparation.Ready) { "Prepare: $preparation" }
                (preparation.descriptor as? LlmModelDescriptor)?.let {
                    report("prepared_metadata", "format=${it.format}; architecture=${it.architecture}; context=${it.contextLimit}; shape=${it.transformerShape}")
                }
                val resolution = resolver.resolve(preparation)
                val request = when (resolution) {
                    is InstalledModelLoadResolution.Ready -> resolution.request
                    is InstalledModelLoadResolution.SafeAlternative -> resolution.saferRequest
                    else -> error("Resolve: $resolution")
                }
                val settings = object : SettingsRepository {
                    override fun getSettings() = flowOf(AppSettings(systemPrompt = "You are a helpful assistant.", temperature = 0f))
                    override suspend fun updateSettings(settings: AppSettings) = error("Read only")
                    override suspend fun updateRecommendationProfile(profile: RecommendationProfile) = error("Read only")
                    override suspend fun completeModelProfileOnboarding(profile: RecommendationProfile) = error("Read only")
                }
                val engine = OnnxInferenceRepository(createOnnxTextRuntime(), settings, koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get())
                inference = engine
                suspend fun loadForDeviceValidation(): ModelLoadResult {
                    val result = engine.loadModel(request)
                    val admission = (result as? ModelLoadResult.AdmissionRequired)?.admission as? LoadAdmission.ConfirmationRequired
                    // This explicit debug validation is authorized by the device-test task. Keep all
                    // memory/thermal/integrity blocks and crash quarantine intact.
                    if (admission?.reason == LoadAdmissionReason.RISK_ACKNOWLEDGEMENT_REQUIRED && !admission.explicitRetryRequired) {
                        report("risk_acknowledged", "Conservative ONNX memory estimate; ${request.plan.stableKey}")
                        return engine.loadModel(request.copy(riskAcknowledgement = RiskAcknowledgement(
                            request.assessmentKey, request.plan.stableKey, System.currentTimeMillis())))
                    }
                    return result
                }
                val load = loadForDeviceValidation()
                check(load is ModelLoadResult.Success) { "Load: ${load::class.simpleName}" }
                report("load", engine.getRuntimeConfigString())
                for (prompt in listOf("My name is Mira. What is my name? Answer briefly.", "Repeat my name in one short sentence.")) {
                    val text = StringBuilder()
                    val reasoning = StringBuilder()
                    var chunks = 0
                    engine.generateResponse(prompt).collect { chunk ->
                        text.append(chunk.contentDelta)
                        reasoning.append(chunk.reasoningDelta)
                        if (++chunks >= if (caseId == "qwen3-06b") 256 else 64) engine.cancelGeneration()
                    }
                    check(text.isNotBlank()) { "Model did not reach a final answer within the validation budget" }
                    report("turn", "content=$text; reasoning_chars=${reasoning.length}")
                }
                engine.resetContext()
                var chunks = 0
                engine.generateResponse("List 200 numbers with explanations.").collect { if (++chunks >= 4) engine.cancelGeneration() }
                check(engine.getStopReason() == StopReason.CANCELLED)
                engine.unloadModel()
                check(engine.getContextLimit() == 0)
                check(loadForDeviceValidation() is ModelLoadResult.Success)
                report("complete", "discovery, download, publication, two turns, cancellation and reload passed")
                code = Activity.RESULT_OK
            }
        } catch (failure: Exception) {
            android.util.Log.e("OnnxBundleValidation", "Validation failed", failure)
            report("error", failure.toString().take(1200))
        } finally {
            ownedBatch?.let { koin.get<DownloadCoordinator>().pause(it) }
            inference?.unloadModel()
            foreground?.let { runOnMainSync { it.finish() } }
            finish(code, Bundle())
        }
    }

    companion object {
        private val cases = mapOf(
            "smollm2-135m" to ("onnx-community/SmolLM2-135M-Instruct-ONNX" to "onnx/model_int8.onnx"),
            "smollm2-360m" to ("onnx-community/SmolLM2-360M-Instruct-ONNX" to "onnx/model_int8.onnx"),
            "qwen25-05b" to ("onnx-community/Qwen2.5-0.5B-Instruct" to "onnx/model_int8.onnx"),
            "qwen3-06b" to ("onnx-community/Qwen3-0.6B-ONNX" to "onnx/model_int8.onnx"),
            "llama32-1b" to ("onnx-community/Llama-3.2-1B-Instruct-ONNX" to "onnx/model_q4.onnx"),
        )
    }
}
