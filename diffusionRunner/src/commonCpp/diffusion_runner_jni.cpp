#include "diffusion_runner_core.h"
#include <cmath>
#include <cstdlib>
#include <jni.h>
#include <limits>
#include <new>
#include <string>
#include <utility>
#include <vector>

#ifdef __ANDROID__

#include <android/log.h>

#define LOG_TAG "DiffusionRunner"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#else
#include <iostream>
#define LOGD(...) fprintf(stdout, __VA_ARGS__); fprintf(stdout, "\n")
#define LOGI(...) fprintf(stdout, __VA_ARGS__); fprintf(stdout, "\n")
#define LOGW(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#define LOGE(...) fprintf(stderr, __VA_ARGS__); fprintf(stderr, "\n")
#endif

// JNI logger implementation
static void jni_log_callback(DiffusionLogLevel level, const char *msg) {
    switch (level) {
        case DIFFUSION_LOG_DEBUG:
            LOGD("%s", msg);
            break;
        case DIFFUSION_LOG_INFO:
            LOGI("%s", msg);
            break;
        case DIFFUSION_LOG_WARN:
            LOGW("%s", msg);
            break;
        case DIFFUSION_LOG_ERROR:
            LOGE("%s", msg);
            break;
        default:
            LOGI("%s", msg);
            break;
    }
}

// Helper to get string from jstring
std::string jstring_to_string(JNIEnv *env, jstring jstr) {
    if (!jstr) return "";
    const char *cstr = env->GetStringUTFChars(jstr, nullptr);
    if (!cstr) return "";
    try {
        std::string result(cstr);
        env->ReleaseStringUTFChars(jstr, cstr);
        return result;
    } catch (...) {
        env->ReleaseStringUTFChars(jstr, cstr);
        throw;
    }
}

namespace {

struct PngResultOwner {
    explicit PngResultOwner(PngResult owned) : value(owned) {}
    ~PngResultOwner() { free(value.data); }
    PngResult value;
};

struct PngResultsOwner {
    explicit PngResultsOwner(std::vector<PngResult> &&owned) : values(std::move(owned)) {}
    ~PngResultsOwner() {
        for (auto &result : values) free(result.data);
    }
    std::vector<PngResult> values;
};

bool read_string_field(
        JNIEnv *env,
        jobject source,
        jfieldID field,
        std::string &destination) {
    auto value = static_cast<jstring>(env->GetObjectField(source, field));
    if (env->ExceptionCheck()) return false;
    if (!value) {
        destination.clear();
        return true;
    }

    const char *chars = env->GetStringUTFChars(value, nullptr);
    if (!chars) {
        env->DeleteLocalRef(value);
        return false;
    }
    destination.assign(chars);
    env->ReleaseStringUTFChars(value, chars);
    env->DeleteLocalRef(value);
    return !env->ExceptionCheck();
}

struct OwnedDiffusionModelConfig {
    std::string model_path;
    std::string vae_path;
    std::string llm_path;
    std::string clip_l_path;
    std::string clip_g_path;
    std::string t5xxl_path;
    std::string taesd_path;
    std::string max_vram;
    DiffusionModelConfig values = {};

    bool read_runtime_backend(JNIEnv *env, jobject source, jfieldID field) {
        jobject value = env->GetObjectField(source, field);
        if (!value || env->ExceptionCheck()) return false;
        jclass enum_class = env->GetObjectClass(value);
        if (!enum_class || env->ExceptionCheck()) {
            env->DeleteLocalRef(value);
            return false;
        }
        const jmethodID ordinal = env->GetMethodID(enum_class, "ordinal", "()I");
        if (!ordinal || env->ExceptionCheck()) {
            env->DeleteLocalRef(enum_class);
            env->DeleteLocalRef(value);
            return false;
        }
        values.runtime_backend = env->CallIntMethod(value, ordinal);
        const bool success = !env->ExceptionCheck();
        env->DeleteLocalRef(enum_class);
        env->DeleteLocalRef(value);
        return success;
    }

