#include "diffusion_runner_core.h"
#include <jni.h>
#include <string>
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
    std::string result(cstr);
    env->ReleaseStringUTFChars(jstr, cstr);
    return result;
}

// Helper to get DiffusionModelConfig from jobject
DiffusionModelConfig extract_model_config(JNIEnv *env, jobject config) {
    jclass configClass = env->GetObjectClass(config);

    // Get field IDs
    jfieldID modelPathField = env->GetFieldID(configClass, "modelPath", "Ljava/lang/String;");
    jfieldID vaePathField = env->GetFieldID(configClass, "vaePath", "Ljava/lang/String;");
    jfieldID llmPathField = env->GetFieldID(configClass, "llmPath", "Ljava/lang/String;");
    jfieldID clipLPathField = env->GetFieldID(configClass, "clipLPath", "Ljava/lang/String;");
    jfieldID clipGPathField = env->GetFieldID(configClass, "clipGPath", "Ljava/lang/String;");
    jfieldID t5xxlPathField = env->GetFieldID(configClass, "t5xxlPath", "Ljava/lang/String;");
    jfieldID offloadToCpuField = env->GetFieldID(configClass, "offloadToCpu", "Z");
    jfieldID keepClipOnCpuField = env->GetFieldID(configClass, "keepClipOnCpu", "Z");
    jfieldID keepVaeOnCpuField = env->GetFieldID(configClass, "keepVaeOnCpu", "Z");
    jfieldID diffusionFlashAttnField = env->GetFieldID(configClass, "diffusionFlashAttn", "Z");
    jfieldID enableMmapField = env->GetFieldID(configClass, "enableMmap", "Z");
    jfieldID diffusionConvDirectField = env->GetFieldID(configClass, "diffusionConvDirect", "Z");
    jfieldID wtypeField = env->GetFieldID(configClass, "wtype", "I");
    jfieldID flowShiftField = env->GetFieldID(configClass, "flowShift", "F");
    jfieldID nThreadsField = env->GetFieldID(configClass, "nThreads", "I");

    // Extract values
    static std::string model_path_str = jstring_to_string(env, (jstring) env->GetObjectField(config, modelPathField));
    static std::string vae_path_str = jstring_to_string(env, (jstring) env->GetObjectField(config, vaePathField));
    static std::string llm_path_str = jstring_to_string(env, (jstring) env->GetObjectField(config, llmPathField));
    static std::string clip_l_path_str = jstring_to_string(env, (jstring) env->GetObjectField(config, clipLPathField));
    static std::string clip_g_path_str = jstring_to_string(env, (jstring) env->GetObjectField(config, clipGPathField));
    static std::string t5xxl_path_str = jstring_to_string(env, (jstring) env->GetObjectField(config, t5xxlPathField));

    DiffusionModelConfig cpp_config = {};
    cpp_config.model_path = model_path_str.c_str();
    cpp_config.vae_path = vae_path_str.c_str();
    cpp_config.llm_path = llm_path_str.c_str();
    cpp_config.clip_l_path = clip_l_path_str.c_str();
    cpp_config.clip_g_path = clip_g_path_str.c_str();
    cpp_config.t5xxl_path = t5xxl_path_str.c_str();
    cpp_config.offload_to_cpu = env->GetBooleanField(config, offloadToCpuField);
    cpp_config.keep_clip_on_cpu = env->GetBooleanField(config, keepClipOnCpuField);
    cpp_config.keep_vae_on_cpu = env->GetBooleanField(config, keepVaeOnCpuField);
    cpp_config.diffusion_flash_attn = env->GetBooleanField(config, diffusionFlashAttnField);
    cpp_config.enable_mmap = env->GetBooleanField(config, enableMmapField);
    cpp_config.diffusion_conv_direct = env->GetBooleanField(config, diffusionConvDirectField);
    cpp_config.wtype = env->GetIntField(config, wtypeField);
    float flow_shift = env->GetFloatField(config, flowShiftField);
    cpp_config.flow_shift = flow_shift;
    cpp_config.flow_shift_is_set = !std::isinf(flow_shift);
    cpp_config.n_threads = env->GetIntField(config, nThreadsField);

    env->DeleteLocalRef(configClass);
    return cpp_config;
}

