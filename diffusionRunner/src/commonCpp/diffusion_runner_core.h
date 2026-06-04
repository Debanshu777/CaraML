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

struct DiffusionModelConfig {
    const char *model_path = "";
    const char *vae_path = "";
    const char *llm_path = "";
    const char *clip_l_path = "";
    const char *clip_g_path = "";
    const char *t5xxl_path = "";
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

std::vector<PngResult> diffusion_runner_core_video_gen(int64_t handle, const VideoGenConfig &config);

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