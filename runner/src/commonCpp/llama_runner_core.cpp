#include "llama_runner_core.h"

#include <atomic>
#include <algorithm>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdarg>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <exception>
#include <limits>
#include <memory>
#include <mutex>
#include <optional>
#include <string>
#include <vector>

#include "build-info.h"
#include "chat.h"
#include "common.h"
#include "fit.h"
#include "ggml.h"
#include "ggml-backend.h"
#include "ggml-cpp.h"
#include "llama.h"
#include "llama-arch.h"
#include "llama-model.h"
#include "llama_operation_gate.h"
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
LlamaOperationGate g_operation_gate;
bool g_backend_initialized = false;
std::string g_backend_path;

class ScopedSessionEnd {
public:
    explicit ScopedSessionEnd(LlamaOperationGate &gate) : gate_(gate) {}
    ~ScopedSessionEnd() { if (armed_) gate_.end_session(); }

    ScopedSessionEnd(const ScopedSessionEnd &) = delete;
    ScopedSessionEnd &operator=(const ScopedSessionEnd &) = delete;

    void keep_session() { armed_ = false; }

private:
    LlamaOperationGate &gate_;
    bool armed_ = true;
};

std::atomic<bool> g_cancel_flag{false};
// 0 is idle, a positive token owns the probe, and its negation is the same
// active probe with cancellation requested. A queued/non-owner token cannot mutate it.
std::atomic<int64_t> g_calibration_state{0};
// A timed-out native probe cannot be killed safely in-process. Quarantine all
// later blocking model operations instead of allowing them to wait behind it.
std::atomic<bool> g_native_operations_poisoned{false};

static bool native_operations_poisoned() {
    return g_native_operations_poisoned.load(std::memory_order_acquire);
}

class ScopedCalibrationProbe {
public:
    explicit ScopedCalibrationProbe(int64_t token) : token_(token) {}
    ~ScopedCalibrationProbe() {
        int64_t expected = token_;
        if (!g_calibration_state.compare_exchange_strong(
                expected, 0, std::memory_order_acq_rel, std::memory_order_acquire)) {
            expected = -token_;
            g_calibration_state.compare_exchange_strong(
                expected, 0, std::memory_order_acq_rel, std::memory_order_acquire);
        }
    }

    ScopedCalibrationProbe(const ScopedCalibrationProbe &) = delete;
    ScopedCalibrationProbe &operator=(const ScopedCalibrationProbe &) = delete;

private:
    int64_t token_;
};
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

// Parser params for splitting reasoning vs content from the model's own
// template format. Rebuilt at each generation start from g_chat_msgs so the
// forced-open generation_prompt (e.g. a template-injected "<think>") is fed to
// common_chat_parse and the reasoning/content split stays aligned.
static common_chat_parser_params g_parser_params;
// Cumulative parsed reasoning/content for the current turn, refreshed per token.
static std::string g_reasoning_accum;
static std::string g_content_accum;

// Byte offsets already emitted as deltas. Reset per turn (see reset_delta_offsets).
static size_t g_reasoning_emitted = 0;
static size_t g_content_emitted = 0;

// Holding buffers so the returned const char* outlives the accessor call.
static std::string g_reasoning_delta_buf;
static std::string g_content_delta_buf;

static void reset_delta_offsets() {
    g_reasoning_emitted = 0;
    g_content_emitted = 0;
    g_reasoning_delta_buf.clear();
    g_content_delta_buf.clear();
}

// Template capability: does this model's chat template support thinking?
static bool g_supports_thinking = false;

static void log_line(LlamaLogLevel level, const char *fmt, ...);

static void sanitized_upstream_log(
    ggml_log_level level,
    const char * /*text*/,
    void * /*user_data*/) {
    if (!g_logger || level < GGML_LOG_LEVEL_WARN) {
        return;
    }
    g_logger(
        level >= GGML_LOG_LEVEL_ERROR ? LLAMA_LOG_ERROR : LLAMA_LOG_WARN,
        "native engine diagnostic suppressed");
}

static void discard_upstream_log(
    ggml_log_level /*level*/,
    const char * /*text*/,
    void * /*user_data*/) {}

class ScopedLlamaLoggerOverride {
public:
    explicit ScopedLlamaLoggerOverride(ggml_log_callback replacement) {
        llama_log_get(&original_callback_, &original_user_data_);
        llama_log_set(replacement, nullptr);
    }

    ~ScopedLlamaLoggerOverride() {
        llama_log_set(original_callback_, original_user_data_);
    }

    ScopedLlamaLoggerOverride(const ScopedLlamaLoggerOverride &) = delete;
    ScopedLlamaLoggerOverride &operator=(const ScopedLlamaLoggerOverride &) = delete;

private:
    ggml_log_callback original_callback_ = nullptr;
    void *original_user_data_ = nullptr;
};

static bool is_bounded_c_string(const char *value, size_t max_bytes) {
    if (!value) {
        return false;
    }
    size_t length = 0;
    while (length <= max_bytes && value[length] != '\0') {
        length++;
    }
    return length > 0 && length <= max_bytes;
}

static bool is_bounded_engine_version(const char *value) {
    if (!is_bounded_c_string(value, 72)) return false;
    for (size_t index = 0; value[index] != '\0'; index++) {
        const unsigned char byte = static_cast<unsigned char>(value[index]);
        const bool accepted = (byte >= 'A' && byte <= 'Z') ||
            (byte >= 'a' && byte <= 'z') || (byte >= '0' && byte <= '9') ||
            byte == '.' || byte == '_' || byte == '+' || byte == '-';
        if (!accepted) return false;
    }
    return true;
}

static bool is_valid_config(const LlamaRunnerConfig &config) {
    return config.n_ctx >= 0 && config.n_ctx <= 16777216 &&
        config.n_ctx_min >= 1 && config.n_ctx_min <= 16777216 &&
        config.n_threads >= 1 && config.n_threads <= 1024 &&
        config.n_threads_batch >= 0 && config.n_threads_batch <= 1024 &&
        config.n_batch >= 1 && config.n_batch <= 1048576 &&
        config.n_ubatch >= 1 && config.n_ubatch <= config.n_batch &&
        config.n_outputs_max_per_seq >= 0 && config.n_outputs_max_per_seq <= config.n_batch &&
        config.flash_attn >= -1 && config.flash_attn <= 1 &&
        config.type_k >= 0 && config.type_k < GGML_TYPE_COUNT &&
        config.type_v >= 0 && config.type_v < GGML_TYPE_COUNT &&
        config.n_gpu_layers >= -1 && config.n_gpu_layers <= 65536 &&
        config.lazy_mode >= LLAMA_LAZY_MODE_OFF && config.lazy_mode <= LLAMA_LAZY_MODE_ON &&
        !(config.lazy_mode == LLAMA_LAZY_MODE_ON && !config.use_mmap) &&
        std::isfinite(config.temperature) &&
        config.temperature >= 0.0f && config.temperature <= 10.0f;
}

