#include "llama_runner_core.h"

#include <atomic>
#include <chrono>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <exception>
#include <optional>
#include <string>
#include <vector>

#include "chat.h"
#include "common.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "llama.h"
#include "sampling.h"

namespace {

constexpr const char *ROLE_SYSTEM = "system";
constexpr const char *ROLE_USER = "user";
constexpr const char *ROLE_ASSISTANT = "assistant";

llama_model *g_model = nullptr;
llama_context *g_context = nullptr;
llama_batch g_batch = llama_batch_init(0, 0, 0);
common_sampler *g_sampler = nullptr;
LlamaRunnerConfig g_config;
LlamaLogFn g_logger = nullptr;
float g_active_temperature = -1.0f;
std::string g_active_grammar;
int g_actual_gpu_layers = 0;

std::atomic<bool> g_cancel_flag{false};
int g_max_tokens_remaining = 0;
std::vector<llama_token> g_streaming_tokens;
size_t g_streaming_n_generated = 0;
llama_pos g_current_position = 0;
llama_pos g_system_prompt_position = 0;
std::string g_cached_utf8_chars;
std::string g_current_token;
int g_stop_reason = STOP_NONE;

static ggml_threadpool * g_tp_gen   = nullptr;
static ggml_threadpool * g_tp_batch = nullptr;

// Function pointers resolved at runtime via the backend registry.
// When GGML_BACKEND_DL=ON (Android phase-07), ggml_threadpool_new/free live
// inside the CPU MODULE library which cannot be linked at build time.
typedef struct ggml_threadpool * (*fn_tp_new)(struct ggml_threadpool_params *);
typedef void                     (*fn_tp_free)(struct ggml_threadpool *);
static fn_tp_new  g_tp_new_fn  = nullptr;
static fn_tp_free g_tp_free_fn = nullptr;


// Tracks all tokens decoded into KV cache. Used to compute common prefix
// during full re-render fallback, avoiding re-decode of already-cached tokens.
std::vector<llama_token> g_kv_token_history;

common_chat_templates_ptr g_chat_templates;
std::vector<common_chat_msg> g_chat_msgs;
static std::string g_assistant_buffer;
// True when a system prompt was added to g_chat_msgs but its KV decode was
// deferred because the chat template refused to render `[system]` alone (e.g.
// Qwen3-style templates that demand a user query). The deferred system content
// is decoded together with the first user prompt.
static bool g_pending_chat_decode = false;

static void log_line(LlamaLogLevel level, const char *fmt, ...);

// Lazily resolve ggml_threadpool_new/free via the backend registry.
// When GGML_BACKEND_DL=ON the CPU backend is a MODULE (dlopen-only),
// so its symbols can't be linked at build time — look them up at runtime.
static void resolve_threadpool_fns() {
    if (g_tp_new_fn && g_tp_free_fn) return;
    ggml_backend_reg_t cpu_reg = ggml_backend_reg_by_name("CPU");
    if (!cpu_reg) {
        log_line(LLAMA_LOG_WARN, "threadpool: CPU backend not found, pinning disabled");
        return;
    }
    g_tp_new_fn  = (fn_tp_new)  ggml_backend_reg_get_proc_address(cpu_reg, "ggml_threadpool_new");
    g_tp_free_fn = (fn_tp_free) ggml_backend_reg_get_proc_address(cpu_reg, "ggml_threadpool_free");
    if (!g_tp_new_fn || !g_tp_free_fn) {
        log_line(LLAMA_LOG_WARN, "threadpool: proc address lookup failed, pinning disabled");
        g_tp_new_fn  = nullptr;
        g_tp_free_fn = nullptr;
    }
}

// Render the full conversation in `messages` (and optionally append a generation
// prompt). Returns std::nullopt if the template raises an exception (some
// templates require certain message roles to be present). BOS handling is left
// to the tokenizer — the templates struct is opaque so we can't read its
// add_bos/add_eos flags here.
static std::optional<std::string> try_apply_full_template(
    const std::vector<common_chat_msg> &messages, bool add_generation_prompt) {
    if (!g_chat_templates || !g_chat_templates.get()) {
        return std::nullopt;
    }
    try {
        common_chat_templates_inputs inputs;
        inputs.use_jinja = true;
        inputs.messages = messages;
        inputs.add_generation_prompt = add_generation_prompt;
        return common_chat_templates_apply(g_chat_templates.get(), inputs).prompt;
    } catch (const std::exception &e) {
        log_line(LLAMA_LOG_WARN, "chat template apply failed: %s", e.what());
        return std::nullopt;
    } catch (...) {
        log_line(LLAMA_LOG_WARN, "chat template apply failed: unknown exception");
        return std::nullopt;
    }
}

// Format `new_msg` as the incremental diff against the existing chat history,
// catching template exceptions instead of letting them propagate. Returns
// std::nullopt on failure.
static std::optional<std::string> try_chat_format_single(
    const std::string &role, const std::string &content) {
    if (!g_chat_templates || !g_chat_templates.get()) {
        return content;
    }
    common_chat_msg new_msg;
    new_msg.role = role;
    new_msg.content = content;
    try {
        return common_chat_format_single(
            g_chat_templates.get(), g_chat_msgs, new_msg, role == ROLE_USER, true);
    } catch (const std::exception &e) {
        log_line(LLAMA_LOG_WARN,
            "chat template format_single failed (role=%s): %s",
            role.c_str(), e.what());
        return std::nullopt;
    } catch (...) {
        log_line(LLAMA_LOG_WARN,
            "chat template format_single failed (role=%s): unknown exception",
            role.c_str());
        return std::nullopt;
    }
}

static bool is_valid_utf8(const char *string) {
    if (!string) {
        return true;
    }
    const auto *bytes = reinterpret_cast<const unsigned char *>(string);
    int num;
    while (*bytes != 0x00) {
        if ((*bytes & 0x80) == 0x00) {
            num = 1;
        } else if ((*bytes & 0xE0) == 0xC0) {
            num = 2;
        } else if ((*bytes & 0xF0) == 0xE0) {
            num = 3;
        } else if ((*bytes & 0xF8) == 0xF0) {
            num = 4;
        } else {
            return false;
        }
        bytes += 1;
        for (int i = 1; i < num; ++i) {
            if ((*bytes & 0xC0) != 0x80) {
                return false;
            }
            bytes += 1;
        }
    }
    return true;
}

std::string truncate_for_log(const std::string &s, size_t max_len = 80) {
    if (s.size() <= max_len) {
        return s;
    }
    return s.substr(0, max_len) + "...";
}

void log_line(LlamaLogLevel level, const char *fmt, ...) {
    char buffer[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buffer, sizeof(buffer), fmt, args);
    va_end(args);

    if (g_logger) {
        g_logger(level, buffer);
    }
}

float resolve_temperature(float temperature) {
    if (temperature >= 0.0f) {
        return temperature;
    }
    return g_config.temperature;
}

void recreate_sampler(float temperature, const std::string &grammar) {
    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    common_params_sampling sparams{};
    sparams.temp = resolve_temperature(temperature);
    sparams.penalty_last_n = 64;
    sparams.penalty_repeat = 1.1f;
    sparams.penalty_freq = 0.0f;
    sparams.penalty_present = 0.0f;
    if (!grammar.empty()) {
        sparams.grammar = common_grammar(COMMON_GRAMMAR_TYPE_USER, grammar);
    }
    // empty grammar string = leave default-constructed (COMMON_GRAMMAR_TYPE_NONE)
    g_sampler = common_sampler_init(g_model, sparams);
    g_active_temperature = sparams.temp;
    g_active_grammar = grammar;

    log_line(LLAMA_LOG_INFO,
        "recreate_sampler: temp=%.3f grammar_len=%zu grammar_preview=\"%s\"",
        sparams.temp, grammar.size(), truncate_for_log(grammar).c_str());
}

void reset_sampler_state() {
    if (!g_sampler) {
        return;
    }
    common_sampler_reset(g_sampler);
    log_line(LLAMA_LOG_INFO,
        "reset_sampler_state: cleared sampler state (temp=%.3f, grammar_len=%zu)",
        g_active_temperature, g_active_grammar.size());
}

// Apply the per-turn sampler decision. Grammar-active turns always recreate
// because common_sampler_reset only clears the repetition/frequency penalty
// chain — it does NOT reset the grammar parser (a separate `grmr` sampler
// within the chain). Without recreation, a grammar that reached its terminal
// state on turn N would only allow EOG on turn N+1.
//
// Performance note: recreation cost is dominated by GBNF parsing in
// common_sampler_init. For the typical ~166-char structured output grammar
// this is <1ms — negligible vs the ~120ms/token decode time on mobile.
// A future upstream API (grammar-only reset) could eliminate this entirely.
bool apply_sampler_for_turn(float temperature, const char *grammar) {
    const float target_temp = resolve_temperature(temperature);
    const std::string g_in = grammar ? std::string(grammar) : std::string();
    const bool grammar_changed = (g_in != g_active_grammar);
    const bool temp_changed    = (target_temp != g_active_temperature);
    const bool grammar_active  = !g_in.empty();
    if (grammar_changed || temp_changed || grammar_active) {
        recreate_sampler(target_temp, g_in);
    } else {
        reset_sampler_state();
    }
    return g_sampler != nullptr;
}

void finalize_assistant_turn() {
    if (g_chat_templates && !g_assistant_buffer.empty()) {
        // Format for side-effect logging only; we always record into history,
        // even if the template can't render an isolated diff.
        try_chat_format_single(ROLE_ASSISTANT, g_assistant_buffer);
        common_chat_msg asst_msg;
        asst_msg.role = ROLE_ASSISTANT;
        asst_msg.content = g_assistant_buffer;
        g_chat_msgs.push_back(asst_msg);
        g_assistant_buffer.clear();
    }
}

int decode_tokens_in_batches(
    llama_context *context,
    llama_batch &batch,
    const std::vector<llama_token> &tokens,
    llama_pos start_pos,
    bool compute_last_logit) {
    const int batch_size = g_config.n_batch;
    for (int i = 0; i < static_cast<int>(tokens.size()); i += batch_size) {
        const int cur_batch_size =
            std::min(static_cast<int>(tokens.size()) - i, batch_size);
        common_batch_clear(batch);
        for (int j = 0; j < cur_batch_size; j++) {
            const llama_token token_id = tokens[i + j];
            const llama_pos position = start_pos + i + j;
            const bool want_logit =
                compute_last_logit && (i + j == static_cast<int>(tokens.size()) - 1);
            common_batch_add(batch, token_id, position, {0}, want_logit);
        }
        const int ret = llama_decode(context, batch);
        if (ret != 0) {
            log_line(LLAMA_LOG_ERROR,
                "decode_tokens_in_batches: failed at offset %d ret=%d", i, ret);
            return ret;
        }
    }
    return 0;
}

} // namespace

