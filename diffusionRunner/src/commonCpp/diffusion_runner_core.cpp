#include "diffusion_runner_core.h"
#include "stable-diffusion.h"
#include "core/ggml_extend_backend.h"
#include "model.h"
#include "model_loader.h"
#include "core/backend_fit.h"
#include "core/ggml_graph_cut.h"
#include "diffusion_runner_preflight_internal.h"
#include "scoped_context_publication.h"
#include "ggml.h"
#include "ggml-backend.h"
#ifdef SD_USE_VULKAN
#include "ggml-vulkan.h"
#endif
#include <memory>
#include <algorithm>
#include <array>
#include <cmath>
#include <limits>
#include <map>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>
#include <mutex>
#include <atomic>
#include <cstdlib>
#include <cstring>
#include <cstdio>
#include <cstdarg>

// Define STB_IMAGE_WRITE_IMPLEMENTATION for PNG encoding
#define STB_IMAGE_WRITE_IMPLEMENTATION

#include <stb_image_write.h>

struct SdHandle {
    sd_ctx_t *ctx = nullptr;
    std::mutex operation_mutex;
    std::mutex cancellation_mutex;
    bool closing = false;
    float flow_shift = 0.0f;
    bool flow_shift_is_set = false;
    bool vae_tiling = false;
    std::string model_version;
};

// Global state
static std::unordered_map<int64_t, std::shared_ptr<SdHandle>> g_handles;
static std::mutex g_handles_mutex;
// All stable-diffusion.cpp operations that can touch global callbacks, backend
// registries, model loaders, or contexts share one process-wide gate. Cancellation
// intentionally bypasses this gate and signals the upstream atomic cancel mode.
static std::mutex g_operation_mutex;
static int64_t g_next_handle = 1;
static DiffusionLogFn g_log_fn = nullptr;
static bool g_backend_initialized = false;
static std::string g_backend_path;

// Step-progress tracking (updated by the C callback on the generation thread;
// read from the Kotlin polling coroutine on a different thread — atomics are sufficient).
static std::atomic<int> g_progress_step(0);
static std::atomic<int> g_progress_total(0);

static std::shared_ptr<SdHandle> lookup_handle(int64_t handle_id) {
    std::lock_guard<std::mutex> lock(g_handles_mutex);
    auto it = g_handles.find(handle_id);
    return it == g_handles.end() ? nullptr : it->second;
}

static bool signal_cancellation(const std::shared_ptr<SdHandle> &handle, sd_cancel_mode_t mode) {
    if (!handle) return false;
    std::lock_guard<std::mutex> lock(handle->cancellation_mutex);
    if (!handle->ctx || handle->closing) return false;
    sd_cancel_generation(handle->ctx, mode);
    return true;
}

static void free_images(sd_image_t *images, int count) {
    if (!images) return;
    for (int i = 0; i < count; ++i) {
        free(images[i].data);
    }
    free(images);
}

struct SdImagesOwner {
    SdImagesOwner(sd_image_t *owned_images, int owned_count)
        : images(owned_images), count(owned_count > 0 ? owned_count : 0) {}
    ~SdImagesOwner() { free_images(images, count); }
    sd_image_t *images;
    int count;
};

static void step_progress_callback(int step, int steps, float /*time*/, void* /*data*/) {
    g_progress_step.store(step,  std::memory_order_relaxed);
    g_progress_total.store(steps, std::memory_order_relaxed);
}

void diffusion_runner_get_step_progress(int* step, int* total) {
    *step  = g_progress_step.load(std::memory_order_relaxed);
    *total = g_progress_total.load(std::memory_order_relaxed);
}

// PNG encoding callback
struct PngWriteContext {
    std::vector<uint8_t> data;
};

static void png_write_callback(void *context, void *data, int size) {
    auto *ctx = static_cast<PngWriteContext *>(context);
    const uint8_t *bytes = static_cast<const uint8_t *>(data);
    ctx->data.insert(ctx->data.end(), bytes, bytes + size);
}

// Stable diffusion log callback
static void sd_log_callback(sd_log_level_t level, const char * /*text*/, void * /*data*/) {
    if (!g_log_fn || level < SD_LOG_WARN) return;

    DiffusionLogLevel log_level;
    switch (level) {
        case SD_LOG_DEBUG:
            log_level = DIFFUSION_LOG_DEBUG;
            break;
        case SD_LOG_INFO:
            log_level = DIFFUSION_LOG_INFO;
            break;
        case SD_LOG_WARN:
            log_level = DIFFUSION_LOG_WARN;
            break;
        case SD_LOG_ERROR:
            log_level = DIFFUSION_LOG_ERROR;
            break;
        default:
            log_level = DIFFUSION_LOG_INFO;
            break;
    }

    g_log_fn(log_level, "native engine diagnostic suppressed");
}

// ggml-level log callback. Vulkan backend errors (GGML_ABORT messages, "fatal error",
// pipeline lookup failures) come through here, NOT through sd.cpp's logger. Without this
// hook those messages disappear into stderr and we only see SIGABRT in logcat.
static void dr_ggml_log_callback(ggml_log_level level, const char * /*text*/, void * /*user_data*/) {
    if (!g_log_fn || level < GGML_LOG_LEVEL_WARN) return;
    DiffusionLogLevel log_level;
    switch (level) {
        case GGML_LOG_LEVEL_DEBUG: log_level = DIFFUSION_LOG_DEBUG; break;
        case GGML_LOG_LEVEL_INFO:  log_level = DIFFUSION_LOG_INFO;  break;
        case GGML_LOG_LEVEL_WARN:  log_level = DIFFUSION_LOG_WARN;  break;
        case GGML_LOG_LEVEL_ERROR: log_level = DIFFUSION_LOG_ERROR; break;
        case GGML_LOG_LEVEL_CONT:  log_level = DIFFUSION_LOG_DEBUG; break;
        default:                   log_level = DIFFUSION_LOG_INFO;  break;
    }
    g_log_fn(log_level, "native backend diagnostic suppressed");
}

static void discard_sd_log(sd_log_level_t, const char *, void *) {}
static void discard_ggml_log(ggml_log_level, const char *, void *) {}

class ScopedNativeLogSuppression {
public:
    ScopedNativeLogSuppression() {
        sd_set_log_callback(discard_sd_log, nullptr);
        ggml_log_set(discard_ggml_log, nullptr);
    }

    ~ScopedNativeLogSuppression() {
        sd_set_log_callback(sd_log_callback, nullptr);
        ggml_log_set(dr_ggml_log_callback, nullptr);
    }

    ScopedNativeLogSuppression(const ScopedNativeLogSuppression &) = delete;
    ScopedNativeLogSuppression &operator=(const ScopedNativeLogSuppression &) = delete;
};

// Small helper for one-shot formatted log lines from this file.
static void dr_logf(DiffusionLogLevel level, const char *fmt, ...) {
    if (!g_log_fn) return;
    char buf[2048];
    va_list ap;
    va_start(ap, fmt);
    vsnprintf(buf, sizeof(buf), fmt, ap);
    va_end(ap);
    g_log_fn(level, buf);
}

void diffusion_runner_core_set_logger(DiffusionLogFn fn) {
    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    g_log_fn = fn;
    sd_set_log_callback(sd_log_callback, nullptr);
    ggml_log_set(dr_ggml_log_callback, nullptr);
}

void diffusion_runner_core_init(const char *backend_path) {
    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    if (!backend_path || strnlen(backend_path, 4097) > 4096) {
        dr_logf(DIFFUSION_LOG_ERROR, "init: invalid backend directory");
        return;
    }
    const std::string requested_path(backend_path);
    if (g_backend_initialized) {
        if (requested_path != g_backend_path) {
            dr_logf(DIFFUSION_LOG_WARN, "init: backend directory change rejected");
        }
        return;
    }
    if (!requested_path.empty()) {
        ggml_backend_load_all_from_path(requested_path.c_str());
    }
    sd_set_log_callback(sd_log_callback, nullptr);
    ggml_log_set(dr_ggml_log_callback, nullptr);
    g_backend_path = requested_path;
    g_backend_initialized = true;
    dr_logf(DIFFUSION_LOG_INFO, "init: backend registry initialized");
}

