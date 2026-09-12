#include "llama_runner_core.h"
#include "llama_runner.h"

#include <cstring>
#include <exception>
#include <iostream>
#include <new>
#include <string>

namespace {

static_assert(LLAMA_RUNNER_PREFLIGHT_MAX_POOLS == LLAMA_PREFLIGHT_MAX_POOLS);
static_assert(LLAMA_RUNNER_BACKEND_MAX_DEVICES == LLAMA_BACKEND_MAX_DEVICES);
static_assert(LLAMA_RUNNER_CALIBRATION_MAX_WINDOWS == LLAMA_CALIBRATION_MAX_WINDOWS);

void ios_log(LlamaLogLevel level, const char *msg) {
    if (level == LLAMA_LOG_ERROR) {
        std::cerr << "[LlamaRunner] ERROR: " << (msg ? msg : "") << std::endl;
        return;
    }
    if (level == LLAMA_LOG_WARN) {
        std::cerr << "[LlamaRunner] WARN: " << (msg ? msg : "") << std::endl;
        return;
    }
    std::cout << "[LlamaRunner] " << (msg ? msg : "") << std::endl;
}

template <typename Result, typename Action>
Result ffi_guard(const char *operation, Result fallback, Action &&action) noexcept {
    try {
        return action();
    } catch (const std::bad_alloc &) {
        std::cerr << "[LlamaRunner] ERROR: " << operation << ": allocation failed" << std::endl;
    } catch (const std::exception &) {
        std::cerr << "[LlamaRunner] ERROR: " << operation << ": native failure" << std::endl;
    } catch (...) {
        std::cerr << "[LlamaRunner] ERROR: " << operation << ": unknown native failure" << std::endl;
    }
    return fallback;
}

template <typename Action>
void ffi_guard_void(const char *operation, Action &&action) noexcept {
    (void)ffi_guard<int>(operation, 0, [&action]() {
        action();
        return 1;
    });
}

LlamaRunnerConfig to_core_config(const LlamaRunnerConfigFFI &ffi_config) {
    LlamaRunnerConfig config;
    config.n_ctx = ffi_config.n_ctx;
    config.n_ctx_min = ffi_config.n_ctx_min;
    config.n_threads = ffi_config.n_threads;
    config.n_threads_batch = ffi_config.n_threads_batch;
    config.n_batch = ffi_config.n_batch;
    config.n_ubatch = ffi_config.n_ubatch;
    config.flash_attn = ffi_config.flash_attn;
    config.offload_kqv = ffi_config.offload_kqv != 0;
    config.type_k = ffi_config.type_k;
    config.type_v = ffi_config.type_v;
    config.n_gpu_layers = ffi_config.n_gpu_layers;
    config.use_mmap = ffi_config.use_mmap != 0;
    config.use_mlock = ffi_config.use_mlock != 0;
    config.temperature = ffi_config.temperature;
    config.auto_fit = ffi_config.auto_fit != 0;
    config.cpu_mask = ffi_config.cpu_mask ? ffi_config.cpu_mask : "";
    config.cpu_mask_batch = ffi_config.cpu_mask_batch ? ffi_config.cpu_mask_batch : "";
    return config;
}

} // namespace

