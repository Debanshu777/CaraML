#include "llama_operation_gate.h"
#include "llama_runner_core.h"
#include "scoped-model-context.h"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <future>
#include <initializer_list>
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

void streamed_session_excludes_unload_between_tokens() {
    LlamaOperationGate gate;
    auto prompt = gate.begin_session();
    expect(prompt.has_value(), "stream session did not begin");
    prompt.reset();

    auto first_token = gate.lock_session();
    expect(first_token.has_value(), "first token did not enter stream session");
    first_token.reset();

    expect(
        !gate.try_lock().has_value(),
        "unload entered between streamed tokens");

    std::promise<void> unload_started;
    auto unload_started_signal = unload_started.get_future();
    auto unload = std::async(std::launch::async, [&] {
        unload_started.set_value();
        auto operation = gate.lock();
        return operation.has_value();
    });
    unload_started_signal.get();
    expect(
        unload.wait_for(std::chrono::milliseconds(100)) == std::future_status::timeout,
        "unload entered between streamed tokens");

    auto second_token = std::async(std::launch::async, [&] {
        auto operation = gate.lock_session();
        return operation.has_value();
    });
    expect(
        second_token.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready &&
            second_token.get(),
        "stream session was bound to the prompt thread");

    auto finalize = gate.lock_session();
    expect(finalize.has_value(), "finalization could not enter stream session");
    gate.end_session();
    finalize.reset();

    expect(
        unload.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready && unload.get(),
        "unload remained blocked after stream finalization");
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

void pinned_native_quantization_labels_are_exact() {
    for (const char *label : {
            "Q3_K", "Q3_K_S", "Q3_K_M", "Q3_K_L",
            "Q4_K", "Q4_K_S", "Q4_K_M",
            "Q5_K", "Q5_K_S", "Q5_K_M"}) {
        const LlamaModelFeatureSupportNative support =
            llama_runner_core_probe_model_features("llama", label);
        expect(
            support.quantization == LLAMA_FEATURE_SUPPORTED,
            "exact pinned K-quant label was not supported");
    }

    for (const char *label : {
            "Q4_K_L", "Q5_K_L", "Q4_K_FUTURE", "future_quant"}) {
        const LlamaModelFeatureSupportNative support =
            llama_runner_core_probe_model_features("llama", label);
        expect(
            support.quantization == LLAMA_FEATURE_UNKNOWN,
            "unmapped native quantization did not remain unknown");
    }
}

void calibration_rejects_unbounded_requests_without_allocating() {
    const auto too_short = llama_runner_core_calibrate_backend(
        LLAMA_BACKEND_CPU, 499, 4LL * 1024LL * 1024LL);
    expect(too_short.status == LLAMA_CALIBRATION_INVALID, "short calibration was accepted");
    expect(too_short.window_count == 0, "invalid calibration exposed partial windows");

    const auto too_large = llama_runner_core_calibrate_backend(
        LLAMA_BACKEND_CPU, 500, 65LL * 1024LL * 1024LL);
    expect(too_large.status == LLAMA_CALIBRATION_INVALID, "oversized calibration was accepted");
    expect(too_large.window_count == 0, "oversized calibration exposed partial windows");
}

void calibration_cancellation_is_atomic_and_nonblocking() {
    auto cancellation = std::async(std::launch::async, [] {
        llama_runner_core_cancel_calibration();
    });
    expect(
        cancellation.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready,
        "calibration cancellation blocked behind operation ownership");
}

} // namespace

int main() {
    operation_gate_excludes_concurrent_owners();
    streamed_session_excludes_unload_between_tokens();
    exceptional_context_path_releases_both_handles();
    repeated_initialization_is_idempotent();
    core_gate_blocks_discovery_but_not_atomic_cancellation();
    pinned_native_quantization_labels_are_exact();
    calibration_rejects_unbounded_requests_without_allocating();
    calibration_cancellation_is_atomic_and_nonblocking();
    llama_runner_core_shutdown();
    return 0;
}