void llama_runner_core_set_logger(LlamaLogFn fn) {
    g_logger = fn;
}

void llama_runner_core_init(const char *backend_path) {
    if (backend_path && std::strlen(backend_path) > 0) {
        log_line(LLAMA_LOG_INFO, "init: Loading backends from %s", backend_path);
        ggml_backend_load_all_from_path(backend_path);
    } else {
        log_line(LLAMA_LOG_INFO, "init: No backend path provided, skipping backend path load");
    }
    llama_backend_init();
    log_line(LLAMA_LOG_INFO, "init: Backend initialized");
}

bool llama_runner_core_load_model(const char *model_path, const LlamaRunnerConfig &config) {
    log_line(LLAMA_LOG_INFO, "load: path=%s", model_path ? model_path : "(null)");
    if (!model_path || std::strlen(model_path) == 0) {
        log_line(LLAMA_LOG_ERROR, "load: model_path is null or empty");
        return false;
    }

    llama_runner_core_unload();
    g_config = config;

    llama_log_set(
        [](ggml_log_level level, const char *text, void * /*user_data*/) {
            const LlamaLogLevel mapped_level =
                (level == GGML_LOG_LEVEL_CONT)
                    ? LLAMA_LOG_INFO
                    : (level >= GGML_LOG_LEVEL_ERROR)
                    ? LLAMA_LOG_ERROR
                    : (level >= GGML_LOG_LEVEL_WARN) ? LLAMA_LOG_WARN : LLAMA_LOG_INFO;
            if (g_logger) {
                g_logger(mapped_level, text ? text : "");
            }
        },
        nullptr);

    auto t0 = std::chrono::steady_clock::now();

    llama_model_params model_params = llama_model_default_params();
    llama_context_params ctx_params = llama_context_default_params();

    // Set params that are OURS to decide (not auto-fitted)
    ctx_params.n_threads       = g_config.n_threads;
    ctx_params.n_threads_batch = g_config.n_threads_batch > 0
                                   ? g_config.n_threads_batch : g_config.n_threads;
    ctx_params.n_batch         = g_config.n_batch;
    ctx_params.n_ubatch        = g_config.n_ubatch;
    ctx_params.flash_attn_type = static_cast<llama_flash_attn_type>(g_config.flash_attn);
    ctx_params.offload_kqv     = g_config.offload_kqv;
    ctx_params.type_k          = static_cast<ggml_type>(g_config.type_k);
    ctx_params.type_v          = static_cast<ggml_type>(g_config.type_v);
    model_params.use_mmap      = g_config.use_mmap;
    model_params.use_mlock     = g_config.use_mlock;

    if (g_config.auto_fit) {
        log_line(LLAMA_LOG_INFO, "load: Using llama_params_fit() for automatic memory optimization");
        
        // If user explicitly set n_gpu_layers to 0, force CPU-only
        if (g_config.n_gpu_layers == 0) {
            model_params.n_gpu_layers = 0;
            log_line(LLAMA_LOG_INFO, "load: Forcing CPU-only (n_gpu_layers=0)");
        }
        
        // Leave n_gpu_layers at default (-1) so params_fit auto-determines it
        // Set n_ctx = 0 so params_fit finds the largest context that fits
        ctx_params.n_ctx = 0;

        std::vector<float> tensor_split(llama_max_devices(), 0.0f);
        std::vector<llama_model_tensor_buft_override> buft_overrides(
            llama_max_tensor_buft_overrides() + 1);
        buft_overrides.back() = {nullptr, nullptr};
        std::vector<size_t> margins(llama_max_devices(), 0);

        auto status = llama_params_fit(
            model_path, &model_params, &ctx_params,
            tensor_split.data(), buft_overrides.data(), margins.data(),
            g_config.n_ctx_min, GGML_LOG_LEVEL_INFO);

        if (status == LLAMA_PARAMS_FIT_STATUS_SUCCESS) {
            model_params.tensor_split = tensor_split.data();
            log_line(LLAMA_LOG_INFO, "load: params_fit succeeded - n_gpu_layers=%d, n_ctx=%u",
                model_params.n_gpu_layers, ctx_params.n_ctx);
            
            // Safety net: if params_fit left n_ctx at 0, apply a sensible default
            if (ctx_params.n_ctx == 0) {
                ctx_params.n_ctx = g_config.n_ctx > 0 ? g_config.n_ctx : 4096;
                log_line(LLAMA_LOG_INFO, "load: n_ctx was 0 after params_fit, defaulting to %u", ctx_params.n_ctx);
            }
            
            // Cap n_ctx to user's preference if they specified one
            if (g_config.n_ctx > 0 && static_cast<uint32_t>(g_config.n_ctx) < ctx_params.n_ctx) {
                ctx_params.n_ctx = g_config.n_ctx;
                log_line(LLAMA_LOG_INFO, "load: Capping n_ctx to user preference: %u", ctx_params.n_ctx);
            }

            // If Kotlin assumed GPU would be active but params_fit resolved to
            // CPU-only, the configured thread count is too low. Bump generation
            // threads to at least the batch thread count so CPU inference isn't
            // starved of parallelism.
            if (model_params.n_gpu_layers == 0 && g_config.n_gpu_layers != 0) {
                const int adjusted = std::max(ctx_params.n_threads, ctx_params.n_threads_batch);
                log_line(LLAMA_LOG_WARN,
                    "load: GPU offload requested but params_fit resolved to 0 layers; "
                    "raising n_threads %d -> %d",
                    ctx_params.n_threads, adjusted);
                ctx_params.n_threads = adjusted;
            }
        } else {
            log_line(LLAMA_LOG_WARN, "load: params_fit failed, falling back to CPU-only mode");
            // Fallback: force CPU-only, minimal context
            model_params.n_gpu_layers = 0;
            ctx_params.n_ctx = g_config.n_ctx > 0 ? g_config.n_ctx : g_config.n_ctx_min;

            // Same thread adjustment — caller assumed GPU offload
            if (g_config.n_gpu_layers != 0) {
                const int adjusted = std::max(ctx_params.n_threads, ctx_params.n_threads_batch);
                log_line(LLAMA_LOG_WARN,
                    "load: raising n_threads %d -> %d for CPU-only fallback",
                    ctx_params.n_threads, adjusted);
                ctx_params.n_threads = adjusted;
            }
        }
    } else {
        // Manual mode
        log_line(LLAMA_LOG_INFO, "load: Using manual configuration (auto_fit disabled)");
        model_params.n_gpu_layers = g_config.n_gpu_layers;
        ctx_params.n_ctx = g_config.n_ctx > 0 ? g_config.n_ctx : 2048;
    }

    g_actual_gpu_layers = model_params.n_gpu_layers;

    log_line(
        LLAMA_LOG_INFO,
        "load: Final params - n_ctx=%u, n_threads=%d, n_threads_batch=%d, n_batch=%d, n_gpu_layers=%d",
        ctx_params.n_ctx,
        ctx_params.n_threads,
        ctx_params.n_threads_batch,
        ctx_params.n_batch,
        model_params.n_gpu_layers);

    g_model = llama_model_load_from_file(model_path, model_params);

    auto t1 = std::chrono::steady_clock::now();
    const auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    if (!g_model) {
        log_line(LLAMA_LOG_ERROR, "load: Failed (took %lld ms)", static_cast<long long>(load_ms));
        return false;
    }
    log_line(LLAMA_LOG_INFO, "load: Model loaded in %lld ms", static_cast<long long>(load_ms));

    g_context = llama_init_from_model(g_model, ctx_params);
    if (!g_context) {
        log_line(LLAMA_LOG_ERROR, "load: Failed to create context");
        llama_model_free(g_model);
        g_model = nullptr;
        return false;
    }
    log_line(LLAMA_LOG_INFO, "load: Context ready, n_ctx=%u", llama_n_ctx(g_context));

    // CPU pinning: create dedicated threadpools for gen and batch if mask is set
    if (!g_config.cpu_mask.empty()) {
        resolve_threadpool_fns();
        if (g_tp_new_fn) {
            ggml_threadpool_params tpp_gen  = ggml_threadpool_params_default(g_config.n_threads);
            ggml_threadpool_params tpp_batch = ggml_threadpool_params_default(g_config.n_threads_batch > 0 ? g_config.n_threads_batch : g_config.n_threads);
            bool gen_pinned = parse_cpu_mask(g_config.cpu_mask, tpp_gen.cpumask);
            if (gen_pinned) {
                tpp_gen.strict_cpu = true;
                g_tp_gen = g_tp_new_fn(&tpp_gen);
            }
            const std::string& bmask = g_config.cpu_mask_batch.empty() ? g_config.cpu_mask : g_config.cpu_mask_batch;
            bool batch_pinned = parse_cpu_mask(bmask, tpp_batch.cpumask);
            if (batch_pinned) {
                tpp_batch.strict_cpu = true;
                g_tp_batch = g_tp_new_fn(&tpp_batch);
            }
            if (g_tp_gen || g_tp_batch) {
                llama_attach_threadpool(g_context, g_tp_gen, g_tp_batch);
                log_line(LLAMA_LOG_INFO, "llama_runner_core: threadpool attached, mask=%s", g_config.cpu_mask.c_str());
            }
        } else {
            log_line(LLAMA_LOG_WARN, "llama_runner_core: threadpool unavailable (GGML_BACKEND_DL?), CPU pinning skipped");
        }
    }

    g_batch = llama_batch_init(g_config.n_batch, 0, 1);
    recreate_sampler(g_config.temperature, std::string());
    if (!g_sampler) {
        log_line(LLAMA_LOG_ERROR, "load: Failed to initialize sampler");
        llama_runner_core_unload();
        return false;
    }

    g_chat_templates = common_chat_templates_init(g_model, "");
    g_chat_msgs.clear();
    g_pending_chat_decode = false;
    g_system_prompt_position = 0;
    g_current_position = 0;

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    log_line(LLAMA_LOG_INFO, "load: Model ready (vocab_size=%d)", llama_vocab_n_tokens(vocab));
    return true;
}

