#pragma once

#include <cstdint>
#include <functional>
#include <string>
#include <vector>

enum DiffusionLogLevel {
    DIFFUSION_LOG_DEBUG = 0,
    DIFFUSION_LOG_INFO = 1,
    DIFFUSION_LOG_WARN = 2,
    DIFFUSION_LOG_ERROR = 3,
};

using DiffusionLogFn = std::function<void(DiffusionLogLevel level, const char *msg)>;

enum DiffusionRuntimeBackendNative {
    DIFFUSION_RUNTIME_BACKEND_CPU = 0,
    DIFFUSION_RUNTIME_BACKEND_METAL = 1,
    DIFFUSION_RUNTIME_BACKEND_VULKAN = 2,
    DIFFUSION_RUNTIME_BACKEND_CUDA = 3,
};

struct DiffusionModelConfig {
    const char *model_path = "";
    const char *vae_path = "";
    const char *llm_path = "";
    const char *clip_l_path = "";
    const char *clip_g_path = "";
    const char *t5xxl_path = "";
    int runtime_backend = DIFFUSION_RUNTIME_BACKEND_CPU;
    bool offload_to_cpu = false;
    bool keep_clip_on_cpu = false;
    bool keep_vae_on_cpu = false;
    bool diffusion_flash_attn = false;
    bool enable_mmap = false;
    bool diffusion_conv_direct = false;
    bool free_params_immediately = false;
    int wtype = -1;
    float flow_shift = 0.0f;
    bool flow_shift_is_set = false;
    int n_threads = -1;
    /** -1=auto-detect (PREDICTION_COUNT), 0=EPS, 1=V_PRED, 2=EDM_V_PRED, 3=FLOW, 4=FLUX_FLOW, 5=FLUX2_FLOW */
    int prediction = -1;
    /** Optional path to TAESD safetensors; replaces full VAE decoder for fast decode. Empty = disabled. */
    const char *taesd_path = "";
    /** Enable VAE tiling for large-image decoding to avoid OOM. Only the enabled flag is needed; lib auto-sizes. */
    bool vae_tiling = false;
    /** Bounded max-VRAM budget grammar consumed by the pinned native engine. */
    const char *max_vram = "";
    bool segmented_compute = false;
    bool prefetch = false;
    bool auto_fit = false;
};

struct ImageGenConfig {
    const char *prompt = "";
    const char *negative_prompt = "";
    int width = 512;
    int height = 512;
    int steps = 20;
    float cfg_scale = 7.0f;
    int64_t seed = -1;
    int sample_method = 1;
    float flow_shift = 0.0f;
    bool flow_shift_is_set = false;
    bool vae_tiling = false;
    const char **lora_paths = nullptr;
    float *lora_strengths = nullptr;
    int lora_count = 0;
};

struct VideoGenConfig {
    const char *prompt = "";
    const char *negative_prompt = "";
    int width = 512;
    int height = 512;
    int video_frames = 16;
    int steps = 20;
    float cfg_scale = 7.0f;
    int64_t seed = -1;
    int sample_method = 1;
    float flow_shift = 0.0f;
    bool flow_shift_is_set = false;
    bool vae_tiling = false;
    const char **lora_paths = nullptr;
    float *lora_strengths = nullptr;
    int lora_count = 0;
};

struct PngResult {
    uint8_t *data = nullptr;
    int size = 0;
};

void diffusion_runner_core_set_logger(DiffusionLogFn fn);

void diffusion_runner_core_init(const char *backend_path);

int64_t diffusion_runner_core_load_model(const DiffusionModelConfig &config);

PngResult diffusion_runner_core_txt2img(int64_t handle, const ImageGenConfig &config);

struct VideoGenResultNative {
    std::vector<PngResult> frames;
    int effective_fps = 0;
};

VideoGenResultNative diffusion_runner_core_video_gen(int64_t handle, const VideoGenConfig &config);

/** Signals the active generation for [handle] to stop as soon as possible. */
bool diffusion_runner_core_cancel_generation(int64_t handle);

void diffusion_runner_core_release(int64_t handle);

/** Returns the current step (0-based) and total steps for the ongoing generation. */
void diffusion_runner_get_step_progress(int* step, int* total);

