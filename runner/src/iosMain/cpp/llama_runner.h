#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define LLAMA_RUNNER_PREFLIGHT_MAX_POOLS 17
#define LLAMA_RUNNER_BACKEND_MAX_DEVICES 16
#define LLAMA_RUNNER_CALIBRATION_MAX_WINDOWS 16

struct LlamaRunnerConfigFFI {
    int n_ctx;
    int n_ctx_min;
    int n_threads;
    int n_threads_batch;
    int n_batch;
    int n_ubatch;
    int n_outputs_max_per_seq;
    int flash_attn;
    int offload_kqv;
    int type_k;
    int type_v;
    int n_gpu_layers;
    int use_mmap;
    int use_mlock;
    int lazy_mode;
    float temperature;
    int auto_fit;
    const char *cpu_mask;
    const char *cpu_mask_batch;
};

struct LlamaPreflightMemoryPoolFFI {
    int kind;
    int ordinal;
    int64_t model_bytes;
    int64_t context_bytes;
    int64_t compute_bytes;
    int64_t free_bytes;
    int64_t total_bytes;
};

struct LlamaPreflightResultFFI {
    int status;
    int n_ctx;
    int n_gpu_layers;
    int pool_count;
    struct LlamaPreflightMemoryPoolFFI pools[LLAMA_RUNNER_PREFLIGHT_MAX_POOLS];
};

struct LlamaBackendCapabilityFFI {
    int kind;
    int device_type;
    int64_t free_bytes;
    int64_t total_bytes;
    int device_identity_length;
    int64_t device_identity_words[8];
};

struct LlamaBackendCapabilitiesFFI {
    int count;
    struct LlamaBackendCapabilityFFI devices[LLAMA_RUNNER_BACKEND_MAX_DEVICES];
};

struct LlamaCalibrationWindowFFI {
    int metric;
    int64_t completed_units;
    int64_t elapsed_nanoseconds;
};

struct LlamaCalibrationResultFFI {
    int status;
    int backend;
    int window_count;
    struct LlamaCalibrationWindowFFI windows[LLAMA_RUNNER_CALIBRATION_MAX_WINDOWS];
};

struct LlamaModelFeatureSupportFFI {
    int architecture;
    int quantization;
    int engine_build;
};

void llama_runner_init(void);
const char *llama_runner_engine_version(void);
int llama_runner_load_model_v2(const char *model_path, struct LlamaRunnerConfigFFI config);
struct LlamaPreflightResultFFI llama_runner_preflight_model(
    const char *model_path,
    struct LlamaRunnerConfigFFI config);
struct LlamaBackendCapabilitiesFFI llama_runner_backend_capabilities(void);
struct LlamaCalibrationResultFFI llama_runner_calibrate_backend(
    int64_t probe_token,
    int backend,
    int duration_millis,
    int64_t buffer_bytes);
int llama_runner_reserve_backend_calibration(int64_t probe_token);
void llama_runner_cancel_backend_calibration(int64_t probe_token);
int llama_runner_abandon_backend_calibration(int64_t probe_token);
struct LlamaModelFeatureSupportFFI llama_runner_probe_model_features(
    const char *architecture,
    const char *quantization);
int llama_runner_load_model(
    const char *model_path,
    int n_ctx,
    int n_threads,
    int n_batch,
    int n_gpu_layers,
    float temperature);
char *llama_runner_generate_text(const char *prompt, int max_tokens, float temperature);
int llama_runner_start_generate(const char *prompt, int max_tokens, float temperature, const char *grammar);
char *llama_runner_next_token(void);
void llama_runner_cancel_generate(void);
void llama_runner_finalize_generation(void);
int llama_runner_process_system_prompt(const char *prompt);
int llama_runner_process_user_prompt(const char *prompt, int predict_length);
void llama_runner_unload_model(void);
void llama_runner_shutdown(void);
int llama_runner_get_context_used(void);
int llama_runner_get_context_limit(void);
int llama_runner_get_stop_reason(void);
int llama_runner_get_gpu_layers(void);
void llama_runner_clear_context(void);
const char *llama_runner_get_model_architecture(void);
char *llama_runner_get_reasoning(void);
char *llama_runner_get_content(void);
int llama_runner_supports_thinking(void);
char *llama_runner_get_reasoning_delta(void);
char *llama_runner_get_content_delta(void);

#ifdef __cplusplus
}
#endif
