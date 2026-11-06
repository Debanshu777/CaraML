#pragma once

#include <atomic>
#include <cstdint>
#include <utility>

namespace caraml::diffusion {

// sd.cpp shares one callback between tensor loading, sampling, and VAE tiles.
// Publish a coherent pair; image requests filter to their sampling budget.
class SamplingProgress {
public:
    void begin(int steps, bool exact_step_budget = true) {
        expected_steps_ = steps;
        exact_step_budget_ = exact_step_budget;
        state_.store(0, std::memory_order_relaxed);
    }

    void observe(int step, int total) {
        if (expected_steps_ <= 0 || total <= 0 || step < 0 || step > total) return;
        if (exact_step_budget_) {
            if (total != expected_steps_) return;
            const auto previous = read();
            if (previous.first > step || previous.first == expected_steps_) return;
        }
        state_.store((uint64_t{static_cast<uint32_t>(total)} << 32) |
                     static_cast<uint32_t>(step), std::memory_order_relaxed);
    }

    std::pair<int, int> read() const {
        const uint64_t value = state_.load(std::memory_order_relaxed);
        return {static_cast<int>(value & 0xffffffffu), static_cast<int>(value >> 32)};
    }

    void end() {
        expected_steps_ = 0;
        state_.store(0, std::memory_order_relaxed);
    }

private:
    int expected_steps_ = 0; // Only the serialized native operation thread writes or observes.
    bool exact_step_budget_ = true; // Video preserves upstream per-stage callback budgets.
    std::atomic<uint64_t> state_{0};
};

}
