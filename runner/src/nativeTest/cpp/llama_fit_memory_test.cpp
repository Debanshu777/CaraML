#include "fit-memory-pool.h"
#include "ggml-backend-impl.h"

#include <array>
#include <stdexcept>
#include <vector>

namespace {

void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

enum ggml_backend_dev_type cpu_type(ggml_backend_dev_t) {
    return GGML_BACKEND_DEVICE_TYPE_CPU;
}

enum ggml_backend_dev_type gpu_type(ggml_backend_dev_t) {
    return GGML_BACKEND_DEVICE_TYPE_GPU;
}

bool host_layout(ggml_backend_buffer_type_t) { return true; }

} // namespace

int main() {
    ggml_backend_device cpu{};
    cpu.iface.get_type = cpu_type;
    ggml_backend_device gpu{};
    gpu.iface.get_type = gpu_type;
    ggml_backend_device other_gpu{};
    other_gpu.iface.get_type = gpu_type;

    // Like CPU_REPACK, this has CPU ownership and no is_host callback.
    ggml_backend_buffer_type repacked{};
    repacked.device = &cpu;
    ggml_backend_buffer_type plain_host{};
    plain_host.iface.is_host = host_layout;
    ggml_backend_buffer_type gpu_host = plain_host;
    gpu_host.device = &gpu;
    ggml_backend_buffer_type gpu_buffer{};
    gpu_buffer.device = &gpu;
    ggml_backend_buffer_type unknown{};
    ggml_backend_buffer_type unlisted_gpu{};
    unlisted_gpu.device = &other_gpu;

    const std::vector<ggml_backend_dev_t> no_devices;
    const std::vector<ggml_backend_dev_t> devices{&gpu};
    expect(!ggml_backend_buft_is_host(&repacked), "fixture must model nonstandard CPU layout");
    expect(common_fit_memory_pool_index(&repacked, no_devices) == 0,
        "strict CPU fit dropped repacked weights");
    expect(common_fit_memory_pool_index(&repacked, devices) == 1,
        "mixed fit dropped repacked CPU weights");
    expect(common_fit_memory_pool_index(&plain_host, devices) == 1,
        "ordinary host buffer left host pool");
    expect(common_fit_memory_pool_index(&gpu_host, devices) == 1,
        "GPU-owned host buffer was counted as device memory");
    expect(common_fit_memory_pool_index(&gpu_buffer, devices) == 0,
        "GPU buffer left its device pool");
    expect(common_fit_memory_pool_index(&unknown, devices) == -1,
        "unknown ownership must fail closed");
    expect(common_fit_memory_pool_index(nullptr, devices) == -1,
        "null buffer must fail closed");
    expect(common_fit_memory_pool_index(&unlisted_gpu, devices) == -1,
        "unlisted GPU must not be charged to host memory");

    // Each real production classification selects exactly one pool for all metrics.
    const std::array<ggml_backend_buffer_type_t, 4> buffers{
        &repacked, &plain_host, &gpu_host, &gpu_buffer};
    const std::array<std::array<size_t, 3>, 4> bytes{{
        {{1'400, 20, 30}}, {{100, 2, 3}}, {{50, 4, 5}}, {{500, 6, 7}}}};
    std::array<std::array<size_t, 3>, 2> totals{};
    for (size_t i = 0; i < buffers.size(); ++i) {
        const int pool = common_fit_memory_pool_index(buffers[i], devices);
        expect(pool >= 0 && pool < 2, "valid buffer has no memory pool");
        for (size_t metric = 0; metric < 3; ++metric) totals[pool][metric] += bytes[i][metric];
    }
    expect(totals[0] == std::array<size_t, 3>{500, 6, 7}, "GPU metrics changed or double counted");
    expect(totals[1] == std::array<size_t, 3>{1'550, 26, 38}, "host metrics lost CPU repack memory");
    return 0;
}
