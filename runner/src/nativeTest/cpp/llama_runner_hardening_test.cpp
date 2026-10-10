#include "llama_operation_gate.h"
#include "llama_sparse_penalties.h"
#include "llama_runner_core.h"
#include "llama_fit_policy.h"
#include "llama_config_validation.h"
#include "scoped-model-context.h"
#include "gguf.h"
#include "llama.h"
#include "log.h"
#ifdef _WIN32
#include <io.h>
#define TEST_DUP _dup
#define TEST_DUP2 _dup2
#define TEST_CLOSE _close
#define TEST_FILENO _fileno
#else
#include <unistd.h>
#define TEST_DUP dup
#define TEST_DUP2 dup2
#define TEST_CLOSE close
#define TEST_FILENO fileno
#endif
#include <filesystem>
#include <random>
#include <cstdio>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <future>
#include <initializer_list>
#include <mutex>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

struct FakeModel {};
struct FakeContext {};
int fake_model_frees = 0;
int fake_context_frees = 0;
std::vector<std::string> cleanup_order;

void free_fake_model(FakeModel *) {
    fake_model_frees++;
    cleanup_order.emplace_back("model");
}
void free_fake_context(FakeContext *) {
    fake_context_frees++;
    cleanup_order.emplace_back("context");
}
void restore_fake_logger(int, void *) { cleanup_order.emplace_back("logger"); }

void expect(bool condition, const char *message) {
    if (!condition) {
        throw std::runtime_error(message);
    }
}

std::vector<std::string> diagnostic_messages;
void capture_diagnostic(LlamaLogLevel, const char *message) {
    diagnostic_messages.emplace_back(message ? message : "");
}

// Capture the real C stdio sinks used by common/log.cpp on every native platform.
class CapturedStdio {
public:
    explicit CapturedStdio(FILE *stream) : stream_(stream), capture_(std::tmpfile()) {
        expect(capture_ != nullptr, "could not create stdio capture");
        std::fflush(stream_);
        original_ = TEST_DUP(TEST_FILENO(stream_));
        if (original_ < 0 || TEST_DUP2(TEST_FILENO(capture_), TEST_FILENO(stream_)) < 0) {
            if (original_ >= 0) TEST_CLOSE(original_);
            std::fclose(capture_);
            throw std::runtime_error("could not redirect stdio capture");
        }
    }
    ~CapturedStdio() {
        restore();
        std::fclose(capture_);
    }
    std::string read() {
        restore();
        std::rewind(capture_);
        std::string result;
        char buffer[256];
        size_t count;
        while ((count = std::fread(buffer, 1, sizeof(buffer), capture_)) != 0) {
            result.append(buffer, count);
            expect(result.size() <= 65536, "stdio capture exceeded test bound");
        }
        return result;
    }
private:
    void restore() {
        if (original_ < 0) return;
        std::fflush(stream_);
        TEST_DUP2(original_, TEST_FILENO(stream_));
        TEST_CLOSE(original_);
        original_ = -1;
    }
    FILE *stream_;
    FILE *capture_;
    int original_ = -1;
};

void common_logger_cannot_bypass_private_upstream_diagnostics() {
    CapturedStdio captured_out(stdout);
    CapturedStdio captured_err(stderr);
    llama_runner_core_set_logger(capture_diagnostic);
    llama_runner_core_init(nullptr);
    diagnostic_messages.clear();
    LOG_ERR("private-sentinel-log-error prompt=private-sentinel-prompt\n");
    // Direct additions bypass the verbosity macro and must also be discarded.
    common_log_add(common_log_main(), GGML_LOG_LEVEL_ERROR,
        "private-sentinel-direct-error path=/private/sentinel-model\n");
    common_log_add(common_log_main(), GGML_LOG_LEVEL_NONE,
        "private-sentinel-direct-output template=private-sentinel-template\n");
    // Join the writer so the pre-fix failure is deterministic, without resuming it.
    common_log_pause(common_log_main());
    ggml_log_callback callback = nullptr;
    void *user_data = nullptr;
    llama_log_get(&callback, &user_data);
    expect(callback != nullptr, "sanitized upstream logger was not installed");
    callback(GGML_LOG_LEVEL_ERROR, "failed to open /private/private-sentinel-model", user_data);
    llama_runner_core_shutdown();
    llama_runner_core_set_logger({});
    const std::string raw_output = captured_out.read() + captured_err.read();
    expect(raw_output.find("private-sentinel") == std::string::npos,
        "common logger bypass leaked private details through stdio");
    bool classified = false;
    for (const auto &message : diagnostic_messages) {
        expect(message.find("private-sentinel") == std::string::npos,
            "common logger bypass leaked private details through runner logger");
        classified = classified || message.find("reason=ARTIFACT_ACCESS_FAILED") != std::string::npos;
    }
    expect(classified, "safe upstream reason stopped reaching runner logger");
}

