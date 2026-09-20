#include "diffusion_runner_preflight_internal.h"

#include <stdexcept>
#include <string>
#include <unordered_map>
#include <utility>
#include <vector>

namespace {

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

} // namespace

int main() {
    using namespace caraml::diffusion;

    static_assert(DIFFUSION_RUNTIME_BACKEND_CPU == 0);
    static_assert(DIFFUSION_RUNTIME_BACKEND_METAL == 1);
    static_assert(DIFFUSION_RUNTIME_BACKEND_VULKAN == 2);
    static_assert(DIFFUSION_RUNTIME_BACKEND_CUDA == 3);

    const std::vector<std::pair<int, std::string>> expected_assignments = {
        {DIFFUSION_RUNTIME_BACKEND_CPU, "cpu"},
        {DIFFUSION_RUNTIME_BACKEND_METAL, "metal"},
        {DIFFUSION_RUNTIME_BACKEND_VULKAN, "vulkan,te=cpu,vae=cpu"},
        {DIFFUSION_RUNTIME_BACKEND_CUDA, "cuda"},
    };
    for (const auto &[backend, expected] : expected_assignments) {
        DiffusionModelConfig config;
        config.model_path = "/models/model.gguf";
        config.runtime_backend = backend;
        std::string captured;
        expect(diffusion_runner_core_capture_context_backend_for_test(config, captured),
               "production context-backend resolution failed");
        expect(captured == expected,
               "production sd_ctx_params_t.backend did not match the requested runtime");
    }

    std::string spec;
    expect(explicit_runtime_backend_spec(DIFFUSION_RUNTIME_BACKEND_CPU, spec) && spec == "cpu",
           "CPU plan must produce an explicit CPU runtime assignment");
    expect(explicit_runtime_backend_spec(DIFFUSION_RUNTIME_BACKEND_METAL, spec) && spec == "metal",
           "Metal plan must produce an explicit Metal runtime assignment");
    expect(explicit_runtime_backend_spec(DIFFUSION_RUNTIME_BACKEND_VULKAN, spec) && spec == "vulkan",
           "Vulkan plan must produce an explicit Vulkan runtime assignment");
    expect(explicit_runtime_backend_spec(DIFFUSION_RUNTIME_BACKEND_CUDA, spec) && spec == "cuda",
           "CUDA plan must produce an explicit CUDA runtime assignment");
    expect(!explicit_runtime_backend_spec(-1, spec),
           "unknown runtime backends must fail closed");

    const std::unordered_map<std::string, int> available_backends = {
        {"cpu", DIFFUSION_BACKEND_CPU},
        {"cuda0", DIFFUSION_BACKEND_CUDA},
        {"metal0", DIFFUSION_BACKEND_METAL},
        {"vulkan0", DIFFUSION_BACKEND_VULKAN},
    };
    const auto kind_for_assignment = [&](const std::string &assignment) {
        const auto found = available_backends.find(assignment);
        return found == available_backends.end() ? DIFFUSION_BACKEND_OTHER : found->second;
    };
    DiffusionModelConfig auto_fit_config;
    auto_fit_config.auto_fit = true;
    const std::string derived_non_vulkan = "diffusion=cuda0,te=metal0,vae=cuda0";
    expect(!component_requires_vulkan_cpu_safety(
               auto_fit_config,
               derived_non_vulkan,
               "te",
               kind_for_assignment),
           "ambient Vulkan must not CPU-pin an auto-fit Metal text encoder");
    expect(!component_requires_vulkan_cpu_safety(
               auto_fit_config,
               derived_non_vulkan,
               "vae",
               kind_for_assignment),
           "ambient Vulkan must not CPU-pin an auto-fit CUDA VAE");
    expect(component_requires_vulkan_cpu_safety(
               auto_fit_config,
               "diffusion=cuda0,te=vulkan0,vae=metal0",
               "te",
               kind_for_assignment),
           "an auto-fit component actually assigned to Vulkan must retain CPU safety");

    DiffusionModelConfig explicit_vulkan_config;
    explicit_vulkan_config.runtime_backend = DIFFUSION_RUNTIME_BACKEND_VULKAN;
    expect(component_requires_vulkan_cpu_safety(
               explicit_vulkan_config,
               "vulkan",
               "te",
               kind_for_assignment),
           "an explicit Vulkan plan must keep unsafe components on CPU");

    const std::vector<PreflightTensorEvidence> bundled = {
        {"model.diffusion_model.input.weight", 1'000},
        {"text_encoders.clip_l.transformer.weight", 2'000},
        {"first_stage_model.decoder.weight", 3'000},
        {"model_ema.decay", 400},
    };
    expect(explicit_runtime_backend_spec(DIFFUSION_RUNTIME_BACKEND_CPU, spec),
           "CPU runtime assignment could not be built");
    const auto components = classify_bundled_components(
        bundled,
        spec,
        "cpu",
        [](const std::string &) { return int64_t{0}; });
    expect(components.size() == 4, "CPU bundle classification lost component evidence");
    for (const auto &component : components) {
        expect(component.runtime_placement == DIFFUSION_RUNTIME_CPU,
               "CPU plan retained a default or GPU-first runtime component");
        expect(component.runtime_backend_mask == 0,
               "CPU runtime component unexpectedly references a GPU backend");
    }
    return 0;
}