std::string llama_runner_core_generate(const char *prompt, int max_tokens, float temperature) {
    if (!llama_runner_core_start_generate(prompt, max_tokens, temperature, nullptr)) {
        return "";
    }
    std::string result;
    while (const char *tok = llama_runner_core_next_token()) {
        result.append(tok);
    }
    log_line(LLAMA_LOG_INFO, "generate: done output_len=%zu preview=\"%s\"",
        result.size(), truncate_for_log(result).c_str());
    return result;
}

bool llama_runner_core_start_generate(const char *prompt, int max_tokens, float temperature, const char *grammar) {
    log_line(LLAMA_LOG_INFO, "start_generate: entry max_tokens=%d grammar=%s",
        max_tokens, grammar ? "yes" : "no");

    g_stop_reason = STOP_NONE;

    if (!g_model || !g_context || !g_sampler) {
        log_line(LLAMA_LOG_ERROR, "start_generate: Model not loaded");
        return false;
    }
    if (!prompt || std::strlen(prompt) == 0) {
        log_line(LLAMA_LOG_ERROR, "start_generate: Empty prompt");
        return false;
    }
    if (max_tokens <= 0) {
        log_line(LLAMA_LOG_ERROR, "start_generate: max_tokens must be > 0");
        return false;
    }

    g_cancel_flag = false;
    g_cached_utf8_chars.clear();
    g_streaming_n_generated = 0;
    g_assistant_buffer.clear();

    if (!apply_sampler_for_turn(temperature, grammar)) {
        log_line(LLAMA_LOG_ERROR, "start_generate: Failed to reconfigure sampler");
        return false;
    }

    llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);

    std::string prompt_copy(prompt);
    log_line(LLAMA_LOG_INFO, "start_generate: input prompt_len=%zu preview=\"%s\"",
        prompt_copy.size(), truncate_for_log(prompt_copy).c_str());

    const llama_vocab *vocab = llama_model_get_vocab(g_model);
    g_streaming_tokens = common_tokenize(vocab, prompt_copy, true, true);
    log_line(LLAMA_LOG_INFO, "start_generate: tokenized n_tokens=%zu", g_streaming_tokens.size());
    if (g_streaming_tokens.empty()) {
        log_line(LLAMA_LOG_ERROR, "start_generate: Tokenization produced no tokens");
        return false;
    }

    const uint32_t n_ctx = llama_n_ctx(g_context);
    const int headroom = 4;
    if (static_cast<int>(g_streaming_tokens.size()) >= static_cast<int>(n_ctx) - headroom) {
        log_line(LLAMA_LOG_ERROR,
            "start_generate: prompt too long (%zu tokens) for context (%u). Max is %d.",
            g_streaming_tokens.size(), n_ctx, static_cast<int>(n_ctx) - headroom);
        return false;
    }

    int decode_ret =
        decode_tokens_in_batches(g_context, g_batch, g_streaming_tokens, 0, true);
    if (decode_ret != 0) {
        log_line(LLAMA_LOG_ERROR, "start_generate: Prefill decode failed ret=%d", decode_ret);
        return false;
    }
    log_line(LLAMA_LOG_INFO, "start_generate: Prefill ok");

    llama_synchronize(g_context);
    if (llama_get_logits_ith(g_context, -1) == nullptr) {
        log_line(LLAMA_LOG_ERROR, "start_generate: n_outputs=0 after prefill");
        return false;
    }

    g_current_position = static_cast<llama_pos>(g_streaming_tokens.size());
    g_max_tokens_remaining = max_tokens;
    return true;
}