struct FitPlan {
    llama_model_params model_params = llama_model_default_params();
    llama_context_params context_params = llama_context_default_params();
    std::vector<float> tensor_split = std::vector<float>(llama_max_devices(), 0.0f);
    std::vector<llama_model_tensor_buft_override> buffer_overrides =
        std::vector<llama_model_tensor_buft_override>(llama_max_tensor_buft_overrides() + 1);
    std::vector<size_t> margins = std::vector<size_t>(llama_max_devices(), 0);
    common_params_fit_status status = COMMON_PARAMS_FIT_STATUS_ERROR;

    FitPlan() {
        buffer_overrides.back() = {nullptr, nullptr};
    }

    FitPlan(const FitPlan &) = delete;
    FitPlan &operator=(const FitPlan &) = delete;
    FitPlan(FitPlan &&) = default;
    FitPlan &operator=(FitPlan &&) = default;

    void bind_owned_buffers() {
        model_params.tensor_split = tensor_split.data();
        if (model_params.tensor_buft_overrides != nullptr) {
            model_params.tensor_buft_overrides = buffer_overrides.data();
        }
    }
};

static FitPlan resolve_fit_plan(const char *model_path, const LlamaRunnerConfig &config) {
    FitPlan plan;
    if (!is_bounded_c_string(model_path, 4096) || !is_valid_config(config)) {
        return plan;
    }

    plan.context_params.n_threads = config.n_threads;
    plan.context_params.n_threads_batch = config.n_threads_batch > 0
        ? config.n_threads_batch : config.n_threads;
    plan.context_params.n_batch = config.n_batch;
    plan.context_params.n_ubatch = config.n_ubatch;
    plan.context_params.n_outputs_max_per_seq = static_cast<uint32_t>(config.n_outputs_max_per_seq);
    plan.context_params.flash_attn_type = static_cast<llama_flash_attn_type>(config.flash_attn);
    plan.context_params.offload_kqv = config.offload_kqv;
    plan.context_params.type_k = static_cast<ggml_type>(config.type_k);
    plan.context_params.type_v = static_cast<ggml_type>(config.type_v);
    plan.model_params.load_mode = config.use_mlock
        ? (config.use_mmap ? LLAMA_LOAD_MODE_MMAP_MLOCK : LLAMA_LOAD_MODE_MLOCK)
        : (config.use_mmap ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_NONE);
    plan.model_params.lazy_mode = static_cast<llama_lazy_mode>(config.lazy_mode);

    if (config.auto_fit) {
        if (config.n_gpu_layers == 0) {
            plan.model_params.n_gpu_layers = 0;
        }
        plan.context_params.n_ctx = 0;
        plan.status = common_fit_params(
            model_path,
            &plan.model_params,
            &plan.context_params,
            plan.tensor_split.data(),
            plan.buffer_overrides.data(),
            plan.margins.data(),
            static_cast<uint32_t>(config.n_ctx_min),
            nullptr,
            GGML_LOG_LEVEL_ERROR);
        if (plan.status != COMMON_PARAMS_FIT_STATUS_SUCCESS) {
            return plan;
        }
        if (plan.context_params.n_ctx == 0) {
            plan.context_params.n_ctx = static_cast<uint32_t>(
                config.n_ctx > 0 ? config.n_ctx : 4096);
        }
        if (config.n_ctx > 0 &&
            static_cast<uint32_t>(config.n_ctx) < plan.context_params.n_ctx) {
            plan.context_params.n_ctx = static_cast<uint32_t>(config.n_ctx);
        }
        if (plan.model_params.n_gpu_layers == 0 && config.n_gpu_layers != 0) {
            plan.context_params.n_threads = std::max(
                plan.context_params.n_threads,
                plan.context_params.n_threads_batch);
        }
    } else {
        plan.model_params.n_gpu_layers = config.n_gpu_layers;
        plan.context_params.n_ctx = static_cast<uint32_t>(config.n_ctx > 0 ? config.n_ctx : 2048);
        plan.status = COMMON_PARAMS_FIT_STATUS_SUCCESS;
    }

    if (plan.context_params.n_ctx == 0 ||
        plan.context_params.n_ctx > static_cast<uint32_t>(std::numeric_limits<int>::max()) ||
        plan.model_params.n_gpu_layers < 0) {
        plan.status = COMMON_PARAMS_FIT_STATUS_ERROR;
        return plan;
    }
    plan.bind_owned_buffers();
    return plan;
}

static int preflight_pool_kind(ggml_backend_dev_t device) {
    if (!device) {
        return LLAMA_POOL_OTHER;
    }
    switch (ggml_backend_dev_type(device)) {
        case GGML_BACKEND_DEVICE_TYPE_CPU: return LLAMA_POOL_HOST;
        case GGML_BACKEND_DEVICE_TYPE_GPU: return LLAMA_POOL_DISCRETE_GPU;
        case GGML_BACKEND_DEVICE_TYPE_IGPU: return LLAMA_POOL_INTEGRATED_GPU;
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return LLAMA_POOL_ACCELERATOR;
        case GGML_BACKEND_DEVICE_TYPE_META: return LLAMA_POOL_META;
    }
    return LLAMA_POOL_OTHER;
}

static bool checked_size_to_i64(size_t value, int64_t &result) {
    if (value > static_cast<size_t>(std::numeric_limits<int64_t>::max())) {
        return false;
    }
    result = static_cast<int64_t>(value);
    return true;
}

static std::string lower_ascii(const char *value) {
    std::string result = value ? value : "";
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char byte) {
        return byte >= 'A' && byte <= 'Z' ? static_cast<char>(byte - 'A' + 'a') : static_cast<char>(byte);
    });
    return result;
}

static int backend_kind(ggml_backend_dev_t device) {
    const ggml_backend_reg_t registry = device ? ggml_backend_dev_backend_reg(device) : nullptr;
    const std::string name = lower_ascii(registry ? ggml_backend_reg_name(registry) : nullptr);
    if (name.find("cpu") != std::string::npos) return LLAMA_BACKEND_CPU;
    if (name.find("cuda") != std::string::npos || name.find("hip") != std::string::npos) return LLAMA_BACKEND_CUDA;
    if (name.find("metal") != std::string::npos) return LLAMA_BACKEND_METAL;
    if (name.find("vulkan") != std::string::npos) return LLAMA_BACKEND_VULKAN;
    if (name.find("opencl") != std::string::npos) return LLAMA_BACKEND_OPENCL;
    if (name.find("sycl") != std::string::npos) return LLAMA_BACKEND_SYCL;
    return LLAMA_BACKEND_OTHER;
}

static int backend_device_type(ggml_backend_dev_t device) {
    switch (ggml_backend_dev_type(device)) {
        case GGML_BACKEND_DEVICE_TYPE_CPU: return LLAMA_BACKEND_DEVICE_CPU;
        case GGML_BACKEND_DEVICE_TYPE_GPU: return LLAMA_BACKEND_DEVICE_DISCRETE_GPU;
        case GGML_BACKEND_DEVICE_TYPE_IGPU: return LLAMA_BACKEND_DEVICE_INTEGRATED_GPU;
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return LLAMA_BACKEND_DEVICE_ACCELERATOR;
        case GGML_BACKEND_DEVICE_TYPE_META: return LLAMA_BACKEND_DEVICE_META;
    }
    return LLAMA_BACKEND_DEVICE_META;
}

