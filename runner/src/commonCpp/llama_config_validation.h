#pragma once

#include "ggml.h"

#include <algorithm>
#include <cstddef>
#include <iterator>
#include <string>

// Keep aligned with llama.cpp common/arg.cpp kv_cache_types and Kotlin validation.
inline bool llama_runner_supported_cache_type(int type) {
    switch (type) {
        case GGML_TYPE_F32:
        case GGML_TYPE_F16:
        case GGML_TYPE_BF16:
        case GGML_TYPE_Q8_0:
        case GGML_TYPE_Q4_0:
        case GGML_TYPE_Q4_1:
        case GGML_TYPE_IQ4_NL:
        case GGML_TYPE_Q5_0:
        case GGML_TYPE_Q5_1:
            return true;
        default:
            return false;
    }
}

// Decimal indices/ranges, e.g. "7", "4-7", "0-3,7"; empty means unpinned.
// Commit only a fully validated mask, so malformed input never partially pins CPUs.
inline bool llama_runner_parse_cpu_mask(
    const std::string &mask,
    bool (&output)[GGML_MAX_N_THREADS]) {
    static_assert(GGML_MAX_N_THREADS == 512, "Update Kotlin CPU index validation with this bound");
    if (mask.size() > 4096) return false;
    bool parsed[GGML_MAX_N_THREADS]{};
    size_t cursor = 0;
    auto parse_index = [&](size_t &value) {
        if (cursor == mask.size() || mask[cursor] < '0' || mask[cursor] > '9') return false;
        value = 0;
        while (cursor < mask.size() && mask[cursor] >= '0' && mask[cursor] <= '9') {
            value = value * 10 + static_cast<size_t>(mask[cursor++] - '0');
            if (value >= GGML_MAX_N_THREADS) return false;
        }
        return true;
    };
    while (cursor < mask.size()) {
        size_t first = 0;
        if (!parse_index(first)) return false;
        size_t last = first;
        if (cursor < mask.size() && mask[cursor] == '-') {
            ++cursor;
            if (!parse_index(last) || first > last) return false;
        }
        for (size_t index = first; index <= last; ++index) parsed[index] = true;
        if (cursor == mask.size()) break;
        if (mask[cursor++] != ',' || cursor == mask.size()) return false;
    }
    std::copy(std::begin(parsed), std::end(parsed), std::begin(output));
    return true;
}

inline bool llama_runner_valid_cpu_mask(const std::string &mask) {
    bool parsed[GGML_MAX_N_THREADS]{};
    return llama_runner_parse_cpu_mask(mask, parsed);
}
