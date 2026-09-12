#include "diffusion_runner_preflight_internal.h"

#include "ggml-backend.h"

#include <cmath>
#include <memory>
#include <stdexcept>
#include <string>
#include <utility>

namespace {

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

struct BackendDeleter {
    void operator()(ggml_backend_t backend) const {
        if (backend) ggml_backend_free(backend);
    }
};

} // namespace

int main() {
    ggml_backend_load_all();
    expect(ggml_backend_dev_count() > 0, "no native backend device available");
    ggml_backend_dev_t device = nullptr;
    std::unique_ptr<ggml_backend, BackendDeleter> backend;
    for (size_t index = 0; index < ggml_backend_dev_count() && !backend; ++index) {
        ggml_backend_dev_t candidate = ggml_backend_dev_get(index);
        std::unique_ptr<ggml_backend, BackendDeleter> initialized(
            ggml_backend_dev_init(candidate, nullptr));
        if (initialized) {
            device = candidate;
            backend = std::move(initialized);
        }
    }
    expect(backend != nullptr, "backend initialization failed");

    const std::string device_name = ggml_backend_dev_name(device);
    const auto resolved = [&](const std::string &spec) {
        return caraml::diffusion::max_vram_bytes_for_backend(spec, backend.get());
    };
    expect(resolved("0") == 0, "integer zero must disable max-VRAM");
    expect(resolved("0.0") == 0, "decimal zero must disable max-VRAM");
    expect(resolved(device_name + "=0") == 0, "keyed zero must disable max-VRAM");

    sd::ggml_graph_cut::MaxVramAssignment pinned_auto;
    pinned_auto.reset(0.0f);
    std::string parse_error;
    expect(pinned_auto.parse("-1", &parse_error) &&
            pinned_auto.canonicalize_backend_keys(&parse_error),
           "pinned automatic budget could not be parsed");
    expect(resolved("-1") == pinned_auto.bytes_for_backend(backend.get()),
           "-1 must use the pinned automatic budget resolution");

    constexpr size_t GIB = size_t{1024} * 1024 * 1024;
    constexpr size_t POSITIVE_GIB = 100'000;
    expect(resolved(std::to_string(POSITIVE_GIB)) == POSITIVE_GIB * GIB,
           "positive max-VRAM must be returned verbatim even above free memory");
    return 0;
}