static bool encode_device_identity(
        ggml_backend_dev_t device,
        int &length,
        int64_t (&words)[8]) {
    const char *raw = device ? ggml_backend_dev_name(device) : nullptr;
    if (!raw) return false;
    const size_t raw_length = ::strnlen(raw, 65);
    if (raw_length == 0 || raw_length > 64) return false;
    size_t first = 0;
    size_t last = raw_length;
    while (first < last && std::isspace(static_cast<unsigned char>(raw[first]))) ++first;
    while (last > first && std::isspace(static_cast<unsigned char>(raw[last - 1]))) --last;
    const size_t canonical_length = last - first;
    if (canonical_length == 0 || canonical_length > 64) return false;
    for (int64_t &word : words) word = 0;
    for (size_t index = 0; index < canonical_length; ++index) {
        const unsigned char byte = static_cast<unsigned char>(raw[first + index]);
        if (byte < 0x20 || byte > 0x7e) return false;
        const unsigned char canonical = byte >= 'A' && byte <= 'Z'
            ? static_cast<unsigned char>(byte - 'A' + 'a') : byte;
        const uint64_t shifted = static_cast<uint64_t>(canonical) << ((index % 8) * 8);
        words[index / 8] = static_cast<int64_t>(
            static_cast<uint64_t>(words[index / 8]) | shifted);
    }
    length = static_cast<int>(canonical_length);
    return true;
}

static bool is_safe_feature_label(const char *value, size_t max_bytes) {
    if (!is_bounded_c_string(value, max_bytes)) {
        return false;
    }
    for (size_t i = 0; value[i] != '\0'; i++) {
        const unsigned char byte = static_cast<unsigned char>(value[i]);
        if (byte < 0x20 || byte > 0x7e) {
            return false;
        }
    }
    return true;
}

static ggml_type quantization_type(const std::string &label) {
    if (label == "F32") return GGML_TYPE_F32;
    if (label == "F16") return GGML_TYPE_F16;
    if (label == "BF16") return GGML_TYPE_BF16;
    if (label == "Q4_0") return GGML_TYPE_Q4_0;
    if (label == "Q4_1") return GGML_TYPE_Q4_1;
    if (label == "Q5_0") return GGML_TYPE_Q5_0;
    if (label == "Q5_1") return GGML_TYPE_Q5_1;
    if (label == "Q8_0") return GGML_TYPE_Q8_0;
    if (label == "Q2_K") return GGML_TYPE_Q2_K;
    if (label == "Q3_K" || label == "Q3_K_S" || label == "Q3_K_M" || label == "Q3_K_L") return GGML_TYPE_Q3_K;
    if (label == "Q4_K" || label == "Q4_K_S" || label == "Q4_K_M") return GGML_TYPE_Q4_K;
    if (label == "Q5_K" || label == "Q5_K_S" || label == "Q5_K_M") return GGML_TYPE_Q5_K;
    if (label == "Q6_K") return GGML_TYPE_Q6_K;
    if (label == "IQ2_XXS") return GGML_TYPE_IQ2_XXS;
    if (label == "IQ2_XS") return GGML_TYPE_IQ2_XS;
    if (label == "IQ2_S" || label == "IQ2_M") return GGML_TYPE_IQ2_S;
    if (label == "IQ3_XXS") return GGML_TYPE_IQ3_XXS;
    if (label == "IQ3_XS" || label == "IQ3_S" || label == "IQ3_M") return GGML_TYPE_IQ3_S;
    if (label == "IQ1_S") return GGML_TYPE_IQ1_S;
    if (label == "IQ1_M") return GGML_TYPE_IQ1_M;
    if (label == "IQ4_NL") return GGML_TYPE_IQ4_NL;
    if (label == "IQ4_XS") return GGML_TYPE_IQ4_XS;
    if (label == "TQ1_0") return GGML_TYPE_TQ1_0;
    if (label == "TQ2_0") return GGML_TYPE_TQ2_0;
    if (label == "MXFP4") return GGML_TYPE_MXFP4;
    if (label == "NVFP4") return GGML_TYPE_NVFP4;
    if (label == "Q1_0") return GGML_TYPE_Q1_0;
    if (label == "Q2_0") return GGML_TYPE_Q2_0;
    return GGML_TYPE_COUNT;
}

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

// Rebuild g_parser_params from the current chat history so per-token parsing
// knows the template's format, PEG parser arena, and forced-open generation
// prompt. Called at generation start (after the user message is in g_chat_msgs).
static void capture_parser_params() {
    g_parser_params = common_chat_parser_params{};
    if (!g_chat_templates || !g_chat_templates.get()) {
        return;
    }
    try {
        common_chat_templates_inputs inputs;
        inputs.use_jinja              = true;
        inputs.messages               = g_chat_msgs;
        inputs.add_generation_prompt  = true;
        inputs.reasoning_format       = COMMON_REASONING_FORMAT_AUTO;
        inputs.enable_thinking        = true;
        common_chat_params p = common_chat_templates_apply(g_chat_templates.get(), inputs);
        g_parser_params.format            = p.format;
        g_parser_params.generation_prompt = p.generation_prompt;
        g_parser_params.reasoning_format  = COMMON_REASONING_FORMAT_AUTO;
        g_parser_params.parser            = p.parser.empty()
            ? common_peg_arena{}
            : ([&]{ common_peg_arena a; a.load(p.parser); return a; })();
        log_line(LLAMA_LOG_INFO,
            "capture_parser_params: format=%s gen_prompt_len=%zu",
            common_chat_format_name(p.format), p.generation_prompt.size());
    } catch (const std::exception &e) {
        log_line(LLAMA_LOG_WARN, "capture_parser_params failed: %s", e.what());
        g_parser_params = common_chat_parser_params{};
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
        "recreate_sampler: temp=%.3f grammar_len=%zu",
        sparams.temp, grammar.size());
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
        reset_delta_offsets();
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

// Re-parse the cumulative assistant buffer into reasoning/content. Called after
// each token (is_partial=true) and once at finalize (is_partial=false).
static void reparse_assistant_buffer(bool is_partial) {
    if (g_assistant_buffer.empty()) {
        g_reasoning_accum.clear();
        g_content_accum.clear();
        return;
    }
    try {
        common_chat_msg msg = common_chat_parse(
            g_assistant_buffer, is_partial, g_parser_params);
        g_reasoning_accum = msg.reasoning_content;
        g_content_accum   = msg.content;
    } catch (const std::exception &e) {
        // Lenient fallback: if parsing throws (malformed partial), leave the
        // last good accumulators in place; on final pass, surface raw buffer as
        // content so nothing is lost.
        if (!is_partial) {
            g_reasoning_accum.clear();
            g_content_accum = g_assistant_buffer;
        }
        log_line(LLAMA_LOG_WARN, "reparse_assistant_buffer failed: %s", e.what());
    }
}

static void unload_model_state() {
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
    reset_delta_offsets();
    log_line(LLAMA_LOG_INFO, "unload: Model unloaded");
}

} // namespace