/** Lightweight metadata from ModelLoader header scan. Heap-free, safe to return by value. */
struct DiffusionMetadataResult {
    char architecture[64];      /**< e.g. "FLUX", "SDXL", "SD3", "SD1", "WAN2_SMALL", "WAN2_LARGE", "UNKNOWN" */
    char dominant_quant[32];    /**< ggml_type_name() of most frequent weight tensor. Empty string if none. */
    int64_t estimated_ram;      /**< get_params_mem_size() result in bytes; 0 if unavailable. */
    bool success;               /**< false if file could not be loaded or format unsupported. */
};

/**
 * Queries model metadata without loading weights into GPU/CPU memory.
 * Uses stable-diffusion.cpp ModelLoader to read GGUF tensor headers.
 * Thread-safe; does not modify global state.
 *
 * @param model_path Absolute path to a .gguf model file.
 * @return DiffusionMetadataResult; check result.success before reading fields.
 */
DiffusionMetadataResult diffusion_runner_core_get_metadata(const char* model_path);

constexpr int DIFFUSION_PREFLIGHT_MAX_COMPONENTS = 10;
constexpr int DIFFUSION_PREFLIGHT_MAX_BACKENDS = 16;
constexpr int DIFFUSION_BACKEND_MAX_DEVICES = 16;

enum DiffusionPreflightStatusNative {
    DIFFUSION_PREFLIGHT_FIT = 0,
    DIFFUSION_PREFLIGHT_NO_FIT = 1,
    DIFFUSION_PREFLIGHT_INVALID = 2,
    DIFFUSION_PREFLIGHT_UNAVAILABLE = 3,
};

enum DiffusionArchitectureNative {
    DIFFUSION_ARCH_UNKNOWN = 0,
    DIFFUSION_ARCH_SD1 = 1,
    DIFFUSION_ARCH_SD2 = 2,
    DIFFUSION_ARCH_SDXL = 3,
    DIFFUSION_ARCH_SD3 = 4,
    DIFFUSION_ARCH_FLUX = 5,
    DIFFUSION_ARCH_WAN = 6,
    DIFFUSION_ARCH_OTHER_IMAGE = 7,
    DIFFUSION_ARCH_OTHER_VIDEO = 8,
};

enum DiffusionQuantizationNative {
    DIFFUSION_QUANT_UNKNOWN = 0,
    DIFFUSION_QUANT_MIXED = 1,
    DIFFUSION_QUANT_F32 = 2,
    DIFFUSION_QUANT_F16 = 3,
    DIFFUSION_QUANT_BF16 = 4,
    DIFFUSION_QUANT_Q4_0 = 5,
    DIFFUSION_QUANT_Q4_1 = 6,
    DIFFUSION_QUANT_Q5_0 = 7,
    DIFFUSION_QUANT_Q5_1 = 8,
    DIFFUSION_QUANT_Q8_0 = 9,
    DIFFUSION_QUANT_Q2_K = 10,
    DIFFUSION_QUANT_Q3_K = 11,
    DIFFUSION_QUANT_Q4_K = 12,
    DIFFUSION_QUANT_Q5_K = 13,
    DIFFUSION_QUANT_Q6_K = 14,
    DIFFUSION_QUANT_OTHER = 15,
};

enum DiffusionComponentRoleNative {
    DIFFUSION_COMPONENT_MODEL_BUNDLE = 0,
    DIFFUSION_COMPONENT_DIFFUSION_MODEL = 1,
    DIFFUSION_COMPONENT_VAE = 2,
    DIFFUSION_COMPONENT_LLM = 3,
    DIFFUSION_COMPONENT_CLIP_L = 4,
    DIFFUSION_COMPONENT_CLIP_G = 5,
    DIFFUSION_COMPONENT_T5XXL = 6,
    DIFFUSION_COMPONENT_TAESD = 7,
    DIFFUSION_COMPONENT_TEXT_ENCODER = 8,
    DIFFUSION_COMPONENT_OTHER = 9,
};

enum DiffusionRuntimePlacementNative {
    DIFFUSION_RUNTIME_DEFAULT = 0,
    DIFFUSION_RUNTIME_CPU = 1,
    DIFFUSION_RUNTIME_GPU = 2,
    DIFFUSION_RUNTIME_SPLIT_GPU = 3,
};

enum DiffusionParameterPlacementNative {
    DIFFUSION_PARAMS_DEFAULT = 0,
    DIFFUSION_PARAMS_CPU = 1,
    DIFFUSION_PARAMS_DISK = 2,
};