void upstream_diagnostics_are_classified_bounded_and_private() {
    llama_runner_core_set_logger(capture_diagnostic);
    llama_runner_core_init(nullptr);
    ggml_log_callback callback = nullptr;
    void *user_data = nullptr;
    llama_log_get(&callback, &user_data);
    expect(callback != nullptr, "safe upstream logger was not installed");
    diagnostic_messages.clear();
    callback(GGML_LOG_LEVEL_WARN,
        "FaIlEd To OpEn /private/sentinel-path prompt=sentinel-prompt template=sentinel-template", user_data);
    for (int index = 0; index < 32; ++index) {
        callback(GGML_LOG_LEVEL_ERROR, "raw-secret exception=sentinel-exception", user_data);
    }
    expect(!diagnostic_messages.empty() && diagnostic_messages.size() <= 8,
        "upstream warning count was not bounded");
    expect(diagnostic_messages.front().find("stage=IDLE reason=ARTIFACT_ACCESS_FAILED") != std::string::npos,
        "mixed-case upstream diagnostic was not classified");
    for (const auto &message : diagnostic_messages) {
        expect(message.find("sentinel") == std::string::npos && message.find("raw-secret") == std::string::npos &&
            message.find("/private/") == std::string::npos, "upstream diagnostic leaked sensitive details");
    }
    const size_t before = diagnostic_messages.size();
    callback(GGML_LOG_LEVEL_INFO, "sentinel-generated-output", user_data);
    expect(diagnostic_messages.size() == before, "upstream informational content was emitted");
    llama_runner_core_shutdown();
    llama_runner_core_set_logger({});
}