const char *llama_runner_core_next_token() {
    if (g_cancel_flag) {
        log_line(LLAMA_LOG_INFO, "next_token: cancelled");
        g_stop_reason = STOP_CANCELLED;
        finalize_assistant_turn();
        return nullptr;
    }
    if (g_max_tokens_remaining <= 0) {
        log_line(LLAMA_LOG_INFO, "next_token: max_tokens reached");
        g_stop_reason = STOP_MAX_TOKENS;
        finalize_assistant_turn();
        return nullptr;
    }

    const uint32_t n_ctx = llama_n_ctx(g_context);
    const int headroom = 4;
    if (g_chat_templates && g_current_position >= static_cast<llama_pos>(n_ctx) - headroom) {
        log_line(LLAMA_LOG_INFO, "next_token: context full");
        g_stop_reason = STOP_CONTEXT_FULL;
        finalize_assistant_turn();
        return nullptr;
    }

    const llama_vocab *vocab = llama_model_get_vocab(g_model);

    llama_synchronize(g_context);
    if (llama_get_logits_ith(g_context, -1) == nullptr) {
        log_line(LLAMA_LOG_ERROR, "next_token: n_outputs=0, stopping");
        g_stop_reason = STOP_ERROR;
        return nullptr;
    }

    const llama_token token = common_sampler_sample(g_sampler, g_context, -1);
    if (llama_vocab_is_eog(vocab, token)) {
        log_line(LLAMA_LOG_INFO, "next_token: EOG token=%d", token);

        // (a) Decode the EOG token itself so g_current_position reflects it.
        // Without this, the next user-turn diff would tokenize from the same
        // position as the EOG, leaking the EOG bytes back into KV at the wrong
        // offset and causing template misalignment on multi-turn chats.
        common_batch_clear(g_batch);
        common_batch_add(g_batch, token, g_current_position, {0}, false);
        if (llama_decode(g_context, g_batch) == 0) {
            g_kv_token_history.push_back(token);
            g_current_position++;
            log_line(LLAMA_LOG_INFO,
                "next_token: decoded EOG into KV, new_pos=%d", (int)g_current_position);
        } else {
            log_line(LLAMA_LOG_WARN,
                "next_token: EOG decode failed; KV may be misaligned");
        }

        // (b) Detect post-EOG template residual (e.g. Gemma-2's trailing '\n'
        // after `<end_of_turn>`). Render what the template would emit for the
        // just-completed assistant turn, tokenize it, find the EOG inside, and
        // decode any tokens after the EOG. Template-agnostic: works for any
        // chat template that places template chars after the EOG.
        if (g_chat_templates && !g_assistant_buffer.empty()) {
            auto diff = try_chat_format_single(ROLE_ASSISTANT, g_assistant_buffer);
            if (diff.has_value()) {
                std::vector<llama_token> diff_tokens = common_tokenize(
                    g_context, *diff, /*add_special*/ false, /*parse_special*/ true);
                int eog_idx = -1;
                for (int i = static_cast<int>(diff_tokens.size()) - 1; i >= 0; --i) {
                    if (llama_vocab_is_eog(vocab, diff_tokens[i])) {
                        eog_idx = i;
                        break;
                    }
                }
                if (eog_idx >= 0
                    && eog_idx + 1 < static_cast<int>(diff_tokens.size())) {
                    std::vector<llama_token> residual(
                        diff_tokens.begin() + eog_idx + 1, diff_tokens.end());
                    if (decode_tokens_in_batches(
                            g_context, g_batch, residual,
                            g_current_position, /*compute_last_logit*/ false) == 0) {
                        const int first_residual = residual.front();
                        g_kv_token_history.insert(g_kv_token_history.end(),
                            residual.begin(), residual.end());
                        g_current_position += static_cast<llama_pos>(residual.size());
                        log_line(LLAMA_LOG_INFO,
                            "next_token: decoded %zu post-EOG residual tokens "
                            "(first=%d, new_pos=%d)",
                            residual.size(), first_residual, (int)g_current_position);
                    } else {
                        log_line(LLAMA_LOG_WARN,
                            "next_token: residual decode failed; KV may be misaligned");
                    }
                } else {
                    log_line(LLAMA_LOG_INFO,
                        "next_token: no post-EOG residual tokens (eog_idx=%d, total=%zu)",
                        eog_idx, diff_tokens.size());
                }
            } else {
                log_line(LLAMA_LOG_WARN,
                    "next_token: residual skipped — assistant-turn template render failed");
            }
        }

        g_stop_reason = STOP_EOG;
        finalize_assistant_turn();
        return nullptr;
    }

    common_sampler_accept(g_sampler, token, true);

    common_batch_clear(g_batch);
    common_batch_add(g_batch, token, g_current_position, {0}, true);

    int decode_ret = llama_decode(g_context, g_batch);
    if (decode_ret != 0) {
        log_line(LLAMA_LOG_ERROR, "next_token: Decode failed ret=%d", decode_ret);
        g_stop_reason = STOP_ERROR;
        return nullptr;
    }

    g_kv_token_history.push_back(token);
    g_current_position++;
    g_streaming_n_generated++;
    g_max_tokens_remaining--;

    std::string new_token_chars = common_token_to_piece(vocab, token, true);
    g_cached_utf8_chars += new_token_chars;

    g_current_token.clear();
    if (is_valid_utf8(g_cached_utf8_chars.c_str())) {
        g_current_token = g_cached_utf8_chars;
        g_cached_utf8_chars.clear();
        if (g_chat_templates) {
            g_assistant_buffer += g_current_token;
        }
        return g_current_token.c_str();
    }
    return "";
}

