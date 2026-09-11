#pragma once

#include <mutex>
#include <optional>
#include <utility>

class LlamaOperationGate {
public:
    using Lease = std::unique_lock<std::recursive_mutex>;

    [[nodiscard]] std::optional<Lease> lock() {
        return std::optional<Lease>(std::in_place, mutex_);
    }

    [[nodiscard]] std::optional<Lease> try_lock() {
        Lease lease(mutex_, std::try_to_lock);
        if (!lease.owns_lock()) {
            return std::nullopt;
        }
        return std::optional<Lease>(std::move(lease));
    }

private:
    std::recursive_mutex mutex_;
};
