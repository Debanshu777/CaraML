#pragma once

#include <algorithm>
#include <cstdint>
#include <limits>

// The upstream default -1 means all layers, including the output layer.
// Preserve that native sentinel; expose only a resolved nonnegative count over JNI/FFI.
inline bool llama_runner_valid_fitted_dimensions(uint32_t context, int gpu_layers) {
    return context > 0 && context <= static_cast<uint32_t>(std::numeric_limits<int>::max()) &&
        gpu_layers >= -1;
}

inline int llama_runner_resolved_gpu_layers(int requested, uint32_t model_layers, bool has_gpu) {
    if (!has_gpu || requested < -1 || model_layers >= static_cast<uint32_t>(std::numeric_limits<int>::max())) {
        return 0;
    }
    const int maximum = static_cast<int>(model_layers) + 1;
    return requested == -1 ? maximum : std::min(requested, maximum);
}
