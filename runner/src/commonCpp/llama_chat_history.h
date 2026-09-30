#pragma once

#include <algorithm>
#include <cstddef>
#include <optional>
#include <vector>

// predict_length is an upper bound, not a minimum reservation. Admit the entire
// prompt and shorten the answer only; keep the generation loop's stop headroom.
inline int llama_runner_response_budget(size_t prompt_end, int context_size, int requested) {
    constexpr int headroom = 4;
    if (requested <= 0 || context_size <= headroom ||
        prompt_end >= static_cast<size_t>(context_size - headroom)) return 0;
    return std::min(requested, context_size - headroom - static_cast<int>(prompt_end));
}

// Reuse only tokens that are actually present in memory. Re-evaluate the final
// token when the complete prompt is cached so the caller obtains fresh logits.
// remove_suffix(0) must clear the whole sequence; recurrent models may reject
// every nonzero rollback position.
template <typename Token, typename RemoveSuffix>
std::optional<size_t> llama_runner_reconcile_history(
    const std::vector<Token> &cached,
    const std::vector<Token> &prompt,
    RemoveSuffix remove_suffix) {
    if (prompt.empty()) return std::nullopt;
    size_t prefix = 0;
    const size_t limit = std::min(cached.size(), prompt.size());
    while (prefix < limit && cached[prefix] == prompt[prefix]) ++prefix;
    if (prefix == prompt.size()) --prefix;
    if (prefix < cached.size() && !remove_suffix(prefix)) {
        if (prefix == 0 || !remove_suffix(0)) return std::nullopt;
        prefix = 0;
    }
    return prefix;
}