void llama_runner_core_cancel_generate() {
    g_cancel_flag = true;
}

void llama_runner_core_finalize_generation() {
    // Persist assistant content into templated chat history when generation
    // is ended by caller rather than EOG/cancel/max-token boundary.
    finalize_assistant_turn();
    g_cached_utf8_chars.clear();
}

int llama_runner_core_process_system_prompt(const char *system_prompt) {
    if (!g_model || !g_context || !g_sampler) {
        log_line(LLAMA_LOG_ERROR, "process_system_prompt: Model not loaded");
        return 1;
    }

    g_chat_msgs.clear();
    g_pending_chat_decode = false;
    g_system_prompt_position = 0;
    g_current_position = 0;
    g_assistant_buffer.clear();
    g_kv_token_history.clear();
    llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);

    bool has_template = g_chat_templates && common_chat_templates_was_explicit(g_chat_templates.get());

    if (!has_template) {
        std::string formatted = std::string("System: ") + system_prompt + "\n";
        log_line(LLAMA_LOG_WARN, "process_system_prompt: no explicit chat template, using raw text");
        std::vector<llama_token> tokens = common_tokenize(g_context, formatted, false, false);
        const uint32_t n_ctx = llama_n_ctx(g_context);
        if (static_cast<int>(tokens.size()) > static_cast<int>(n_ctx) - 4) {
            log_line(LLAMA_LOG_ERROR, "process_system_prompt: System prompt too long");
            return 1;
        }
        if (decode_tokens_in_batches(g_context, g_batch, tokens, 0, false) != 0) {
            return 2;
        }
        g_kv_token_history.insert(g_kv_token_history.end(), tokens.begin(), tokens.end());
        g_system_prompt_position = g_current_position = static_cast<llama_pos>(tokens.size());
        return 0;
    }

    auto formatted = try_chat_format_single(ROLE_SYSTEM, system_prompt);

    common_chat_msg system_msg;
    system_msg.role = ROLE_SYSTEM;
    system_msg.content = system_prompt;
    g_chat_msgs.push_back(system_msg);

    if (!formatted.has_value()) {
        log_line(LLAMA_LOG_INFO,
            "process_system_prompt: template requires user message; deferring decode");
        g_pending_chat_decode = true;
        return 0;
    }

    std::vector<llama_token> tokens =
        common_tokenize(g_context, *formatted, /*add_special*/ true, /*parse_special*/ true);
    const uint32_t n_ctx = llama_n_ctx(g_context);
    const int max_tokens = static_cast<int>(n_ctx) - 4;
    if (static_cast<int>(tokens.size()) > max_tokens) {
        log_line(LLAMA_LOG_ERROR, "process_system_prompt: System prompt too long");
        return 1;
    }

    if (decode_tokens_in_batches(g_context, g_batch, tokens, 0, false) != 0) {
        return 2;
    }

    g_kv_token_history.insert(g_kv_token_history.end(), tokens.begin(), tokens.end());
    g_system_prompt_position = g_current_position = static_cast<llama_pos>(tokens.size());
    return 0;
}