static bool bounded_string(const char *value, size_t max_bytes, bool allow_empty) {
    if (!value) return false;
    const size_t length = strnlen(value, max_bytes + 1);
    return length <= max_bytes && (allow_empty || length > 0);
}

static bool safe_feature_label(const char *value, size_t max_bytes) {
    if (!bounded_string(value, max_bytes, false)) return false;
    for (size_t index = 0; value[index] != '\0'; ++index) {
        const unsigned char byte = static_cast<unsigned char>(value[index]);
        if (byte < 0x20 || byte > 0x7e) return false;
    }
    return true;
}

static bool valid_sd_weight_type(int value) {
    switch (value) {
        case -1:
        case SD_TYPE_F32:
        case SD_TYPE_F16:
        case SD_TYPE_Q4_0:
        case SD_TYPE_Q4_1:
        case SD_TYPE_Q5_0:
        case SD_TYPE_Q5_1:
        case SD_TYPE_Q8_0:
        case SD_TYPE_Q8_1:
        case SD_TYPE_Q2_K:
        case SD_TYPE_Q3_K:
        case SD_TYPE_Q4_K:
        case SD_TYPE_Q5_K:
        case SD_TYPE_Q6_K:
        case SD_TYPE_Q8_K:
        case SD_TYPE_IQ2_XXS:
        case SD_TYPE_IQ2_XS:
        case SD_TYPE_IQ3_XXS:
        case SD_TYPE_IQ1_S:
        case SD_TYPE_IQ4_NL:
        case SD_TYPE_IQ3_S:
        case SD_TYPE_IQ2_S:
        case SD_TYPE_IQ4_XS:
        case SD_TYPE_I8:
        case SD_TYPE_I16:
        case SD_TYPE_I32:
        case SD_TYPE_I64:
        case SD_TYPE_F64:
        case SD_TYPE_IQ1_M:
        case SD_TYPE_BF16:
        case SD_TYPE_TQ1_0:
        case SD_TYPE_TQ2_0:
        case SD_TYPE_MXFP4:
        case SD_TYPE_NVFP4:
        case SD_TYPE_Q1_0:
            return true;
        default:
            return false;
    }
}

static bool valid_model_config_native(const DiffusionModelConfig &config) {
    const std::array<const char *, 7> paths = {
        config.model_path, config.vae_path, config.llm_path, config.clip_l_path,
        config.clip_g_path, config.t5xxl_path, config.taesd_path,
    };
    if (!bounded_string(paths[0], 4096, false)) return false;
    for (size_t index = 1; index < paths.size(); ++index) {
        if (!bounded_string(paths[index], 4096, true)) return false;
    }
    return bounded_string(config.max_vram, 256, true) &&
        config.runtime_backend >= DIFFUSION_RUNTIME_BACKEND_CPU &&
        config.runtime_backend <= DIFFUSION_RUNTIME_BACKEND_CUDA &&
        (config.n_threads == -1 || (config.n_threads >= 1 && config.n_threads <= 1024)) &&
        valid_sd_weight_type(config.wtype) &&
        config.prediction >= -1 && config.prediction < PREDICTION_COUNT &&
        (!config.prefetch || config.segmented_compute) &&
        (!config.flow_shift_is_set || (std::isfinite(config.flow_shift) &&
            config.flow_shift >= -1000.0f && config.flow_shift <= 1000.0f));
}

static std::string lower_ascii(const char *value) {
    std::string result = value ? value : "";
    std::transform(result.begin(), result.end(), result.begin(), [](unsigned char byte) {
        return byte >= 'A' && byte <= 'Z'
            ? static_cast<char>(byte - 'A' + 'a')
            : static_cast<char>(byte);
    });
    return result;
}

static int diffusion_backend_kind(ggml_backend_dev_t device) {
    const ggml_backend_reg_t registry = device ? ggml_backend_dev_backend_reg(device) : nullptr;
    const std::string name = lower_ascii(registry ? ggml_backend_reg_name(registry) : nullptr);
    if (name == "cpu") return DIFFUSION_BACKEND_CPU;
    if (name == "cuda" || name == "rocm" || name == "hip") return DIFFUSION_BACKEND_CUDA;
    if (name == "metal") return DIFFUSION_BACKEND_METAL;
    if (name == "vulkan") return DIFFUSION_BACKEND_VULKAN;
    if (name == "opencl") return DIFFUSION_BACKEND_OPENCL;
    if (name == "sycl") return DIFFUSION_BACKEND_SYCL;
    return DIFFUSION_BACKEND_OTHER;
}

