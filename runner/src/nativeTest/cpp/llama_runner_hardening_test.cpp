#include "llama_operation_gate.h"
#include "llama_runner_core.h"
#include "scoped-model-context.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <future>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>

namespace {

struct FakeModel {};
struct FakeContext {};
int fake_model_frees = 0;
int fake_context_frees = 0;

void free_fake_model(FakeModel *) { fake_model_frees++; }
void free_fake_context(FakeContext *) { fake_context_frees++; }

void expect(bool condition, const char *message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

void operation_gate_excludes_concurrent_owners() {
    LlamaOperationGate gate;
    auto first_owner = gate.lock();

    auto attempt = std::async(std::launch::async, [&gate] {
        return gate.try_lock().has_value();
    });
    expect(attempt.get() == false, "concurrent owner entered operation gate");

    first_owner.reset();
    auto after_release = std::async(std::launch::async, [&gate] {
        return gate.try_lock().has_value();
    });
    expect(after_release.get() == true, "operation gate remained locked after release");
}

void exceptional_context_path_releases_both_handles() {
    fake_model_frees = 0;
    fake_context_frees = 0;
    FakeModel model;
    FakeContext context;

    try {
        caraml::ScopedModelContext<FakeModel, FakeContext> resources(
            free_fake_model,
            free_fake_context);
        resources.reset_model(&model);
        resources.reset_context(&context);
        throw std::runtime_error("fault after context creation");
    } catch (const std::runtime_error &) {
    }

    expect(fake_context_frees == 1, "exceptional path did not release context");
    expect(fake_model_frees == 1, "exceptional path did not release model");
}

void repeated_initialization_is_idempotent() {
    std::atomic<int> initialization_count{0};
    llama_runner_core_set_logger([&](LlamaLogLevel, const char *message) {
        if (std::string(message) == "init: Backend initialized") {
            initialization_count++;
        }
    });

    llama_runner_core_init(nullptr);
    llama_runner_core_init(nullptr);

    if (initialization_count != 1) {
        throw std::runtime_error(
            "backend initialization count=" + std::to_string(initialization_count.load()));
    }
    llama_runner_core_shutdown();
}

void core_gate_blocks_discovery_but_not_atomic_cancellation() {
    std::mutex barrier_mutex;
    std::condition_variable barrier;
    bool logger_entered = false;
    bool release_logger = false;

    llama_runner_core_set_logger([&](LlamaLogLevel, const char *message) {
        if (std::string(message).find("init:") == std::string::npos) return;
        std::unique_lock<std::mutex> lock(barrier_mutex);
        logger_entered = true;
        barrier.notify_all();
        barrier.wait(lock, [&] { return release_logger; });
    });

    auto initialization = std::async(std::launch::async, [] {
        llama_runner_core_init(nullptr);
    });
    {
        std::unique_lock<std::mutex> lock(barrier_mutex);
        barrier.wait(lock, [&] { return logger_entered; });
    }

    std::promise<void> discovery_started;
    auto discovery_started_signal = discovery_started.get_future();
    auto discovery = std::async(std::launch::async, [&discovery_started] {
        discovery_started.set_value();
        return llama_runner_core_backend_capabilities();
    });
    discovery_started_signal.get();
    expect(
        discovery.wait_for(std::chrono::milliseconds(100)) == std::future_status::timeout,
        "capability discovery entered during another native operation");

    auto cancellation = std::async(std::launch::async, [] {
        llama_runner_core_cancel_generate();
    });
    expect(
        cancellation.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready,
        "atomic cancellation blocked behind operation gate");

    {
        std::lock_guard<std::mutex> lock(barrier_mutex);
        release_logger = true;
    }
    barrier.notify_all();
    initialization.get();
    const auto capabilities = discovery.get();
    (void) capabilities;
    llama_runner_core_set_logger(nullptr);
}

void unmapped_native_quantization_is_unknown() {
    const LlamaModelFeatureSupportNative support =
        llama_runner_core_probe_model_features("llama", "future_quant");
    expect(
        support.quantization == LLAMA_FEATURE_UNKNOWN,
        "unmapped native quantization was treated as unsupported");
}

} // namespace

int main() {
    operation_gate_excludes_concurrent_owners();
    exceptional_context_path_releases_both_handles();
    repeated_initialization_is_idempotent();
    core_gate_blocks_discovery_but_not_atomic_cancellation();
    unmapped_native_quantization_is_unknown();
    llama_runner_core_shutdown();
    return 0;
}
