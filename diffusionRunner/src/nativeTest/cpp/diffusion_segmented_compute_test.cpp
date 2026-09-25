#include "diffusion_runner_preflight_internal.h"

#include <stdexcept>

namespace {

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

} // namespace

int main() {
    DiffusionModelConfig config;
    config.model_path = "/models/model.gguf";
    config.runtime_backend = DIFFUSION_RUNTIME_BACKEND_METAL;
    config.offload_to_cpu = true;
    config.max_vram = "*=2";
    config.segmented_compute = true;
    config.prefetch = false;

    DiffusionContextParamsForTest captured;
    expect(diffusion_runner_core_capture_context_params_for_test(config, captured),
           "final context parameters could not be captured");
    expect(captured.backend == "metal", "runtime backend changed after admission");
    expect(captured.params_backend == "*=cpu,diffusion=cpu,te=cpu,vae=cpu",
           "parameter backend changed after admission");
    expect(captured.max_vram == "*=2", "max VRAM changed after admission");
    expect(captured.segmented_compute, "segmented compute was not preserved");
    expect(!captured.prefetch, "prefetch was enabled without admission");
    expect(!captured.auto_fit, "context construction recomputed the admitted plan");

    config.prefetch = true;
    expect(diffusion_runner_core_capture_context_params_for_test(config, captured),
           "prefetch context parameters could not be captured");
    expect(captured.segmented_compute && captured.prefetch,
           "independent segmented-compute controls were not preserved");
    return 0;
}
