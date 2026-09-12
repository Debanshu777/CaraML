#include <jni.h>

#include <cstdio>
#include <array>
#include <exception>
#include <new>
#include <string>

#ifdef __ANDROID__
#include <android/log.h>
#endif

#include "llama_runner_core.h"

namespace {

constexpr const char *LOG_TAG = "LlamaRunner";

void platform_log(LlamaLogLevel level, const char *msg) {
#ifdef __ANDROID__
    int prio = ANDROID_LOG_INFO;
    if (level == LLAMA_LOG_WARN) {
        prio = ANDROID_LOG_WARN;
    } else if (level == LLAMA_LOG_ERROR) {
        prio = ANDROID_LOG_ERROR;
    }
    __android_log_print(prio, LOG_TAG, "%s", msg ? msg : "");
#else
    const char *lvl = "INFO";
    if (level == LLAMA_LOG_WARN) {
        lvl = "WARN";
    } else if (level == LLAMA_LOG_ERROR) {
        lvl = "ERROR";
    }
    std::fprintf(stderr, "[%s] [%s] %s\n", LOG_TAG, lvl, msg ? msg : "");
#endif
}

class ScopedUtfChars {
public:
    ScopedUtfChars(JNIEnv *env, jstring value)
        : env_(env), value_(value), chars_(value ? env->GetStringUTFChars(value, nullptr) : nullptr) {}

    ~ScopedUtfChars() {
        if (chars_) env_->ReleaseStringUTFChars(value_, chars_);
    }

    ScopedUtfChars(const ScopedUtfChars &) = delete;
    ScopedUtfChars &operator=(const ScopedUtfChars &) = delete;