void llama_runner_core_set_logger(LlamaLogFn fn) {
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return;
    g_logger = fn;
}

void llama_runner_core_init(const char *backend_path) {
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return;
    if (backend_path && !is_bounded_c_string(backend_path, 4096)) {
        log_line(LLAMA_LOG_ERROR, "init: Invalid backend directory");
        return;
    }
    if (g_backend_initialized) {
        const std::string requested_path = backend_path ? backend_path : "";
        if (requested_path != g_backend_path) {
            log_line(LLAMA_LOG_WARN, "init: Backend directory change rejected");
        }
        return;
    }
    if (backend_path && std::strlen(backend_path) > 0) {
        log_line(LLAMA_LOG_INFO, "init: Loading backends from configured directory");
        ggml_backend_load_all_from_path(backend_path);
    } else {
        log_line(LLAMA_LOG_INFO, "init: No backend path provided, skipping backend path load");
    }
    llama_backend_init();
    llama_log_set(sanitized_upstream_log, nullptr);
    g_backend_path = backend_path ? backend_path : "";
    g_backend_initialized = true;
    log_line(LLAMA_LOG_INFO, "init: Backend initialized");
}

std::string llama_runner_core_engine_version() {
    const char *version = llama_version();
    return is_bounded_engine_version(version) ? std::string(version) : std::string();
}

LlamaPreflightResultNative llama_runner_core_preflight(
    const char *model_path,
    const LlamaRunnerConfig &config) {
    LlamaPreflightResultNative result;
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) {
        result.status = LLAMA_PREFLIGHT_UNAVAILABLE;
        return result;
    }
    if (!is_bounded_c_string(model_path, 4096) || !is_valid_config(config)) {
        result.status = LLAMA_PREFLIGHT_INVALID;
        return result;
    }

    if (!g_backend_initialized) {
        result.status = LLAMA_PREFLIGHT_UNAVAILABLE;
        return result;
    }
    ScopedLlamaLoggerOverride suppress(discard_upstream_log);
    try {
        FitPlan plan = resolve_fit_plan(model_path, config);
        if (plan.status == COMMON_PARAMS_FIT_STATUS_FAILURE) {
            result.status = LLAMA_PREFLIGHT_NO_FIT;
            return result;
        }
        if (plan.status != COMMON_PARAMS_FIT_STATUS_SUCCESS) {
            result.status = LLAMA_PREFLIGHT_INVALID;
            return result;
        }
        plan.bind_owned_buffers();

        std::vector<ggml_backend_dev_t> devices;
        uint32_t model_layers = 0;
        uint32_t training_context = 0;
        uint32_t expert_count = 0;
        const common_device_memory_data_vec memory = common_get_device_memory_data(
            model_path,
            &plan.model_params,
            &plan.context_params,
            devices,
            model_layers,
            training_context,
            expert_count,
            GGML_LOG_LEVEL_ERROR);
        if (memory.empty() || memory.size() != devices.size() + 1 ||
            memory.size() > static_cast<size_t>(LLAMA_PREFLIGHT_MAX_POOLS)) {
            result.status = LLAMA_PREFLIGHT_UNAVAILABLE;
            return result;
        }

        for (size_t index = 0; index < memory.size(); index++) {
            const common_device_memory_data &source = memory[index];
            LlamaPreflightMemoryPool &destination = result.pools[index];
            destination.kind = index < devices.size()
                ? preflight_pool_kind(devices[index]) : LLAMA_POOL_HOST;
            destination.ordinal = static_cast<int>(index);
            if (source.free < 0 || source.total < 0 ||
                !checked_size_to_i64(source.model, destination.model_bytes) ||
                !checked_size_to_i64(source.context, destination.context_bytes) ||
                !checked_size_to_i64(source.compute, destination.compute_bytes)) {
                result = LlamaPreflightResultNative{};
                result.status = LLAMA_PREFLIGHT_INVALID;
                return result;
            }
            destination.free_bytes = source.free;
            destination.total_bytes = source.total;
        }

        result.status = LLAMA_PREFLIGHT_FIT;
        result.n_ctx = static_cast<int>(plan.context_params.n_ctx);
        result.n_gpu_layers = plan.model_params.n_gpu_layers;
        result.pool_count = static_cast<int>(memory.size());
        return result;
    } catch (const std::invalid_argument &) {
        result.status = LLAMA_PREFLIGHT_INVALID;
    } catch (const std::runtime_error &) {
        result.status = LLAMA_PREFLIGHT_UNAVAILABLE;
    } catch (...) {
        result.status = LLAMA_PREFLIGHT_UNAVAILABLE;
    }
    return result;
}

LlamaBackendCapabilitiesNative llama_runner_core_backend_capabilities() {
    LlamaBackendCapabilitiesNative result;
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return result;
    if (!g_backend_initialized) {
        return result;
    }
    ScopedLlamaLoggerOverride suppress(discard_upstream_log);
    try {
        const size_t count = ggml_backend_dev_count();
        if (count == 0 || count > static_cast<size_t>(LLAMA_BACKEND_MAX_DEVICES)) {
            return result;
        }
        for (size_t index = 0; index < count; index++) {
            ggml_backend_dev_t device = ggml_backend_dev_get(index);
            if (!device) {
                return LlamaBackendCapabilitiesNative{};
            }
            ggml_backend_dev_props properties{};
            ggml_backend_dev_get_props(device, &properties);
            LlamaBackendCapabilityNative &destination = result.devices[index];
            destination.kind = backend_kind(device);
            destination.device_type = backend_device_type(device);
            if (!encode_device_identity(
                    device,
                    destination.device_identity_length,
                    destination.device_identity_words)) {
                return LlamaBackendCapabilitiesNative{};
            }
            if (properties.memory_total > 0) {
                if (!checked_size_to_i64(properties.memory_free, destination.free_bytes) ||
                    !checked_size_to_i64(properties.memory_total, destination.total_bytes) ||
                    destination.free_bytes > destination.total_bytes) {
                    return LlamaBackendCapabilitiesNative{};
                }
            }
        }
        result.count = static_cast<int>(count);
    } catch (...) {
        result.count = -1;
    }
    return result;
}

