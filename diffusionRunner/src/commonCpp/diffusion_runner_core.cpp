#include "diffusion_runner_core.h"
#include "stable-diffusion.h"
#include "model.h"
#include "ggml.h"
#ifdef SD_USE_VULKAN
#include "ggml-vulkan.h"
#endif
#include <memory>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <cstdarg>

// Define STB_IMAGE_WRITE_IMPLEMENTATION for PNG encoding
#define STB_IMAGE_WRITE_IMPLEMENTATION

#include <stb_image_write.h>

struct SdHandle {
    sd_ctx_t *ctx = nullptr;
    float flow_shift = 0.0f;
    bool flow_shift_is_set = false;
    bool vae_tiling = false;
};

// Global state
static std::unordered_map<int64_t, std::unique_ptr<SdHandle>> g_handles;
static std::mutex g_handles_mutex;
static int64_t g_next_handle = 1;
static DiffusionLogFn g_log_fn = nullptr;

// Step-progress tracking (updated by the C callback on the generation thread;
// read from the Kotlin polling coroutine on a different thread — atomics are sufficient).
static std::atomic<int> g_progress_step(0);
static std::atomic<int> g_progress_total(0);

static void step_progress_callback(int step, int steps, float /*time*/, void* /*data*/) {
    g_progress_step.store(step,  std::memory_order_relaxed);
    g_progress_total.store(steps, std::memory_order_relaxed);
}

void diffusion_runner_get_step_progress(int* step, int* total) {
    *step  = g_progress_step.load(std::memory_order_relaxed);
    *total = g_progress_total.load(std::memory_order_relaxed);
}

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

// ggml-level log callback. Vulkan backend errors (GGML_ABORT messages, "fatal error",
// pipeline lookup failures) come through here, NOT through sd.cpp's logger. Without this
// hook those messages disappear into stderr and we only see SIGABRT in logcat.
static void dr_ggml_log_callback(ggml_log_level level, const char *text, void * /*user_data*/) {
    if (!g_log_fn || !text) return;
    DiffusionLogLevel log_level;
    switch (level) {
        case GGML_LOG_LEVEL_DEBUG: log_level = DIFFUSION_LOG_DEBUG; break;
        case GGML_LOG_LEVEL_INFO:  log_level = DIFFUSION_LOG_INFO;  break;
        case GGML_LOG_LEVEL_WARN:  log_level = DIFFUSION_LOG_WARN;  break;
        case GGML_LOG_LEVEL_ERROR: log_level = DIFFUSION_LOG_ERROR; break;
        case GGML_LOG_LEVEL_CONT:  log_level = DIFFUSION_LOG_DEBUG; break;
        default:                   log_level = DIFFUSION_LOG_INFO;  break;
    }
    // Prefix so we can distinguish ggml lines from sd lines in logcat.
    char buf[1024];
    snprintf(buf, sizeof(buf), "[ggml] %s", text);
    g_log_fn(log_level, buf);
}

// Small helper for one-shot formatted log lines from this file.
static void dr_logf(DiffusionLogLevel level, const char *fmt, ...) {
    if (!g_log_fn) return;
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    g_log_fn(level, buf);
}

void diffusion_runner_core_set_logger(DiffusionLogFn fn) {
    g_log_fn = fn;
    sd_set_log_callback(sd_log_callback, nullptr);
    ggml_log_set(dr_ggml_log_callback, nullptr);
}

void diffusion_runner_core_init(const char *backend_path) {
    // Initialize any backend-specific paths if needed
    // For now, just ensure logger is set up
    if (g_log_fn) {
        sd_set_log_callback(sd_log_callback, nullptr);
    }
}

