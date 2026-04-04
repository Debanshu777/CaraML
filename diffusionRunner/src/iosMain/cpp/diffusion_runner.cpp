#include "diffusion_runner.h"
#include "../../commonCpp/diffusion_runner_core.h"
#include <iostream>
#include <cstdlib>
#include <cstring>

// iOS logger implementation
static void ios_log_callback(DiffusionLogLevel level, const char *msg) {
    const char *level_str = "";
    switch (level) {
        case DIFFUSION_LOG_DEBUG:
            level_str = "[DEBUG]";
            break;
        case DIFFUSION_LOG_INFO:
            level_str = "[INFO]";
            break;
        case DIFFUSION_LOG_WARN:
            level_str = "[WARN]";
            break;
        case DIFFUSION_LOG_ERROR:
            level_str = "[ERROR]";
            break;
        default:
            level_str = "[INFO]";
            break;
    }
    std::cerr << level_str << " " << msg << std::endl;
}

// Convert FFI config to internal config
static DiffusionModelConfig convert_model_config(const DiffusionModelConfigFFI &ffi_config) {
    DiffusionModelConfig config = {};
    config.model_path = ffi_config.model_path;
    config.vae_path = ffi_config.vae_path;
    config.llm_path = ffi_config.llm_path;
    config.clip_l_path = ffi_config.clip_l_path;
    config.clip_g_path = ffi_config.clip_g_path;
    config.t5xxl_path = ffi_config.t5xxl_path;
    config.offload_to_cpu = ffi_config.offload_to_cpu != 0;
    config.keep_clip_on_cpu = ffi_config.keep_clip_on_cpu != 0;
    config.keep_vae_on_cpu = ffi_config.keep_vae_on_cpu != 0;
    config.diffusion_flash_attn = ffi_config.diffusion_flash_attn != 0;
    config.enable_mmap = ffi_config.enable_mmap != 0;
    config.diffusion_conv_direct = ffi_config.diffusion_conv_direct != 0;
    config.wtype = ffi_config.wtype;
    config.flow_shift = ffi_config.flow_shift;
    config.flow_shift_is_set = ffi_config.flow_shift_is_set != 0;
    config.n_threads = ffi_config.n_threads;
    return config;
}

// Convert FFI config to internal config
static ImageGenConfig convert_image_config(const ImageGenConfigFFI &ffi_config,
        const char **lora_paths,
        float *lora_strengths,
        int lora_count) {
    ImageGenConfig config = {};
    config.prompt = ffi_config.prompt;
    config.negative_prompt = ffi_config.negative_prompt;
    config.width = ffi_config.width;
    config.height = ffi_config.height;
    config.steps = ffi_config.steps;
    config.cfg_scale = ffi_config.cfg_scale;
    config.seed = ffi_config.seed;
    config.sample_method = ffi_config.sample_method;
    config.lora_paths = lora_paths;
    config.lora_strengths = lora_strengths;
    config.lora_count = lora_count;
    return config;
}

extern "C" {

void diffusion_runner_ios_init(void) {
    diffusion_runner_core_set_logger(ios_log_callback);
    diffusion_runner_core_init("");
}

long long diffusion_runner_ios_load_model(DiffusionModelConfigFFI config) {
    DiffusionModelConfig cpp_config = convert_model_config(config);
    return diffusion_runner_core_load_model(cpp_config);
}

PngResultFFI *diffusion_runner_ios_txt2img(long long handle,
        ImageGenConfigFFI config,
        const char **lora_paths,
        float *lora_strengths,
        int lora_count) {
    ImageGenConfig cpp_config = convert_image_config(config, lora_paths, lora_strengths, lora_count);
    PngResult result = diffusion_runner_core_txt2img(handle, cpp_config);

    PngResultFFI *ffi_result = (PngResultFFI *) malloc(sizeof(PngResultFFI));
    if (ffi_result) {
        ffi_result->data = result.data;
        ffi_result->size = result.size;
    }
    return ffi_result;
}

void diffusion_runner_ios_free_png(unsigned char *data) {
    if (data) {
        free(data);
    }
}

void diffusion_runner_ios_free_result(PngResultFFI *result) {
    if (result) {
        free(result);
    }
}

void diffusion_runner_ios_release(long long handle) {
    diffusion_runner_core_release(handle);
}

}