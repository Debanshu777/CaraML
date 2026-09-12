#pragma once

#include <functional>
#include <cstdint>
#include <string>

enum LlamaLogLevel {
    LLAMA_LOG_INFO = 0,
    LLAMA_LOG_WARN = 1,
    LLAMA_LOG_ERROR = 2,
};

enum LlamaStopReason {
    STOP_NONE = 0,
    STOP_EOG = 1,
    STOP_MAX_TOKENS = 2,
    STOP_CONTEXT_FULL = 3,
    STOP_CANCELLED = 4,
    STOP_ERROR = 5,
};

constexpr int LLAMA_PREFLIGHT_MAX_POOLS = 17;
constexpr int LLAMA_BACKEND_MAX_DEVICES = 16;

enum LlamaPreflightStatus {
    LLAMA_PREFLIGHT_FIT = 0,
    LLAMA_PREFLIGHT_NO_FIT = 1,
    LLAMA_PREFLIGHT_INVALID = 2,
    LLAMA_PREFLIGHT_UNAVAILABLE = 3,
};

enum LlamaPreflightPoolKind {
    LLAMA_POOL_HOST = 0,
    LLAMA_POOL_DISCRETE_GPU = 1,
    LLAMA_POOL_INTEGRATED_GPU = 2,
    LLAMA_POOL_ACCELERATOR = 3,
    LLAMA_POOL_META = 4,
    LLAMA_POOL_OTHER = 5,
};

struct LlamaPreflightMemoryPool {
    int kind = LLAMA_POOL_OTHER;
    int ordinal = 0;
    int64_t model_bytes = 0;
    int64_t context_bytes = 0;
    int64_t compute_bytes = 0;
    int64_t free_bytes = 0;
    int64_t total_bytes = 0;
};

struct LlamaPreflightResultNative {
    int status = LLAMA_PREFLIGHT_UNAVAILABLE;
    int n_ctx = 0;
    int n_gpu_layers = 0;
    int pool_count = 0;
    LlamaPreflightMemoryPool pools[LLAMA_PREFLIGHT_MAX_POOLS]{};
};

enum LlamaBackendKindNative {
    LLAMA_BACKEND_CPU = 0,
    LLAMA_BACKEND_CUDA = 1,
    LLAMA_BACKEND_METAL = 2,
    LLAMA_BACKEND_VULKAN = 3,
    LLAMA_BACKEND_OPENCL = 4,
    LLAMA_BACKEND_SYCL = 5,
    LLAMA_BACKEND_OTHER = 6,
};

enum LlamaBackendDeviceTypeNative {
    LLAMA_BACKEND_DEVICE_CPU = 0,
    LLAMA_BACKEND_DEVICE_DISCRETE_GPU = 1,
    LLAMA_BACKEND_DEVICE_INTEGRATED_GPU = 2,
    LLAMA_BACKEND_DEVICE_ACCELERATOR = 3,
    LLAMA_BACKEND_DEVICE_META = 4,
};

struct LlamaBackendCapabilityNative {
    int kind = LLAMA_BACKEND_OTHER;
    int device_type = LLAMA_BACKEND_DEVICE_CPU;
    int64_t free_bytes = -1;
    int64_t total_bytes = -1;
    int device_identity_length = 0;
    int64_t device_identity_words[8]{};
};

struct LlamaBackendCapabilitiesNative {
    int count = -1;
    LlamaBackendCapabilityNative devices[LLAMA_BACKEND_MAX_DEVICES]{};
};

enum LlamaFeatureStateNative {
    LLAMA_FEATURE_SUPPORTED = 0,
    LLAMA_FEATURE_UNSUPPORTED = 1,
    LLAMA_FEATURE_UNKNOWN = 2,
};

struct LlamaModelFeatureSupportNative {
    int architecture = LLAMA_FEATURE_UNKNOWN;
    int quantization = LLAMA_FEATURE_UNKNOWN;
    int engine_build = 0;
};

using LlamaLogFn = std::function<void(LlamaLogLevel level, const char *msg)>;

struct LlamaRunnerConfig {
    // Context params
    int n_ctx          = 0;       // 0 = auto-fit by llama_params_fit()
    int n_ctx_min      = 512;     // floor for auto-fit
    int n_threads      = 4;       // generation threads (= perf core count)
    int n_threads_batch = 0;      // prompt processing threads (0 = same as n_threads)
    int n_batch        = 512;
    int n_ubatch       = 512;
    int flash_attn     = -1;      // -1=auto, 0=off, 1=on (maps to llama_flash_attn_type)
    bool offload_kqv   = true;
    int type_k         = 1;       // ggml_type for KV cache keys (1=F16, 8=Q8_0)
    int type_v         = 1;       // ggml_type for KV cache values

    // Model params
    int n_gpu_layers   = -1;      // -1 = auto-fit (all layers), 0 = CPU only
    bool use_mmap      = true;
    bool use_mlock     = false;

    // Sampler
    float temperature  = 0.3f;

    // Fitting control
    bool auto_fit      = true;    // use llama_params_fit() before loading

    // CPU pinning
    std::string cpu_mask       = "";  // e.g. "4-7" or "4,5,6,7" or "" (no pinning)
    std::string cpu_mask_batch = "";  // "" = use cpu_mask for batch too
};

void llama_runner_core_set_logger(LlamaLogFn fn);
void llama_runner_core_init(const char *backend_path);
bool llama_runner_core_load_model(const char *model_path, const LlamaRunnerConfig &config);
LlamaPreflightResultNative llama_runner_core_preflight(
    const char *model_path,
    const LlamaRunnerConfig &config);
LlamaBackendCapabilitiesNative llama_runner_core_backend_capabilities();
LlamaModelFeatureSupportNative llama_runner_core_probe_model_features(
    const char *architecture,
    const char *quantization);
std::string llama_runner_core_generate(const char *prompt, int max_tokens, float temperature);
void llama_runner_core_unload();
void llama_runner_core_shutdown();

bool llama_runner_core_start_generate(const char *prompt, int max_tokens, float temperature, const char *grammar);
const char *llama_runner_core_next_token();
void llama_runner_core_cancel_generate();
void llama_runner_core_finalize_generation();

int llama_runner_core_process_system_prompt(const char *system_prompt);
int llama_runner_core_process_user_prompt(const char *user_prompt, int predict_length);

int llama_runner_core_get_context_used();
int llama_runner_core_get_context_limit();

int llama_runner_core_get_stop_reason();
int llama_runner_core_get_gpu_layers();
void llama_runner_core_clear_context();
const char* llama_runner_core_get_model_architecture();
const char *llama_runner_core_get_reasoning();
const char *llama_runner_core_get_content();
const char *llama_runner_core_get_reasoning_delta();
const char *llama_runner_core_get_content_delta();
int llama_runner_core_supports_thinking();
