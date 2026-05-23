#include <jni.h>

#include <cstdio>
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

} // namespace

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeInit(JNIEnv *env, jobject, jstring libDir) {
    const char *path = env->GetStringUTFChars(libDir, 0);
    llama_runner_core_set_logger(platform_log);
    llama_runner_core_init(path);
    env->ReleaseStringUTFChars(libDir, path);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeLoadModel(
    JNIEnv *env,
    jobject,
    jstring modelPath,
    jobject configObj) {
    const char *path = env->GetStringUTFChars(modelPath, 0);

    jclass cls = env->GetObjectClass(configObj);
    
    LlamaRunnerConfig config;
    config.n_ctx           = env->GetIntField(configObj, env->GetFieldID(cls, "nCtx", "I"));
    config.n_ctx_min       = env->GetIntField(configObj, env->GetFieldID(cls, "nCtxMin", "I"));
    config.n_threads       = env->GetIntField(configObj, env->GetFieldID(cls, "nThreads", "I"));
    config.n_threads_batch = env->GetIntField(configObj, env->GetFieldID(cls, "nThreadsBatch", "I"));
    config.n_batch         = env->GetIntField(configObj, env->GetFieldID(cls, "nBatch", "I"));
    config.n_ubatch        = env->GetIntField(configObj, env->GetFieldID(cls, "nUbatch", "I"));
    config.flash_attn      = env->GetIntField(configObj, env->GetFieldID(cls, "flashAttn", "I"));
    config.offload_kqv     = env->GetBooleanField(configObj, env->GetFieldID(cls, "offloadKqv", "Z"));
    config.type_k          = env->GetIntField(configObj, env->GetFieldID(cls, "typeK", "I"));
    config.type_v          = env->GetIntField(configObj, env->GetFieldID(cls, "typeV", "I"));
    config.n_gpu_layers    = env->GetIntField(configObj, env->GetFieldID(cls, "nGpuLayers", "I"));
    config.use_mmap        = env->GetBooleanField(configObj, env->GetFieldID(cls, "useMmap", "Z"));
    config.use_mlock       = env->GetBooleanField(configObj, env->GetFieldID(cls, "useMlock", "Z"));
    config.temperature     = env->GetFloatField(configObj, env->GetFieldID(cls, "temperature", "F"));
    config.auto_fit        = env->GetBooleanField(configObj, env->GetFieldID(cls, "autoFit", "Z"));

    jstring jCpuMask = (jstring) env->GetObjectField(configObj, env->GetFieldID(cls, "cpuMask", "Ljava/lang/String;"));
    jstring jCpuMaskBatch = (jstring) env->GetObjectField(configObj, env->GetFieldID(cls, "cpuMaskBatch", "Ljava/lang/String;"));
    if (jCpuMask) {
        const char* mask = env->GetStringUTFChars(jCpuMask, nullptr);
        config.cpu_mask = mask;
        env->ReleaseStringUTFChars(jCpuMask, mask);
    }
    if (jCpuMaskBatch) {
        const char* batchMask = env->GetStringUTFChars(jCpuMaskBatch, nullptr);
        config.cpu_mask_batch = batchMask;
        env->ReleaseStringUTFChars(jCpuMaskBatch, batchMask);
    }

    const bool ok = llama_runner_core_load_model(path, config);
    env->ReleaseStringUTFChars(modelPath, path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGenerateText(
    JNIEnv *env,
    jobject,
    jstring prompt,
    jint maxTokens,
    jfloat temperature) {
    const char *prompt_str = env->GetStringUTFChars(prompt, 0);
    const std::string result = llama_runner_core_generate(
        prompt_str,
        static_cast<int>(maxTokens),
        static_cast<float>(temperature));
    env->ReleaseStringUTFChars(prompt, prompt_str);
    return env->NewStringUTF(result.c_str());
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeStartGenerate(
    JNIEnv *env, jobject, jstring prompt, jint maxTokens, jfloat temperature, jstring grammar) {
    const char *p = env->GetStringUTFChars(prompt, nullptr);
    const char *g = grammar ? env->GetStringUTFChars(grammar, nullptr) : nullptr;
    const bool ok = llama_runner_core_start_generate(p, static_cast<int>(maxTokens),
        static_cast<float>(temperature), g);
    env->ReleaseStringUTFChars(prompt, p);
    if (g) {
        env->ReleaseStringUTFChars(grammar, g);
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeNextToken(JNIEnv *env, jobject) {
    const char *tok = llama_runner_core_next_token();
    if (tok == nullptr) {
        return nullptr;
    }
    return env->NewStringUTF(tok);
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeCancelGenerate(JNIEnv *, jobject) {
    llama_runner_core_cancel_generate();
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeFinalizeGeneration(JNIEnv *, jobject) {
    llama_runner_core_finalize_generation();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeProcessSystemPrompt(JNIEnv *env, jobject, jstring prompt) {
    const char *p = env->GetStringUTFChars(prompt, nullptr);
    const int ret = llama_runner_core_process_system_prompt(p);
    env->ReleaseStringUTFChars(prompt, p);
    return static_cast<jint>(ret);
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeProcessUserPrompt(
    JNIEnv *env, jobject, jstring prompt, jint predictLength, jstring grammar) {
    const char *p = env->GetStringUTFChars(prompt, nullptr);
    const char *g = grammar ? env->GetStringUTFChars(grammar, nullptr) : nullptr;
    const int ret = llama_runner_core_process_user_prompt(p, static_cast<int>(predictLength), g);
    env->ReleaseStringUTFChars(prompt, p);
    if (g) {
        env->ReleaseStringUTFChars(grammar, g);
    }
    return static_cast<jint>(ret);
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeUnloadModel(JNIEnv *, jobject) {
    llama_runner_core_unload();
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeShutdown(JNIEnv *, jobject) {
    llama_runner_core_shutdown();
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetContextUsed(JNIEnv *, jobject) {
    return static_cast<jint>(llama_runner_core_get_context_used());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetContextLimit(JNIEnv *, jobject) {
    return static_cast<jint>(llama_runner_core_get_context_limit());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetStopReason(JNIEnv *, jobject) {
    return static_cast<jint>(llama_runner_core_get_stop_reason());
}

extern "C" JNIEXPORT jint JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetGpuLayers(JNIEnv *, jobject) {
    return static_cast<jint>(llama_runner_core_get_gpu_layers());
}

extern "C" JNIEXPORT void JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeClearContext(JNIEnv *, jobject) {
    llama_runner_core_clear_context();
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_debanshu777_runner_LlamaRunner_nativeGetModelArchitecture(JNIEnv *env, jobject) {
    const char* arch = llama_runner_core_get_model_architecture();
    return env->NewStringUTF(arch ? arch : "");
}
