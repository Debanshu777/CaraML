#pragma once

#include <chrono>
#include <condition_variable>
#include <mutex>
#include <optional>
#include <utility>

class LlamaOperationGate {
public:
    class Lease {
    public:
        Lease(Lease &&other) noexcept : owner_(std::exchange(other.owner_, nullptr)) {}

        Lease &operator=(Lease &&other) noexcept {
            if (this != &other) {
                release();
                owner_ = std::exchange(other.owner_, nullptr);
            }
            return *this;
        }

        ~Lease() { release(); }

        Lease(const Lease &) = delete;
        Lease &operator=(const Lease &) = delete;

    private:
        friend class LlamaOperationGate;

        explicit Lease(LlamaOperationGate *owner) : owner_(owner) {}

        void release() noexcept {
            if (owner_) {
                owner_->release_operation();
                owner_ = nullptr;
            }
        }

        LlamaOperationGate *owner_;
    };

    [[nodiscard]] std::optional<Lease> lock() {
        std::unique_lock<std::mutex> state_lock(state_mutex_);
        state_changed_.wait(state_lock, [this] {
            return !session_active_ && !operation_active_;
        });
        operation_active_ = true;
        return std::optional<Lease>(Lease(this));
    }

    [[nodiscard]] std::optional<Lease> try_lock() {
        std::lock_guard<std::mutex> state_lock(state_mutex_);
        if (session_active_ || operation_active_) {
            return std::nullopt;
        }
        operation_active_ = true;
        return std::optional<Lease>(Lease(this));
    }

    template <typename Clock, typename Duration, typename Cancelled>
    [[nodiscard]] std::optional<Lease> lock_until(
        const std::chrono::time_point<Clock, Duration> &deadline,
        Cancelled cancelled) {
        std::unique_lock<std::mutex> state_lock(state_mutex_);
        while (session_active_ || operation_active_) {
            if (cancelled()) return std::nullopt;
            if (state_changed_.wait_until(state_lock, deadline) == std::cv_status::timeout &&
                (session_active_ || operation_active_)) {
                return std::nullopt;
            }
        }
        if (cancelled()) return std::nullopt;
        operation_active_ = true;
        return std::optional<Lease>(Lease(this));
    }

    template <typename Cancelled>
    [[nodiscard]] std::optional<Lease> lock_interruptible(Cancelled cancelled) {
        std::unique_lock<std::mutex> state_lock(state_mutex_);
        while (session_active_ || operation_active_) {
            if (cancelled()) return std::nullopt;
            state_changed_.wait(state_lock);
        }
        if (cancelled()) return std::nullopt;
        operation_active_ = true;
        return std::optional<Lease>(Lease(this));
    }

    void notify_waiters() noexcept {
        state_changed_.notify_all();
    }

    [[nodiscard]] std::optional<Lease> begin_session() {
        std::unique_lock<std::mutex> state_lock(state_mutex_);
        state_changed_.wait(state_lock, [this] {
            return !session_active_ && !operation_active_;
        });
        session_active_ = true;
        operation_active_ = true;
        return std::optional<Lease>(Lease(this));
    }

    [[nodiscard]] std::optional<Lease> lock_session() {
        std::unique_lock<std::mutex> state_lock(state_mutex_);
        state_changed_.wait(state_lock, [this] {
            return !session_active_ || !operation_active_;
        });
        if (!session_active_) {
            return std::nullopt;
        }
        operation_active_ = true;
        return std::optional<Lease>(Lease(this));
    }

    [[nodiscard]] std::optional<Lease> lock_session_compatible() {
        std::unique_lock<std::mutex> state_lock(state_mutex_);
        state_changed_.wait(state_lock, [this] { return !operation_active_; });
        operation_active_ = true;
        return std::optional<Lease>(Lease(this));
    }

    void end_session() noexcept {
        try {
            {
                std::lock_guard<std::mutex> state_lock(state_mutex_);
                session_active_ = false;
            }
            state_changed_.notify_all();
        } catch (...) {
            // std::mutex failures are unrecoverable, but native destructors must not unwind.
        }
    }

private:
    void release_operation() noexcept {
        try {
            {
                std::lock_guard<std::mutex> state_lock(state_mutex_);
                operation_active_ = false;
            }
            state_changed_.notify_all();
        } catch (...) {
            // std::mutex failures are unrecoverable, but native destructors must not unwind.
        }
    }

    std::mutex state_mutex_;
    std::condition_variable state_changed_;
    bool operation_active_ = false;
    bool session_active_ = false;
};