int llama_runner_core_process_user_prompt(const char *user_prompt, int predict_length, const char *grammar) {
    if (!g_model || !g_context || !g_sampler) {
        log_line(LLAMA_LOG_ERROR, "process_user_prompt: Model not loaded");
        return 1;
    }

    g_cancel_flag = false;
    g_cached_utf8_chars.clear();
    g_streaming_n_generated = 0;
    g_assistant_buffer.clear();
    g_stop_reason = STOP_NONE;

    if (!apply_sampler_for_turn(/*temperature*/ -1.0f, grammar)) {
        log_line(LLAMA_LOG_ERROR, "process_user_prompt: Failed to reconfigure sampler");
        return 1;
    }

    bool has_template = g_chat_templates && common_chat_templates_was_explicit(g_chat_templates.get());

    common_chat_msg user_msg;
    user_msg.role = ROLE_USER;
    user_msg.content = user_prompt;

    std::string formatted;
    llama_pos decode_start_pos = g_current_position;
    bool reset_kv = false;

    if (!has_template) {
        formatted = std::string("\nUser: ") + user_prompt + "\nAssistant:";
        g_chat_msgs.push_back(user_msg);
    } else if (g_pending_chat_decode) {
        std::vector<common_chat_msg> full = g_chat_msgs;
        full.push_back(user_msg);
        auto rendered = try_apply_full_template(full, /*add_generation_prompt*/ true);
        if (!rendered.has_value()) {
            log_line(LLAMA_LOG_ERROR,
                "process_user_prompt: chat template still failed after adding user message");
            return 3;
        }
        formatted = *rendered;
        g_chat_msgs.push_back(user_msg);
        g_pending_chat_decode = false;
        llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);
        decode_start_pos = 0;
        g_current_position = 0;
        g_system_prompt_position = 0;
        g_kv_token_history.clear();
        reset_kv = true;
    } else {
        auto diff = try_chat_format_single(ROLE_USER, user_prompt);
        if (diff.has_value()) {
            formatted = *diff;
            g_chat_msgs.push_back(user_msg);
        } else {
            // Incremental diff failed. Instead of clearing the entire KV and
            // re-decoding from position 0, use prefix matching: tokenize the
            // full re-render and find the longest common prefix with what's
            // already in KV. Only decode the divergent suffix.
            log_line(LLAMA_LOG_WARN,
                "process_user_prompt: incremental diff failed, attempting prefix-matched re-render");
            std::vector<common_chat_msg> full = g_chat_msgs;
            full.push_back(user_msg);
            auto rendered = try_apply_full_template(full, /*add_generation_prompt*/ true);
            if (!rendered.has_value()) {
                log_line(LLAMA_LOG_WARN,
                    "process_user_prompt: full re-render failed, falling back to plain-text format");
                formatted = std::string("\nUser: ") + user_prompt + "\nAssistant:";
                g_chat_msgs.push_back(user_msg);
                llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);
                decode_start_pos = 0;
                g_current_position = 0;
                g_system_prompt_position = 0;
                g_kv_token_history.clear();
                reset_kv = true;
            } else {
                g_chat_msgs.push_back(user_msg);

                std::vector<llama_token> full_tokens = common_tokenize(
                    g_context, *rendered,
                    /*add_special*/ true, /*parse_special*/ true);

                // Find the longest common prefix between the new full render
                // and the tokens already decoded into KV.
                size_t prefix_len = 0;
                const size_t max_prefix = std::min(
                    g_kv_token_history.size(), full_tokens.size());
                for (size_t i = 0; i < max_prefix; ++i) {
                    if (g_kv_token_history[i] != full_tokens[i]) break;
                    prefix_len = i + 1;
                }

                if (prefix_len > 0 && prefix_len >= g_kv_token_history.size() / 2) {
                    // Significant prefix match — reuse cached KV up to the
                    // divergence point and only decode the new suffix.
                    llama_memory_seq_rm(llama_get_memory(g_context), 0,
                        static_cast<llama_pos>(prefix_len), -1);

                    std::vector<llama_token> suffix(
                        full_tokens.begin() + prefix_len, full_tokens.end());

                    log_line(LLAMA_LOG_INFO,
                        "process_user_prompt: prefix reuse %zu/%zu tokens, "
                        "decoding %zu new tokens (saved %zu decode ops)",
                        prefix_len, full_tokens.size(), suffix.size(),
                        prefix_len);

                    const uint32_t n_ctx = llama_n_ctx(g_context);
                    const int max_ctx = static_cast<int>(n_ctx) - 4;
                    decode_start_pos = static_cast<llama_pos>(prefix_len);

                    if (decode_start_pos + static_cast<int>(suffix.size()) > max_ctx) {
                        const int to_skip = decode_start_pos +
                            static_cast<int>(suffix.size()) - max_ctx;
                        if (suffix.size() > static_cast<size_t>(to_skip)) {
                            suffix.resize(suffix.size() - to_skip);
                        }
                    }

                    if (decode_tokens_in_batches(g_context, g_batch, suffix,
                            decode_start_pos, true) != 0) {
                        return 2;
                    }

                    g_kv_token_history.resize(prefix_len);
                    g_kv_token_history.insert(g_kv_token_history.end(),
                        suffix.begin(), suffix.end());
                    g_current_position = decode_start_pos +
                        static_cast<llama_pos>(suffix.size());
                    g_max_tokens_remaining = predict_length;
                    g_streaming_tokens.clear();
                    return 0;
                }

                // Prefix too short or no match — fall back to full re-decode.
                log_line(LLAMA_LOG_INFO,
                    "process_user_prompt: prefix match too short (%zu/%zu), "
                    "full re-decode",
                    prefix_len, g_kv_token_history.size());
                formatted = *rendered;
                llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);
                decode_start_pos = 0;
                g_current_position = 0;
                g_system_prompt_position = 0;
                g_kv_token_history.clear();
                reset_kv = true;
            }
        }
    }

    std::vector<llama_token> tokens = common_tokenize(
        g_context, formatted,
        /*add_special*/ has_template && reset_kv,
        /*parse_special*/ has_template);
    const uint32_t n_ctx = llama_n_ctx(g_context);
    const int max_ctx = static_cast<int>(n_ctx) - 4;
    if (decode_start_pos + static_cast<int>(tokens.size()) > max_ctx) {
        const int to_skip = decode_start_pos + static_cast<int>(tokens.size()) - max_ctx;
        if (tokens.size() > static_cast<size_t>(to_skip)) {
            tokens.resize(tokens.size() - to_skip);
        }
    }

    if (decode_tokens_in_batches(g_context, g_batch, tokens, decode_start_pos, true) != 0) {
        return 2;
    }

    g_kv_token_history.insert(g_kv_token_history.end(), tokens.begin(), tokens.end());
    g_current_position = decode_start_pos + static_cast<llama_pos>(tokens.size());
    g_max_tokens_remaining = predict_length;
    g_streaming_tokens.clear();
    return 0;
}

