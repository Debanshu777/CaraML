#pragma once

#include "diffusion_runner_core.h"
#include "core/ggml_graph_cut.h"
#include "ggml-backend.h"

#include <algorithm>
#include <cctype>
#include <cstdint>
#include <functional>
#include <limits>
#include <memory>
#include <string>
#include <vector>

namespace caraml::diffusion {

struct PreflightTensorEvidence {
    std::string name;
    int64_t parameter_bytes = 0;
};

struct PreflightComponentEvidence {
    int role = DIFFUSION_COMPONENT_OTHER;
    int64_t parameter_bytes = 0;
    int runtime_placement = DIFFUSION_RUNTIME_DEFAULT;
    int64_t runtime_backend_mask = 0;
    int parameter_placement = DIFFUSION_PARAMS_DEFAULT;
};

struct PreflightBackendCandidate {
    std::string canonical_name;
    size_t registry_ordinal = 0;
};

enum class PreflightBackendBudgetStatus {
    SUCCESS,
    UNAVAILABLE,
};

struct PreflightSelectedBackendBudget {
    size_t candidate_index = 0;
    int64_t budget_bytes = 0;
};

struct PreflightBackendBudgetCollection {
    PreflightBackendBudgetStatus status = PreflightBackendBudgetStatus::UNAVAILABLE;
    std::vector<PreflightSelectedBackendBudget> backends;
};

inline std::string lower_ascii(std::string value) {
    std::transform(value.begin(), value.end(), value.begin(), [](unsigned char byte) {
        return byte >= 'A' && byte <= 'Z'
            ? static_cast<char>(byte - 'A' + 'a')
            : static_cast<char>(byte);
    });
    return value;
}

inline std::string canonical_backend_name(std::string value) {
    size_t first = 0;
    size_t last = value.size();
    while (first < last && std::isspace(static_cast<unsigned char>(value[first]))) ++first;
    while (last > first && std::isspace(static_cast<unsigned char>(value[last - 1]))) --last;
    return lower_ascii(value.substr(first, last - first));
}

inline bool explicit_runtime_backend_spec(int runtime_backend, std::string &destination) {
    switch (runtime_backend) {
        case DIFFUSION_RUNTIME_BACKEND_CPU: destination = "cpu"; return true;
        case DIFFUSION_RUNTIME_BACKEND_METAL: destination = "metal"; return true;
        case DIFFUSION_RUNTIME_BACKEND_VULKAN: destination = "vulkan"; return true;
        case DIFFUSION_RUNTIME_BACKEND_CUDA: destination = "cuda"; return true;
        default:
            destination.clear();
            return false;
    }
}

inline std::string assignment_value(const std::string &spec, const std::string &module) {
    std::string default_value;
    std::string exact_value;
    size_t start = 0;
    while (start <= spec.size()) {
        const size_t end = spec.find(',', start);
        const std::string token = spec.substr(
            start,
            end == std::string::npos ? std::string::npos : end - start);
        const size_t equals = token.find('=');
        if (equals == std::string::npos) {
            if (!token.empty()) default_value = token;
        } else {
            const std::string key = lower_ascii(token.substr(0, equals));
            const std::string value = token.substr(equals + 1);
            if (key == "*" || key == "all" || key == "default") default_value = value;
            if (key == module) exact_value = value;
        }
        if (end == std::string::npos) break;
        start = end + 1;
    }
    return exact_value.empty() ? default_value : exact_value;
}

inline bool component_requires_vulkan_cpu_safety(
        const DiffusionModelConfig &config,
        const std::string &runtime_spec,
        const std::string &module,
        const std::function<int(const std::string &)> &backend_kind_for_assignment) {
    if (!config.auto_fit) {
        return config.runtime_backend == DIFFUSION_RUNTIME_BACKEND_VULKAN;
    }

    const std::string assignment = assignment_value(runtime_spec, module);
    size_t start = 0;
    do {
        const size_t end = assignment.find('&', start);
        const std::string token = canonical_backend_name(assignment.substr(
            start,
            end == std::string::npos ? std::string::npos : end - start));
        if (backend_kind_for_assignment(token) == DIFFUSION_BACKEND_VULKAN) return true;
        if (end == std::string::npos) break;
        start = end + 1;
    } while (start <= assignment.size());
    return false;
}

inline int component_role_for_tensor(const std::string &name) {
    const auto contains = [&](const char *needle) {
        return name.find(needle) != std::string::npos;
    };
    if (contains("model.diffusion_model.") || contains("unet.")) {
        return DIFFUSION_COMPONENT_DIFFUSION_MODEL;
    }
    if (contains("first_stage_model.") || name.rfind("vae.", 0) == 0 ||
        name.rfind("tae.", 0) == 0) {
        return DIFFUSION_COMPONENT_VAE;
    }
    if (contains("text_encoders") || contains("cond_stage_model") ||
        contains("te.text_model.") || contains("conditioner") ||
        name.rfind("text_encoder.", 0) == 0 ||
        name.rfind("text_embedding_projection.", 0) == 0 ||
        contains(".aggregate_embed.")) {
        return DIFFUSION_COMPONENT_TEXT_ENCODER;
    }
    return DIFFUSION_COMPONENT_OTHER;
}

inline int parameter_placement_for_assignment(const std::string &assignment) {
    const std::string normalized = lower_ascii(assignment);
    if (normalized == "cpu") return DIFFUSION_PARAMS_CPU;
    if (normalized == "disk") return DIFFUSION_PARAMS_DISK;
    return DIFFUSION_PARAMS_DEFAULT;
}

inline int runtime_placement_for_assignment(const std::string &assignment, int64_t backend_mask) {
    if (assignment.empty()) return DIFFUSION_RUNTIME_DEFAULT;
    if (lower_ascii(assignment) == "cpu") return DIFFUSION_RUNTIME_CPU;
    return backend_mask != 0 && (backend_mask & (backend_mask - 1)) == 0
        ? DIFFUSION_RUNTIME_GPU
        : DIFFUSION_RUNTIME_SPLIT_GPU;
}

inline std::vector<PreflightComponentEvidence> classify_bundled_components(
        const std::vector<PreflightTensorEvidence> &tensors,
        const std::string &runtime_spec,
        const std::string &params_spec,
        const std::function<int64_t(const std::string &)> &backend_mask) {
    constexpr int roles[] = {
        DIFFUSION_COMPONENT_DIFFUSION_MODEL,
        DIFFUSION_COMPONENT_VAE,
        DIFFUSION_COMPONENT_TEXT_ENCODER,
        DIFFUSION_COMPONENT_OTHER,
    };
    int64_t totals[4] = {0, 0, 0, 0};
    for (const PreflightTensorEvidence &tensor : tensors) {
        if (tensor.parameter_bytes < 0) return {};
        const int role = component_role_for_tensor(tensor.name);
        size_t role_index = 3;
        for (size_t index = 0; index < 4; ++index) {
            if (roles[index] == role) {
                role_index = index;
                break;
            }
        }
        if (totals[role_index] > std::numeric_limits<int64_t>::max() - tensor.parameter_bytes) {
            return {};
        }
        totals[role_index] += tensor.parameter_bytes;
    }

    std::vector<PreflightComponentEvidence> result;
    for (size_t index = 0; index < 4; ++index) {
        if (totals[index] == 0) continue;
        const char *module = roles[index] == DIFFUSION_COMPONENT_DIFFUSION_MODEL ? "diffusion" :
            roles[index] == DIFFUSION_COMPONENT_VAE ? "vae" :
            roles[index] == DIFFUSION_COMPONENT_TEXT_ENCODER ? "te" : "";
        const std::string runtime = assignment_value(runtime_spec, module);
        const std::string params = assignment_value(params_spec, module);
        const int64_t mask = runtime.empty() || lower_ascii(runtime) == "cpu"
            ? 0 : backend_mask(runtime);
        result.push_back({
            roles[index],
            totals[index],
            runtime_placement_for_assignment(runtime, mask),
            mask,
            parameter_placement_for_assignment(params),
        });
    }
    return result;
}

inline bool effective_stream_layers(
        bool requested,
        const std::string &runtime_spec,
        const std::string &params_spec) {
    if (!requested) return false;
    const std::string runtime = lower_ascii(assignment_value(runtime_spec, "diffusion"));
    const std::string params = lower_ascii(assignment_value(params_spec, "diffusion"));
    const bool params_are_cpu = params == "cpu" ||
        ((params.empty() || params == "disk") && runtime == "cpu");
    if (!params_are_cpu) return false;
    return runtime.find('&') == std::string::npos;
}

inline size_t max_vram_bytes_for_backend(
        const std::string &spec,
        ggml_backend_t backend) {
    sd::ggml_graph_cut::MaxVramAssignment assignment;
    assignment.reset(0.0f);
    std::string error;
    if (!backend || !assignment.parse(spec, &error) ||
        !assignment.canonicalize_backend_keys(&error)) {
        return 0;
    }
    return assignment.bytes_for_backend(backend);
}

inline bool max_vram_bytes_for_device(
        sd::ggml_graph_cut::MaxVramAssignment &assignment,
        ggml_backend_dev_t device,
        int64_t &bytes) {
    if (!device) return false;
    struct BackendDeleter {
        void operator()(ggml_backend *backend) const {
            if (backend) ggml_backend_free(backend);
        }
    };
    std::unique_ptr<ggml_backend, BackendDeleter> backend(
        ggml_backend_dev_init(device, nullptr));
    if (!backend) return false;
    const size_t resolved = assignment.bytes_for_backend(backend.get());
    if (resolved > static_cast<size_t>(std::numeric_limits<int64_t>::max())) return false;
    bytes = static_cast<int64_t>(resolved);
    return true;
}

template <typename NameResolver, typename BudgetResolver>
PreflightBackendBudgetCollection collect_selected_backend_budgets(
        const std::string &runtime_spec,
        const std::string &params_spec,
        const std::vector<PreflightBackendCandidate> &candidates,
        NameResolver resolve_name,
        BudgetResolver resolve_budget) {
    bool selected[DIFFUSION_PREFLIGHT_MAX_BACKENDS]{};
    if (candidates.empty() || candidates.size() > DIFFUSION_PREFLIGHT_MAX_BACKENDS) return {};

    const auto select_assignment = [&](const std::string &raw_assignment) {
        const std::string assignment = canonical_backend_name(raw_assignment);
        size_t start = 0;
        do {
            const size_t end = assignment.find('&', start);
            const std::string token = assignment.substr(
                start,
                end == std::string::npos ? std::string::npos : end - start);
            const std::string resolved = canonical_backend_name(
                resolve_name(token == "default" || token == "auto" ? std::string() : token));
            if (resolved.empty()) return false;
            size_t matched_index = candidates.size();
            for (size_t index = 0; index < candidates.size(); ++index) {
                if (canonical_backend_name(candidates[index].canonical_name) != resolved) continue;
                if (matched_index != candidates.size()) return false;
                matched_index = index;
            }
            if (matched_index == candidates.size()) return false;
            selected[matched_index] = true;
            if (end == std::string::npos) break;
            start = end + 1;
        } while (start <= assignment.size());
        return true;
    };

    constexpr const char *modules[] = {"diffusion", "te", "vae"};
    for (const char *module : modules) {
        const std::string runtime = assignment_value(runtime_spec, module);
        if (!select_assignment(runtime)) return {};
        const std::string params = canonical_backend_name(assignment_value(params_spec, module));
        if (!params.empty() && params != "disk" && !select_assignment(params)) return {};
    }

    PreflightBackendBudgetCollection result;
    for (size_t index = 0; index < candidates.size(); ++index) {
        if (!selected[index]) continue;
        int64_t budget_bytes = 0;
        if (!resolve_budget(candidates[index], budget_bytes) || budget_bytes < 0) return {};
        result.backends.push_back({index, budget_bytes});
    }
    if (result.backends.empty()) return {};
    result.status = PreflightBackendBudgetStatus::SUCCESS;
    return result;
}

} // namespace caraml::diffusion