enum DiffusionBackendKindNative {
    DIFFUSION_BACKEND_CPU = 0,
    DIFFUSION_BACKEND_CUDA = 1,
    DIFFUSION_BACKEND_METAL = 2,
    DIFFUSION_BACKEND_VULKAN = 3,
    DIFFUSION_BACKEND_OPENCL = 4,
    DIFFUSION_BACKEND_SYCL = 5,
    DIFFUSION_BACKEND_OTHER = 6,
};

enum DiffusionBackendDeviceTypeNative {
    DIFFUSION_BACKEND_DEVICE_CPU = 0,
    DIFFUSION_BACKEND_DEVICE_DISCRETE_GPU = 1,
    DIFFUSION_BACKEND_DEVICE_INTEGRATED_GPU = 2,
    DIFFUSION_BACKEND_DEVICE_ACCELERATOR = 3,
    DIFFUSION_BACKEND_DEVICE_META = 4,
};

struct DiffusionPreflightComponentNative {
    int source_role = DIFFUSION_COMPONENT_MODEL_BUNDLE;
    int source_ordinal = 0;
    int subdivision_role = DIFFUSION_COMPONENT_OTHER;
    int ordinal = 0;
    int64_t parameter_bytes = 0;
    int runtime_placement = DIFFUSION_RUNTIME_DEFAULT;
    int64_t runtime_backend_mask = 0;
    int parameter_placement = DIFFUSION_PARAMS_DEFAULT;
};

struct DiffusionPreflightBackendNative {
    int kind = DIFFUSION_BACKEND_OTHER;
    int device_type = DIFFUSION_BACKEND_DEVICE_META;
    int ordinal = 0;
    int64_t budget_bytes = 0;
    int64_t free_bytes = 0;
    int64_t total_bytes = 0;
};

struct DiffusionPreflightResultNative {
    int status = DIFFUSION_PREFLIGHT_UNAVAILABLE;
    int architecture = DIFFUSION_ARCH_UNKNOWN;
    int quantization = DIFFUSION_QUANT_UNKNOWN;
    int memory_confidence = 0;
    bool segmented_compute = false;
    bool prefetch = false;
    bool auto_fit = false;
    int64_t declared_source_mask = 0;
    int source_count = 0;
    int component_count = 0;
    int backend_count = 0;
    DiffusionPreflightComponentNative components[DIFFUSION_PREFLIGHT_MAX_COMPONENTS]{};
    DiffusionPreflightBackendNative backends[DIFFUSION_PREFLIGHT_MAX_BACKENDS]{};
};

struct DiffusionBackendCapabilityNative {
    int kind = DIFFUSION_BACKEND_OTHER;
    int device_type = DIFFUSION_BACKEND_DEVICE_META;
    int64_t free_bytes = -1;
    int64_t total_bytes = -1;
    int device_identity_length = 0;
    int64_t device_identity_words[8]{};
};

struct DiffusionBackendCapabilitiesNative {
    int count = -1;
    DiffusionBackendCapabilityNative devices[DIFFUSION_BACKEND_MAX_DEVICES]{};
};

enum DiffusionFeatureStateNative {
    DIFFUSION_FEATURE_SUPPORTED = 0,
    DIFFUSION_FEATURE_UNSUPPORTED = 1,
    DIFFUSION_FEATURE_UNKNOWN = 2,
};

struct DiffusionModelFeatureSupportNative {
    int architecture = DIFFUSION_FEATURE_UNKNOWN;
    int quantization = DIFFUSION_FEATURE_UNKNOWN;
    int mode = DIFFUSION_FEATURE_UNKNOWN;
};

DiffusionPreflightResultNative diffusion_runner_core_preflight(const DiffusionModelConfig &config);
DiffusionBackendCapabilitiesNative diffusion_runner_core_backend_capabilities();
DiffusionModelFeatureSupportNative diffusion_runner_core_probe_model_features(
    const char *architecture,
    const char *quantization,
    int mode);
std::string diffusion_runner_core_engine_version();
std::string diffusion_runner_core_model_version(int64_t handle);

#ifdef CARAML_DIFFUSION_NATIVE_TESTING
struct DiffusionContextParamsForTest {
    std::string backend;
    std::string params_backend;
    std::string max_vram;
    bool segmented_compute = false;
    bool prefetch = false;
    bool auto_fit = true;
};

bool diffusion_runner_core_capture_context_params_for_test(
    const DiffusionModelConfig &config,
    DiffusionContextParamsForTest &captured);
#endif