LlamaCalibrationResultNative llama_runner_core_calibrate_backend(
    int64_t probe_token,
    int requested_backend,
    int duration_millis,
    int64_t buffer_bytes) {
    LlamaCalibrationResultNative result;
    result.backend = requested_backend;
    constexpr int64_t min_buffer = 4LL * 1024LL * 1024LL;
    constexpr int64_t max_buffer = 64LL * 1024LL * 1024LL;
    if (probe_token <= 0 ||
        requested_backend < LLAMA_BACKEND_CPU || requested_backend > LLAMA_BACKEND_OTHER ||
        duration_millis < 500 || duration_millis > 3000 ||
        buffer_bytes < min_buffer || buffer_bytes > max_buffer) {
        result.status = LLAMA_CALIBRATION_INVALID;
        return result;
    }

    int64_t state = g_calibration_state.load(std::memory_order_acquire);
    if (native_operations_poisoned() && state != -probe_token) {
        result.status = LLAMA_CALIBRATION_QUARANTINED;
        return result;
    }
    if (state == 0) {
        int64_t expected_idle = 0;
        if (g_calibration_state.compare_exchange_strong(
                expected_idle,
                probe_token,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            state = probe_token;
        } else {
            state = expected_idle;
        }
    }
    if (state != probe_token && state != -probe_token) {
        result.status = LLAMA_CALIBRATION_DEFERRED;
        return result;
    }
    const ScopedCalibrationProbe release_probe(probe_token);

    const auto cancelled = [probe_token]() {
        return g_calibration_state.load(std::memory_order_acquire) == -probe_token;
    };
    if (cancelled()) {
        result.status = LLAMA_CALIBRATION_CANCELLED;
        return result;
    }
    const auto admission_deadline = std::chrono::steady_clock::now() +
        std::chrono::milliseconds(std::min(250, std::max(50, duration_millis / 10)));
    auto operation = g_operation_gate.lock_until(admission_deadline, cancelled);
    if (!operation) {
        result.status = cancelled() ? LLAMA_CALIBRATION_CANCELLED : LLAMA_CALIBRATION_DEFERRED;
        return result;
    }
    if (!g_backend_initialized) {
        result.status = LLAMA_CALIBRATION_UNAVAILABLE;
        return result;
    }
    ScopedLlamaLoggerOverride suppress(discard_upstream_log);
    try {
        ggml_backend_dev_t selected_device = nullptr;
        const size_t device_count = ggml_backend_dev_count();
        if (device_count == 0 || device_count > static_cast<size_t>(LLAMA_BACKEND_MAX_DEVICES)) {
            return result;
        }
        for (size_t index = 0; index < device_count; ++index) {
            ggml_backend_dev_t candidate = ggml_backend_dev_get(index);
            if (candidate && backend_kind(candidate) == requested_backend &&
                ggml_backend_dev_type(candidate) != GGML_BACKEND_DEVICE_TYPE_META) {
                selected_device = candidate;
                break;
            }
        }
        if (!selected_device) return result;

        ggml_backend_ptr backend(ggml_backend_dev_init(selected_device, nullptr));
        if (!backend) return result;

        constexpr size_t graph_nodes = 16;
        constexpr int windows_per_metric = 5;
        const auto target_per_window = std::chrono::milliseconds(
            std::max(1, duration_millis / (windows_per_metric * 2)));
        const auto run_windows = [&](ggml_cgraph *graph, int metric, int64_t units_per_iteration,
                                     int start_window) -> bool {
            if (!graph || units_per_iteration <= 0) return false;
            if (ggml_backend_graph_compute(backend.get(), graph) != GGML_STATUS_SUCCESS) return false;
            for (int offset = 0; offset < windows_per_metric; ++offset) {
                const auto start = std::chrono::steady_clock::now();
                int64_t iterations = 0;
                do {
                    if (cancelled()) return false;
                    if (ggml_backend_graph_compute(backend.get(), graph) != GGML_STATUS_SUCCESS) return false;
                    ++iterations;
                } while (std::chrono::steady_clock::now() - start < target_per_window);
                const auto elapsed = std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now() - start).count();
                if (iterations <= 0 || elapsed <= 0 ||
                    iterations > std::numeric_limits<int64_t>::max() / units_per_iteration) {
                    return false;
                }
                result.windows[start_window + offset] = LlamaCalibrationWindowNative{
                    metric,
                    units_per_iteration * iterations,
                    elapsed,
                };
            }
            return true;
        };

        {
            const int64_t memory_elements = buffer_bytes /
                (2LL * static_cast<int64_t>(sizeof(float)));
            if (memory_elements <= 0) {
                result.status = LLAMA_CALIBRATION_INVALID;
                return result;
            }
            ggml_init_params params{
                ggml_tensor_overhead() * 6 + ggml_graph_overhead_custom(graph_nodes, false),
                nullptr,
                true,
            };
            ggml_context_ptr context(ggml_init(params));
            if (!context) return result;
            ggml_tensor *source = ggml_new_tensor_1d(context.get(), GGML_TYPE_F32, memory_elements);
            ggml_tensor *destination = ggml_new_tensor_1d(context.get(), GGML_TYPE_F32, memory_elements);
            ggml_tensor *copy = source && destination ? ggml_cpy(context.get(), source, destination) : nullptr;
            if (!copy || !ggml_backend_supports_op(backend.get(), copy)) {
                result.status = LLAMA_CALIBRATION_DEFERRED;
                return result;
            }
            const size_t source_bytes = ggml_nbytes(source);
            const size_t destination_bytes = ggml_nbytes(destination);
            if (source_bytes > static_cast<size_t>(buffer_bytes) ||
                destination_bytes > static_cast<size_t>(buffer_bytes) - source_bytes) {
                result.status = LLAMA_CALIBRATION_DEFERRED;
                return result;
            }
            ggml_backend_buffer_ptr allocation(ggml_backend_alloc_ctx_tensors(context.get(), backend.get()));
            if (!allocation) {
                result.status = LLAMA_CALIBRATION_DEFERRED;
                return result;
            }
            std::vector<float> synthetic(static_cast<size_t>(memory_elements), 0.03125f);
            ggml_backend_tensor_set(source, synthetic.data(), 0, source_bytes);
            ggml_cgraph *graph = ggml_new_graph_custom(context.get(), graph_nodes, false);
            if (!graph) return result;
            ggml_build_forward_expand(graph, copy);
            const size_t bytes_per_iteration_size = source_bytes + destination_bytes;
            if (bytes_per_iteration_size > static_cast<size_t>(std::numeric_limits<int64_t>::max()) ||
                !run_windows(
                    graph,
                    LLAMA_CALIBRATION_MEMORY_BANDWIDTH,
                    static_cast<int64_t>(bytes_per_iteration_size),
                    0)) {
                result.status = cancelled() ? LLAMA_CALIBRATION_CANCELLED : LLAMA_CALIBRATION_FAILED;
                result.window_count = 0;
                return result;
            }
        }

        {
            const long double element_budget = static_cast<long double>(buffer_bytes) /
                (3.0L * static_cast<long double>(sizeof(float)));
            const int64_t dimension = std::max<int64_t>(64, std::min<int64_t>(512,
                static_cast<int64_t>(std::sqrt(element_budget))));
            ggml_init_params params{
                ggml_tensor_overhead() * 8 + ggml_graph_overhead_custom(graph_nodes, false),
                nullptr,
                true,
            };
            ggml_context_ptr context(ggml_init(params));
            if (!context) return result;
            ggml_tensor *left = ggml_new_tensor_2d(context.get(), GGML_TYPE_F32, dimension, dimension);
            ggml_tensor *right = ggml_new_tensor_2d(context.get(), GGML_TYPE_F32, dimension, dimension);
            ggml_tensor *output = left && right ? ggml_mul_mat(context.get(), left, right) : nullptr;
            if (!output || !ggml_backend_supports_op(backend.get(), output)) {
                result.status = LLAMA_CALIBRATION_DEFERRED;
                return result;
            }
            const size_t left_bytes = ggml_nbytes(left);
            const size_t right_bytes = ggml_nbytes(right);
            const size_t output_bytes = ggml_nbytes(output);
            if (left_bytes > static_cast<size_t>(buffer_bytes) ||
                right_bytes > static_cast<size_t>(buffer_bytes) - left_bytes ||
                output_bytes > static_cast<size_t>(buffer_bytes) - left_bytes - right_bytes) {
                result.status = LLAMA_CALIBRATION_DEFERRED;
                return result;
            }
            ggml_backend_buffer_ptr allocation(ggml_backend_alloc_ctx_tensors(context.get(), backend.get()));
            if (!allocation) {
                result.status = LLAMA_CALIBRATION_DEFERRED;
                return result;
            }
            const size_t element_count = static_cast<size_t>(dimension) * static_cast<size_t>(dimension);
            std::vector<float> synthetic(element_count, 0.03125f);
            ggml_backend_tensor_set(left, synthetic.data(), 0, left_bytes);
            ggml_backend_tensor_set(right, synthetic.data(), 0, right_bytes);
            ggml_cgraph *graph = ggml_new_graph_custom(context.get(), graph_nodes, false);
            if (!graph) return result;
            ggml_build_forward_expand(graph, output);
            const long double operations_value = 2.0L * dimension * dimension * dimension;
            if (operations_value <= 0.0L ||
                operations_value > static_cast<long double>(std::numeric_limits<int64_t>::max()) ||
                !run_windows(
                    graph,
                    LLAMA_CALIBRATION_COMPUTE,
                    static_cast<int64_t>(operations_value),
                    windows_per_metric)) {
                result.status = cancelled() ? LLAMA_CALIBRATION_CANCELLED : LLAMA_CALIBRATION_FAILED;
                result.window_count = 0;
                return result;
            }
        }
        result.status = LLAMA_CALIBRATION_COMPLETE;
        result.window_count = windows_per_metric * 2;
        return result;
    } catch (...) {
        result.status = LLAMA_CALIBRATION_FAILED;
        result.window_count = 0;
        return result;
    }
}

