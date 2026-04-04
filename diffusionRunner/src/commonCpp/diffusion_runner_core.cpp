#include "diffusion_runner_core.h"
#include "stable-diffusion.h"
#include <memory>
#include <unordered_map>
#include <mutex>
#include <cstdlib>
#include <cstring>

// Define STB_IMAGE_WRITE_IMPLEMENTATION for PNG encoding
#define STB_IMAGE_WRITE_IMPLEMENTATION

#include <stb_image_write.h>

struct SdHandle {
    sd_ctx_t *ctx = nullptr;
};

// Global state
static std::unordered_map<int64_t, std::unique_ptr<SdHandle>> g_handles;
static std::mutex g_handles_mutex;
static int64_t g_next_handle = 1;
static DiffusionLogFn g_log_fn = nullptr;

// PNG encoding callback
struct PngWriteContext {
    std::vector<uint8_t> data;
};

static void png_write_callback(void *context, void *data, int size) {
    auto *ctx = static_cast<PngWriteContext *>(context);
    const uint8_t *bytes = static_cast<const uint8_t *>(data);
    ctx->data.insert(ctx->data.end(), bytes, bytes + size);
}

// Stable diffusion log callback
static void sd_log_callback(sd_log_level_t level, const char *text, void *data) {
    if (!g_log_fn) return;

    DiffusionLogLevel log_level;
    switch (level) {
        case SD_LOG_DEBUG:
            log_level = DIFFUSION_LOG_DEBUG;
            break;
        case SD_LOG_INFO:
            log_level = DIFFUSION_LOG_INFO;
            break;
        case SD_LOG_WARN:
            log_level = DIFFUSION_LOG_WARN;
            break;
        case SD_LOG_ERROR:
            log_level = DIFFUSION_LOG_ERROR;
            break;
        default:
            log_level = DIFFUSION_LOG_INFO;
            break;
    }

    g_log_fn(log_level, text);
}

void diffusion_runner_core_set_logger(DiffusionLogFn fn) {
    g_log_fn = fn;
    sd_set_log_callback(sd_log_callback, nullptr);
}

void diffusion_runner_core_init(const char *backend_path) {
    // Initialize any backend-specific paths if needed
    // For now, just ensure logger is set up
    if (g_log_fn) {
        sd_set_log_callback(sd_log_callback, nullptr);
    }
}

int64_t diffusion_runner_core_load_model(const DiffusionModelConfig &config) {
    // Set up sd_ctx_params_t
    sd_ctx_params_t params = {};
    sd_ctx_params_init(&params);

    // Determine model path - if any of the component paths are set, use diffusion_model_path
    if (strlen(config.vae_path) > 0 || strlen(config.llm_path) > 0 ||
            strlen(config.clip_l_path) > 0 || strlen(config.clip_g_path) > 0 ||
            strlen(config.t5xxl_path) > 0) {
        params.diffusion_model_path = config.model_path;
        if (strlen(config.vae_path) > 0) params.vae_path = config.vae_path;
        if (strlen(config.llm_path) > 0) params.llm_path = config.llm_path;
        if (strlen(config.clip_l_path) > 0) params.clip_l_path = config.clip_l_path;
        if (strlen(config.clip_g_path) > 0) params.clip_g_path = config.clip_g_path;
        if (strlen(config.t5xxl_path) > 0) params.t5xxl_path = config.t5xxl_path;
    } else {
        params.model_path = config.model_path;
    }

    params.offload_params_to_cpu = config.offload_to_cpu;
    params.keep_clip_on_cpu = config.keep_clip_on_cpu;
    params.keep_vae_on_cpu = config.keep_vae_on_cpu;
    params.diffusion_flash_attn = config.diffusion_flash_attn;
    params.enable_mmap = config.enable_mmap;
    params.diffusion_conv_direct = config.diffusion_conv_direct;

    if (config.wtype >= 0) {
        params.wtype = static_cast<sd_type_t>(config.wtype);
    }

    if (config.n_threads > 0) {
        params.n_threads = config.n_threads;
    } else if (config.n_threads == -1) {
        params.n_threads = sd_get_num_physical_cores();
    }

    // Create context
    sd_ctx_t *ctx = new_sd_ctx(&params);
    if (!ctx) {
        return 0;  // Failed to load
    }

    // Create handle
    auto handle = std::make_unique<SdHandle>();
    handle->ctx = ctx;

    std::lock_guard<std::mutex> lock(g_handles_mutex);
    int64_t handle_id = g_next_handle++;
    g_handles[handle_id] = std::move(handle);

    return handle_id;
}