void llama_runner_core_unload() {
    log_line(LLAMA_LOG_INFO, "unload: Releasing model and context");

    g_chat_templates.reset();
    g_chat_msgs.clear();
    g_pending_chat_decode = false;

    if (g_sampler) {
        common_sampler_free(g_sampler);
        g_sampler = nullptr;
    }

    if (g_batch.token) {
        llama_batch_free(g_batch);
        g_batch = llama_batch_init(0, 0, 0);
    }

    if (g_context) {
        if (g_tp_gen)   { if (g_tp_free_fn) g_tp_free_fn(g_tp_gen);   g_tp_gen   = nullptr; }
        if (g_tp_batch) { if (g_tp_free_fn) g_tp_free_fn(g_tp_batch); g_tp_batch = nullptr; }
        llama_free(g_context);
        g_context = nullptr;
    }

    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    g_active_temperature = -1.0f;
    g_active_grammar.clear();
    g_actual_gpu_layers = 0;
    g_kv_token_history.clear();
    log_line(LLAMA_LOG_INFO, "unload: Model unloaded");
}

void llama_runner_core_shutdown() {
    log_line(LLAMA_LOG_INFO, "shutdown: Freeing backend");
    llama_backend_free();
}

int llama_runner_core_get_context_used() {
    if (!g_context) {
        return 0;
    }
    return static_cast<int>(g_current_position);
}

