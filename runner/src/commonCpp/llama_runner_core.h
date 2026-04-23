#pragma once

#include <functional>
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

    // Sampler
    float temperature  = 0.3f;

    // Fitting control
    bool auto_fit      = true;    // use llama_params_fit() before loading
};

void llama_runner_core_set_logger(LlamaLogFn fn);
void llama_runner_core_init(const char *backend_path);
bool llama_runner_core_load_model(const char *model_path, const LlamaRunnerConfig &config);
std::string llama_runner_core_generate(const char *prompt, int max_tokens, float temperature);
void llama_runner_core_unload();
void llama_runner_core_shutdown();

bool llama_runner_core_start_generate(const char *prompt, int max_tokens, float temperature, const char *grammar);
const char *llama_runner_core_next_token();
void llama_runner_core_cancel_generate();
void llama_runner_core_finalize_generation();

int llama_runner_core_process_system_prompt(const char *system_prompt);
int llama_runner_core_process_user_prompt(const char *user_prompt, int predict_length, const char *grammar);

int llama_runner_core_get_context_used();
int llama_runner_core_get_context_limit();

int llama_runner_core_get_stop_reason();
void llama_runner_core_clear_context();