int llama_runner_core_reserve_calibration(int64_t probe_token) {
    if (probe_token <= 0) return LLAMA_CALIBRATION_RESERVATION_INVALID;
    if (native_operations_poisoned()) return LLAMA_CALIBRATION_RESERVATION_QUARANTINED;
    int64_t expected_idle = 0;
    if (!g_calibration_state.compare_exchange_strong(
        expected_idle,
        probe_token,
        std::memory_order_acq_rel,
        std::memory_order_acquire)) {
        return LLAMA_CALIBRATION_RESERVATION_BUSY;
    }
    if (native_operations_poisoned()) {
        int64_t expected_token = probe_token;
        g_calibration_state.compare_exchange_strong(
            expected_token,
            0,
            std::memory_order_acq_rel,
            std::memory_order_acquire);
        return LLAMA_CALIBRATION_RESERVATION_QUARANTINED;
    }
    return LLAMA_CALIBRATION_RESERVATION_ACCEPTED;
}

void llama_runner_core_cancel_calibration(int64_t probe_token) {
    if (probe_token <= 0) return;
    int64_t expected = probe_token;
    if (g_calibration_state.compare_exchange_strong(
            expected,
            -probe_token,
            std::memory_order_acq_rel,
            std::memory_order_acquire)) {
        g_operation_gate.notify_waiters();
    }
}

int llama_runner_core_abandon_calibration(int64_t probe_token) {
    if (probe_token <= 0) return LLAMA_CALIBRATION_ABANDONMENT_INVALID;
    int64_t state = g_calibration_state.load(std::memory_order_acquire);
    while (state == probe_token || state == -probe_token) {
        // The self-CAS for -probe_token is deliberate: it linearizes this
        // decision against ScopedCalibrationProbe clearing a completed probe.
        if (g_calibration_state.compare_exchange_weak(
                state,
                -probe_token,
                std::memory_order_acq_rel,
                std::memory_order_acquire)) {
            g_operation_gate.interrupt_waiters([] {
                g_native_operations_poisoned.store(true, std::memory_order_release);
            });
            return LLAMA_CALIBRATION_ABANDONMENT_QUARANTINED;
        }
    }
    return LLAMA_CALIBRATION_ABANDONMENT_NOT_ACTIVE;
}

LlamaModelFeatureSupportNative llama_runner_core_probe_model_features(
    const char *architecture,
    const char *quantization) {
    LlamaModelFeatureSupportNative result;
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return result;
    result.engine_build = std::max(0, llama_build_number());
    if (!is_safe_feature_label(architecture, 64) ||
        (quantization != nullptr && !is_safe_feature_label(quantization, 32))) {
        return result;
    }

    if (!g_backend_initialized) {
        return result;
    }
    ScopedLlamaLoggerOverride suppress(discard_upstream_log);
    try {
        const llm_arch arch = llm_arch_from_string(architecture);
        if (arch == LLM_ARCH_UNKNOWN) {
            result.architecture = LLAMA_FEATURE_UNSUPPORTED;
        } else {
            std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
                llama_model_create(arch, llama_model_default_params()),
                llama_model_free);
            result.architecture = model ? LLAMA_FEATURE_SUPPORTED : LLAMA_FEATURE_UNSUPPORTED;
        }
    } catch (const std::runtime_error &) {
        result.architecture = LLAMA_FEATURE_UNSUPPORTED;
    } catch (...) {
        result.architecture = LLAMA_FEATURE_UNKNOWN;
    }

    if (quantization == nullptr) {
        result.quantization = LLAMA_FEATURE_UNKNOWN;
    } else {
        const ggml_type type = quantization_type(quantization);
        if (type == GGML_TYPE_COUNT) {
            result.quantization = LLAMA_FEATURE_UNKNOWN;
        } else {
            result.quantization = type >= 0 && type < GGML_TYPE_COUNT && ggml_type_name(type) != nullptr
                ? LLAMA_FEATURE_SUPPORTED : LLAMA_FEATURE_UNSUPPORTED;
        }
    }
    return result;
}

