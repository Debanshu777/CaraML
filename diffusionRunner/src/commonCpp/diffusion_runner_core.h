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
    int wtype = -1;
    float flow_shift = 0.0f;
    bool flow_shift_is_set = false;
    int n_threads = -1;
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