extern "C" {

JNIEXPORT void JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeInit(JNIEnv *env, jobject thiz, jstring libDir) {
    std::string lib_dir = jstring_to_string(env, libDir);
    diffusion_runner_core_set_logger(jni_log_callback);
    diffusion_runner_core_init(lib_dir.c_str());
}

JNIEXPORT jlong JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeLoadModel(JNIEnv *env, jobject thiz, jobject config) {
    DiffusionModelConfig cpp_config = extract_model_config(env, config);
    return diffusion_runner_core_load_model(cpp_config);
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
    // Set up ImageGenConfig
    std::string prompt_str = jstring_to_string(env, prompt);
    std::string negative_str = jstring_to_string(env, negative);

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

        if (lora_count == strength_count && lora_count > 0) {
            lora_path_strings.reserve(lora_count);
            lora_path_ptrs.reserve(lora_count);

            for (int i = 0; i < lora_count; i++) {
                jstring path = (jstring) env->GetObjectArrayElement(loraPaths, i);
                lora_path_strings.push_back(jstring_to_string(env, path));
                lora_path_ptrs.push_back(lora_path_strings.back().c_str());
                env->DeleteLocalRef(path);
            }

            jfloat *strengths = env->GetFloatArrayElements(loraStrengths, nullptr);
            lora_strength_values.assign(strengths, strengths + strength_count);
            env->ReleaseFloatArrayElements(loraStrengths, strengths, JNI_ABORT);

            gen_config.lora_paths = lora_path_ptrs.data();
            gen_config.lora_strengths = lora_strength_values.data();
            gen_config.lora_count = lora_count;
        }
    }

    // Generate image
    PngResult result = diffusion_runner_core_txt2img(handle, gen_config);

    if (!result.data || result.size <= 0) {
        return nullptr;
    }

    // Create Java byte array
    jbyteArray jbytes = env->NewByteArray(result.size);
    env->SetByteArrayRegion(jbytes, 0, result.size, (const jbyte *) result.data);

    // Free C++ result
    free(result.data);

    return jbytes;
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

    // Set up VideoGenConfig
    std::string prompt_str = jstring_to_string(env, prompt);
    std::string negative_str = jstring_to_string(env, negative);

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

        if (lora_count == strength_count && lora_count > 0) {
            lora_path_strings.reserve(lora_count);
            lora_path_ptrs.reserve(lora_count);

            for (int i = 0; i < lora_count; i++) {
                jstring path = (jstring) env->GetObjectArrayElement(loraPaths, i);
                lora_path_strings.push_back(jstring_to_string(env, path));
                lora_path_ptrs.push_back(lora_path_strings.back().c_str());
                env->DeleteLocalRef(path);
            }

            jfloat *strengths = env->GetFloatArrayElements(loraStrengths, nullptr);
            lora_strength_values.assign(strengths, strengths + strength_count);
            env->ReleaseFloatArrayElements(loraStrengths, strengths, JNI_ABORT);

            gen_config.lora_paths = lora_path_ptrs.data();
            gen_config.lora_strengths = lora_strength_values.data();
            gen_config.lora_count = lora_count;
        }
    }

    // Generate video frames
    std::vector<PngResult> results = diffusion_runner_core_video_gen(handle, gen_config);

    if (results.empty()) {
        return nullptr;
    }

    // Create Java byte array array
    jclass byteArrayClass = env->FindClass("[B");
    jobjectArray frameArray = env->NewObjectArray(results.size(), byteArrayClass, nullptr);

    for (size_t i = 0; i < results.size(); i++) {
        if (results[i].data && results[i].size > 0) {
            jbyteArray frameBytes = env->NewByteArray(results[i].size);
            env->SetByteArrayRegion(frameBytes, 0, results[i].size, (const jbyte *) results[i].data);
            env->SetObjectArrayElement(frameArray, i, frameBytes);
            env->DeleteLocalRef(frameBytes);

            // Free C++ result
            free(results[i].data);
        }
    }

    env->DeleteLocalRef(byteArrayClass);
    return frameArray;
}

JNIEXPORT void JNICALL
Java_com_debanshu777_diffusionrunner_DiffusionRunner_nativeRelease(JNIEnv *env, jobject thiz, jlong handle) {
    diffusion_runner_core_release(handle);
}

}