bool llama_runner_core_load_model(const char *model_path, const LlamaRunnerConfig &config) {
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return false;
    log_line(LLAMA_LOG_INFO, "load: model path supplied=%d", model_path ? 1 : 0);
    if (!g_backend_initialized ||
        !is_bounded_c_string(model_path, 4096) || !is_valid_config(config)) {
        log_line(LLAMA_LOG_ERROR, "load: invalid model path or configuration");
        return false;
    }

    unload_model_state();
    g_config = config;
    llama_log_set(sanitized_upstream_log, nullptr);

    auto t0 = std::chrono::steady_clock::now();
    {
        ScopedLlamaLoggerOverride suppress(discard_upstream_log);
        FitPlan plan = resolve_fit_plan(model_path, g_config);
        if (plan.status != COMMON_PARAMS_FIT_STATUS_SUCCESS) {
            log_line(LLAMA_LOG_ERROR, "load: no valid fitted allocation plan");
            return false;
        }
        plan.bind_owned_buffers();
        g_actual_gpu_layers = plan.model_params.n_gpu_layers;

        log_line(
            LLAMA_LOG_INFO,
            "load: Final params - n_ctx=%u, n_threads=%d, n_threads_batch=%d, n_batch=%d, n_gpu_layers=%d",
            plan.context_params.n_ctx,
            plan.context_params.n_threads,
            plan.context_params.n_threads_batch,
            plan.context_params.n_batch,
            plan.model_params.n_gpu_layers);

        g_model = llama_model_load_from_file(model_path, plan.model_params);
        if (!g_model) {
            log_line(LLAMA_LOG_ERROR, "load: model allocation failed");
            return false;
        }

        g_context = llama_init_from_model(g_model, plan.context_params);
        if (!g_context) {
            log_line(LLAMA_LOG_ERROR, "load: context allocation failed");
            llama_model_free(g_model);
            g_model = nullptr;
            return false;
        }
    }

    auto t1 = std::chrono::steady_clock::now();
    const auto load_ms = std::chrono::duration_cast<std::chrono::milliseconds>(t1 - t0).count();
    log_line(LLAMA_LOG_INFO, "load: Model loaded in %lld ms", static_cast<long long>(load_ms));
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
                log_line(LLAMA_LOG_INFO, "llama_runner_core: threadpool attached with configured mask");
            }
        } else {
            log_line(LLAMA_LOG_WARN, "llama_runner_core: threadpool unavailable (GGML_BACKEND_DL?), CPU pinning skipped");
        }
    }

    g_batch = llama_batch_init(g_config.n_batch, 0, 1);
    recreate_sampler(g_config.temperature, std::string());
    if (!g_sampler) {
        log_line(LLAMA_LOG_ERROR, "load: Failed to initialize sampler");
        unload_model_state();
        return false;
    }

    g_chat_templates = common_chat_templates_init(g_model, "");
    g_supports_thinking = g_chat_templates
        ? common_chat_templates_support_enable_thinking(g_chat_templates.get())
        : false;
    log_line(LLAMA_LOG_INFO, "load: supports_thinking=%d", g_supports_thinking ? 1 : 0);
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
    ScopedSessionEnd session(g_operation_gate);
    std::string result;
    while (const char *tok = llama_runner_core_next_token()) {
        result.append(tok);
    }
    llama_runner_core_finalize_generation();
    session.keep_session();
    log_line(LLAMA_LOG_INFO, "generate: done output_len=%zu", result.size());
    return result;
}

bool llama_runner_core_start_generate(const char *prompt, int max_tokens, float temperature, const char *grammar) {
    auto operation = g_operation_gate.begin_session_interruptible(native_operations_poisoned);
    if (!operation) return false;
    ScopedSessionEnd session(g_operation_gate);
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
    reset_delta_offsets();

    if (!apply_sampler_for_turn(temperature, grammar)) {
        log_line(LLAMA_LOG_ERROR, "start_generate: Failed to reconfigure sampler");
        return false;
    }

    llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);

    std::string prompt_copy(prompt);
    log_line(LLAMA_LOG_INFO, "start_generate: input prompt_len=%zu", prompt_copy.size());

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
    session.keep_session();
    return true;
}

const char *llama_runner_core_next_token() {
    auto operation = g_operation_gate.lock_session_interruptible(native_operations_poisoned);
    if (!operation.has_value()) {
        return nullptr;
    }
    if (g_cancel_flag) {
        log_line(LLAMA_LOG_INFO, "next_token: cancelled");
        g_stop_reason = STOP_CANCELLED;
        reparse_assistant_buffer(/*is_partial*/ false);
        finalize_assistant_turn();
        return nullptr;
    }
    if (g_max_tokens_remaining <= 0) {
        log_line(LLAMA_LOG_INFO, "next_token: max_tokens reached");
        g_stop_reason = STOP_MAX_TOKENS;
        reparse_assistant_buffer(/*is_partial*/ false);
        finalize_assistant_turn();
        return nullptr;
    }

    const uint32_t n_ctx = llama_n_ctx(g_context);
    const int headroom = 4;
    if (g_chat_templates && g_current_position >= static_cast<llama_pos>(n_ctx) - headroom) {
        log_line(LLAMA_LOG_INFO, "next_token: context full");
        g_stop_reason = STOP_CONTEXT_FULL;
        reparse_assistant_buffer(/*is_partial*/ false);
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
        reparse_assistant_buffer(/*is_partial*/ false);
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
            reparse_assistant_buffer(/*is_partial*/ true);
        }
        return g_current_token.c_str();
    }
    return "";
}

void llama_runner_core_cancel_generate() {
    g_cancel_flag = true;
}

void llama_runner_core_finalize_generation() {
    auto operation = g_operation_gate.lock_session_interruptible(native_operations_poisoned);
    if (!operation.has_value()) {
        return;
    }
    ScopedSessionEnd session(g_operation_gate);
    // Persist assistant content into templated chat history when generation
    // is ended by caller rather than EOG/cancel/max-token boundary.
    reparse_assistant_buffer(/*is_partial*/ false);
    finalize_assistant_turn();
    g_cached_utf8_chars.clear();
}