int64_t diffusion_runner_core_load_model(const DiffusionModelConfig &config) {
    dr_logf(DIFFUSION_LOG_INFO,
            "load_model: ENTER model_path='%s' vae='%s' clip_l='%s' clip_g='%s' t5xxl='%s' llm='%s' "
            "taesd='%s' offload=%d keep_clip_cpu=%d keep_vae_cpu=%d flash_attn=%d mmap=%d "
            "conv_direct=%d free_immediate=%d wtype=%d prediction=%d flow_shift=%.4f set=%d "
            "vae_tiling=%d n_threads=%d",
            config.model_path ? config.model_path : "(null)",
            config.vae_path ? config.vae_path : "",
            config.clip_l_path ? config.clip_l_path : "",
            config.clip_g_path ? config.clip_g_path : "",
            config.t5xxl_path ? config.t5xxl_path : "",
            config.llm_path ? config.llm_path : "",
            config.taesd_path ? config.taesd_path : "",
            (int)config.offload_to_cpu, (int)config.keep_clip_on_cpu, (int)config.keep_vae_on_cpu,
            (int)config.diffusion_flash_attn, (int)config.enable_mmap,
            (int)config.diffusion_conv_direct, (int)config.free_params_immediately,
            config.wtype, config.prediction, config.flow_shift, (int)config.flow_shift_is_set,
            (int)config.vae_tiling, config.n_threads);

#ifdef SD_USE_VULKAN
    {
        int vk_count = ggml_backend_vk_get_device_count();
        dr_logf(DIFFUSION_LOG_INFO, "load_model: SD_USE_VULKAN defined; vulkan_device_count=%d", vk_count);
        for (int i = 0; i < vk_count; i++) {
            char desc[256] = {0};
            ggml_backend_vk_get_device_description(i, desc, sizeof(desc));
            size_t free_mem = 0, total_mem = 0;
            ggml_backend_vk_get_device_memory(i, &free_mem, &total_mem);
            dr_logf(DIFFUSION_LOG_INFO,
                    "load_model: vulkan device[%d]='%s' free=%.1fMB total=%.1fMB",
                    i, desc,
                    free_mem  / (1024.0 * 1024.0),
                    total_mem / (1024.0 * 1024.0));
        }
    }
#else
    dr_logf(DIFFUSION_LOG_WARN,
            "load_model: SD_USE_VULKAN NOT defined in this translation unit — "
            "vulkan-aware workarounds (keep_clip_on_cpu, wtype=F16) are DISABLED here. "
            "If sd.cpp was built with SD_USE_VULKAN, the backend will still pick Vulkan "
            "and CLIP/VAE will crash with GGML_ABORT. Fix the CMake macro propagation.");
#endif

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
        dr_logf(DIFFUSION_LOG_INFO, "load_model: using split-component path layout (diffusion_model_path)");
    } else {
        params.model_path = config.model_path;
        dr_logf(DIFFUSION_LOG_INFO, "load_model: using single-file path layout (model_path)");
    }

    params.offload_params_to_cpu = config.offload_to_cpu;
    // ggml-vulkan on Android (Adreno/Mali) lacks F16 softmax/norm pipeline variants
    // used by the CLIP transformer and VAE decoder. Submitting those graphs to the
    // Vulkan backend triggers GGML_ABORT inside ggml_vk_op_get_pipeline.
    // Mirror stable-diffusion.cpp's documented workaround: pin CLIP + VAE to the CPU
    // backend whenever a Vulkan device is present. The heavy UNet stays on Vulkan.
    // Caller opt-in (config flag = true) is preserved via OR.
#ifdef SD_USE_VULKAN
    if (ggml_backend_vk_get_device_count() > 0) {
        params.keep_clip_on_cpu = true;
        params.keep_vae_on_cpu  = true;
        dr_logf(DIFFUSION_LOG_INFO,
                "load_model: vulkan present → FORCING keep_clip_on_cpu=true, keep_vae_on_cpu=true");
    } else {
        params.keep_clip_on_cpu = config.keep_clip_on_cpu;
        params.keep_vae_on_cpu  = config.keep_vae_on_cpu;
    }
#else
    params.keep_clip_on_cpu = config.keep_clip_on_cpu;
    params.keep_vae_on_cpu = config.keep_vae_on_cpu;