    bool read(JNIEnv *env, jobject source) {
        if (!source) return false;
        jclass clazz = env->GetObjectClass(source);
        if (!clazz || env->ExceptionCheck()) return false;

        const jfieldID modelPath = env->GetFieldID(clazz, "modelPath", "Ljava/lang/String;");
        const jfieldID vaePath = env->GetFieldID(clazz, "vaePath", "Ljava/lang/String;");
        const jfieldID llmPath = env->GetFieldID(clazz, "llmPath", "Ljava/lang/String;");
        const jfieldID clipLPath = env->GetFieldID(clazz, "clipLPath", "Ljava/lang/String;");
        const jfieldID clipGPath = env->GetFieldID(clazz, "clipGPath", "Ljava/lang/String;");
        const jfieldID t5xxlPath = env->GetFieldID(clazz, "t5xxlPath", "Ljava/lang/String;");
        const jfieldID runtimeBackend = env->GetFieldID(
            clazz,
            "runtimeBackend",
            "Lcom/debanshu777/diffusionrunner/DiffusionRuntimeBackend;");
        const jfieldID offloadToCpu = env->GetFieldID(clazz, "offloadToCpu", "Z");
        const jfieldID keepClipOnCpu = env->GetFieldID(clazz, "keepClipOnCpu", "Z");
        const jfieldID keepVaeOnCpu = env->GetFieldID(clazz, "keepVaeOnCpu", "Z");
        const jfieldID diffusionFlashAttn = env->GetFieldID(clazz, "diffusionFlashAttn", "Z");
        const jfieldID enableMmap = env->GetFieldID(clazz, "enableMmap", "Z");
        const jfieldID diffusionConvDirect = env->GetFieldID(clazz, "diffusionConvDirect", "Z");
        const jfieldID freeParamsImmediately = env->GetFieldID(clazz, "freeParamsImmediately", "Z");
        const jfieldID wtype = env->GetFieldID(clazz, "wtype", "I");
        const jfieldID flowShift = env->GetFieldID(clazz, "flowShift", "F");
        const jfieldID nThreads = env->GetFieldID(clazz, "nThreads", "I");
        const jfieldID prediction = env->GetFieldID(clazz, "prediction", "I");
        const jfieldID taesdPath = env->GetFieldID(clazz, "taesdPath", "Ljava/lang/String;");
        const jfieldID vaeTiling = env->GetFieldID(clazz, "vaeTiling", "Z");
        const jfieldID maxVram = env->GetFieldID(clazz, "maxVram", "Ljava/lang/String;");
        const jfieldID streamLayers = env->GetFieldID(clazz, "streamLayers", "Z");
        const jfieldID autoFit = env->GetFieldID(clazz, "autoFit", "Z");

        const bool fields_ok = modelPath && vaePath && llmPath && clipLPath && clipGPath &&
                t5xxlPath && runtimeBackend && offloadToCpu && keepClipOnCpu && keepVaeOnCpu &&
                diffusionFlashAttn && enableMmap && diffusionConvDirect &&
                freeParamsImmediately && wtype && flowShift && nThreads && prediction &&
                taesdPath && vaeTiling && maxVram && streamLayers && autoFit &&
                !env->ExceptionCheck();
        if (!fields_ok) {
            env->DeleteLocalRef(clazz);
            return false;
        }

        const bool strings_ok =
                read_string_field(env, source, modelPath, model_path) &&
                read_string_field(env, source, vaePath, vae_path) &&
                read_string_field(env, source, llmPath, llm_path) &&
                read_string_field(env, source, clipLPath, clip_l_path) &&
                read_string_field(env, source, clipGPath, clip_g_path) &&
                read_string_field(env, source, t5xxlPath, t5xxl_path) &&
                read_string_field(env, source, taesdPath, taesd_path) &&
                read_string_field(env, source, maxVram, max_vram);
        if (!strings_ok || !read_runtime_backend(env, source, runtimeBackend)) {
            env->DeleteLocalRef(clazz);
            return false;
        }

        values.model_path = model_path.c_str();
        values.vae_path = vae_path.c_str();
        values.llm_path = llm_path.c_str();
        values.clip_l_path = clip_l_path.c_str();
        values.clip_g_path = clip_g_path.c_str();
        values.t5xxl_path = t5xxl_path.c_str();
        values.offload_to_cpu = env->GetBooleanField(source, offloadToCpu);
        values.keep_clip_on_cpu = env->GetBooleanField(source, keepClipOnCpu);
        values.keep_vae_on_cpu = env->GetBooleanField(source, keepVaeOnCpu);
        values.diffusion_flash_attn = env->GetBooleanField(source, diffusionFlashAttn);
        values.enable_mmap = env->GetBooleanField(source, enableMmap);
        values.diffusion_conv_direct = env->GetBooleanField(source, diffusionConvDirect);
        values.free_params_immediately = env->GetBooleanField(source, freeParamsImmediately);
        values.wtype = env->GetIntField(source, wtype);
        values.flow_shift = env->GetFloatField(source, flowShift);
        values.flow_shift_is_set = !std::isinf(values.flow_shift);
        values.n_threads = env->GetIntField(source, nThreads);
        values.prediction = env->GetIntField(source, prediction);
        values.taesd_path = taesd_path.c_str();
        values.vae_tiling = env->GetBooleanField(source, vaeTiling);
        values.max_vram = max_vram.c_str();
        values.stream_layers = env->GetBooleanField(source, streamLayers);
        values.auto_fit = env->GetBooleanField(source, autoFit);

        env->DeleteLocalRef(clazz);
        return !env->ExceptionCheck();
    }
};

jlongArray make_long_array(JNIEnv *env, const std::vector<int64_t> &values) {
    if (values.empty() || values.size() > static_cast<size_t>(std::numeric_limits<jsize>::max())) {
        return nullptr;
    }
    jlongArray array = env->NewLongArray(static_cast<jsize>(values.size()));
    if (!array || env->ExceptionCheck()) return nullptr;
    env->SetLongArrayRegion(
        array,
        0,
        static_cast<jsize>(values.size()),
        reinterpret_cast<const jlong *>(values.data()));
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(array);
        return nullptr;
    }
    return array;
}

}