int llama_runner_core_process_system_prompt(const char *system_prompt) {
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return 1;
    if (!g_model || !g_context || !g_sampler) {
        log_line(LLAMA_LOG_ERROR, "process_system_prompt: Model not loaded");
        return 1;
    }

    g_chat_msgs.clear();
    g_pending_chat_decode = false;
    g_system_prompt_position = 0;
    g_current_position = 0;
    g_assistant_buffer.clear();
    reset_delta_offsets();
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

int llama_runner_core_process_user_prompt(const char *user_prompt, int predict_length) {
    auto operation = g_operation_gate.begin_session_interruptible(native_operations_poisoned);
    if (!operation) return 1;
    ScopedSessionEnd session(g_operation_gate);
    if (!g_model || !g_context || !g_sampler) {
        log_line(LLAMA_LOG_ERROR, "process_user_prompt: Model not loaded");
        return 1;
    }

    g_cancel_flag = false;
    g_cached_utf8_chars.clear();
    g_streaming_n_generated = 0;
    g_assistant_buffer.clear();
    reset_delta_offsets();
    g_stop_reason = STOP_NONE;
    g_reasoning_accum.clear();
    g_content_accum.clear();

    if (!apply_sampler_for_turn(/*temperature*/ -1.0f, nullptr)) {
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
        decode_start_pos = 0;
        reset_kv = true;
    } else {
        auto diff = try_chat_format_single(ROLE_USER, user_prompt);
        if (diff.has_value()) {
            formatted = *diff;
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
                decode_start_pos = 0;
                reset_kv = true;
            } else {
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
                    std::vector<llama_token> suffix(
                        full_tokens.begin() + prefix_len, full_tokens.end());

                    log_line(LLAMA_LOG_INFO,
                        "process_user_prompt: prefix reuse %zu/%zu tokens, "
                        "decoding %zu new tokens (saved %zu decode ops)",
                        prefix_len, full_tokens.size(), suffix.size(),
                        prefix_len);

                    const uint32_t n_ctx = llama_n_ctx(g_context);
                    const int reserved_generation = std::max(4, predict_length);
                    const int max_ctx = static_cast<int>(n_ctx) - reserved_generation;
                    decode_start_pos = static_cast<llama_pos>(prefix_len);

                    if (max_ctx < 0 ||
                        decode_start_pos + static_cast<int>(suffix.size()) > max_ctx) {
                        log_line(LLAMA_LOG_WARN,
                            "process_user_prompt: prompt does not fit with generation reserve");
                        g_stop_reason = STOP_CONTEXT_FULL;
                        return 4;
                    }

                    llama_memory_seq_rm(llama_get_memory(g_context), 0,
                        static_cast<llama_pos>(prefix_len), -1);

                    if (decode_tokens_in_batches(g_context, g_batch, suffix,
                            decode_start_pos, true) != 0) {
                        llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);
                        g_current_position = 0;
                        g_system_prompt_position = 0;
                        g_kv_token_history.clear();
                        g_pending_chat_decode = has_template;
                        return 2;
                    }

                    g_kv_token_history.resize(prefix_len);
                    g_kv_token_history.insert(g_kv_token_history.end(),
                        suffix.begin(), suffix.end());
                    g_current_position = decode_start_pos +
                        static_cast<llama_pos>(suffix.size());
                    g_max_tokens_remaining = predict_length;
                    g_streaming_tokens.clear();
                    g_chat_msgs.push_back(user_msg);
                    g_pending_chat_decode = false;
                    capture_parser_params();
                    session.keep_session();
                    return 0;
                }

                // Prefix too short or no match — fall back to full re-decode.
                log_line(LLAMA_LOG_INFO,
                    "process_user_prompt: prefix match too short (%zu/%zu), "
                    "full re-decode",
                    prefix_len, g_kv_token_history.size());
                formatted = *rendered;
                decode_start_pos = 0;
                reset_kv = true;
            }
        }
    }

    std::vector<llama_token> tokens = common_tokenize(
        g_context, formatted,
        /*add_special*/ has_template && reset_kv,
        /*parse_special*/ has_template);
    const uint32_t n_ctx = llama_n_ctx(g_context);
    const int reserved_generation = std::max(4, predict_length);
    const int max_ctx = static_cast<int>(n_ctx) - reserved_generation;
    if (max_ctx < 0 || decode_start_pos + static_cast<int>(tokens.size()) > max_ctx) {
        log_line(LLAMA_LOG_WARN,
            "process_user_prompt: prompt does not fit with generation reserve");
        g_stop_reason = STOP_CONTEXT_FULL;
        return 4;
    }

    if (reset_kv) {
        llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);
        g_current_position = 0;
        g_system_prompt_position = 0;
        g_kv_token_history.clear();
    }

    if (decode_tokens_in_batches(g_context, g_batch, tokens, decode_start_pos, true) != 0) {
        llama_memory_seq_rm(llama_get_memory(g_context), 0, -1, -1);
        g_current_position = 0;
        g_system_prompt_position = 0;
        g_kv_token_history.clear();
        g_pending_chat_decode = has_template;
        return 2;
    }

    g_kv_token_history.insert(g_kv_token_history.end(), tokens.begin(), tokens.end());
    g_current_position = decode_start_pos + static_cast<llama_pos>(tokens.size());
    g_max_tokens_remaining = predict_length;
    g_streaming_tokens.clear();
    g_chat_msgs.push_back(user_msg);
    g_pending_chat_decode = false;
    capture_parser_params();
    session.keep_session();
    return 0;
}

void llama_runner_core_unload() {
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return;
    unload_model_state();
}

void llama_runner_core_shutdown() {
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return;
    if (!g_backend_initialized) {
        return;
    }
    log_line(LLAMA_LOG_INFO, "shutdown: Freeing backend");
    llama_backend_free();
    g_backend_path.clear();
    g_backend_initialized = false;
}

int llama_runner_core_get_context_used() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return 0;
    if (!g_context) {
        return 0;
    }
    return static_cast<int>(g_current_position);
}

int llama_runner_core_get_context_limit() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return 0;
    if (!g_context) {
        return 0;
    }
    return static_cast<int>(llama_n_ctx(g_context));
}

int llama_runner_core_get_stop_reason() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return STOP_ERROR;
    return g_stop_reason;
}

int llama_runner_core_get_gpu_layers() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return 0;
    return g_actual_gpu_layers;
}

const char* llama_runner_core_get_model_architecture() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return "";
    if (!g_model) return "";
    static char buf[64];
    buf[0] = '\0';
    llama_model_meta_val_str(g_model, "general.architecture", buf, sizeof(buf));
    return buf;
}

const char *llama_runner_core_get_reasoning() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return "";
    return g_reasoning_accum.c_str();
}

const char *llama_runner_core_get_content() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return "";
    return g_content_accum.c_str();
}

// Returns bytes appended to g_reasoning_accum since the last call. If the
// accumulator shrank (parser retroactively reclassified bytes), returns the
// FULL accumulator prefixed with a 0x01 sentinel so the caller knows to
// replace, not append. Empty string means no new bytes.
const char *llama_runner_core_get_reasoning_delta() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return "";
    const std::string &acc = g_reasoning_accum;
    if (acc.size() < g_reasoning_emitted) {
        g_reasoning_delta_buf = std::string(1, '\x01') + acc;
        g_reasoning_emitted = acc.size();
        return g_reasoning_delta_buf.c_str();
    }
    g_reasoning_delta_buf = acc.substr(g_reasoning_emitted);
    g_reasoning_emitted = acc.size();
    return g_reasoning_delta_buf.c_str();
}

const char *llama_runner_core_get_content_delta() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return "";
    const std::string &acc = g_content_accum;
    if (acc.size() < g_content_emitted) {
        g_content_delta_buf = std::string(1, '\x01') + acc;
        g_content_emitted = acc.size();
        return g_content_delta_buf.c_str();
    }
    g_content_delta_buf = acc.substr(g_content_emitted);
    g_content_emitted = acc.size();
    return g_content_delta_buf.c_str();
}

int llama_runner_core_supports_thinking() {
    auto operation = g_operation_gate.lock_session_compatible_interruptible(native_operations_poisoned);
    if (!operation) return 0;
    return g_supports_thinking ? 1 : 0;
}

void llama_runner_core_clear_context() {
    auto operation = g_operation_gate.lock_interruptible(native_operations_poisoned);
    if (!operation) return;
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
    reset_delta_offsets();
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
