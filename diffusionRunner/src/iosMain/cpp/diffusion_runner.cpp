#include "diffusion_runner.h"
#include "../../commonCpp/diffusion_runner_core.h"
#include <iostream>
#include <cstdlib>
#include <cstring>
#include <exception>
#include <new>

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

template <typename Result, typename Action>
static Result ffi_guard(const char *operation, Result fallback, Action &&action) noexcept {
    try {
        return action();
    } catch (const std::bad_alloc &) {
        std::cerr << "[ERROR] " << operation << ": allocation failed" << std::endl;
    } catch (const std::exception &) {
        std::cerr << "[ERROR] " << operation << ": native failure" << std::endl;
    } catch (...) {
        std::cerr << "[ERROR] " << operation << ": unknown native failure" << std::endl;
    }
    return fallback;
}

template <typename Action>
static void ffi_guard_void(const char *operation, Action &&action) noexcept {
    (void)ffi_guard<int>(operation, 0, [&action]() {
        action();
        return 1;
    });
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
    config.runtime_backend = ffi_config.runtime_backend;
    config.offload_to_cpu = ffi_config.offload_to_cpu != 0;
    config.keep_clip_on_cpu = ffi_config.keep_clip_on_cpu != 0;
    config.keep_vae_on_cpu = ffi_config.keep_vae_on_cpu != 0;
    config.diffusion_flash_attn = ffi_config.diffusion_flash_attn != 0;
    config.enable_mmap = ffi_config.enable_mmap != 0;
    config.diffusion_conv_direct = ffi_config.diffusion_conv_direct != 0;
    config.free_params_immediately = ffi_config.free_params_immediately != 0;
    config.wtype = ffi_config.wtype;
    config.flow_shift = ffi_config.flow_shift;
    config.flow_shift_is_set = ffi_config.flow_shift_is_set != 0;
    config.n_threads = ffi_config.n_threads;
    config.prediction = ffi_config.prediction;
    config.taesd_path = ffi_config.taesd_path ? ffi_config.taesd_path : "";
    config.vae_tiling = ffi_config.vae_tiling != 0;
    config.max_vram = ffi_config.max_vram ? ffi_config.max_vram : "";
    config.stream_layers = ffi_config.stream_layers != 0;
    config.auto_fit = ffi_config.auto_fit != 0;
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
    ffi_guard_void("init", []() {
        diffusion_runner_core_set_logger(ios_log_callback);
        diffusion_runner_core_init("");
    });
}

long long diffusion_runner_ios_load_model(DiffusionModelConfigFFI config) {
    return ffi_guard<long long>("load_model", 0, [&config]() {
        DiffusionModelConfig cpp_config = convert_model_config(config);
        return diffusion_runner_core_load_model(cpp_config);
    });
}