extern "C" {

JNIEXPORT void JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeInit(JNIEnv *env, jobject thiz, jstring libDir) {
    try {
        std::string lib_dir = jstring_to_string(env, libDir);
        diffusion_runner_core_set_logger(jni_log_callback);
        diffusion_runner_core_init(lib_dir.c_str());
    } catch (...) {
        LOGE("nativeInit: native failure");
    }
}

JNIEXPORT jlong JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeLoadModel(JNIEnv *env, jobject thiz, jobject config) {
    try {
        OwnedDiffusionModelConfig owned_config;
        if (!owned_config.read(env, config)) {
            LOGE("nativeLoadModel: invalid Java configuration");
            return 0;
        }
        return diffusion_runner_core_load_model(owned_config.values);
    } catch (const std::bad_alloc &) {
        LOGE("nativeLoadModel: allocation failed");
        return 0;
    } catch (const std::exception &) {
        LOGE("nativeLoadModel: native failure");
        return 0;
    } catch (...) {
        LOGE("nativeLoadModel: unknown native failure");
        return 0;
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativePreflightModel(
        JNIEnv *env, jobject /* thiz */, jobject config) {
    try {
        OwnedDiffusionModelConfig owned_config;
        if (!owned_config.read(env, config)) return nullptr;
        const DiffusionPreflightResultNative native =
            diffusion_runner_core_preflight(owned_config.values);
        std::vector<int64_t> payload = {
            native.status,
            native.architecture,
            native.quantization,
            native.memory_confidence,
            native.stream_layers ? 1 : 0,
            native.declared_component_mask,
            native.component_count,
            native.backend_count,
        };
        if (native.component_count < 0 || native.component_count > DIFFUSION_PREFLIGHT_MAX_COMPONENTS ||
            native.backend_count < 0 || native.backend_count > DIFFUSION_PREFLIGHT_MAX_BACKENDS) {
            return nullptr;
        }
        payload.reserve(8 + native.component_count * 6 + native.backend_count * 6);
        for (int index = 0; index < native.component_count; ++index) {
            const DiffusionPreflightComponentNative &component = native.components[index];
            payload.insert(payload.end(), {
                component.role,
                component.ordinal,
                component.parameter_bytes,
                component.runtime_placement,
                component.runtime_backend_mask,
                component.parameter_placement,
            });
        }
        for (int index = 0; index < native.backend_count; ++index) {
            const DiffusionPreflightBackendNative &backend = native.backends[index];
            payload.insert(payload.end(), {
                backend.kind,
                backend.device_type,
                backend.ordinal,
                backend.budget_bytes,
                backend.free_bytes,
                backend.total_bytes,
            });
        }
        return make_long_array(env, payload);
    } catch (...) {
        LOGE("nativePreflightModel: native failure");
        return nullptr;
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeBackendCapabilities(
        JNIEnv *env, jobject /* thiz */) {
    try {
        const DiffusionBackendCapabilitiesNative native =
            diffusion_runner_core_backend_capabilities();
        if (native.count < 1 || native.count > DIFFUSION_BACKEND_MAX_DEVICES) return nullptr;
        std::vector<int64_t> payload;
        payload.reserve(1 + native.count * 13);
        payload.push_back(native.count);
        for (int index = 0; index < native.count; ++index) {
            const DiffusionBackendCapabilityNative &device = native.devices[index];
            payload.insert(payload.end(), {
                device.kind,
                device.device_type,
                device.free_bytes,
                device.total_bytes,
                device.device_identity_length,
            });
            for (int word = 0; word < 8; ++word) {
                payload.push_back(device.device_identity_words[word]);
            }
        }
        return make_long_array(env, payload);
    } catch (...) {
        LOGE("nativeBackendCapabilities: native failure");
        return nullptr;
    }
}

JNIEXPORT jlongArray JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeProbeModelFeatures(
        JNIEnv *env,
        jobject /* thiz */,
        jstring architecture,
        jstring quantization,
        jint mode) {
    try {
        const std::string architecture_value = jstring_to_string(env, architecture);
        if (env->ExceptionCheck()) return nullptr;
        const std::string quantization_value = jstring_to_string(env, quantization);
        if (env->ExceptionCheck()) return nullptr;
        const DiffusionModelFeatureSupportNative native =
            diffusion_runner_core_probe_model_features(
                architecture_value.c_str(),
                quantization ? quantization_value.c_str() : nullptr,
                mode);
        return make_long_array(env, {
            native.architecture,
            native.quantization,
            native.mode,
        });
    } catch (...) {
        LOGE("nativeProbeModelFeatures: native failure");
        return nullptr;
    }
}

JNIEXPORT jstring JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeEngineVersion(
        JNIEnv *env, jobject /* thiz */) {
    try {
        const std::string version = diffusion_runner_core_engine_version();
        if (version.empty() || version.size() > 96) return nullptr;
        return env->NewStringUTF(version.c_str());
    } catch (...) {
        LOGE("nativeEngineVersion: native failure");
        return nullptr;
    }
}

JNIEXPORT jbyteArray JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeTxt2Img(JNIEnv *env, jobject thiz,
        jlong handle,
        jstring prompt,
        jstring negative,
        jint width,
        jint height,
        jint steps,
        jfloat cfg,
        jlong seed,
        jint sampleMethod,
        jobjectArray loraPaths,
        jfloatArray loraStrengths) {
    try {
    // Set up ImageGenConfig
    std::string prompt_str = jstring_to_string(env, prompt);
    std::string negative_str = jstring_to_string(env, negative);
    if (env->ExceptionCheck()) return nullptr;

    ImageGenConfig gen_config = {};
    gen_config.prompt = prompt_str.c_str();
    gen_config.negative_prompt = negative_str.c_str();
    gen_config.width = width;
    gen_config.height = height;
    gen_config.steps = steps;
    gen_config.cfg_scale = cfg;
    gen_config.seed = seed;
    gen_config.sample_method = sampleMethod;

    // Handle LoRA arrays
    std::vector<std::string> lora_path_strings;
    std::vector<const char *> lora_path_ptrs;
    std::vector<float> lora_strength_values;

    if (loraPaths && loraStrengths) {
        int lora_count = env->GetArrayLength(loraPaths);
        int strength_count = env->GetArrayLength(loraStrengths);
        if (env->ExceptionCheck()) return nullptr;

        if (lora_count == strength_count && lora_count > 0) {
            lora_path_strings.reserve(lora_count);
            lora_path_ptrs.reserve(lora_count);

            for (int i = 0; i < lora_count; i++) {
                jstring path = (jstring) env->GetObjectArrayElement(loraPaths, i);
                if (!path || env->ExceptionCheck()) return nullptr;
                lora_path_strings.push_back(jstring_to_string(env, path));
                env->DeleteLocalRef(path);
                if (env->ExceptionCheck()) return nullptr;
                lora_path_ptrs.push_back(lora_path_strings.back().c_str());
            }

            jfloat *strengths = env->GetFloatArrayElements(loraStrengths, nullptr);
            if (!strengths || env->ExceptionCheck()) return nullptr;
            lora_strength_values.assign(strengths, strengths + strength_count);
            env->ReleaseFloatArrayElements(loraStrengths, strengths, JNI_ABORT);
            if (env->ExceptionCheck()) return nullptr;

            gen_config.lora_paths = lora_path_ptrs.data();
            gen_config.lora_strengths = lora_strength_values.data();
            gen_config.lora_count = lora_count;
        }
    }

    // Generate image
    PngResultOwner result(diffusion_runner_core_txt2img(handle, gen_config));

    if (!result.value.data || result.value.size <= 0) {
        return nullptr;
    }

    // Create Java byte array
    jbyteArray jbytes = env->NewByteArray(result.value.size);
    if (!jbytes || env->ExceptionCheck()) return nullptr;
    env->SetByteArrayRegion(
        jbytes, 0, result.value.size, (const jbyte *) result.value.data);
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(jbytes);
        return nullptr;
    }

    return jbytes;
    } catch (const std::bad_alloc &) {
        LOGE("nativeTxt2Img: allocation failed");
    } catch (const std::exception &) {
        LOGE("nativeTxt2Img: native failure");
    } catch (...) {
        LOGE("nativeTxt2Img: unknown native failure");
    }
    return nullptr;
}

JNIEXPORT jobjectArray JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeVideoGen(JNIEnv *env, jobject thiz,
        jlong handle,
        jstring prompt,
        jstring negative,
        jint width,
        jint height,
        jint videoFrames,
        jint steps,
        jfloat cfg,
        jlong seed,
        jint sampleMethod,
        jobjectArray loraPaths,
        jfloatArray loraStrengths) {
    try {

    // Set up VideoGenConfig
    std::string prompt_str = jstring_to_string(env, prompt);
    std::string negative_str = jstring_to_string(env, negative);
    if (env->ExceptionCheck()) return nullptr;

    VideoGenConfig gen_config = {};
    gen_config.prompt = prompt_str.c_str();
    gen_config.negative_prompt = negative_str.c_str();
    gen_config.width = width;
    gen_config.height = height;
    gen_config.video_frames = videoFrames;
    gen_config.steps = steps;
    gen_config.cfg_scale = cfg;
    gen_config.seed = seed;
    gen_config.sample_method = sampleMethod;

    // Handle LoRA arrays (same as txt2img)
    std::vector<std::string> lora_path_strings;
    std::vector<const char *> lora_path_ptrs;
    std::vector<float> lora_strength_values;

    if (loraPaths && loraStrengths) {
        int lora_count = env->GetArrayLength(loraPaths);
        int strength_count = env->GetArrayLength(loraStrengths);
        if (env->ExceptionCheck()) return nullptr;

        if (lora_count == strength_count && lora_count > 0) {
            lora_path_strings.reserve(lora_count);
            lora_path_ptrs.reserve(lora_count);

            for (int i = 0; i < lora_count; i++) {
                jstring path = (jstring) env->GetObjectArrayElement(loraPaths, i);
                if (!path || env->ExceptionCheck()) return nullptr;
                lora_path_strings.push_back(jstring_to_string(env, path));
                env->DeleteLocalRef(path);
                if (env->ExceptionCheck()) return nullptr;
                lora_path_ptrs.push_back(lora_path_strings.back().c_str());
            }

            jfloat *strengths = env->GetFloatArrayElements(loraStrengths, nullptr);
            if (!strengths || env->ExceptionCheck()) return nullptr;
            lora_strength_values.assign(strengths, strengths + strength_count);
            env->ReleaseFloatArrayElements(loraStrengths, strengths, JNI_ABORT);
            if (env->ExceptionCheck()) return nullptr;

            gen_config.lora_paths = lora_path_ptrs.data();
            gen_config.lora_strengths = lora_strength_values.data();
            gen_config.lora_count = lora_count;
        }
    }

    // Generate video frames
    PngResultsOwner results(diffusion_runner_core_video_gen(handle, gen_config));

    if (results.values.empty()) {
        return nullptr;
    }

    // Create Java byte array array
    jclass byteArrayClass = env->FindClass("[B");
    if (!byteArrayClass || env->ExceptionCheck()) return nullptr;
    jobjectArray frameArray = env->NewObjectArray(
        static_cast<jsize>(results.values.size()), byteArrayClass, nullptr);
    if (!frameArray || env->ExceptionCheck()) {
        env->DeleteLocalRef(byteArrayClass);
        return nullptr;
    }

    for (size_t i = 0; i < results.values.size(); i++) {
        const PngResult &result = results.values[i];
        if (!result.data || result.size <= 0) {
            env->DeleteLocalRef(frameArray);
            env->DeleteLocalRef(byteArrayClass);
            return nullptr;
        }
        jbyteArray frameBytes = env->NewByteArray(result.size);
        if (!frameBytes || env->ExceptionCheck()) {
            env->DeleteLocalRef(frameArray);
            env->DeleteLocalRef(byteArrayClass);
            return nullptr;
        }
        env->SetByteArrayRegion(frameBytes, 0, result.size, (const jbyte *) result.data);
        if (!env->ExceptionCheck()) {
            env->SetObjectArrayElement(frameArray, static_cast<jsize>(i), frameBytes);
        }
        env->DeleteLocalRef(frameBytes);
        if (env->ExceptionCheck()) {
            env->DeleteLocalRef(frameArray);
            env->DeleteLocalRef(byteArrayClass);
            return nullptr;
        }
    }

    env->DeleteLocalRef(byteArrayClass);
    return frameArray;
    } catch (const std::bad_alloc &) {
        LOGE("nativeVideoGen: allocation failed");
    } catch (const std::exception &) {
        LOGE("nativeVideoGen: native failure");
    } catch (...) {
        LOGE("nativeVideoGen: unknown native failure");
    }
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeRelease(JNIEnv *env, jobject thiz, jlong handle) {
    try {
        diffusion_runner_core_release(handle);
    } catch (...) {
        LOGE("nativeRelease: native failure");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeCancelGeneration(
        JNIEnv * /* env */, jobject /* thiz */, jlong handle) {
    try {
        return diffusion_runner_core_cancel_generation(handle) ? JNI_TRUE : JNI_FALSE;
    } catch (...) {
        LOGE("nativeCancelGeneration: native failure");
        return JNI_FALSE;
    }
}

JNIEXPORT jintArray JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeGetStepProgress(JNIEnv *env, jobject thiz) {
    int step = 0, total = 0;
    try {
        diffusion_runner_get_step_progress(&step, &total);
    } catch (...) {
        LOGE("nativeGetStepProgress: native failure");
    }
    jintArray arr = env->NewIntArray(2);
    if (!arr || env->ExceptionCheck()) return nullptr;
    jint values[2] = {step, total};
    env->SetIntArrayRegion(arr, 0, 2, values);
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(arr);
        return nullptr;
    }
    return arr;
}

JNIEXPORT jobject JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeGetDiffusionModelMetadata(
        JNIEnv* env, jobject /* thiz */, jstring modelPath) {
    try {

    std::string path = jstring_to_string(env, modelPath);
    DiffusionMetadataResult meta = diffusion_runner_core_get_metadata(path.c_str());

    if (!meta.success) return nullptr;

    jclass clazz = env->FindClass("com/debanshu777/diffusionrunner/DiffusionModelMetadata");
    if (!clazz || env->ExceptionCheck()) return nullptr;

    jmethodID ctor = env->GetMethodID(clazz, "<init>",
        "(Ljava/lang/String;Ljava/lang/String;J)V");
    if (!ctor || env->ExceptionCheck()) {
        env->DeleteLocalRef(clazz);
        return nullptr;
    }

    jstring jArch = env->NewStringUTF(meta.architecture);
    if (!jArch || env->ExceptionCheck()) {
        env->DeleteLocalRef(clazz);
        return nullptr;
    }
    jstring jQuant = (meta.dominant_quant[0] != '\0')
        ? env->NewStringUTF(meta.dominant_quant)
        : nullptr;
    if (env->ExceptionCheck()) {
        env->DeleteLocalRef(jArch);
        env->DeleteLocalRef(clazz);
        return nullptr;
    }

    jobject obj = env->NewObject(clazz, ctor, jArch, jQuant, (jlong)meta.estimated_ram);

    env->DeleteLocalRef(jArch);
    if (jQuant) env->DeleteLocalRef(jQuant);
    env->DeleteLocalRef(clazz);

    return env->ExceptionCheck() ? nullptr : obj;
    } catch (const std::bad_alloc &) {
        LOGE("nativeGetDiffusionModelMetadata: allocation failed");
    } catch (const std::exception &) {
        LOGE("nativeGetDiffusionModelMetadata: native failure");
    } catch (...) {
        LOGE("nativeGetDiffusionModelMetadata: unknown native failure");
    }
    return nullptr;
}

}