#endif
    params.diffusion_flash_attn = config.diffusion_flash_attn;
    params.enable_mmap = config.enable_mmap;
    params.diffusion_conv_direct = config.diffusion_conv_direct;
    params.free_params_immediately = config.free_params_immediately;

    if (config.wtype >= 0) {
        params.wtype = static_cast<sd_type_t>(config.wtype);
        dr_logf(DIFFUSION_LOG_INFO, "load_model: caller wtype override = %d", config.wtype);
    }
    if (config.n_threads > 0) {
        params.n_threads = config.n_threads;
    } else if (config.n_threads == -1) {
        params.n_threads = sd_get_num_physical_cores();
    }
    dr_logf(DIFFUSION_LOG_INFO, "load_model: resolved n_threads=%d", params.n_threads);

    // Explicit prediction type overrides auto-detection.
    // Critically: setting this skips is_using_v_parameterization_for_sd2() which
    // runs a test UNet compute — that test can abort() on Vulkan for some models.
    if (config.prediction >= 0 && config.prediction < PREDICTION_COUNT) {
        params.prediction = static_cast<prediction_t>(config.prediction);
        dr_logf(DIFFUSION_LOG_INFO, "load_model: explicit prediction=%d (skips v_param probe)", config.prediction);
    } else {
        dr_logf(DIFFUSION_LOG_WARN,
                "load_model: prediction=auto — sd.cpp will run is_using_v_parameterization_for_sd2() "
                "probe. This can crash on Vulkan for some SD2 models.");
    }

    // TAESD: tiny autoencoder — replaces full VAE decode for fast preview.
    if (config.taesd_path && strlen(config.taesd_path) > 0) {
        params.taesd_path = config.taesd_path;
    }

    dr_logf(DIFFUSION_LOG_INFO,
            "load_model: → calling new_sd_ctx(wtype=%d, keep_clip_cpu=%d, keep_vae_cpu=%d, "
            "offload=%d, flash_attn=%d, prediction=%d)",
            (int)params.wtype, (int)params.keep_clip_on_cpu, (int)params.keep_vae_on_cpu,
            (int)params.offload_params_to_cpu, (int)params.diffusion_flash_attn,
            (int)params.prediction);

    // Create context
    sd_ctx_t *ctx = new_sd_ctx(&params);
    if (!ctx) {
        dr_logf(DIFFUSION_LOG_ERROR, "load_model: new_sd_ctx returned NULL");
        return 0;  // Failed to load
    }
    dr_logf(DIFFUSION_LOG_INFO, "load_model: new_sd_ctx OK");

    // Create handle
    auto handle = std::make_unique<SdHandle>();
    handle->ctx = ctx;
    handle->flow_shift = config.flow_shift;
    handle->flow_shift_is_set = config.flow_shift_is_set;
    handle->vae_tiling = config.vae_tiling;

    std::lock_guard<std::mutex> lock(g_handles_mutex);
    int64_t handle_id = g_next_handle++;
    g_handles[handle_id] = std::move(handle);

    dr_logf(DIFFUSION_LOG_INFO, "load_model: handle_id=%lld", (long long)handle_id);
    return handle_id;
}