int llama_runner_core_get_context_limit() {
    if (!g_context) {
        return 0;
    }
    return static_cast<int>(llama_n_ctx(g_context));
}

int llama_runner_core_get_stop_reason() {
    return g_stop_reason;
}

int llama_runner_core_get_gpu_layers() {
    return g_actual_gpu_layers;
}

const char* llama_runner_core_get_model_architecture() {
    if (!g_model) return "";
    static char buf[64];
    buf[0] = '\0';
    llama_model_meta_val_str(g_model, "general.architecture", buf, sizeof(buf));
    return buf;
}

void llama_runner_core_clear_context() {
    if (!g_context) {
        log_line(LLAMA_LOG_WARN, "clear_context: No context to clear");
        return;
    }

    log_line(LLAMA_LOG_INFO, "clear_context: Clearing KV cache and resetting state");

    llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);

    g_current_position = 0;
    g_system_prompt_position = 0;
    g_chat_msgs.clear();
    g_pending_chat_decode = false;
    g_assistant_buffer.clear();
    g_cached_utf8_chars.clear();
    g_streaming_tokens.clear();
    g_streaming_n_generated = 0;
    g_kv_token_history.clear();
    
    if (g_sampler) {
        common_sampler_reset(g_sampler);
    }
    
    g_stop_reason = STOP_NONE;
    
    log_line(LLAMA_LOG_INFO, "clear_context: Context cleared");
}
