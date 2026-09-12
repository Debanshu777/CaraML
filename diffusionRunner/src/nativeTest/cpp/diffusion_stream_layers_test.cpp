#include "diffusion_runner_preflight_internal.h"

#include <stdexcept>

namespace {

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

} // namespace

int main() {
    using caraml::diffusion::effective_stream_layers;
    expect(effective_stream_layers(true, "diffusion=metal0", "diffusion=cpu"),
           "CPU-resident diffusion params on one device must preserve streaming");
    expect(effective_stream_layers(true, "diffusion=cpu", ""),
           "params following a CPU diffusion runtime must preserve streaming");
    expect(effective_stream_layers(true, "diffusion=cpu", "diffusion=disk"),
           "disk-backed params following a CPU diffusion runtime must preserve streaming");
    expect(!effective_stream_layers(true, "diffusion=metal0", "diffusion=metal0"),
           "GPU-resident diffusion params must disable streaming");
    expect(!effective_stream_layers(true, "diffusion=metal0&metal1", "diffusion=cpu"),
           "a split diffusion runtime must disable streaming");
    expect(!effective_stream_layers(false, "diffusion=metal0", "diffusion=cpu"),
           "an explicit disabled option must remain disabled");
    return 0;
}