extern "C" {

void llama_runner_init(void) {
    ffi_guard_void("init", []() {
        llama_runner_core_set_logger(ios_log);
        llama_runner_core_init(nullptr);
    });
}

int llama_runner_load_model_v2(const char *model_path, struct LlamaRunnerConfigFFI ffi_config) {
    return ffi_guard<int>("load_model", 0, [model_path, ffi_config]() {
        return llama_runner_core_load_model(model_path, to_core_config(ffi_config)) ? 1 : 0;
    });
}

struct LlamaPreflightResultFFI llama_runner_preflight_model(
    const char *model_path,
    struct LlamaRunnerConfigFFI ffi_config) {
    return ffi_guard<LlamaPreflightResultFFI>(
        "preflight_model",
        LlamaPreflightResultFFI{LLAMA_PREFLIGHT_UNAVAILABLE, 0, 0, 0, {}},
        [model_path, ffi_config]() {
            const LlamaPreflightResultNative native =
                llama_runner_core_preflight(model_path, to_core_config(ffi_config));
            LlamaPreflightResultFFI result{};
            result.status = native.status;
            result.n_ctx = native.n_ctx;
            result.n_gpu_layers = native.n_gpu_layers;
            result.pool_count = native.pool_count;
            for (int index = 0; index < native.pool_count && index < LLAMA_RUNNER_PREFLIGHT_MAX_POOLS; index++) {
                result.pools[index].kind = native.pools[index].kind;
                result.pools[index].ordinal = native.pools[index].ordinal;
                result.pools[index].model_bytes = native.pools[index].model_bytes;
                result.pools[index].context_bytes = native.pools[index].context_bytes;
                result.pools[index].compute_bytes = native.pools[index].compute_bytes;
                result.pools[index].free_bytes = native.pools[index].free_bytes;
                result.pools[index].total_bytes = native.pools[index].total_bytes;
            }
            return result;
        });
}

struct LlamaBackendCapabilitiesFFI llama_runner_backend_capabilities(void) {
    return ffi_guard<LlamaBackendCapabilitiesFFI>(
        "backend_capabilities",
        LlamaBackendCapabilitiesFFI{-1, {}},
        []() {
            const LlamaBackendCapabilitiesNative native = llama_runner_core_backend_capabilities();
            LlamaBackendCapabilitiesFFI result{};
            result.count = native.count;
            for (int index = 0; index < native.count && index < LLAMA_RUNNER_BACKEND_MAX_DEVICES; index++) {
                result.devices[index].kind = native.devices[index].kind;
                result.devices[index].device_type = native.devices[index].device_type;
                result.devices[index].free_bytes = native.devices[index].free_bytes;
                result.devices[index].total_bytes = native.devices[index].total_bytes;
                result.devices[index].device_identity_length =
                    native.devices[index].device_identity_length;
                for (int word = 0; word < 8; ++word) {
                    result.devices[index].device_identity_words[word] =
                        native.devices[index].device_identity_words[word];
                }
            }
            return result;
        });
}

struct LlamaCalibrationResultFFI llama_runner_calibrate_backend(
    int64_t probe_token,
    int backend,
    int duration_millis,
    int64_t buffer_bytes) {
    return ffi_guard<LlamaCalibrationResultFFI>(
        "calibrate_backend",
        LlamaCalibrationResultFFI{LLAMA_CALIBRATION_UNAVAILABLE, backend, 0, {}},
        [probe_token, backend, duration_millis, buffer_bytes]() {
            const LlamaCalibrationResultNative native = llama_runner_core_calibrate_backend(
                probe_token,
                backend,
                duration_millis,
                buffer_bytes);
            LlamaCalibrationResultFFI result{};
            result.status = native.status;
            result.backend = native.backend;
            result.window_count = native.window_count;
            for (int index = 0;
                 index < native.window_count && index < LLAMA_RUNNER_CALIBRATION_MAX_WINDOWS;
                 ++index) {
                result.windows[index].metric = native.windows[index].metric;
                result.windows[index].completed_units = native.windows[index].completed_units;
                result.windows[index].elapsed_nanoseconds =
                    native.windows[index].elapsed_nanoseconds;
            }
            return result;
        });
}

void llama_runner_cancel_backend_calibration(int64_t probe_token) {
    ffi_guard_void("cancel_backend_calibration", [probe_token]() {
        llama_runner_core_cancel_calibration(probe_token);
    });
}

struct LlamaModelFeatureSupportFFI llama_runner_probe_model_features(
    const char *architecture,
    const char *quantization) {
    return ffi_guard<LlamaModelFeatureSupportFFI>(
        "probe_model_features",
        LlamaModelFeatureSupportFFI{LLAMA_FEATURE_UNKNOWN, LLAMA_FEATURE_UNKNOWN, 0},
        [architecture, quantization]() {
            const LlamaModelFeatureSupportNative native =
                llama_runner_core_probe_model_features(architecture, quantization);
            return LlamaModelFeatureSupportFFI{
                native.architecture,
                native.quantization,
                native.engine_build,
            };
        });
}

int llama_runner_load_model(
    const char *model_path,
    int n_ctx,
    int n_threads,
    int n_batch,
    int n_gpu_layers,
    float temperature) {
    return ffi_guard<int>("load_model", 0, [=]() {
        LlamaRunnerConfig config;
        config.n_ctx = n_ctx;
        config.n_threads = n_threads;
        config.n_batch = n_batch;
        config.n_gpu_layers = n_gpu_layers;
        config.temperature = temperature;
        return llama_runner_core_load_model(model_path, config) ? 1 : 0;
    });
}

char *llama_runner_generate_text(const char *prompt, int max_tokens, float temperature) {
    return ffi_guard<char *>("generate_text", nullptr, [=]() {
        const std::string result = llama_runner_core_generate(prompt, max_tokens, temperature);
        return strdup(result.c_str());
    });
}

int llama_runner_start_generate(const char *prompt, int max_tokens, float temperature, const char *grammar) {
    return ffi_guard<int>("start_generate", 0, [=]() {
        return llama_runner_core_start_generate(prompt, max_tokens, temperature, grammar) ? 1 : 0;
    });
}

char *llama_runner_next_token(void) {
    return ffi_guard<char *>("next_token", nullptr, []() {
        const char *tok = llama_runner_core_next_token();
        return tok ? strdup(tok) : nullptr;
    });
}

void llama_runner_cancel_generate(void) {
    ffi_guard_void("cancel_generate", []() { llama_runner_core_cancel_generate(); });
}

void llama_runner_finalize_generation(void) {
    ffi_guard_void("finalize_generation", []() { llama_runner_core_finalize_generation(); });
}

int llama_runner_process_system_prompt(const char *prompt) {
    return ffi_guard<int>("process_system_prompt", -1, [prompt]() {
        return llama_runner_core_process_system_prompt(prompt);
    });
}

int llama_runner_process_user_prompt(const char *prompt, int predict_length) {
    return ffi_guard<int>("process_user_prompt", -1, [=]() {
        return llama_runner_core_process_user_prompt(prompt, predict_length);
    });
}

void llama_runner_unload_model(void) {
    ffi_guard_void("unload_model", []() { llama_runner_core_unload(); });
}

void llama_runner_shutdown(void) {
    ffi_guard_void("shutdown", []() { llama_runner_core_shutdown(); });
}

int llama_runner_get_context_used(void) {
    return ffi_guard<int>("get_context_used", 0, []() {
        return llama_runner_core_get_context_used();
    });
}

int llama_runner_get_context_limit(void) {
    return ffi_guard<int>("get_context_limit", 0, []() {
        return llama_runner_core_get_context_limit();
    });
}

int llama_runner_get_stop_reason(void) {
    return ffi_guard<int>("get_stop_reason", 5, []() {
        return llama_runner_core_get_stop_reason();
    });
}

int llama_runner_get_gpu_layers(void) {
    return ffi_guard<int>("get_gpu_layers", 0, []() {
        return llama_runner_core_get_gpu_layers();
    });
}

void llama_runner_clear_context(void) {
    ffi_guard_void("clear_context", []() { llama_runner_core_clear_context(); });
}

const char* llama_runner_get_model_architecture(void) {
    return ffi_guard<const char *>("get_model_architecture", nullptr, []() {
        return llama_runner_core_get_model_architecture();
    });
}

char *llama_runner_get_reasoning(void) {
    return ffi_guard<char *>("get_reasoning", nullptr, []() {
        return strdup(llama_runner_core_get_reasoning());
    });
}

char *llama_runner_get_content(void) {
    return ffi_guard<char *>("get_content", nullptr, []() {
        return strdup(llama_runner_core_get_content());
    });
}

int llama_runner_supports_thinking(void) {
    return ffi_guard<int>("supports_thinking", 0, []() {
        return llama_runner_core_supports_thinking();
    });
}

char *llama_runner_get_reasoning_delta(void) {
    return ffi_guard<char *>("get_reasoning_delta", nullptr, []() {
        return strdup(llama_runner_core_get_reasoning_delta());
    });
}

char *llama_runner_get_content_delta(void) {
    return ffi_guard<char *>("get_content_delta", nullptr, []() {
        return strdup(llama_runner_core_get_content_delta());
    });
}

}
