#include "llama_chat_history.h"

#include <stdexcept>
#include <vector>

namespace {
void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}

struct Memory {
    std::vector<int> tokens;
    std::vector<size_t> removals;
    bool recurrent = false;
    bool fail_clear = false;

    bool remove(size_t position) {
        removals.push_back(position);
        if ((recurrent && position != 0) || (fail_clear && position == 0)) return false;
        tokens.resize(position);
        return true;
    }
};

void reconcile_and_decode(Memory &memory, const std::vector<int> &prompt) {
    auto prefix = llama_runner_reconcile_history(memory.tokens, prompt,
        [&](size_t position) { return memory.remove(position); });
    expect(prefix.has_value(), "reconciliation failed");
    expect(*prefix < prompt.size(), "no token left to refresh logits");
    memory.tokens.insert(memory.tokens.end(), prompt.begin() + *prefix, prompt.end());
    expect(memory.tokens == prompt, "decoded history differs from rendered conversation");
}
}

int main() {
    // 1=assistant opener, 2=answer, 3=turn terminator, 4=next user.
    Memory truncated{{1, 2}, {}};
    reconcile_and_decode(truncated, {1, 2, 3, 4});
    expect(truncated.removals.empty(), "truncated turn should append its missing closure");

    Memory rewritten{{1, 2, 8, 9}, {}};
    reconcile_and_decode(rewritten, {1, 2, 3, 4});
    expect(rewritten.removals == std::vector<size_t>{2}, "rewritten history was not trimmed");

    Memory recurrent{{1, 2, 8, 9}, {}, true};
    reconcile_and_decode(recurrent, {1, 2, 3, 4});
    expect(recurrent.removals == std::vector<size_t>({2, 0}),
        "rejected recurrent rollback must clear and replay the full prompt");

    Memory identical{{1, 2, 3}, {}};
    reconcile_and_decode(identical, {1, 2, 3});
    expect(identical.removals == std::vector<size_t>{2}, "cached prompt needs refreshed final logits");

    Memory shorter{{1, 2, 3, 4}, {}};
    reconcile_and_decode(shorter, {1, 2});
    expect(shorter.removals == std::vector<size_t>{1}, "shorter render retained stale tail");

    Memory failed{{1, 2, 8}, {}, true, true};
    auto prefix = llama_runner_reconcile_history(failed.tokens, std::vector<int>{1, 2, 3},
        [&](size_t position) { return failed.remove(position); });
    expect(!prefix.has_value(), "failed full clear must prohibit suffix decode");
    expect(failed.tokens == std::vector<int>({1, 2, 8}), "failed clear changed recorded memory");

    Memory empty;
    expect(!llama_runner_reconcile_history(empty.tokens, std::vector<int>{},
        [&](size_t position) { return empty.remove(position); }).has_value(),
        "empty render must not sample stale logits");
    reconcile_and_decode(empty, {1, 2, 3});

    expect(llama_runner_response_budget(3250, 4096, 896) == 842,
        "near-full conversation should retain prompt and reduce answer budget");
    expect(llama_runner_response_budget(100, 4096, 128) == 128,
        "requested maximum must cap answer length");
    expect(llama_runner_response_budget(4091, 4096, 128) == 1,
        "one remaining answer token should be usable");
    expect(llama_runner_response_budget(4092, 4096, 128) == 0,
        "stop headroom must remain available");
    expect(llama_runner_response_budget(5000, 4096, 128) == 0,
        "oversized prompt must fail without truncation");
    expect(llama_runner_response_budget(100, 4096, 0) == 0,
        "nonpositive answer limit must fail");
    expect(llama_runner_response_budget(0, 4, 128) == 0,
        "tiny context cannot reserve termination headroom");
    expect(llama_runner_response_budget(40, 512, 4096) == 468,
        "small contexts must use remaining space rather than a quarter-context cap");
    expect(llama_runner_reasoning_budget(468, 3) == 153,
        "reasoning must leave most of the budget for the final answer and its delimiter");
    expect(llama_runner_reasoning_budget(4096, 3) == 1024,
        "long contexts must keep reasoning bounded");
    expect(llama_runner_reasoning_budget(8, 6) == 0,
        "tiny budgets must close reasoning immediately");
    expect(llama_runner_reasoning_budget(0, 3) == 0,
        "no output space must not grant a reasoning budget");
    expect(llama_runner_generation_limit(508, 512, 0) == LlamaGenerationLimit::Context,
        "exhausting the native-clamped allowance must report context full, not length limit");
    expect(llama_runner_generation_limit(128, 512, 0) == LlamaGenerationLimit::Output,
        "an independent output cap must remain a length limit");
    expect(llama_runner_generation_limit(128, 512, 10) == LlamaGenerationLimit::None,
        "generation with remaining space must continue");
    return 0;
}
