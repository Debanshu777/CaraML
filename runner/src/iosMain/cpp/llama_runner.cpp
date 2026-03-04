#include "llama_runner_core.h"

#include <cstring>
#include <iostream>
#include <string>

namespace {

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

} // namespace

extern "C" {

void llama_runner_init(void) {
    llama_runner_core_set_logger(ios_log);
    llama_runner_core_init(nullptr);
}

int llama_runner_load_model(
    const char *model_path,
    int n_ctx,
    int n_threads,
    int n_batch,
    int n_gpu_layers,
    float temperature) {
    LlamaRunnerConfig config;
    config.n_ctx = n_ctx;
    config.n_threads = n_threads;
    config.n_batch = n_batch;
    config.n_gpu_layers = n_gpu_layers;
    config.temperature = temperature;
    return llama_runner_core_load_model(model_path, config) ? 1 : 0;
}

char *llama_runner_generate_text(const char *prompt, int max_tokens, float temperature) {
    const std::string result = llama_runner_core_generate(prompt, max_tokens, temperature);
    return strdup(result.c_str());
}

int llama_runner_start_generate(const char *prompt, int max_tokens, float temperature) {
    return llama_runner_core_start_generate(prompt, max_tokens, temperature) ? 1 : 0;
}

char *llama_runner_next_token(void) {
    const char *tok = llama_runner_core_next_token();
    if (tok == nullptr) {
        return nullptr;
    }
    return strdup(tok);
}

void llama_runner_cancel_generate(void) {
    llama_runner_core_cancel_generate();
}

void llama_runner_finalize_generation(void) {
    llama_runner_core_finalize_generation();
}

int llama_runner_process_system_prompt(const char *prompt) {
    return llama_runner_core_process_system_prompt(prompt);
}

int llama_runner_process_user_prompt(const char *prompt, int predict_length) {
    return llama_runner_core_process_user_prompt(prompt, predict_length);
}

void llama_runner_unload_model(void) { llama_runner_core_unload(); }

void llama_runner_shutdown(void) { llama_runner_core_shutdown(); }

}
