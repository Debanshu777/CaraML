#pragma once
#include "llama.h"
#include <array>
#include <cmath>
#include <cstring>
#include <memory>
#include <vector>

namespace caraml_sampling {
using OwnedSampler = std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)>;
constexpr size_t PENALTY_WINDOW = 64;
constexpr int MAX_DENSE_VOCAB = 262144;
struct PenaltyState {
    OwnedSampler original;
    int vocab;
    float repeat, frequency, presence;
    std::array<llama_token, PENALTY_WINDOW> recent{};
    size_t size = 0, next = 0;
    bool invalid_history = false;
    PenaltyState(OwnedSampler owned, int v, float r, float f, float p)
        : original(std::move(owned)), vocab(v), repeat(r), frequency(f), presence(p) {}
    PenaltyState(const PenaltyState &) = delete;
    PenaltyState & operator=(const PenaltyState &) = delete;
};
inline bool apply_dense_penalties(PenaltyState & state, llama_token_data_array * candidates) {
    if (!candidates || state.invalid_history || state.vocab <= 0 || state.vocab > MAX_DENSE_VOCAB ||
        !std::isfinite(state.repeat) || state.repeat <= 0 || !std::isfinite(1.0f / state.repeat) ||
        !std::isfinite(state.frequency) || !std::isfinite(state.presence)) return false;
    if (state.repeat == 1.0f && state.frequency == 0.0f && state.presence == 0.0f) return true;
    // Upstream also clears sorted on an active penalty sampler with empty history.
    if (state.size == 0) { candidates->sorted = false; return true; }
    if (!candidates->data || candidates->size != static_cast<size_t>(state.vocab)) return false;
    for (size_t i = 0; i < candidates->size; ++i) {
        if (candidates->data[i].id != static_cast<llama_token>(i)) return false;
    }
    for (size_t i = 0; i < state.size; ++i) {
        const llama_token token = state.recent[i];
        bool already_applied = false;
        for (size_t j = 0; j < i; ++j) if (state.recent[j] == token) { already_applied = true; break; }
        if (already_applied) continue;
        int count = 0;
        for (size_t j = i; j < state.size; ++j) if (state.recent[j] == token) ++count;
        auto & logit = candidates->data[token].logit;
        // Keep the pinned upstream expression/order exactly, including negative logits.
        if (logit <= 0) logit *= state.repeat; else logit /= state.repeat;
        logit -= float(count) * state.frequency + float(count > 0) * state.presence;
    }
    candidates->sorted = false;
    return true;
}
inline llama_sampler * wrap_penalties(llama_sampler * owned, int vocab, float repeat, float frequency, float presence);
inline llama_sampler_i & penalty_interface() {
    static llama_sampler_i api = [] {
        llama_sampler_i value{};
        value.name = [](const llama_sampler *) { return "caraml-dense-penalties"; };
        value.accept = [](llama_sampler * s, llama_token token) {
            auto & state = *static_cast<PenaltyState *>(s->ctx);
            llama_sampler_accept(state.original.get(), token);
            state.invalid_history = state.invalid_history || token < 0 || token >= state.vocab;
            if (state.size < PENALTY_WINDOW) state.recent[state.size++] = token;
            else { state.recent[state.next] = token; state.next = (state.next + 1) % PENALTY_WINDOW; }
        };
        value.apply = [](llama_sampler * s, llama_token_data_array * candidates) {
            auto & state = *static_cast<PenaltyState *>(s->ctx);
            if (!apply_dense_penalties(state, candidates)) llama_sampler_apply(state.original.get(), candidates);
        };
        value.reset = [](llama_sampler * s) {
            auto & state = *static_cast<PenaltyState *>(s->ctx);
            llama_sampler_reset(state.original.get()); state.size = state.next = 0; state.invalid_history = false;
        };
        value.clone = [](const llama_sampler * s) {
            const auto & old = *static_cast<const PenaltyState *>(s->ctx);
            auto * copy = wrap_penalties(llama_sampler_clone(old.original.get()), old.vocab, old.repeat, old.frequency, old.presence);
            auto & state = *static_cast<PenaltyState *>(copy->ctx);
            state.recent = old.recent; state.size = old.size; state.next = old.next; state.invalid_history = old.invalid_history;
            return copy;
        };
        value.free = [](llama_sampler * s) { delete static_cast<PenaltyState *>(s->ctx); };
        return value;
    }();
    return api;
}
// Install only before first accept, with the same known 64-token upstream settings.
// The original sampler is always kept synchronized for exact fallback semantics.
inline llama_sampler * wrap_penalties(llama_sampler * original, int vocab, float repeat, float frequency, float presence) {
    OwnedSampler owned(original, llama_sampler_free);
    auto state = std::make_unique<PenaltyState>(std::move(owned), vocab, repeat, frequency, presence);
    auto * sampler = llama_sampler_init(&penalty_interface(), state.get()); state.release(); return sampler;
}
} // namespace caraml_sampling

namespace caraml_sampling {
// This hook is called immediately after common_sampler_init, before any accept.
// CPU sampling is separate from model GPU offload; backend sampling stays untouched.
inline bool install_fresh_dense_penalties(llama_sampler * chain, int vocab, int window,
                                          float repeat, float frequency, float presence,
                                          bool backend_sampling) {
    if (backend_sampling || window != static_cast<int>(PENALTY_WINDOW) || vocab <= 0 || vocab > MAX_DENSE_VOCAB ||
        !std::isfinite(repeat) || repeat <= 0 || !std::isfinite(1.0f / repeat) ||
        !std::isfinite(frequency) || !std::isfinite(presence)) return false;
    if (!chain || llama_sampler_chain_get(chain, -1) != chain) return false;
    const int count = llama_sampler_chain_n(chain);
    if (count <= 0 || count > 32) return false;
    int index = -1;
    for (int i = 0; i < count; ++i) {
        const auto * sampler = llama_sampler_chain_get(chain, i);
        const auto * name = sampler ? llama_sampler_name(sampler) : nullptr;
        if (name && std::strcmp(name, "penalties") == 0) {
            if (index >= 0) return false;
            index = i;
        }
    }
    if (index < 0) return false;
    try {
        // Allocate/clone before touching the live chain, retaining safe OOM fallback.
        std::vector<OwnedSampler> tail; tail.reserve(static_cast<size_t>(count - index));
        OwnedSampler replacement(wrap_penalties(llama_sampler_clone(llama_sampler_chain_get(chain, index)),
            vocab, repeat, frequency, presence), llama_sampler_free);
        for (int i = index; i < count; ++i) tail.emplace_back(llama_sampler_chain_remove(chain, index), llama_sampler_free);
        // The chain retained its old vector capacity; rebuilding the same number
        // of samplers preserves prefix/suffix order without further allocation.
        llama_sampler_chain_add(chain, replacement.release());
        for (size_t i = 1; i < tail.size(); ++i) llama_sampler_chain_add(chain, tail[i].release());
        return true;
    } catch (const std::bad_alloc &) {
        return false;
    }
}
} // namespace caraml_sampling
