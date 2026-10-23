#pragma once

#include <utility>

#if defined(__ANDROID__)
#include <android/trace.h>
#endif

// Synchronous native boundaries stay on the calling thread. Fixed names only:
// never pass model paths, prompt text, token text or user identifiers here.
// This does not synchronize a backend or introduce an artificial warmup.
class ScopedNativeTrace {
public:
    explicit ScopedNativeTrace(const char *name, bool requested = true) {
#if defined(__ANDROID__)
        enabled_ = requested && ATrace_isEnabled();
        if (enabled_) ATrace_beginSection(name);
#else
        (void) name;
        (void) requested;
#endif
    }

    ~ScopedNativeTrace() {
#if defined(__ANDROID__)
        if (enabled_) ATrace_endSection();
#endif
    }

    ScopedNativeTrace(const ScopedNativeTrace &) = delete;
    ScopedNativeTrace &operator=(const ScopedNativeTrace &) = delete;

private:
#if defined(__ANDROID__)
    bool enabled_ = false;
#endif
};

template <typename Action>
decltype(auto) trace_native_call(const char *name, Action &&action) {
    ScopedNativeTrace trace(name);
    return std::forward<Action>(action)();
}