    const char *get() const { return chars_; }

private:
    JNIEnv *env_;
    jstring value_;
    const char *chars_;
};

class ScopedLocalRef {
public:
    ScopedLocalRef(JNIEnv *env, jobject value) : env_(env), value_(value) {}
    ~ScopedLocalRef() {
        if (value_) env_->DeleteLocalRef(value_);
    }
    ScopedLocalRef(const ScopedLocalRef &) = delete;
    ScopedLocalRef &operator=(const ScopedLocalRef &) = delete;
    jobject get() const { return value_; }

private:
    JNIEnv *env_;
    jobject value_;
};

bool read_runner_config(JNIEnv *env, jobject config_obj, LlamaRunnerConfig &config) {
    if (!config_obj || env->ExceptionCheck()) return false;
    ScopedLocalRef cls_ref(env, env->GetObjectClass(config_obj));
    jclass cls = reinterpret_cast<jclass>(cls_ref.get());
    if (!cls || env->ExceptionCheck()) return false;

    auto field = [&](const char *name, const char *signature) -> jfieldID {
        const jfieldID id = env->GetFieldID(cls, name, signature);
        return env->ExceptionCheck() ? nullptr : id;
    };
    const jfieldID n_ctx = field("nCtx", "I");
    const jfieldID n_ctx_min = field("nCtxMin", "I");
    const jfieldID n_threads = field("nThreads", "I");
    const jfieldID n_threads_batch = field("nThreadsBatch", "I");
    const jfieldID n_batch = field("nBatch", "I");
    const jfieldID n_ubatch = field("nUbatch", "I");
    const jfieldID flash_attn = field("flashAttn", "I");
    const jfieldID offload_kqv = field("offloadKqv", "Z");
    const jfieldID type_k = field("typeK", "I");
    const jfieldID type_v = field("typeV", "I");
    const jfieldID n_gpu_layers = field("nGpuLayers", "I");
    const jfieldID use_mmap = field("useMmap", "Z");
    const jfieldID use_mlock = field("useMlock", "Z");
    const jfieldID temperature = field("temperature", "F");
    const jfieldID auto_fit = field("autoFit", "Z");
    const jfieldID cpu_mask = field("cpuMask", "Ljava/lang/String;");
    const jfieldID cpu_mask_batch = field("cpuMaskBatch", "Ljava/lang/String;");
    if (!n_ctx || !n_ctx_min || !n_threads || !n_threads_batch || !n_batch ||
        !n_ubatch || !flash_attn || !offload_kqv || !type_k || !type_v ||
        !n_gpu_layers || !use_mmap || !use_mlock || !temperature || !auto_fit ||
        !cpu_mask || !cpu_mask_batch) {
        return false;
    }

    config.n_ctx = env->GetIntField(config_obj, n_ctx);
    config.n_ctx_min = env->GetIntField(config_obj, n_ctx_min);
    config.n_threads = env->GetIntField(config_obj, n_threads);
    config.n_threads_batch = env->GetIntField(config_obj, n_threads_batch);
    config.n_batch = env->GetIntField(config_obj, n_batch);
    config.n_ubatch = env->GetIntField(config_obj, n_ubatch);
    config.flash_attn = env->GetIntField(config_obj, flash_attn);
    config.offload_kqv = env->GetBooleanField(config_obj, offload_kqv) != JNI_FALSE;
    config.type_k = env->GetIntField(config_obj, type_k);
    config.type_v = env->GetIntField(config_obj, type_v);
    config.n_gpu_layers = env->GetIntField(config_obj, n_gpu_layers);
    config.use_mmap = env->GetBooleanField(config_obj, use_mmap) != JNI_FALSE;
    config.use_mlock = env->GetBooleanField(config_obj, use_mlock) != JNI_FALSE;
    config.temperature = env->GetFloatField(config_obj, temperature);
    config.auto_fit = env->GetBooleanField(config_obj, auto_fit) != JNI_FALSE;
    if (env->ExceptionCheck()) return false;

    ScopedLocalRef mask_ref(env, env->GetObjectField(config_obj, cpu_mask));
    ScopedLocalRef batch_mask_ref(env, env->GetObjectField(config_obj, cpu_mask_batch));
    if (env->ExceptionCheck()) return false;
    jstring mask_string = reinterpret_cast<jstring>(mask_ref.get());
    jstring batch_mask_string = reinterpret_cast<jstring>(batch_mask_ref.get());
    if (mask_string) {
        ScopedUtfChars mask(env, mask_string);
        if (!mask.get() || env->ExceptionCheck()) return false;
        config.cpu_mask = mask.get();
    }
    if (batch_mask_string) {
        ScopedUtfChars mask(env, batch_mask_string);
        if (!mask.get() || env->ExceptionCheck()) return false;
        config.cpu_mask_batch = mask.get();
    }
    return !env->ExceptionCheck();
}

template <typename Result, typename Action>
Result jni_guard(const char *operation, Result fallback, Action &&action) noexcept {
    try {
        return action();
    } catch (const std::bad_alloc &) {
        platform_log(LLAMA_LOG_ERROR, "JNI allocation failed");
    } catch (const std::exception &) {
        platform_log(LLAMA_LOG_ERROR, operation);
    } catch (...) {
        platform_log(LLAMA_LOG_ERROR, "Unknown JNI native failure");
    }
    return fallback;
}

template <typename Action>
void jni_guard_void(const char *operation, Action &&action) noexcept {
    (void)jni_guard<int>(operation, 0, [&action]() {
        action();
        return 1;
    });
}

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeInit(JNIEnv *env, jobject, jstring libDir) {
    jni_guard_void("nativeInit failed", [&]() {
        ScopedUtfChars path(env, libDir);
        if (!path.get()) return;
        llama_runner_core_set_logger(platform_log);
        llama_runner_core_init(path.get());
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeLoadModel(
    JNIEnv *env,
    jobject,
    jstring modelPath,
    jobject configObj) {
    return jni_guard<jboolean>(
        "nativeLoadModel failed",
        static_cast<jboolean>(JNI_FALSE),
        [&]() {
            ScopedUtfChars path(env, modelPath);
            if (!path.get() || !configObj) return static_cast<jboolean>(JNI_FALSE);
            LlamaRunnerConfig config;
            if (!read_runner_config(env, configObj, config)) return static_cast<jboolean>(JNI_FALSE);

            const bool ok = llama_runner_core_load_model(path.get(), config);
            return static_cast<jboolean>(ok ? JNI_TRUE : JNI_FALSE);
        });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativePreflightModel(
    JNIEnv *env,
    jobject,
    jstring modelPath,
    jobject configObj) {
    return jni_guard<jlongArray>("nativePreflightModel failed", nullptr, [&]() {
        ScopedUtfChars path(env, modelPath);
        LlamaRunnerConfig config;
        if (!path.get() || env->ExceptionCheck() || !read_runner_config(env, configObj, config)) {
            return static_cast<jlongArray>(nullptr);
        }
        const LlamaPreflightResultNative native = llama_runner_core_preflight(path.get(), config);
        if (native.pool_count < 0 || native.pool_count > LLAMA_PREFLIGHT_MAX_POOLS) {
            return static_cast<jlongArray>(nullptr);
        }
        constexpr size_t header_fields = 4;
        constexpr size_t pool_fields = 7;
        const size_t field_count = header_fields + static_cast<size_t>(native.pool_count) * pool_fields;
        std::array<jlong, header_fields + LLAMA_PREFLIGHT_MAX_POOLS * pool_fields> values{};
        values[0] = static_cast<jlong>(native.status);
        values[1] = static_cast<jlong>(native.n_ctx);
        values[2] = static_cast<jlong>(native.n_gpu_layers);
        values[3] = static_cast<jlong>(native.pool_count);
        for (int index = 0; index < native.pool_count; index++) {
            const size_t offset = header_fields + static_cast<size_t>(index) * pool_fields;
            const LlamaPreflightMemoryPool &pool = native.pools[index];
            values[offset] = static_cast<jlong>(pool.kind);
            values[offset + 1] = static_cast<jlong>(pool.ordinal);
            values[offset + 2] = static_cast<jlong>(pool.model_bytes);
            values[offset + 3] = static_cast<jlong>(pool.context_bytes);
            values[offset + 4] = static_cast<jlong>(pool.compute_bytes);
            values[offset + 5] = static_cast<jlong>(pool.free_bytes);
            values[offset + 6] = static_cast<jlong>(pool.total_bytes);
        }
        jlongArray result = env->NewLongArray(static_cast<jsize>(field_count));
        if (!result || env->ExceptionCheck()) return static_cast<jlongArray>(nullptr);
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(field_count), values.data());
        return env->ExceptionCheck() ? static_cast<jlongArray>(nullptr) : result;
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeBackendCapabilities(JNIEnv *env, jobject) {
    return jni_guard<jlongArray>("nativeBackendCapabilities failed", nullptr, [&]() {
        const LlamaBackendCapabilitiesNative native = llama_runner_core_backend_capabilities();
        if (native.count <= 0 || native.count > LLAMA_BACKEND_MAX_DEVICES) {
            return static_cast<jlongArray>(nullptr);
        }
        constexpr size_t header_fields = 1;
        constexpr size_t record_fields = 13;
        const size_t field_count = header_fields + static_cast<size_t>(native.count) * record_fields;
        std::array<jlong, header_fields + LLAMA_BACKEND_MAX_DEVICES * record_fields> values{};
        values[0] = static_cast<jlong>(native.count);
        for (int index = 0; index < native.count; index++) {
            const size_t offset = header_fields + static_cast<size_t>(index) * record_fields;
            const LlamaBackendCapabilityNative &device = native.devices[index];
            values[offset] = static_cast<jlong>(device.kind);
            values[offset + 1] = static_cast<jlong>(device.device_type);
            values[offset + 2] = static_cast<jlong>(device.free_bytes);
            values[offset + 3] = static_cast<jlong>(device.total_bytes);
            values[offset + 4] = static_cast<jlong>(device.device_identity_length);
            for (size_t word = 0; word < 8; ++word) {
                values[offset + 5 + word] = static_cast<jlong>(device.device_identity_words[word]);
            }
        }
        jlongArray result = env->NewLongArray(static_cast<jsize>(field_count));
        if (!result || env->ExceptionCheck()) return static_cast<jlongArray>(nullptr);
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(field_count), values.data());
        return env->ExceptionCheck() ? static_cast<jlongArray>(nullptr) : result;
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeCalibrateBackend(
    JNIEnv *env,
    jobject,
    jint backend,
    jint durationMillis,
    jlong bufferBytes) {
    return jni_guard<jlongArray>("nativeCalibrateBackend failed", nullptr, [&]() {
        const LlamaCalibrationResultNative native = llama_runner_core_calibrate_backend(
            static_cast<int>(backend),
            static_cast<int>(durationMillis),
            static_cast<int64_t>(bufferBytes));
        if (native.window_count < 0 || native.window_count > LLAMA_CALIBRATION_MAX_WINDOWS) {
            return static_cast<jlongArray>(nullptr);
        }
        constexpr size_t header_fields = 3;
        constexpr size_t window_fields = 3;
        const size_t field_count = header_fields +
            static_cast<size_t>(native.window_count) * window_fields;
        std::array<jlong, header_fields + LLAMA_CALIBRATION_MAX_WINDOWS * window_fields> values{};
        values[0] = static_cast<jlong>(native.status);
        values[1] = static_cast<jlong>(native.backend);
        values[2] = static_cast<jlong>(native.window_count);
        for (int index = 0; index < native.window_count; ++index) {
            const size_t offset = header_fields + static_cast<size_t>(index) * window_fields;
            values[offset] = static_cast<jlong>(native.windows[index].bytes_moved);
            values[offset + 1] = static_cast<jlong>(native.windows[index].operations);
            values[offset + 2] = static_cast<jlong>(native.windows[index].elapsed_nanoseconds);
        }
        jlongArray result = env->NewLongArray(static_cast<jsize>(field_count));
        if (!result || env->ExceptionCheck()) return static_cast<jlongArray>(nullptr);
        env->SetLongArrayRegion(result, 0, static_cast<jsize>(field_count), values.data());
        return env->ExceptionCheck() ? static_cast<jlongArray>(nullptr) : result;
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeCancelBackendCalibration(JNIEnv *, jobject) {
    jni_guard_void("nativeCancelBackendCalibration failed", []() {
        llama_runner_core_cancel_calibration();
    });
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeProbeModelFeatures(
    JNIEnv *env,
    jobject,
    jstring architecture,
    jstring quantization) {
    return jni_guard<jlongArray>("nativeProbeModelFeatures failed", nullptr, [&]() {
        ScopedUtfChars architecture_chars(env, architecture);
        ScopedUtfChars quantization_chars(env, quantization);
        if (!architecture_chars.get() || env->ExceptionCheck()) {
            return static_cast<jlongArray>(nullptr);
        }
        const LlamaModelFeatureSupportNative native = llama_runner_core_probe_model_features(
            architecture_chars.get(), quantization_chars.get());
        const jlong values[] = {
            static_cast<jlong>(native.architecture),
            static_cast<jlong>(native.quantization),
            static_cast<jlong>(native.engine_build),
        };
        jlongArray result = env->NewLongArray(3);
        if (!result || env->ExceptionCheck()) return static_cast<jlongArray>(nullptr);
        env->SetLongArrayRegion(result, 0, 3, values);
        return env->ExceptionCheck() ? static_cast<jlongArray>(nullptr) : result;
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGenerateText(
    JNIEnv *env,
    jobject,
    jstring prompt,
    jint maxTokens,
    jfloat temperature) {
    return jni_guard<jstring>("nativeGenerateText failed", nullptr, [&]() {
        ScopedUtfChars promptChars(env, prompt);
        if (!promptChars.get()) return static_cast<jstring>(nullptr);
        const std::string result = llama_runner_core_generate(
            promptChars.get(),
            static_cast<int>(maxTokens),
            static_cast<float>(temperature));
        return env->NewStringUTF(result.c_str());
    });
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeStartGenerate(
    JNIEnv *env, jobject, jstring prompt, jint maxTokens, jfloat temperature, jstring grammar) {
    return jni_guard<jboolean>(
        "nativeStartGenerate failed",
        static_cast<jboolean>(JNI_FALSE),
        [&]() {
            ScopedUtfChars promptChars(env, prompt);
            ScopedUtfChars grammarChars(env, grammar);
            if (!promptChars.get()) return static_cast<jboolean>(JNI_FALSE);
            const bool ok = llama_runner_core_start_generate(
                promptChars.get(), static_cast<int>(maxTokens),
                static_cast<float>(temperature), grammarChars.get());
            return static_cast<jboolean>(ok ? JNI_TRUE : JNI_FALSE);
        });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeNextToken(JNIEnv *env, jobject) {
    return jni_guard<jstring>("nativeNextToken failed", nullptr, [&]() {
        const char *tok = llama_runner_core_next_token();
        return tok ? env->NewStringUTF(tok) : nullptr;
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetReasoning(JNIEnv *env, jobject) {
    return jni_guard<jstring>("nativeGetReasoning failed", nullptr, [&]() {
        const char *s = llama_runner_core_get_reasoning();
        return env->NewStringUTF(s ? s : "");
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetContent(JNIEnv *env, jobject) {
    return jni_guard<jstring>("nativeGetContent failed", nullptr, [&]() {
        const char *s = llama_runner_core_get_content();
        return env->NewStringUTF(s ? s : "");
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeSupportsThinking(JNIEnv *, jobject) {
    return jni_guard<jint>("nativeSupportsThinking failed", 0, []() {
        return static_cast<jint>(llama_runner_core_supports_thinking());
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetReasoningDelta(JNIEnv *env, jobject) {
    return jni_guard<jstring>("nativeGetReasoningDelta failed", nullptr, [&]() {
        const char *s = llama_runner_core_get_reasoning_delta();
        return env->NewStringUTF(s ? s : "");
    });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetContentDelta(JNIEnv *env, jobject) {
    return jni_guard<jstring>("nativeGetContentDelta failed", nullptr, [&]() {
        const char *s = llama_runner_core_get_content_delta();
        return env->NewStringUTF(s ? s : "");
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeCancelGenerate(JNIEnv *, jobject) {
    jni_guard_void("nativeCancelGenerate failed", []() {
        llama_runner_core_cancel_generate();
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeFinalizeGeneration(JNIEnv *, jobject) {
    jni_guard_void("nativeFinalizeGeneration failed", []() {
        llama_runner_core_finalize_generation();
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeProcessSystemPrompt(JNIEnv *env, jobject, jstring prompt) {
    return jni_guard<jint>("nativeProcessSystemPrompt failed", -1, [&]() {
        ScopedUtfChars promptChars(env, prompt);
        if (!promptChars.get()) return static_cast<jint>(-1);
        return static_cast<jint>(llama_runner_core_process_system_prompt(promptChars.get()));
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeProcessUserPrompt(
    JNIEnv *env, jobject, jstring prompt, jint predictLength) {
    return jni_guard<jint>("nativeProcessUserPrompt failed", -1, [&]() {
        ScopedUtfChars promptChars(env, prompt);
        if (!promptChars.get()) return static_cast<jint>(-1);
        return static_cast<jint>(
            llama_runner_core_process_user_prompt(
                promptChars.get(), static_cast<int>(predictLength)));
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeUnloadModel(JNIEnv *, jobject) {
    jni_guard_void("nativeUnloadModel failed", []() { llama_runner_core_unload(); });
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeShutdown(JNIEnv *, jobject) {
    jni_guard_void("nativeShutdown failed", []() { llama_runner_core_shutdown(); });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetContextUsed(JNIEnv *, jobject) {
    return jni_guard<jint>("nativeGetContextUsed failed", 0, []() {
        return static_cast<jint>(llama_runner_core_get_context_used());
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetContextLimit(JNIEnv *, jobject) {
    return jni_guard<jint>("nativeGetContextLimit failed", 0, []() {
        return static_cast<jint>(llama_runner_core_get_context_limit());
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetStopReason(JNIEnv *, jobject) {
    return jni_guard<jint>("nativeGetStopReason failed", 5, []() {
        return static_cast<jint>(llama_runner_core_get_stop_reason());
    });
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetGpuLayers(JNIEnv *, jobject) {
    return jni_guard<jint>("nativeGetGpuLayers failed", 0, []() {
        return static_cast<jint>(llama_runner_core_get_gpu_layers());
    });
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeClearContext(JNIEnv *, jobject) {
    jni_guard_void("nativeClearContext failed", []() { llama_runner_core_clear_context(); });
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetModelArchitecture(JNIEnv *env, jobject) {
    return jni_guard<jstring>("nativeGetModelArchitecture failed", nullptr, [&]() {
        const char* arch = llama_runner_core_get_model_architecture();
        return env->NewStringUTF(arch ? arch : "");
    });
}
