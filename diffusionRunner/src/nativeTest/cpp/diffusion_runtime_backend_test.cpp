#include "diffusion_runner_preflight_internal.h"

#include <stdexcept>
#include <string>
#include <vector>

namespace {

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

} // namespace

int main() {
    using namespace caraml::diffusion;

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

    expect(vulkan_runtime_requires_cpu_components(
               DIFFUSION_RUNTIME_BACKEND_VULKAN,
               false,
               false),
           "an explicit Vulkan plan must keep unsafe components on CPU");
    expect(!vulkan_runtime_requires_cpu_components(
               DIFFUSION_RUNTIME_BACKEND_CUDA,
               false,
               true),
           "an explicit CUDA plan must not be changed by an ambient Vulkan device");
    expect(vulkan_runtime_requires_cpu_components(
               DIFFUSION_RUNTIME_BACKEND_CPU,
               true,
               true),
           "auto-fit must retain Vulkan safety when its resolved backend is not explicit");

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
