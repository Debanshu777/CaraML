#pragma once

#include <functional>
#include <string>

enum LlamaLogLevel {
    LLAMA_LOG_INFO = 0,
    LLAMA_LOG_WARN = 1,
    LLAMA_LOG_ERROR = 2,
};

using LlamaLogFn = std::function<void(LlamaLogLevel level, const char *msg)>;

struct LlamaRunnerConfig {
    int n_ctx = 2048;
    int n_threads = 4;
    int n_batch = 512;
    int n_gpu_layers = 0;
    float temperature = 0.3f;
};

void llama_runner_core_set_logger(LlamaLogFn fn);
void llama_runner_core_init(const char *backend_path);
bool llama_runner_core_load_model(const char *model_path, const LlamaRunnerConfig &config);
std::string llama_runner_core_generate(const char *prompt, int max_tokens, float temperature);
void llama_runner_core_unload();
void llama_runner_core_shutdown();

bool llama_runner_core_start_generate(const char *prompt, int max_tokens, float temperature);
const char *llama_runner_core_next_token();
void llama_runner_core_cancel_generate();
void llama_runner_core_finalize_generation();

int llama_runner_core_process_system_prompt(const char *system_prompt);
int llama_runner_core_process_user_prompt(const char *user_prompt, int predict_length);