PngResult diffusion_runner_core_txt2img(int64_t handle_id, const ImageGenConfig &config) {
    PngResult result = {nullptr, 0};

    // Find handle
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(handle_id);
    if (it == g_handles.end() || !it->second || !it->second->ctx) {
        return result;
    }

    sd_ctx_t *ctx = it->second->ctx;

    // Set up generation parameters
    sd_img_gen_params_t gen_params = {};
    sd_img_gen_params_init(&gen_params);

    gen_params.prompt = config.prompt;
    gen_params.negative_prompt = config.negative_prompt;
    gen_params.width = config.width;
    gen_params.height = config.height;
    gen_params.seed = config.seed;
    gen_params.batch_count = 1;

    // Set up sampling parameters
    sd_sample_params_t sample_params = {};
    sd_sample_params_init(&sample_params);
    sample_params.sample_steps = config.steps;
    sample_params.guidance.txt_cfg = config.cfg_scale;
    sample_params.sample_method = static_cast<sample_method_t>(config.sample_method);

    // Apply flow_shift to sample params if needed (note: flow_shift is in sample_params, not ctx_params)
    // This would need to be passed from the config if needed

    gen_params.sample_params = sample_params;

    // Set up LoRA if provided
    std::vector<sd_lora_t> lora_configs;
    if (config.lora_count > 0 && config.lora_paths && config.lora_strengths) {
        lora_configs.reserve(config.lora_count);
        for (int i = 0; i < config.lora_count; i++) {
            sd_lora_t lora = {};
            lora.path = config.lora_paths[i];
            lora.multiplier = config.lora_strengths[i];
            lora_configs.push_back(lora);
        }
        gen_params.loras = lora_configs.data();
        gen_params.lora_count = config.lora_count;
    }

    // Generate image
    sd_image_t *images = generate_image(ctx, &gen_params);
    if (!images || !images[0].data) {
        return result;
    }

    // Convert to PNG
    PngWriteContext png_ctx;
    int success = stbi_write_png_to_func(png_write_callback, &png_ctx,
            images[0].width, images[0].height,
            images[0].channel, images[0].data,
            images[0].width * images[0].channel);

    // Free the original image data
    if (images[0].data) {
        free(images[0].data);
    }
    free(images);

    if (success && !png_ctx.data.empty()) {
        // Allocate result data
        result.data = static_cast<uint8_t *>(malloc(png_ctx.data.size()));
        if (result.data) {
            memcpy(result.data, png_ctx.data.data(), png_ctx.data.size());
            result.size = static_cast<int>(png_ctx.data.size());
        }
    }

    return result;
}

std::vector<PngResult> diffusion_runner_core_video_gen(int64_t handle_id, const VideoGenConfig &config) {
    std::vector<PngResult> results;

    // Find handle
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(handle_id);
    if (it == g_handles.end() || !it->second || !it->second->ctx) {
        return results;
    }

    sd_ctx_t *ctx = it->second->ctx;

    // Set up generation parameters
    sd_vid_gen_params_t gen_params = {};
    sd_vid_gen_params_init(&gen_params);

    gen_params.prompt = config.prompt;
    gen_params.negative_prompt = config.negative_prompt;
    gen_params.width = config.width;
    gen_params.height = config.height;
    gen_params.video_frames = config.video_frames;
    gen_params.seed = config.seed;

    // Set up sampling parameters
    sd_sample_params_t sample_params = {};
    sd_sample_params_init(&sample_params);
    sample_params.sample_steps = config.steps;
    sample_params.guidance.txt_cfg = config.cfg_scale;
    sample_params.sample_method = static_cast<sample_method_t>(config.sample_method);

    gen_params.sample_params = sample_params;

    // Set up LoRA if provided
    std::vector<sd_lora_t> lora_configs;
    if (config.lora_count > 0 && config.lora_paths && config.lora_strengths) {
        lora_configs.reserve(config.lora_count);
        for (int i = 0; i < config.lora_count; i++) {
            sd_lora_t lora = {};
            lora.path = config.lora_paths[i];
            lora.multiplier = config.lora_strengths[i];
            lora_configs.push_back(lora);
        }
        gen_params.loras = lora_configs.data();
        gen_params.lora_count = config.lora_count;
    }

    // Generate video frames
    int num_frames_out = 0;
    sd_image_t *images = generate_video(ctx, &gen_params, &num_frames_out);
    if (!images || num_frames_out <= 0) {
        return results;
    }

    // Convert each frame to PNG
    results.reserve(num_frames_out);
    for (int i = 0; i < num_frames_out; i++) {
        PngResult result = {nullptr, 0};

        if (images[i].data) {
            PngWriteContext png_ctx;
            int success = stbi_write_png_to_func(png_write_callback, &png_ctx,
                    images[i].width, images[i].height,
                    images[i].channel, images[i].data,
                    images[i].width * images[i].channel);

            if (success && !png_ctx.data.empty()) {
                result.data = static_cast<uint8_t *>(malloc(png_ctx.data.size()));
                if (result.data) {
                    memcpy(result.data, png_ctx.data.data(), png_ctx.data.size());
                    result.size = static_cast<int>(png_ctx.data.size());
                }
            }
        }

        results.push_back(result);
    }

    // Free the original images
    for (int i = 0; i < num_frames_out; i++) {
        if (images[i].data) {
            free(images[i].data);
        }
    }
    free(images);

    return results;
}

void diffusion_runner_core_release(int64_t handle_id) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(handle_id);
    if (it != g_handles.end()) {
        if (it->second && it->second->ctx) {
            free_sd_ctx(it->second->ctx);
        }
        g_handles.erase(it);
    }
}