PngResult diffusion_runner_core_txt2img(int64_t handle_id, const ImageGenConfig &config) {
    PngResult result = {nullptr, 0};

    dr_logf(DIFFUSION_LOG_INFO,
            "txt2img: ENTER handle=%lld dims=%dx%d steps=%d cfg=%.2f seed=%lld sampler=%d "
            "vae_tile=%d lora_n=%d prompt_len=%zu neg_len=%zu",
            (long long)handle_id, config.width, config.height, config.steps, config.cfg_scale,
            (long long)config.seed, config.sample_method, (int)config.vae_tiling,
            config.lora_count,
            config.prompt ? strlen(config.prompt) : 0,
            config.negative_prompt ? strlen(config.negative_prompt) : 0);

    // Find handle
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(handle_id);
    if (it == g_handles.end() || !it->second || !it->second->ctx) {
        dr_logf(DIFFUSION_LOG_ERROR, "txt2img: invalid handle %lld", (long long)handle_id);
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
    if (config.flow_shift_is_set) {
        sample_params.flow_shift = config.flow_shift;
    } else if (it->second->flow_shift_is_set) {
        sample_params.flow_shift = it->second->flow_shift;
    }

    gen_params.sample_params = sample_params;

    // VAE tiling: enable when requested at gen-time or inherited from model config.
    if (config.vae_tiling || it->second->vae_tiling) {
        gen_params.vae_tiling_params.enabled = true;
        // tile_size_x/y = 0 → library auto-picks via first_stage_model->get_tile_sizes()
    }

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

    // Register step-progress callback. Reset to (0,0) — the callback will fill in the real
    // total when the sampling loop actually starts. Pre-sampling work (text encoding, latent
    // prep) doesn't fire the callback, so leaving total=0 lets the UI show "Preparing…" rather
    // than misleadingly claiming "Step 0 / 20".
    g_progress_step.store(0, std::memory_order_relaxed);
    g_progress_total.store(0, std::memory_order_relaxed);
    sd_set_progress_callback(step_progress_callback, nullptr);

    // Generate image
    dr_logf(DIFFUSION_LOG_INFO, "txt2img: → calling generate_image (this is where Vulkan CLIP crashes if mis-configured)");
    sd_image_t *images = generate_image(ctx, &gen_params);
    if (!images || !images[0].data) {
        dr_logf(DIFFUSION_LOG_ERROR, "txt2img: generate_image returned NULL or empty (success path failed quietly)");
        return result;
    }
    dr_logf(DIFFUSION_LOG_INFO, "txt2img: generate_image OK %dx%d ch=%d",
            images[0].width, images[0].height, images[0].channel);

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
    if (config.flow_shift_is_set) {
        sample_params.flow_shift = config.flow_shift;
    } else if (it->second->flow_shift_is_set) {
        sample_params.flow_shift = it->second->flow_shift;
    }

    gen_params.sample_params = sample_params;

    // VAE tiling: enable when requested at gen-time or inherited from model config.
    if (config.vae_tiling || it->second->vae_tiling) {
        gen_params.vae_tiling_params.enabled = true;
    }

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

    // Register step-progress callback. Reset to (0,0); the sampler fills in the real total
    // once it starts. See txt2img comment above.
    g_progress_step.store(0, std::memory_order_relaxed);
    g_progress_total.store(0, std::memory_order_relaxed);
    sd_set_progress_callback(step_progress_callback, nullptr);

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

/** Maps SDVersion enum to a compact family string for the Kotlin layer. */
static const char* sd_arch_family(SDVersion v) {
    switch (v) {
        case VERSION_FLUX:
        case VERSION_FLUX_FILL:
        case VERSION_FLUX_CONTROLS:
        case VERSION_FLEX_2:
        case VERSION_FLUX2:
        case VERSION_FLUX2_KLEIN: return "FLUX";

        case VERSION_SD3: return "SD3";

        case VERSION_WAN2_2_I2V:
        case VERSION_WAN2_2_TI2V: return "WAN2_LARGE";
        case VERSION_WAN2: return "WAN2_SMALL";

        case VERSION_SDXL:
        case VERSION_SDXL_INPAINT:
        case VERSION_SDXL_PIX2PIX:
        case VERSION_SDXL_VEGA:
        case VERSION_SDXL_SSD1B:
        case VERSION_SDXS_09: return "SDXL";

        case VERSION_SD1:
        case VERSION_SD1_INPAINT:
        case VERSION_SD1_PIX2PIX:
        case VERSION_SD1_TINY_UNET:
        case VERSION_SD2:
        case VERSION_SD2_INPAINT:
        case VERSION_SD2_TINY_UNET:
        case VERSION_SDXS_512_DS: return "SD1";

        default: return "UNKNOWN";
    }
}

DiffusionMetadataResult diffusion_runner_core_get_metadata(const char* model_path) {
    DiffusionMetadataResult result = {};
    result.success = false;
    result.estimated_ram = 0;
    result.architecture[0] = '\0';
    result.dominant_quant[0] = '\0';

    if (!model_path || model_path[0] == '\0') return result;

    try {
        ModelLoader loader;
        if (!loader.init_from_file(std::string(model_path))) {
            return result;
        }

        // Architecture
        SDVersion version = loader.get_sd_version();
        const char* arch_name = sd_arch_family(version);
        strncpy(result.architecture, arch_name, sizeof(result.architecture) - 1);
        result.architecture[sizeof(result.architecture) - 1] = '\0';

        // Dominant quantization (most frequent non-F32 weight tensor type)
        auto wtype_stat = loader.get_wtype_stat();
        ggml_type dominant_type = GGML_TYPE_COUNT;
        uint32_t max_count = 0;
        for (const auto& kv : wtype_stat) {
            if (kv.first != GGML_TYPE_F32 && kv.second > max_count) {
                max_count = kv.second;
                dominant_type = kv.first;
            }
        }
        if (dominant_type != GGML_TYPE_COUNT) {
            const char* quant_name = ggml_type_name(dominant_type);
            if (quant_name) {
                strncpy(result.dominant_quant, quant_name, sizeof(result.dominant_quant) - 1);
                result.dominant_quant[sizeof(result.dominant_quant) - 1] = '\0';
            }
        }

        // RAM estimate
        result.estimated_ram = loader.get_params_mem_size(nullptr, GGML_TYPE_COUNT);
        result.success = true;
    } catch (...) {
        // Leave success = false; caller checks result.success
    }

    return result;
}