PngResultFFI *diffusion_runner_ios_txt2img(long long handle,
        ImageGenConfigFFI config,
        const char **lora_paths,
        float *lora_strengths,
        int lora_count) {
    return ffi_guard<PngResultFFI *>("txt2img", nullptr, [&]() {
        ImageGenConfig cpp_config = convert_image_config(config, lora_paths, lora_strengths, lora_count);
        PngResult result = diffusion_runner_core_txt2img(handle, cpp_config);

        PngResultFFI *ffi_result = (PngResultFFI *) malloc(sizeof(PngResultFFI));
        if (!ffi_result) {
            free(result.data);
            return static_cast<PngResultFFI *>(nullptr);
        }
        ffi_result->data = result.data;
        ffi_result->size = result.size;
        return ffi_result;
    });
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

int diffusion_runner_ios_cancel_generation(long long handle) {
    return ffi_guard<int>("cancel_generation", 0, [handle]() {
        return diffusion_runner_core_cancel_generation(handle) ? 1 : 0;
    });
}

void diffusion_runner_ios_release(long long handle) {
    ffi_guard_void("release", [handle]() { diffusion_runner_core_release(handle); });
}

struct DiffusionMetadataResultFFI diffusion_runner_ios_get_metadata(const char* model_path) {
    struct DiffusionMetadataResultFFI fallback = {};
    return ffi_guard<DiffusionMetadataResultFFI>("get_metadata", fallback, [model_path]() {
        DiffusionMetadataResult core_result = diffusion_runner_core_get_metadata(model_path);

        struct DiffusionMetadataResultFFI ffi = {};
        ffi.success = core_result.success ? 1 : 0;
        ffi.estimated_ram = (long long)core_result.estimated_ram;
        strncpy(ffi.architecture, core_result.architecture, sizeof(ffi.architecture) - 1);
        strncpy(ffi.dominant_quant, core_result.dominant_quant, sizeof(ffi.dominant_quant) - 1);
        return ffi;
    });
}

int diffusion_runner_ios_preflight(
        DiffusionModelConfigFFI config,
        long long *output,
        int capacity) {
    return ffi_guard<int>("preflight", 0, [&]() {
        constexpr int header_fields = 8;
        constexpr int component_fields = 6;
        constexpr int backend_fields = 6;
        if (!output || capacity < header_fields) return 0;
        const DiffusionPreflightResultNative native =
            diffusion_runner_core_preflight(convert_model_config(config));
        if (native.component_count < 0 || native.component_count > DIFFUSION_PREFLIGHT_MAX_COMPONENTS ||
            native.backend_count < 0 || native.backend_count > DIFFUSION_PREFLIGHT_MAX_BACKENDS) {
            return 0;
        }
        const int count = header_fields +
            native.component_count * component_fields +
            native.backend_count * backend_fields;
        if (count > capacity) return 0;
        int cursor = 0;
        output[cursor++] = native.status;
        output[cursor++] = native.architecture;
        output[cursor++] = native.quantization;
        output[cursor++] = native.memory_confidence;
        output[cursor++] = native.stream_layers ? 1 : 0;
        output[cursor++] = native.declared_component_mask;
        output[cursor++] = native.component_count;
        output[cursor++] = native.backend_count;
        for (int index = 0; index < native.component_count; ++index) {
            const DiffusionPreflightComponentNative &component = native.components[index];
            output[cursor++] = component.role;
            output[cursor++] = component.ordinal;
            output[cursor++] = component.parameter_bytes;
            output[cursor++] = component.runtime_placement;
            output[cursor++] = component.runtime_backend_mask;
            output[cursor++] = component.parameter_placement;
        }
        for (int index = 0; index < native.backend_count; ++index) {
            const DiffusionPreflightBackendNative &backend = native.backends[index];
            output[cursor++] = backend.kind;
            output[cursor++] = backend.device_type;
            output[cursor++] = backend.ordinal;
            output[cursor++] = backend.budget_bytes;
            output[cursor++] = backend.free_bytes;
            output[cursor++] = backend.total_bytes;
        }
        return cursor;
    });
}

int diffusion_runner_ios_backend_capabilities(long long *output, int capacity) {
    return ffi_guard<int>("backend_capabilities", 0, [&]() {
        const DiffusionBackendCapabilitiesNative native =
            diffusion_runner_core_backend_capabilities();
        if (!output || native.count < 1 || native.count > DIFFUSION_BACKEND_MAX_DEVICES) return 0;
        const int count = 1 + native.count * 13;
        if (capacity < count) return 0;
        int cursor = 0;
        output[cursor++] = native.count;
        for (int index = 0; index < native.count; ++index) {
            const DiffusionBackendCapabilityNative &device = native.devices[index];
            output[cursor++] = device.kind;
            output[cursor++] = device.device_type;
            output[cursor++] = device.free_bytes;
            output[cursor++] = device.total_bytes;
            output[cursor++] = device.device_identity_length;
            for (int word = 0; word < 8; ++word) {
                output[cursor++] = device.device_identity_words[word];
            }
        }
        return cursor;
    });
}

int diffusion_runner_ios_probe_model_features(
        const char *architecture,
        const char *quantization,
        int mode,
        long long *output,
        int capacity) {
    return ffi_guard<int>("probe_model_features", 0, [&]() {
        if (!output || capacity < 3) return 0;
        const DiffusionModelFeatureSupportNative native =
            diffusion_runner_core_probe_model_features(architecture, quantization, mode);
        output[0] = native.architecture;
        output[1] = native.quantization;
        output[2] = native.mode;
        return 3;
    });
}

const char *diffusion_runner_ios_engine_version(void) {
    static thread_local std::string version;
    return ffi_guard<const char *>("engine_version", nullptr, []() {
        version = diffusion_runner_core_engine_version();
        return version.empty() ? nullptr : version.c_str();
    });
}

}