static int diffusion_backend_device_type(ggml_backend_dev_t device) {
    if (!device) return DIFFUSION_BACKEND_DEVICE_META;
    switch (ggml_backend_dev_type(device)) {
        case GGML_BACKEND_DEVICE_TYPE_CPU: return DIFFUSION_BACKEND_DEVICE_CPU;
        case GGML_BACKEND_DEVICE_TYPE_GPU: return DIFFUSION_BACKEND_DEVICE_DISCRETE_GPU;
        case GGML_BACKEND_DEVICE_TYPE_IGPU: return DIFFUSION_BACKEND_DEVICE_INTEGRATED_GPU;
        case GGML_BACKEND_DEVICE_TYPE_ACCEL: return DIFFUSION_BACKEND_DEVICE_ACCELERATOR;
        case GGML_BACKEND_DEVICE_TYPE_META: return DIFFUSION_BACKEND_DEVICE_META;
    }
    return DIFFUSION_BACKEND_DEVICE_META;
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
    std::fill(std::begin(words), std::end(words), int64_t{0});
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

static bool checked_size_to_i64(size_t value, int64_t &destination) {
    if (value > static_cast<size_t>(std::numeric_limits<int64_t>::max())) return false;
    destination = static_cast<int64_t>(value);
    return true;
}

static int architecture_code(SDVersion version) {
    if (sd_version_is_sd1(version)) return DIFFUSION_ARCH_SD1;
    if (sd_version_is_sd2(version)) return DIFFUSION_ARCH_SD2;
    if (sd_version_is_sdxl(version)) return DIFFUSION_ARCH_SDXL;
    if (sd_version_is_sd3(version)) return DIFFUSION_ARCH_SD3;
    if (sd_version_is_flux(version) || sd_version_is_flux2(version)) return DIFFUSION_ARCH_FLUX;
    if (sd_version_is_wan(version)) return DIFFUSION_ARCH_WAN;
    switch (version) {
        case VERSION_SVD:
        case VERSION_LINGBOT_VIDEO:
        case VERSION_HUNYUAN_VIDEO:
        case VERSION_LTXAV:
            return DIFFUSION_ARCH_OTHER_VIDEO;
        case VERSION_COUNT:
            return DIFFUSION_ARCH_UNKNOWN;
        default:
            return DIFFUSION_ARCH_OTHER_IMAGE;
    }
}

static int quantization_code(ggml_type type) {
    switch (type) {
        case GGML_TYPE_F32: return DIFFUSION_QUANT_F32;
        case GGML_TYPE_F16: return DIFFUSION_QUANT_F16;
        case GGML_TYPE_BF16: return DIFFUSION_QUANT_BF16;
        case GGML_TYPE_Q4_0: return DIFFUSION_QUANT_Q4_0;
        case GGML_TYPE_Q4_1: return DIFFUSION_QUANT_Q4_1;
        case GGML_TYPE_Q5_0: return DIFFUSION_QUANT_Q5_0;
        case GGML_TYPE_Q5_1: return DIFFUSION_QUANT_Q5_1;
        case GGML_TYPE_Q8_0: return DIFFUSION_QUANT_Q8_0;
        case GGML_TYPE_Q2_K: return DIFFUSION_QUANT_Q2_K;
        case GGML_TYPE_Q3_K: return DIFFUSION_QUANT_Q3_K;
        case GGML_TYPE_Q4_K: return DIFFUSION_QUANT_Q4_K;
        case GGML_TYPE_Q5_K: return DIFFUSION_QUANT_Q5_K;
        case GGML_TYPE_Q6_K: return DIFFUSION_QUANT_Q6_K;
        case GGML_TYPE_COUNT: return DIFFUSION_QUANT_UNKNOWN;
        default: return DIFFUSION_QUANT_OTHER;
    }
}

static int dominant_quantization_code(ModelLoader &loader) {
    const auto stats = loader.get_wtype_stat();
    if (stats.empty()) return DIFFUSION_QUANT_UNKNOWN;
    uint32_t largest = 0;
    ggml_type dominant = GGML_TYPE_COUNT;
    bool tie = false;
    for (const auto &[type, count] : stats) {
        if (count > largest) {
            largest = count;
            dominant = type;
            tie = false;
        } else if (count == largest && type != dominant) {
            tie = true;
        }
    }
    return tie ? DIFFUSION_QUANT_MIXED : quantization_code(dominant);
}

struct DeclaredComponent {
    int role;
    const char *path;
    const char *prefix;
    const char *module;
};

static bool has_split_components(const DiffusionModelConfig &config) {
    return config.vae_path[0] != '\0' || config.llm_path[0] != '\0' ||
        config.clip_l_path[0] != '\0' || config.clip_g_path[0] != '\0' ||
        config.t5xxl_path[0] != '\0';
}

static std::vector<DeclaredComponent> declared_components(const DiffusionModelConfig &config) {
    std::vector<DeclaredComponent> components;
    const bool split = has_split_components(config);
    components.push_back({
        split ? DIFFUSION_COMPONENT_DIFFUSION_MODEL : DIFFUSION_COMPONENT_MODEL_BUNDLE,
        config.model_path,
        split ? "model.diffusion_model." : "",
        split ? "diffusion" : "",
    });
    if (config.vae_path[0] != '\0') components.push_back({DIFFUSION_COMPONENT_VAE, config.vae_path, "vae.", "vae"});
    if (config.llm_path[0] != '\0') components.push_back({DIFFUSION_COMPONENT_LLM, config.llm_path, "text_encoders.llm.", "te"});
    if (config.clip_l_path[0] != '\0') components.push_back({DIFFUSION_COMPONENT_CLIP_L, config.clip_l_path, "clip_l.", "te"});
    if (config.clip_g_path[0] != '\0') components.push_back({DIFFUSION_COMPONENT_CLIP_G, config.clip_g_path, "clip_g.", "te"});
    if (config.t5xxl_path[0] != '\0') components.push_back({DIFFUSION_COMPONENT_T5XXL, config.t5xxl_path, "text_encoders.t5xxl.transformer.", "te"});
    if (config.taesd_path[0] != '\0') components.push_back({DIFFUSION_COMPONENT_TAESD, config.taesd_path, "tae.", "vae"});
    return components;
}

static bool initialize_combined_loader(
        const std::vector<DeclaredComponent> &components,
        ModelLoader &loader) {
    for (const DeclaredComponent &component : components) {
        if (!loader.init_from_file(component.path, component.prefix)) return false;
    }
    loader.convert_tensors_name();
    return true;
}

static bool collect_preflight_tensor_evidence(
        ModelLoader &loader,
        ggml_type override_type,
        std::vector<caraml::diffusion::PreflightTensorEvidence> &tensors) {
    tensors.clear();
    tensors.reserve(loader.get_tensor_storage_map().size());
    for (const auto &[name, source_storage] : loader.get_tensor_storage_map()) {
        TensorStorage storage = source_storage;
        if (override_type != GGML_TYPE_COUNT &&
            loader.tensor_should_be_converted(storage, override_type)) {
            storage.type = override_type;
        } else if (storage.expected_type != GGML_TYPE_COUNT &&
            storage.expected_type != storage.type) {
            storage.type = storage.expected_type;
        }
        const uint64_t tensor_bytes = storage.nbytes();
        if (tensor_bytes > static_cast<uint64_t>(std::numeric_limits<int64_t>::max() - 64)) {
            return false;
        }
        tensors.push_back({name, static_cast<int64_t>(tensor_bytes) + 64});
    }
    return !tensors.empty();
}

static std::string assignment_value(const std::string &spec, const std::string &module) {
    std::string default_value;
    std::string exact_value;
    size_t start = 0;
    while (start <= spec.size()) {
        const size_t end = spec.find(',', start);
        const std::string token = spec.substr(start, end == std::string::npos ? std::string::npos : end - start);
        const size_t equals = token.find('=');
        if (equals == std::string::npos) {
            if (!token.empty()) default_value = token;
        } else {
            const std::string key = lower_ascii(token.substr(0, equals).c_str());
            const std::string value = token.substr(equals + 1);
            if (key == "*" || key == "all" || key == "default") default_value = value;
            if (key == module) exact_value = value;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return exact_value.empty() ? default_value : exact_value;
}

static void append_assignment(std::string &spec, const char *module, const char *value) {
    if (!spec.empty()) spec += ',';
    spec += module;
    spec += '=';
    spec += value;
}

struct ResolvedModelPlan {
    std::string runtime_spec;
    std::string params_spec;
    sd::ggml_graph_cut::MaxVramAssignment budgets;
    bool valid = false;
};

static int backend_kind_for_assignment(const std::string &assignment) {
    const std::string resolved = caraml::diffusion::canonical_backend_name(
        sd_backend_resolve_name(assignment));
    if (resolved.empty()) return DIFFUSION_BACKEND_OTHER;
    const size_t count = ggml_backend_dev_count();
    for (size_t index = 0; index < count; ++index) {
        ggml_backend_dev_t device = ggml_backend_dev_get(index);
        if (device && resolved == caraml::diffusion::canonical_backend_name(
                ggml_backend_dev_name(device))) {
            return diffusion_backend_kind(device);
        }
    }
    return DIFFUSION_BACKEND_OTHER;
}

static ResolvedModelPlan resolve_model_plan(
        const DiffusionModelConfig &config,
        ModelLoader *initialized_loader) {
    ResolvedModelPlan plan;
    plan.budgets.reset(0.0f);
    std::string ignored_error;
    if (!plan.budgets.parse(config.max_vram, &ignored_error) ||
        !plan.budgets.canonicalize_backend_keys(&ignored_error)) {
        return plan;
    }

    if (config.auto_fit) {
        if (!initialized_loader) return plan;
        const ggml_type override_type = config.wtype >= 0
            ? static_cast<ggml_type>(config.wtype)
            : GGML_TYPE_COUNT;
        if (!sd::backend_fit::derive_backend_specs(
                *initialized_loader,
                override_type,
                plan.budgets,
                plan.runtime_spec,
                plan.params_spec)) {
            return plan;
        }
    } else if (!caraml::diffusion::explicit_runtime_backend_spec(
            config.runtime_backend,
            plan.runtime_spec)) {
        return plan;
    }

    const auto resolved_backend_kind = [](const std::string &assignment) {
        return backend_kind_for_assignment(assignment);
    };
    const bool force_clip_cpu = config.keep_clip_on_cpu ||
        caraml::diffusion::component_requires_vulkan_cpu_safety(
            config,
            plan.runtime_spec,
            "te",
            resolved_backend_kind);
    const bool force_vae_cpu = config.keep_vae_on_cpu ||
        caraml::diffusion::component_requires_vulkan_cpu_safety(
            config,
            plan.runtime_spec,
            "vae",
            resolved_backend_kind);
    if (force_clip_cpu) append_assignment(plan.runtime_spec, "te", "cpu");
    if (force_vae_cpu) append_assignment(plan.runtime_spec, "vae", "cpu");
    if (config.offload_to_cpu) {
        plan.params_spec = "*=cpu,diffusion=cpu,te=cpu,vae=cpu";
    }
    plan.valid = true;
    return plan;
}

static bool resolve_and_apply_model_plan(
        const DiffusionModelConfig &config,
        ModelLoader *initialized_loader,
        ResolvedModelPlan &plan,
        sd_ctx_params_t &params) {
    plan = resolve_model_plan(config, initialized_loader);
    if (!plan.valid) return false;
    if (!plan.runtime_spec.empty()) params.backend = plan.runtime_spec.c_str();
    if (!plan.params_spec.empty()) params.params_backend = plan.params_spec.c_str();
    params.max_vram = config.max_vram[0] == '\0' ? nullptr : config.max_vram;
    params.disable_segmented_compute = !config.segmented_compute;
    params.disable_prefetch = !config.prefetch;
    // The exact auto-fit result was resolved above. Do not let new_sd_ctx derive
    // a second placement from a later memory/device snapshot.
    params.auto_fit = false;
    return true;
}

#ifdef CARAML_DIFFUSION_NATIVE_TESTING
bool diffusion_runner_core_capture_context_params_for_test(
        const DiffusionModelConfig &config,
        DiffusionContextParamsForTest &captured) {
    if (config.auto_fit) return false;
    sd_ctx_params_t params = {};
    sd_ctx_params_init(&params);
    ResolvedModelPlan plan;
    if (!resolve_and_apply_model_plan(config, nullptr, plan, params) || !params.backend) {
        return false;
    }
    captured.backend = params.backend;
    captured.params_backend = params.params_backend ? params.params_backend : "";
    captured.max_vram = params.max_vram ? params.max_vram : "";
    captured.segmented_compute = !params.disable_segmented_compute;
    captured.prefetch = !params.disable_prefetch;
    captured.auto_fit = params.auto_fit;
    return !captured.backend.empty();
}
#endif

static int64_t backend_mask_for_assignment(
        const std::string &value,
        const std::vector<caraml::diffusion::PreflightBackendCandidate> &candidates,
        const caraml::diffusion::PreflightBackendBudgetCollection &selected_backends) {
    if (value.empty() || lower_ascii(value.c_str()) == "cpu") return 0;
    int64_t mask = 0;
    size_t start = 0;
    while (start <= value.size()) {
        const size_t end = value.find('&', start);
        const std::string requested = lower_ascii(value.substr(start, end == std::string::npos ? std::string::npos : end - start).c_str());
        const std::string resolved = caraml::diffusion::canonical_backend_name(
            sd_backend_resolve_name(requested));
        bool matched = false;
        for (size_t report_index = 0; report_index < selected_backends.backends.size(); ++report_index) {
            const size_t candidate_index = selected_backends.backends[report_index].candidate_index;
            if (candidate_index < candidates.size() &&
                resolved == candidates[candidate_index].canonical_name) {
                mask |= int64_t{1} << report_index;
                matched = true;
                break;
            }
        }
        if (!matched) return 0;
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return mask;
}

static int runtime_placement_for(const std::string &value, int64_t backend_mask) {
    if (value.empty()) return DIFFUSION_RUNTIME_DEFAULT;
    if (lower_ascii(value.c_str()) == "cpu") return DIFFUSION_RUNTIME_CPU;
    return (backend_mask & (backend_mask - 1)) == 0
        ? DIFFUSION_RUNTIME_GPU
        : DIFFUSION_RUNTIME_SPLIT_GPU;
}

static int parameter_placement_for(const std::string &value) {
    const std::string normalized = lower_ascii(value.c_str());
    if (normalized == "cpu") return DIFFUSION_PARAMS_CPU;
    if (normalized == "disk") return DIFFUSION_PARAMS_DISK;
    return DIFFUSION_PARAMS_DEFAULT;
}

int64_t diffusion_runner_core_load_model(const DiffusionModelConfig &config) {
    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    if (!g_backend_initialized || !valid_model_config_native(config)) {
        dr_logf(DIFFUSION_LOG_ERROR, "load_model: invalid state or configuration");
        return 0;
    }
    dr_logf(DIFFUSION_LOG_INFO,
            "load_model: ENTER model=%d vae=%d clip_l=%d clip_g=%d t5xxl=%d llm=%d "
            "taesd=%d offload=%d keep_clip_cpu=%d keep_vae_cpu=%d flash_attn=%d mmap=%d "
            "conv_direct=%d free_immediate=%d wtype=%d prediction=%d flow_shift=%.4f set=%d "
            "vae_tiling=%d n_threads=%d",
            config.model_path && config.model_path[0] != '\0',
            config.vae_path && config.vae_path[0] != '\0',
            config.clip_l_path && config.clip_l_path[0] != '\0',
            config.clip_g_path && config.clip_g_path[0] != '\0',
            config.t5xxl_path && config.t5xxl_path[0] != '\0',
            config.llm_path && config.llm_path[0] != '\0',
            config.taesd_path && config.taesd_path[0] != '\0',
            (int)config.offload_to_cpu, (int)config.keep_clip_on_cpu, (int)config.keep_vae_on_cpu,
            (int)config.diffusion_flash_attn, (int)config.enable_mmap,
            (int)config.diffusion_conv_direct, (int)config.free_params_immediately,
            config.wtype, config.prediction, config.flow_shift, (int)config.flow_shift_is_set,
            (int)config.vae_tiling, config.n_threads);

    ScopedNativeLogSuppression suppress_upstream_diagnostics;
    ModelLoader fit_loader;
    if (config.auto_fit) {
        const auto components = declared_components(config);
        if (!initialize_combined_loader(components, fit_loader) ||
            fit_loader.get_sd_version() == VERSION_COUNT) {
            dr_logf(DIFFUSION_LOG_ERROR, "load_model: auto-fit metadata unavailable");
            return 0;
        }
    }
    sd_ctx_params_t params = {};
    sd_ctx_params_init(&params);
    ResolvedModelPlan resolved_plan;
    if (!resolve_and_apply_model_plan(
            config,
            config.auto_fit ? &fit_loader : nullptr,
            resolved_plan,
            params)) {
        dr_logf(DIFFUSION_LOG_ERROR, "load_model: backend placement could not be resolved");
        return 0;
    }

#ifdef SD_USE_VULKAN
    {
        int vk_count = ggml_backend_vk_get_device_count();
        dr_logf(DIFFUSION_LOG_INFO, "load_model: SD_USE_VULKAN defined; vulkan_device_count=%d", vk_count);
        for (int i = 0; i < vk_count; i++) {
            char desc[256] = {0};
            ggml_backend_vk_get_device_description(i, desc, sizeof(desc));
            size_t free_mem = 0, total_mem = 0;
            ggml_backend_vk_get_device_memory(i, &free_mem, &total_mem);
            dr_logf(DIFFUSION_LOG_INFO,
                    "load_model: vulkan device[%d]='%s' free=%.1fMB total=%.1fMB",
                    i, desc,
                    free_mem  / (1024.0 * 1024.0),
                    total_mem / (1024.0 * 1024.0));
        }
    }
#else
    dr_logf(DIFFUSION_LOG_WARN,
            "load_model: diffusion runner was built without SD_USE_VULKAN");
#endif

    // Determine model path - if any of the component paths are set, use diffusion_model_path
    if (strlen(config.vae_path) > 0 || strlen(config.llm_path) > 0 ||
            strlen(config.clip_l_path) > 0 || strlen(config.clip_g_path) > 0 ||
            strlen(config.t5xxl_path) > 0) {
        params.diffusion_model_path = config.model_path;
        if (strlen(config.vae_path) > 0) params.vae_path = config.vae_path;
        if (strlen(config.llm_path) > 0) params.llm_path = config.llm_path;
        if (strlen(config.clip_l_path) > 0) params.clip_l_path = config.clip_l_path;
        if (strlen(config.clip_g_path) > 0) params.clip_g_path = config.clip_g_path;
        if (strlen(config.t5xxl_path) > 0) params.t5xxl_path = config.t5xxl_path;
        dr_logf(DIFFUSION_LOG_INFO, "load_model: using split-component path layout (diffusion_model_path)");
    } else {
        params.model_path = config.model_path;
        dr_logf(DIFFUSION_LOG_INFO, "load_model: using single-file path layout (model_path)");
    }

    // sd.cpp's old offload_params_to_cpu / keep_clip_on_cpu / keep_vae_on_cpu / free_params_immediately
    // bools were replaced by unified --backend / --params-backend module assignment strings
    // (see libraries/stable-diffusion.cpp/docs/backend.md, "Compatibility flags"). free_params_immediately
    // has no direct successor; params_backend=disk covers "reload+release" but that's a heavier
    // behavior change than "free after first use" so it is intentionally not auto-mapped here.
    // resolve_model_plan already encoded caller overrides and Vulkan's CLIP/VAE CPU
    // safety into this exact assignment. An unrelated registered Vulkan device does
    // not mutate an explicit CUDA or Metal plan.
    params.diffusion_flash_attn = config.diffusion_flash_attn;
    params.enable_mmap = config.enable_mmap;
    params.diffusion_conv_direct = config.diffusion_conv_direct;

    if (config.wtype >= 0) {
        params.wtype = static_cast<sd_type_t>(config.wtype);
        dr_logf(DIFFUSION_LOG_INFO, "load_model: caller wtype override = %d", config.wtype);
    }
    if (config.n_threads > 0) {
        params.n_threads = config.n_threads;
    } else if (config.n_threads == -1) {
        params.n_threads = sd_get_num_physical_cores();
    }
    dr_logf(DIFFUSION_LOG_INFO, "load_model: resolved n_threads=%d", params.n_threads);

    // Explicit prediction type overrides auto-detection.
    // Critically: setting this skips is_using_v_parameterization_for_sd2() which
    // runs a test UNet compute — that test can abort() on Vulkan for some models.
    if (config.prediction >= 0 && config.prediction < PREDICTION_COUNT) {
        params.prediction = static_cast<prediction_t>(config.prediction);
        dr_logf(DIFFUSION_LOG_INFO, "load_model: explicit prediction=%d (skips v_param probe)", config.prediction);
    } else {
        dr_logf(DIFFUSION_LOG_WARN,
                "load_model: prediction=auto — sd.cpp will run is_using_v_parameterization_for_sd2() "
                "probe. This can crash on Vulkan for some SD2 models.");
    }

    // TAESD: tiny autoencoder — replaces full VAE decode for fast preview.
    if (config.taesd_path && strlen(config.taesd_path) > 0) {
        params.taesd_path = config.taesd_path;
    }

    dr_logf(DIFFUSION_LOG_INFO,
            "load_model: → calling new_sd_ctx(wtype=%d, backend='%s', params_backend='%s', "
            "flash_attn=%d, prediction=%d)",
            (int)params.wtype, params.backend ? params.backend : "",
            params.params_backend ? params.params_backend : "",
            (int)params.diffusion_flash_attn, (int)params.prediction);

    // Create context
    sd_ctx_t *raw_context = new_sd_ctx(&params);
    if (!raw_context) {
        dr_logf(DIFFUSION_LOG_ERROR, "load_model: new_sd_ctx returned NULL");
        return 0;  // Failed to load
    }
    const int64_t handle_id = caraml::publish_owned_context(
        raw_context,
        [](sd_ctx_t *context) { free_sd_ctx(context); },
        [&config](sd_ctx_t *context) {
            auto handle = std::make_shared<SdHandle>();
            handle->ctx = context;
            handle->flow_shift = config.flow_shift;
            handle->flow_shift_is_set = config.flow_shift_is_set;
            handle->vae_tiling = config.vae_tiling;
            const char *model_version = sd_get_model_version_name(context);
            if (safe_feature_label(model_version, 72)) {
                handle->model_version = model_version;
            }
            return handle;
        },
        [](const std::shared_ptr<SdHandle> &handle) {
            std::lock_guard<std::mutex> lock(g_handles_mutex);
            if (g_next_handle <= 0 || g_next_handle == std::numeric_limits<int64_t>::max()) {
                throw std::overflow_error("native handle space exhausted");
            }
            const int64_t id = g_next_handle;
            if (!g_handles.emplace(id, handle).second) {
                throw std::runtime_error("native handle publication failed");
            }
            ++g_next_handle;
            return id;
        });

    dr_logf(DIFFUSION_LOG_INFO, "load_model: new_sd_ctx OK");
    dr_logf(DIFFUSION_LOG_INFO, "load_model: handle_id=%lld", (long long)handle_id);
    return handle_id;
}

PngResult diffusion_runner_core_txt2img(int64_t handle_id, const ImageGenConfig &config) {
    PngResult result = {nullptr, 0};

    dr_logf(DIFFUSION_LOG_INFO,
            "txt2img: ENTER handle=%lld dims=%dx%d steps=%d cfg=%.2f seed=%lld sampler=%d "
            "vae_tile=%d lora_n=%d prompt_len=%zu neg_len=%zu",
            (long long)handle_id, config.width, config.height, config.steps, config.cfg_scale,
            (long long)config.seed, config.sample_method, (int)config.vae_tiling,
            config.lora_count,
            config.prompt ? strlen(config.prompt) : 0,
            config.negative_prompt ? strlen(config.negative_prompt) : 0);

    auto handle = lookup_handle(handle_id);
    if (!handle) {
        dr_logf(DIFFUSION_LOG_ERROR, "txt2img: invalid handle %lld", (long long)handle_id);
        return result;
    }

    std::lock_guard<std::mutex> global_operation_lock(g_operation_mutex);
    std::lock_guard<std::mutex> operation_lock(handle->operation_mutex);
    sd_ctx_t *ctx = nullptr;
    {
        std::lock_guard<std::mutex> cancel_lock(handle->cancellation_mutex);
        ctx = handle->ctx;
        if (ctx && !handle->closing) sd_cancel_generation(ctx, SD_CANCEL_RESET);
        else ctx = nullptr;
    }
    if (!ctx) return result;

    // Set up generation parameters
    sd_img_gen_params_t gen_params = {};
    sd_img_gen_params_init(&gen_params);

    gen_params.prompt = config.prompt;
    gen_params.negative_prompt = config.negative_prompt;
    gen_params.width = config.width;
    gen_params.height = config.height;
    gen_params.seed = config.seed;
    gen_params.batch_count = 1;

    // Set up sampling parameters
    sd_sample_params_t sample_params = {};
    sd_sample_params_init(&sample_params);
    sample_params.sample_steps = config.steps;
    sample_params.guidance.txt_cfg = config.cfg_scale;
    sample_params.sample_method = static_cast<sample_method_t>(config.sample_method);
    if (config.flow_shift_is_set) {
        sample_params.flow_shift = config.flow_shift;
    } else if (handle->flow_shift_is_set) {
        sample_params.flow_shift = handle->flow_shift;
    }

    gen_params.sample_params = sample_params;

    // VAE tiling: enable when requested at gen-time or inherited from model config.
    if (config.vae_tiling || handle->vae_tiling) {
        gen_params.vae_tiling_params.enabled = true;
        // tile_size_x/y = 0 → library auto-picks via first_stage_model->get_tile_sizes()
    }

    // Set up LoRA if provided
    std::vector<sd_lora_t> lora_configs;
    if (config.lora_count > 0 && config.lora_paths && config.lora_strengths) {
        lora_configs.reserve(config.lora_count);
        for (int i = 0; i < config.lora_count; i++) {
            sd_lora_t lora = {};
            lora.path = config.lora_paths[i];
            lora.multiplier = config.lora_strengths[i];
            lora_configs.push_back(lora);
        }
        gen_params.loras = lora_configs.data();
        gen_params.lora_count = config.lora_count;
    }

    // Register step-progress callback. Reset to (0,0) — the callback will fill in the real
    // total when the sampling loop actually starts. Pre-sampling work (text encoding, latent
    // prep) doesn't fire the callback, so leaving total=0 lets the UI show "Preparing…" rather
    // than misleadingly claiming "Step 0 / 20".
    g_progress_step.store(0, std::memory_order_relaxed);
    g_progress_total.store(0, std::memory_order_relaxed);
    sd_set_progress_callback(step_progress_callback, nullptr);

    // Generate image
    dr_logf(DIFFUSION_LOG_INFO, "txt2img: → calling generate_image (this is where Vulkan CLIP crashes if mis-configured)");
    sd_image_t *images = nullptr;
    int num_images_out = 0;
    bool gen_ok = generate_image(ctx, &gen_params, &images, &num_images_out);
    SdImagesOwner images_owner(images, num_images_out);
    if (!gen_ok || !images || num_images_out <= 0 || !images[0].data) {
        dr_logf(DIFFUSION_LOG_ERROR, "txt2img: generate_image returned NULL or empty (success path failed quietly)");
        return result;
    }
    dr_logf(DIFFUSION_LOG_INFO, "txt2img: generate_image OK %dx%d ch=%d",
            images[0].width, images[0].height, images[0].channel);

    // Convert to PNG
    PngWriteContext png_ctx;
    int success = stbi_write_png_to_func(png_write_callback, &png_ctx,
            images[0].width, images[0].height,
            images[0].channel, images[0].data,
            images[0].width * images[0].channel);

    // Free the original image data
    if (success && !png_ctx.data.empty()) {
        // Allocate result data
        result.data = static_cast<uint8_t *>(malloc(png_ctx.data.size()));
        if (result.data) {
            memcpy(result.data, png_ctx.data.data(), png_ctx.data.size());
            result.size = static_cast<int>(png_ctx.data.size());
        }
    }

    return result;
}

VideoGenResultNative diffusion_runner_core_video_gen(int64_t handle_id, const VideoGenConfig &config) {
    VideoGenResultNative output;

    auto handle = lookup_handle(handle_id);
    if (!handle) {
        return output;
    }

    std::lock_guard<std::mutex> global_operation_lock(g_operation_mutex);
    std::lock_guard<std::mutex> operation_lock(handle->operation_mutex);
    sd_ctx_t *ctx = nullptr;
    {
        std::lock_guard<std::mutex> cancel_lock(handle->cancellation_mutex);
        ctx = handle->ctx;
        if (ctx && !handle->closing) sd_cancel_generation(ctx, SD_CANCEL_RESET);
        else ctx = nullptr;
    }
    if (!ctx) return output;

    // Set up generation parameters
    sd_vid_gen_params_t gen_params = {};
    sd_vid_gen_params_init(&gen_params);

    gen_params.prompt = config.prompt;
    gen_params.negative_prompt = config.negative_prompt;
    gen_params.width = config.width;
    gen_params.height = config.height;
    gen_params.video_frames = config.video_frames;
    gen_params.seed = config.seed;

    // Set up sampling parameters
    sd_sample_params_t sample_params = {};
    sd_sample_params_init(&sample_params);
    sample_params.sample_steps = config.steps;
    sample_params.guidance.txt_cfg = config.cfg_scale;
    sample_params.sample_method = static_cast<sample_method_t>(config.sample_method);
    if (config.flow_shift_is_set) {
        sample_params.flow_shift = config.flow_shift;
    } else if (handle->flow_shift_is_set) {
        sample_params.flow_shift = handle->flow_shift;
    }

    gen_params.sample_params = sample_params;

    // VAE tiling: enable when requested at gen-time or inherited from model config.
    if (config.vae_tiling || handle->vae_tiling) {
        gen_params.vae_tiling_params.enabled = true;
    }

    // Set up LoRA if provided
    std::vector<sd_lora_t> lora_configs;
    if (config.lora_count > 0 && config.lora_paths && config.lora_strengths) {
        lora_configs.reserve(config.lora_count);
        for (int i = 0; i < config.lora_count; i++) {
            sd_lora_t lora = {};
            lora.path = config.lora_paths[i];
            lora.multiplier = config.lora_strengths[i];
            lora_configs.push_back(lora);
        }
        gen_params.loras = lora_configs.data();
        gen_params.lora_count = config.lora_count;
    }

    // Register step-progress callback. Reset to (0,0); the sampler fills in the real total
    // once it starts. See txt2img comment above.
    g_progress_step.store(0, std::memory_order_relaxed);
    g_progress_total.store(0, std::memory_order_relaxed);
    sd_set_progress_callback(step_progress_callback, nullptr);

    // Generate video frames
    int num_frames_out = 0;
    int fps_out = 0;
    sd_image_t *images = nullptr;
    sd_audio_t *audio_out = nullptr;
    bool gen_ok = generate_video(
        ctx, &gen_params, &images, &num_frames_out, &audio_out, &fps_out);
    SdImagesOwner images_owner(images, num_frames_out);
    if (audio_out) {
        free_sd_audio(audio_out);
    }
    if (!gen_ok || !images || num_frames_out <= 0) {
        return output;
    }

    // Convert each frame to PNG
    output.frames.reserve(num_frames_out);
    for (int i = 0; i < num_frames_out; i++) {
        PngResult result = {nullptr, 0};

        if (images[i].data) {
            PngWriteContext png_ctx;
            int success = stbi_write_png_to_func(png_write_callback, &png_ctx,
                    images[i].width, images[i].height,
                    images[i].channel, images[i].data,
                    images[i].width * images[i].channel);

            if (success && !png_ctx.data.empty()) {
                result.data = static_cast<uint8_t *>(malloc(png_ctx.data.size()));
                if (result.data) {
                    memcpy(result.data, png_ctx.data.data(), png_ctx.data.size());
                    result.size = static_cast<int>(png_ctx.data.size());
                }
            }
        }

        output.frames.push_back(result);
    }

    if (!output.frames.empty() && fps_out > 0) output.effective_fps = fps_out;
    return output;
}

bool diffusion_runner_core_cancel_generation(int64_t handle_id) {
    return signal_cancellation(lookup_handle(handle_id), SD_CANCEL_ALL);
}

void diffusion_runner_core_release(int64_t handle_id) {
    std::shared_ptr<SdHandle> handle;
    {
        std::lock_guard<std::mutex> lock(g_handles_mutex);
        auto it = g_handles.find(handle_id);
        if (it == g_handles.end()) return;
        handle = it->second;
        g_handles.erase(it);
    }

    // Wake a potentially long operation, then wait until it no longer uses the context.
    {
        std::lock_guard<std::mutex> cancel_lock(handle->cancellation_mutex);
        handle->closing = true;
        if (handle->ctx) sd_cancel_generation(handle->ctx, SD_CANCEL_ALL);
    }
    std::lock_guard<std::mutex> global_operation_lock(g_operation_mutex);
    std::lock_guard<std::mutex> operation_lock(handle->operation_mutex);
    sd_ctx_t *ctx = nullptr;
    {
        std::lock_guard<std::mutex> cancel_lock(handle->cancellation_mutex);
        ctx = handle->ctx;
        handle->ctx = nullptr;
    }
    if (ctx) free_sd_ctx(ctx);
}

DiffusionBackendCapabilitiesNative diffusion_runner_core_backend_capabilities() {
    DiffusionBackendCapabilitiesNative result;
    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    if (!g_backend_initialized) return result;
    ScopedNativeLogSuppression suppress_upstream_diagnostics;
    try {
        const size_t count = ggml_backend_dev_count();
        if (count == 0 || count > static_cast<size_t>(DIFFUSION_BACKEND_MAX_DEVICES)) {
            return result;
        }
        for (size_t index = 0; index < count; ++index) {
            ggml_backend_dev_t device = ggml_backend_dev_get(index);
            if (!device) return DiffusionBackendCapabilitiesNative{};
            ggml_backend_dev_props properties{};
            ggml_backend_dev_get_props(device, &properties);
            DiffusionBackendCapabilityNative &destination = result.devices[index];
            destination.kind = diffusion_backend_kind(device);
            destination.device_type = diffusion_backend_device_type(device);
            if (!encode_device_identity(
                    device,
                    destination.device_identity_length,
                    destination.device_identity_words)) {
                return DiffusionBackendCapabilitiesNative{};
            }
            if (properties.memory_total > 0) {
                if (!checked_size_to_i64(properties.memory_free, destination.free_bytes) ||
                    !checked_size_to_i64(properties.memory_total, destination.total_bytes) ||
                    destination.free_bytes > destination.total_bytes) {
                    return DiffusionBackendCapabilitiesNative{};
                }
            }
        }
        result.count = static_cast<int>(count);
    } catch (...) {
        result.count = -1;
    }
    return result;
}

DiffusionPreflightResultNative diffusion_runner_core_preflight(const DiffusionModelConfig &config) {
    DiffusionPreflightResultNative result;
    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    if (!g_backend_initialized || !valid_model_config_native(config)) {
        result.status = DIFFUSION_PREFLIGHT_INVALID;
        return result;
    }
    ScopedNativeLogSuppression suppress_upstream_diagnostics;
    try {
        const std::vector<DeclaredComponent> components = declared_components(config);
        if (components.empty() || components.size() > DIFFUSION_PREFLIGHT_MAX_COMPONENTS) {
            result.status = DIFFUSION_PREFLIGHT_INVALID;
            return result;
        }

        ModelLoader combined_loader;
        if (!initialize_combined_loader(components, combined_loader)) {
            result.status = DIFFUSION_PREFLIGHT_INVALID;
            return result;
        }
        const SDVersion version = combined_loader.get_sd_version();
        if (version == VERSION_COUNT) {
            result.status = DIFFUSION_PREFLIGHT_INVALID;
            return result;
        }

        ResolvedModelPlan resolved_plan = resolve_model_plan(config, &combined_loader);
        if (!resolved_plan.valid) {
            result.status = DIFFUSION_PREFLIGHT_INVALID;
            return result;
        }

        const size_t registry_backend_count = ggml_backend_dev_count();
        if (registry_backend_count == 0 || registry_backend_count > DIFFUSION_PREFLIGHT_MAX_BACKENDS) {
            result.status = DIFFUSION_PREFLIGHT_UNAVAILABLE;
            return result;
        }
        std::vector<caraml::diffusion::PreflightBackendCandidate> backend_candidates;
        backend_candidates.reserve(registry_backend_count);
        for (size_t index = 0; index < registry_backend_count; ++index) {
            ggml_backend_dev_t device = ggml_backend_dev_get(index);
            if (!device) {
                result.status = DIFFUSION_PREFLIGHT_UNAVAILABLE;
                return result;
            }
            backend_candidates.push_back({
                caraml::diffusion::canonical_backend_name(ggml_backend_dev_name(device)),
                index,
            });
        }
        const auto selected_backends = caraml::diffusion::collect_selected_backend_budgets(
            resolved_plan.runtime_spec,
            resolved_plan.params_spec,
            backend_candidates,
            [](const std::string &requested) { return sd_backend_resolve_name(requested); },
            [&resolved_plan](
                    const caraml::diffusion::PreflightBackendCandidate &candidate,
                    int64_t &budget_bytes) {
                ggml_backend_dev_t device = ggml_backend_dev_get(candidate.registry_ordinal);
                return caraml::diffusion::max_vram_bytes_for_device(
                    resolved_plan.budgets,
                    device,
                    budget_bytes);
            });
        if (selected_backends.status !=
            caraml::diffusion::PreflightBackendBudgetStatus::SUCCESS) {
            result.status = DIFFUSION_PREFLIGHT_UNAVAILABLE;
            return result;
        }
        for (size_t index = 0; index < selected_backends.backends.size(); ++index) {
            const auto &selected = selected_backends.backends[index];
            const auto &candidate = backend_candidates[selected.candidate_index];
            ggml_backend_dev_t device = ggml_backend_dev_get(candidate.registry_ordinal);
            ggml_backend_dev_props properties{};
            ggml_backend_dev_get_props(device, &properties);
            DiffusionPreflightBackendNative &destination = result.backends[index];
            destination.kind = diffusion_backend_kind(device);
            destination.device_type = diffusion_backend_device_type(device);
            destination.ordinal = static_cast<int>(index);
            destination.budget_bytes = selected.budget_bytes;
            if (properties.memory_total > 0) {
                if (!checked_size_to_i64(properties.memory_free, destination.free_bytes) ||
                    !checked_size_to_i64(properties.memory_total, destination.total_bytes) ||
                    destination.free_bytes > destination.total_bytes) {
                    result.status = DIFFUSION_PREFLIGHT_INVALID;
                    return result;
                }
            }
        }

        const auto selected_backend_mask = [&](const std::string &assignment) {
            return backend_mask_for_assignment(assignment, backend_candidates, selected_backends);
        };

        const ggml_type override_type = config.wtype >= 0
            ? static_cast<ggml_type>(config.wtype)
            : GGML_TYPE_COUNT;
        int64_t declared_source_mask = 0;
        for (const DeclaredComponent &source : components) {
            const int64_t source_bit = int64_t{1} << source.role;
            if ((declared_source_mask & source_bit) != 0) {
                result.status = DIFFUSION_PREFLIGHT_INVALID;
                return result;
            }
            declared_source_mask |= source_bit;
        }
        int result_component_count = 0;
        if (!has_split_components(config)) {
            const DeclaredComponent &bundle_source = components.front();
            ModelLoader bundle_loader;
            if (!bundle_loader.init_from_file(bundle_source.path, bundle_source.prefix)) {
                result.status = DIFFUSION_PREFLIGHT_INVALID;
                return result;
            }
            bundle_loader.convert_tensors_name();
            std::vector<caraml::diffusion::PreflightTensorEvidence> tensors;
            if (!collect_preflight_tensor_evidence(bundle_loader, override_type, tensors)) {
                result.status = DIFFUSION_PREFLIGHT_INVALID;
                return result;
            }
            const auto classified = caraml::diffusion::classify_bundled_components(
                tensors,
                bundle_source.role,
                0,
                resolved_plan.runtime_spec,
                resolved_plan.params_spec,
                selected_backend_mask);
            if (classified.empty() ||
                classified.size() + components.size() - 1 > DIFFUSION_PREFLIGHT_MAX_COMPONENTS) {
                result.status = DIFFUSION_PREFLIGHT_INVALID;
                return result;
            }
            for (const auto &subdivision : classified) {
                DiffusionPreflightComponentNative &destination =
                    result.components[result_component_count];
                destination.source_role = subdivision.source_role;
                destination.source_ordinal = subdivision.source_ordinal;
                destination.subdivision_role = subdivision.subdivision_role;
                destination.ordinal = result_component_count;
                destination.parameter_bytes = subdivision.parameter_bytes;
                destination.runtime_placement = subdivision.runtime_placement;
                destination.runtime_backend_mask = subdivision.runtime_backend_mask;
                destination.parameter_placement = subdivision.parameter_placement;
                ++result_component_count;
            }

            for (size_t source_index = 1; source_index < components.size(); ++source_index) {
                const DeclaredComponent &source = components[source_index];
                ModelLoader component_loader;
                if (!component_loader.init_from_file(source.path, source.prefix)) {
                    result.status = DIFFUSION_PREFLIGHT_INVALID;
                    return result;
                }
                component_loader.convert_tensors_name();
                const int64_t parameter_bytes =
                    component_loader.get_params_mem_size(nullptr, override_type);
                if (parameter_bytes < 0) {
                    result.status = DIFFUSION_PREFLIGHT_INVALID;
                    return result;
                }

                DiffusionPreflightComponentNative &destination =
                    result.components[result_component_count];
                destination.source_role = source.role;
                destination.source_ordinal = static_cast<int>(source_index);
                destination.subdivision_role =
                    caraml::diffusion::declared_source_subdivision_role(source.role);
                destination.ordinal = result_component_count;
                destination.parameter_bytes = parameter_bytes;
                const std::string runtime_value = assignment_value(
                    resolved_plan.runtime_spec,
                    source.module);
                destination.runtime_backend_mask = selected_backend_mask(runtime_value);
                destination.runtime_placement = runtime_placement_for(
                    runtime_value,
                    destination.runtime_backend_mask);
                destination.parameter_placement = parameter_placement_for(
                    assignment_value(resolved_plan.params_spec, source.module));
                ++result_component_count;
            }
        } else for (size_t index = 0; index < components.size(); ++index) {
            const DeclaredComponent &source = components[index];
            ModelLoader component_loader;
            if (!component_loader.init_from_file(source.path, source.prefix)) {
                result.status = DIFFUSION_PREFLIGHT_INVALID;
                return result;
            }
            component_loader.convert_tensors_name();
            const int64_t parameter_bytes = component_loader.get_params_mem_size(nullptr, override_type);
            if (parameter_bytes < 0) {
                result.status = DIFFUSION_PREFLIGHT_INVALID;
                return result;
            }

            DiffusionPreflightComponentNative &destination =
                result.components[result_component_count];
            destination.source_role = source.role;
            destination.source_ordinal = static_cast<int>(index);
            destination.subdivision_role =
                caraml::diffusion::declared_source_subdivision_role(source.role);
            destination.ordinal = result_component_count;
            destination.parameter_bytes = parameter_bytes;

            if (source.module[0] != '\0') {
                const std::string runtime_value = assignment_value(
                    resolved_plan.runtime_spec,
                    source.module);
                destination.runtime_backend_mask = selected_backend_mask(runtime_value);
                destination.runtime_placement = runtime_placement_for(
                    runtime_value,
                    destination.runtime_backend_mask);
                destination.parameter_placement = parameter_placement_for(
                    assignment_value(resolved_plan.params_spec, source.module));
            }
            ++result_component_count;
        }

        result.status = DIFFUSION_PREFLIGHT_FIT;
        result.architecture = architecture_code(version);
        result.quantization = dominant_quantization_code(combined_loader);
        result.memory_confidence = 1;
        result.segmented_compute = config.segmented_compute;
        result.prefetch = config.prefetch;
        result.declared_source_mask = declared_source_mask;
        result.source_count = static_cast<int>(components.size());
        result.component_count = result_component_count;
        result.backend_count = static_cast<int>(selected_backends.backends.size());
    } catch (const std::bad_alloc &) {
        result = DiffusionPreflightResultNative{};
        result.status = DIFFUSION_PREFLIGHT_UNAVAILABLE;
    } catch (...) {
        result = DiffusionPreflightResultNative{};
        result.status = DIFFUSION_PREFLIGHT_INVALID;
    }
    return result;
}

static bool supported_quantization_label(const std::string &label) {
    static constexpr std::array<const char *, 34> labels = {
        "F32", "F16", "BF16", "Q4_0", "Q4_1", "Q5_0", "Q5_1", "Q8_0",
        "Q8_1", "Q2_K", "Q3_K", "Q4_K", "Q5_K", "Q6_K", "Q8_K",
        "IQ2_XXS", "IQ2_XS", "IQ3_XXS", "IQ1_S", "IQ4_NL", "IQ3_S",
        "IQ2_S", "IQ4_XS", "IQ1_M", "TQ1_0", "TQ2_0", "MXFP4", "NVFP4",
        "I8", "I16", "I32", "I64", "F64", "Q1_0",
    };
    return std::find_if(labels.begin(), labels.end(), [&](const char *candidate) {
        return label == candidate;
    }) != labels.end();
}

DiffusionModelFeatureSupportNative diffusion_runner_core_probe_model_features(
        const char *architecture,
        const char *quantization,
        int mode) {
    DiffusionModelFeatureSupportNative result;
    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    if (!g_backend_initialized || !safe_feature_label(architecture, 64) ||
        (quantization && !safe_feature_label(quantization, 32)) ||
        (mode != 0 && mode != 1)) {
        return result;
    }

    const std::string arch(architecture);
    const bool image_arch = arch == "SD1" || arch == "SDXL" || arch == "SD3" || arch == "FLUX";
    const bool video_arch = arch == "WAN_SMALL" || arch == "WAN_LARGE";
    result.architecture = (image_arch || video_arch)
        ? DIFFUSION_FEATURE_SUPPORTED
        : DIFFUSION_FEATURE_UNSUPPORTED;
    if (image_arch || video_arch) {
        result.mode = ((mode == 0 && image_arch) || (mode == 1 && video_arch))
            ? DIFFUSION_FEATURE_SUPPORTED
            : DIFFUSION_FEATURE_UNSUPPORTED;
    }
    if (quantization) {
        result.quantization = supported_quantization_label(quantization)
            ? DIFFUSION_FEATURE_SUPPORTED
            : DIFFUSION_FEATURE_UNKNOWN;
    }
    return result;
}

std::string diffusion_runner_core_engine_version() {
    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    if (!g_backend_initialized) return {};
    const char *version = sd_version();
    if (!version || !safe_feature_label(version, 72)) return {};
    return std::string("stable-diffusion.cpp-") + version;
}

std::string diffusion_runner_core_model_version(int64_t handle_id) {
    auto handle = lookup_handle(handle_id);
    if (!handle) return {};
    std::lock_guard<std::mutex> operation_lock(handle->operation_mutex);
    return handle->model_version;
}

/** Maps SDVersion enum to a compact family string for the Kotlin layer. */
static const char* sd_arch_family(SDVersion v) {
    switch (v) {
        case VERSION_FLUX:
        case VERSION_FLUX_FILL:
        case VERSION_FLUX_CONTROLS:
        case VERSION_FLEX_2:
        case VERSION_FLUX2:
        case VERSION_FLUX2_KLEIN: return "FLUX";

        case VERSION_SD3: return "SD3";

        case VERSION_WAN2_2_I2V:
        case VERSION_WAN2_2_TI2V: return "WAN2_LARGE";
        case VERSION_WAN2: return "WAN2_SMALL";

        case VERSION_SDXL:
        case VERSION_SDXL_INPAINT:
        case VERSION_SDXL_PIX2PIX:
        case VERSION_SDXL_VEGA:
        case VERSION_SDXL_SSD1B:
        case VERSION_SDXS_09: return "SDXL";

        case VERSION_SD1:
        case VERSION_SD1_INPAINT:
        case VERSION_SD1_PIX2PIX:
        case VERSION_SD1_TINY_UNET:
        case VERSION_SD2:
        case VERSION_SD2_INPAINT:
        case VERSION_SD2_TINY_UNET:
        case VERSION_SDXS_512_DS: return "SD1";

        default: return "UNKNOWN";
    }
}

DiffusionMetadataResult diffusion_runner_core_get_metadata(const char* model_path) {
    DiffusionMetadataResult result = {};
    result.success = false;
    result.estimated_ram = 0;
    result.architecture[0] = '\0';
    result.dominant_quant[0] = '\0';

    std::lock_guard<std::mutex> operation_lock(g_operation_mutex);
    if (!g_backend_initialized || !bounded_string(model_path, 4096, false)) return result;

    ScopedNativeLogSuppression suppress_upstream_diagnostics;
    try {
        ModelLoader loader;
        if (!loader.init_from_file(std::string(model_path))) {
            return result;
        }
        loader.convert_tensors_name();

        // Architecture
        SDVersion version = loader.get_sd_version();
        const char* arch_name = sd_arch_family(version);
        strncpy(result.architecture, arch_name, sizeof(result.architecture) - 1);
        result.architecture[sizeof(result.architecture) - 1] = '\0';

        // Dominant quantization (most frequent non-F32 weight tensor type)
        auto wtype_stat = loader.get_wtype_stat();
        ggml_type dominant_type = GGML_TYPE_COUNT;
        uint32_t max_count = 0;
        for (const auto& kv : wtype_stat) {
            if (kv.first != GGML_TYPE_F32 && kv.second > max_count) {
                max_count = kv.second;
                dominant_type = kv.first;
            }
        }
        if (dominant_type != GGML_TYPE_COUNT) {
            const char* quant_name = ggml_type_name(dominant_type);
            if (quant_name) {
                strncpy(result.dominant_quant, quant_name, sizeof(result.dominant_quant) - 1);
                result.dominant_quant[sizeof(result.dominant_quant) - 1] = '\0';
            }
        }

        // RAM estimate
        result.estimated_ram = loader.get_params_mem_size(nullptr, GGML_TYPE_COUNT);
        result.success = true;
    } catch (...) {
        // Leave success = false; caller checks result.success
    }

    return result;
}