void assistant_architecture_requires_a_target_model_context() {
    struct MetadataFixture {
        std::filesystem::path root;
        std::string file;
        MetadataFixture() {
            std::random_device random;
            for (int attempt = 0; attempt < 16; ++attempt) {
                const auto candidate = std::filesystem::temp_directory_path() /
                    ("caraml-assistant-" + std::to_string(random()) + "-" + std::to_string(random()));
                std::error_code error;
                if (!std::filesystem::create_directory(candidate, error)) continue;
                root = candidate;
                std::filesystem::permissions(root, std::filesystem::perms::owner_all,
                    std::filesystem::perm_options::replace, error);
                if (error) {
                    std::filesystem::remove_all(root, error);
                    throw std::runtime_error("could not secure assistant metadata fixture");
                }
                file = (root / "metadata.gguf").string();
                return;
            }
            throw std::runtime_error("could not create assistant metadata fixture");
        }
        ~MetadataFixture() { std::error_code error; std::filesystem::remove_all(root, error); }
    } fixture;
    const char *path = fixture.file.c_str();
    gguf_context * metadata = gguf_init_empty();
    gguf_set_val_str(metadata, "general.architecture", "gemma4-assistant");
    const bool written = gguf_write_to_file(metadata, path, true);
    gguf_free(metadata);
    expect(written, "could not write assistant metadata fixture");
    llama_runner_core_set_logger({});
    llama_runner_core_init(nullptr);
    LlamaRunnerConfig config;
    config.n_ctx = 128;
    config.n_ctx_min = 64;
    config.n_batch = 32;
    config.n_ubatch = 32;
    config.n_gpu_layers = 0;
    config.offload_kqv = false;
    config.auto_fit = false;
    const auto result = llama_runner_core_preflight(path, config);
    llama_runner_core_shutdown();
    expect(result.status == 4, "assistant model was reported as transient runtime failure");
    expect(result.pool_count == 0, "unsupported assistant model supplied allocation evidence");
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
    cleanup_order.clear();
    FakeModel model;
    FakeContext context;

    try {
        caraml::ScopedRestore<int, void *> logger(0, nullptr, restore_fake_logger);
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
    expect(
        cleanup_order == std::vector<std::string>({"context", "model", "logger"}),
        "native fit resources were not released before restoring the logger");
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

void engine_version_is_bounded_and_stable() {
    const std::string version = llama_runner_core_engine_version();
    expect(!version.empty(), "candidate engine version was rejected");
    expect(version.size() <= 72, "candidate engine version exceeded the persistence bound");
    for (const unsigned char byte : version) {
        expect(
            (byte >= 'A' && byte <= 'Z') || (byte >= 'a' && byte <= 'z') ||
                (byte >= '0' && byte <= '9') || byte == '.' || byte == '_' ||
                byte == '+' || byte == '-',
            "candidate engine version contained an unsafe byte");
    }
}

void calibration_rejects_unbounded_requests_without_allocating() {
    const auto too_short = llama_runner_core_calibrate_backend(
        1, LLAMA_BACKEND_CPU, 499, 4LL * 1024LL * 1024LL);
    expect(too_short.status == LLAMA_CALIBRATION_INVALID, "short calibration was accepted");
    expect(too_short.window_count == 0, "invalid calibration exposed partial windows");

    const auto too_large = llama_runner_core_calibrate_backend(
        2, LLAMA_BACKEND_CPU, 500, 65LL * 1024LL * 1024LL);
    expect(too_large.status == LLAMA_CALIBRATION_INVALID, "oversized calibration was accepted");
    expect(too_large.window_count == 0, "oversized calibration exposed partial windows");
}

void calibration_cancellation_is_atomic_and_nonblocking() {
    auto cancellation = std::async(std::launch::async, [] {
        llama_runner_core_cancel_calibration(3);
    });
    expect(
        cancellation.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready,
        "calibration cancellation blocked behind operation ownership");

    const auto result = llama_runner_core_calibrate_backend(
        3, LLAMA_BACKEND_CPU, 500, 4LL * 1024LL * 1024LL);
    expect(
        result.status != LLAMA_CALIBRATION_CANCELLED,
        "queued cancellation poisoned a later probe with the same token");
}

void reserved_calibration_latches_cancel_before_native_entry() {
    expect(
        llama_runner_core_reserve_calibration(4) == LLAMA_CALIBRATION_RESERVATION_ACCEPTED,
        "calibration token could not be reserved before JNI entry");
    llama_runner_core_cancel_calibration(4);

    const auto result = llama_runner_core_calibrate_backend(
        4, LLAMA_BACKEND_CPU, 500, 4LL * 1024LL * 1024LL);
    expect(
        result.status == LLAMA_CALIBRATION_CANCELLED,
        "cancel before native probe entry was lost");
}

void interruptible_operation_waiter_fails_closed_after_poison() {
    LlamaOperationGate gate;
    auto owner = gate.lock();
    std::atomic<bool> poisoned{false};
    auto waiter = std::async(std::launch::async, [&] {
        return gate.lock_interruptible([&] { return poisoned.load(); }).has_value();
    });
    expect(
        waiter.wait_for(std::chrono::milliseconds(100)) == std::future_status::timeout,
        "interruptible waiter entered an owned native operation");

    poisoned.store(true);
    gate.notify_waiters();
    expect(
        waiter.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready && !waiter.get(),
        "poisoned native operation waiter remained blocked");
}

void poison_notification_cannot_finish_between_predicate_check_and_wait() {
    LlamaOperationGate gate;
    auto owner = gate.lock();
    std::atomic<bool> poisoned{false};
    std::mutex predicate_mutex;
    std::condition_variable predicate_changed;
    bool predicate_captured = false;
    bool release_predicate = false;

    auto waiter = std::async(std::launch::async, [&] {
        bool capture_once = true;
        return gate.lock_interruptible([&] {
            const bool captured_poison = poisoned.load(std::memory_order_acquire);
            if (capture_once) {
                capture_once = false;
                std::unique_lock<std::mutex> lock(predicate_mutex);
                predicate_captured = true;
                predicate_changed.notify_all();
                predicate_changed.wait(lock, [&] { return release_predicate; });
            }
            return captured_poison;
        }).has_value();
    });

    {
        std::unique_lock<std::mutex> lock(predicate_mutex);
        predicate_changed.wait(lock, [&] { return predicate_captured; });
    }
    std::promise<void> notifier_started;
    auto notifier_started_signal = notifier_started.get_future();
    auto notifier = std::async(std::launch::async, [&] {
        notifier_started.set_value();
        poisoned.store(true, std::memory_order_release);
        gate.notify_waiters();
    });
    notifier_started_signal.get();
    const bool notification_finished_while_predicate_held =
        notifier.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready;

    {
        std::lock_guard<std::mutex> lock(predicate_mutex);
        release_predicate = true;
    }
    predicate_changed.notify_all();
    const bool waiter_finished_after_interrupt =
        waiter.wait_for(std::chrono::milliseconds(200)) == std::future_status::ready;

    owner.reset();
    notifier.wait();
    if (!waiter_finished_after_interrupt) {
        waiter.wait();
    }
    const bool waiter_acquired = waiter.get();
    expect(
        !notification_finished_while_predicate_held,
        "poison notification was not serialized with its predicate check");
    expect(
        waiter_finished_after_interrupt && !waiter_acquired,
        "interruptible waiter missed poison notification");
}

void completed_calibration_cannot_quarantine_a_reused_runtime() {
    expect(
        llama_runner_core_reserve_calibration(5) == LLAMA_CALIBRATION_RESERVATION_ACCEPTED,
        "calibration token could not be reserved for completion race test");
    llama_runner_core_cancel_calibration(5);
    const auto cancelled = llama_runner_core_calibrate_backend(
        5, LLAMA_BACKEND_CPU, 500, 4LL * 1024LL * 1024LL);
    expect(cancelled.status == LLAMA_CALIBRATION_CANCELLED, "cooperative calibration did not finish");
    expect(
        llama_runner_core_abandon_calibration(5) == LLAMA_CALIBRATION_ABANDONMENT_NOT_ACTIVE,
        "completed calibration was falsely quarantined");

    expect(
        llama_runner_core_reserve_calibration(6) == LLAMA_CALIBRATION_RESERVATION_ACCEPTED,
        "false quarantine prevented a later calibration reservation");
    llama_runner_core_cancel_calibration(6);
    const auto cleanup = llama_runner_core_calibrate_backend(
        6, LLAMA_BACKEND_CPU, 500, 4LL * 1024LL * 1024LL);
    expect(cleanup.status == LLAMA_CALIBRATION_CANCELLED, "completion race cleanup failed");
    expect(
        llama_runner_core_abandon_calibration(0) == LLAMA_CALIBRATION_ABANDONMENT_INVALID,
        "invalid abandonment token was not rejected");
}

void abandoned_calibration_quarantines_later_model_operations() {
    expect(
        llama_runner_core_reserve_calibration(7) == LLAMA_CALIBRATION_RESERVATION_ACCEPTED,
        "calibration token could not be reserved for quarantine test");
    expect(
        llama_runner_core_abandon_calibration(7) == LLAMA_CALIBRATION_ABANDONMENT_QUARANTINED,
        "active calibration abandonment did not confirm quarantine");
    auto preflight = std::async(std::launch::async, [] {
        return llama_runner_core_preflight(nullptr, LlamaRunnerConfig{});
    });
    expect(
        preflight.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready,
        "model preflight blocked behind an abandoned native probe");
    expect(
        preflight.get().status == LLAMA_PREFLIGHT_UNAVAILABLE,
        "model preflight did not fail closed after native probe abandonment");

    const auto cancelled = llama_runner_core_calibrate_backend(
        7, LLAMA_BACKEND_CPU, 500, 4LL * 1024LL * 1024LL);
    expect(cancelled.status == LLAMA_CALIBRATION_CANCELLED, "abandoned probe token was not cancelled");

    expect(
        llama_runner_core_reserve_calibration(8) == LLAMA_CALIBRATION_RESERVATION_QUARANTINED,
        "poisoned runtime accepted another calibration reservation");

    auto discovery = std::async(std::launch::async, [] {
        return llama_runner_core_backend_capabilities();
    });
    expect(
        discovery.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready &&
            discovery.get().count == -1,
        "backend discovery blocked behind a quarantined native operation");

    auto feature_probe = std::async(std::launch::async, [] {
        return llama_runner_core_probe_model_features("llama", "Q4_K_M");
    });
    expect(
        feature_probe.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready &&
            feature_probe.get().architecture == LLAMA_FEATURE_UNKNOWN,
        "feature discovery blocked behind a quarantined native operation");

    auto unload = std::async(std::launch::async, [] { llama_runner_core_unload(); });
    expect(
        unload.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready,
        "unload blocked behind a quarantined native operation");

    auto shutdown = std::async(std::launch::async, [] { llama_runner_core_shutdown(); });
    expect(
        shutdown.wait_for(std::chrono::milliseconds(100)) == std::future_status::ready,
        "shutdown blocked behind a quarantined native operation");
}

} // namespace

namespace {
using PenaltyOwner = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
PenaltyOwner penalty_sampler(bool fast, int vocab, float repeat = 1.1f, float frequency = 0, float presence = 0) {
    auto * original = llama_sampler_init_penalties(vocab, 64, repeat, frequency, presence);
    return PenaltyOwner(fast ? caraml_sampling::wrap_penalties(original, vocab, repeat, frequency, presence) : original, llama_sampler_free);
}
PenaltyOwner penalty_chain(bool fast, int vocab, float temperature) {
    auto * chain = llama_sampler_chain_init(llama_sampler_chain_default_params());
    llama_sampler_chain_add(chain, penalty_sampler(fast, vocab).release());
    llama_sampler_chain_add(chain, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(chain, llama_sampler_init_top_p(0.95f, 0));
    llama_sampler_chain_add(chain, llama_sampler_init_min_p(0.05f, 0));
    llama_sampler_chain_add(chain, llama_sampler_init_temp_ext(temperature, 0.0f, 1.0f));
    llama_sampler_chain_add(chain, llama_sampler_init_dist(42));
    return PenaltyOwner(chain, llama_sampler_free);
}
void sparse_penalties_match_pinned_sampling() {
    std::vector<llama_token_data> source;
    for (int i = 0; i < 1024; ++i) source.push_back({i, static_cast<float>(i) * 0.01f - 5.0f, 0.25f});
    auto original = penalty_sampler(false, 1024);
    auto fast = penalty_sampler(true, 1024);
    for (int event = 0; event < 128; ++event) {
        const int accepted = event % 7;
        llama_sampler_accept(original.get(), accepted); llama_sampler_accept(fast.get(), accepted);
        auto a = source, b = source;
        llama_token_data_array ca{a.data(), a.size(), 9, true}, cb{b.data(), b.size(), 9, true};
        llama_sampler_apply(original.get(), &ca);
        expect(caraml_sampling::apply_dense_penalties(*static_cast<caraml_sampling::PenaltyState *>(fast->ctx), &cb),
            "dense penalties did not take sparse path");
        expect(ca.size == cb.size && ca.selected == cb.selected && ca.sorted == cb.sorted, "penalty metadata changed");
        for (size_t i = 0; i < a.size(); ++i) expect(a[i].id == b[i].id && a[i].logit == b[i].logit && a[i].p == b[i].p,
            "penalty window/wrap/count formula changed");
    }
    // Preserve original behavior for sparse/permuted/backend candidates and invalid history.
    for (int kind = 0; kind < 4; ++kind) {
        auto a = source, b = source;
        if (kind == 0) { a.resize(40); b = a; }
        if (kind == 1) { std::swap(a[0], a[7]); b = a; }
        if (kind == 2) { llama_sampler_accept(original.get(), 9999); llama_sampler_accept(fast.get(), 9999); }
        if (kind == 3) { llama_sampler_reset(original.get()); llama_sampler_reset(fast.get()); }
        llama_token_data_array ca{a.data(), a.size(), -1, false}, cb{b.data(), b.size(), -1, false};
        llama_sampler_apply(original.get(), &ca); llama_sampler_apply(fast.get(), &cb);
        for (size_t i = 0; i < a.size(); ++i) expect(a[i].logit == b[i].logit, "penalty fallback/reset changed logits");
    }
    for (const auto config : {std::array<float, 3>{1.0f, 0, 0}, std::array<float, 3>{1.3f, 0.2f, 0.4f}}) {
        auto baseline = penalty_sampler(false, 1024, config[0], config[1], config[2]);
        auto optimized = penalty_sampler(true, 1024, config[0], config[1], config[2]);
        for (int pass = 0; pass < 2; ++pass) {
            if (pass == 1) for (int accepted : {0, 1, 1, 2, 3, 7, 7}) {
                llama_sampler_accept(baseline.get(), accepted); llama_sampler_accept(optimized.get(), accepted);
            }
            auto a = source, b = source;
            a[0].logit = b[0].logit = -0.0f; a[1].logit = b[1].logit = 0.0f;
            a[2].logit = b[2].logit = -INFINITY;
            llama_token_data_array ca{a.data(), a.size(), 11, true}, cb{b.data(), b.size(), 11, true};
            llama_sampler_apply(baseline.get(), &ca); llama_sampler_apply(optimized.get(), &cb);
            expect(ca.size == cb.size && ca.selected == cb.selected && ca.sorted == cb.sorted, "disabled/empty penalty metadata changed");
            for (size_t i = 0; i < a.size(); ++i) expect(a[i].id == b[i].id && a[i].p == b[i].p &&
                std::memcmp(&a[i].logit, &b[i].logit, sizeof(float)) == 0, "penalty count/frequency/presence/sign changed");
        }
    }
    for (const int vocab : {caraml_sampling::MAX_DENSE_VOCAB, caraml_sampling::MAX_DENSE_VOCAB + 1}) {
        auto sampler = penalty_sampler(true, vocab); llama_sampler_accept(sampler.get(), 7);
        std::vector<llama_token_data> dense;
        for (int i = 0; i < vocab; ++i) dense.push_back({i, 1.0f, 0.0f});
        auto before = dense;
        llama_token_data_array c{dense.data(), dense.size(), 13, true};
        const bool applied = caraml_sampling::apply_dense_penalties(*static_cast<caraml_sampling::PenaltyState *>(sampler->ctx), &c);
        expect(applied == (vocab == caraml_sampling::MAX_DENSE_VOCAB), "dense vocab cap changed");
        if (!applied) {
            expect(c.selected == 13 && c.sorted && c.size == before.size(), "guard mutated metadata");
            for (size_t i = 0; i < dense.size(); ++i) expect(dense[i].id == before[i].id && dense[i].logit == before[i].logit && dense[i].p == before[i].p,
                "guard mutated caller candidates");
        }
    }
    {
        auto sampler = penalty_sampler(true, 1024); llama_sampler_accept(sampler.get(), -1);
        auto unchanged = source; llama_token_data_array c{unchanged.data(), unchanged.size(), 17, true};
        expect(!caraml_sampling::apply_dense_penalties(*static_cast<caraml_sampling::PenaltyState *>(sampler->ctx), &c), "invalid negative token indexed candidates");
        expect(c.selected == 17 && c.sorted && c.size == source.size(), "invalid history changed metadata");
    }
    {
        auto chain = penalty_chain(false, 1024, 2.0f);
        const int count = llama_sampler_chain_n(chain.get());
        expect(!caraml_sampling::install_fresh_dense_penalties(chain.get(), 1024, 64, 1.1f, 0, 0, true), "backend sampler was replaced");
        expect(!caraml_sampling::install_fresh_dense_penalties(chain.get(), 1024, 63, 1.1f, 0, 0, false), "unknown window was replaced");
        expect(caraml_sampling::install_fresh_dense_penalties(chain.get(), 1024, 64, 1.1f, 0, 0, false), "fresh penalty hook not installed");
        expect(count == llama_sampler_chain_n(chain.get()) && std::string(llama_sampler_name(llama_sampler_chain_get(chain.get(), 0))) == "caraml-dense-penalties" &&
            std::string(llama_sampler_name(llama_sampler_chain_get(chain.get(), 1))) == "top-k", "sampler hook changed chain order");
    }
    std::mt19937 random(20261008); std::uniform_real_distribution<float> logits(-8.0f, 8.0f);
    for (const float temperature : {0.0f, 0.7f, 2.0f}) {
        auto baseline = penalty_chain(false, 1024, temperature);
        auto optimized = penalty_chain(false, 1024, temperature);
        expect(caraml_sampling::install_fresh_dense_penalties(optimized.get(), 1024, 64, 1.1f, 0, 0, false), "chain installation failed");
        for (int turn = 0; turn < 3; ++turn) {
            if (turn == 1) {
                baseline = PenaltyOwner(llama_sampler_clone(baseline.get()), llama_sampler_free);
                optimized = PenaltyOwner(llama_sampler_clone(optimized.get()), llama_sampler_free);
            }
            if (turn == 2) { llama_sampler_reset(baseline.get()); llama_sampler_reset(optimized.get()); }
            for (int event = 0; event < 128; ++event) {
                std::vector<llama_token_data> a;
                for (int i = 0; i < 1024; ++i) a.push_back({i, logits(random), 0.0f});
                if (event % 7 == 0) { a[3].logit = 100.0f; a[7].logit = 100.0f; }
                if (event % 11 == 0) for (int i = 0; i < 1000; ++i) a[i].logit = -INFINITY;
                auto b = a;
                llama_token_data_array ca{a.data(), a.size(), -1, false}, cb{b.data(), b.size(), -1, false};
                llama_sampler_apply(baseline.get(), &ca); llama_sampler_apply(optimized.get(), &cb);
                expect(ca.selected >= 0 && cb.selected >= 0 && ca.data[ca.selected].id == cb.data[cb.selected].id,
                    "seeded token changed with penalties/clone/reset/masks");
                expect(ca.size == cb.size, "penalty-chain candidate count changed");
                for (size_t i = 0; i < ca.size; ++i) expect(ca.data[i].id == cb.data[i].id && ca.data[i].p == cb.data[i].p,
                    "penalty-chain probabilities/order changed");
                llama_sampler_accept(baseline.get(), ca.data[ca.selected].id); llama_sampler_accept(optimized.get(), cb.data[cb.selected].id);
            }
        }
    }
    std::vector<llama_token_data> big;
    for (int i = 0; i < 151936; ++i) big.push_back({i, static_cast<float>(i) * 0.0001f, 0.0f});
    std::shuffle(big.begin(), big.end(), random);
    // Whole-chain comparison uses dense vocab order; top-k permutation happens later.
    std::sort(big.begin(), big.end(), [](auto a, auto b) { return a.id < b.id; });
    for (auto & candidate : big) candidate.logit = logits(random);
    for (const float temperature : {0.0f, 2.0f}) {
        double elapsed[2]{};
        for (int variant = 0; variant < 2; ++variant) {
            auto chain = penalty_chain(variant != 0, 151936, temperature);
            for (int i = 0; i < 64; ++i) llama_sampler_accept(chain.get(), (i * 2039) % 151936);
            std::vector<llama_token_data> working(big.size()); auto start = std::chrono::steady_clock::now();
            for (int event = 0; event < 256; ++event) {
                std::copy(big.begin(), big.end(), working.begin());
                llama_token_data_array c{working.data(), working.size(), -1, false}; llama_sampler_apply(chain.get(), &c);
                llama_sampler_accept(chain.get(), c.data[c.selected].id);
            }
            elapsed[variant] = std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
        }
        std::printf("penalty_microbenchmark vocab=151936 events=256 temp=%.1f baseline_ms=%.3f optimized_ms=%.3f\n", temperature, elapsed[0], elapsed[1]);
    }
}
} // namespace

int main() {
    sparse_penalties_match_pinned_sampling();
    common_logger_cannot_bypass_private_upstream_diagnostics();
    upstream_diagnostics_are_classified_bounded_and_private();
    expect(!llama_runner_supported_cache_type(4), "removed KV type accepted");
    expect(!llama_runner_supported_cache_type(GGML_TYPE_I32), "integer KV type accepted");
    expect(llama_runner_supported_cache_type(GGML_TYPE_Q8_0), "supported Q8 KV type rejected");
    bool mask[GGML_MAX_N_THREADS]{};
    expect(llama_runner_parse_cpu_mask("4-7", mask), "decimal CPU range rejected");
    for (int cpu = 0; cpu < GGML_MAX_N_THREADS; ++cpu) {
        expect(mask[cpu] == (cpu >= 4 && cpu <= 7), "CPU range parsed as hexadecimal mask");
    }
    expect(!llama_runner_parse_cpu_mask("0,512", mask), "out-of-bounds CPU accepted");
    expect(mask[4] && !mask[0], "invalid mask partially overwrote affinity");
    expect(llama_runner_parse_cpu_mask("7", mask), "single CPU index rejected");
    expect(mask[7] && !mask[0] && !mask[1] && !mask[2], "single CPU index treated as bitmask");
    expect(llama_runner_parse_cpu_mask("", mask) && !mask[7], "empty mask retained affinity");
    expect(llama_runner_valid_fitted_dimensions(4096, -1),
        "upstream all-layer sentinel rejected after successful auto-fit");
    expect(!llama_runner_valid_fitted_dimensions(4096, -2), "invalid offload sentinel accepted");
    expect(!llama_runner_valid_fitted_dimensions(0, -1), "empty fitted context accepted");
    expect(llama_runner_resolved_gpu_layers(-1, 24, true) == 25, "full offload count lost output layer");
    expect(llama_runner_resolved_gpu_layers(99, 24, true) == 25, "offload count exceeds model layers");
    expect(llama_runner_resolved_gpu_layers(12, 24, true) == 12, "partial offload count changed");
    expect(llama_runner_resolved_gpu_layers(-1, 24, false) == 0, "CPU-only device reports GPU layers");
    operation_gate_excludes_concurrent_owners();
    streamed_session_excludes_unload_between_tokens();
    exceptional_context_path_releases_both_handles();
    repeated_initialization_is_idempotent();
    assistant_architecture_requires_a_target_model_context();
    core_gate_blocks_discovery_but_not_atomic_cancellation();
    pinned_native_quantization_labels_are_exact();
    engine_version_is_bounded_and_stable();
    calibration_rejects_unbounded_requests_without_allocating();
    calibration_cancellation_is_atomic_and_nonblocking();
    reserved_calibration_latches_cancel_before_native_entry();
    interruptible_operation_waiter_fails_closed_after_poison();
    poison_notification_cannot_finish_between_predicate_check_and_wait();
    completed_calibration_cannot_quarantine_a_reused_runtime();
    abandoned_calibration_quarantines_later_model_operations();
    llama_runner_core_shutdown();
    return 0;
}
