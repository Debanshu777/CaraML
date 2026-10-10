// Include the implementation to exercise its private turn state without a model
// or production test API. This target must not also compile the core separately.
#include "llama_runner_core.cpp"

#include <stdexcept>
#include <fstream>
#include <sstream>
#include "reasoning-budget.h"

namespace {
void expect(bool condition, const char *message) {
    if (!condition) throw std::runtime_error(message);
}
}

int main() {
    g_chat_templates = common_chat_templates_init(nullptr,
        "{% for message in messages %}{{ message['role'] + ':' + message['content'] + '\\n' }}{% endfor %}"
        "{% if add_generation_prompt %}assistant:{% endif %}");
    expect(g_chat_templates != nullptr, "test chat template unavailable");

    // Every stop path reaches the same finalizer. Simulate an already drained
    // partial stream and a final parser correction, then drain as Kotlin does.
    for (const int reason : {STOP_EOG, STOP_MAX_TOKENS, STOP_CONTEXT_FULL, STOP_CANCELLED}) {
        auto admission = g_operation_gate.begin_session();
        expect(admission.has_value(), "test session did not begin");
        admission.reset();
        reset_delta_offsets();
        g_chat_msgs.clear();
        g_reasoning_accum = "thought";
        g_content_accum = "answer";
        expect(std::string(llama_runner_core_get_reasoning_delta()) == "thought", "initial reasoning missing");
        expect(std::string(llama_runner_core_get_content_delta()) == "answer", "initial content missing");
        g_assistant_buffer = "thought answer";
        g_stop_reason = reason;
        g_content_accum += "!";
        finalize_assistant_turn();
        expect(std::string(llama_runner_core_get_reasoning_delta()).empty(), "finalization replayed reasoning");
        expect(std::string(llama_runner_core_get_content_delta()) == "!", "finalization replayed content");
        expect(std::string(llama_runner_core_get_content_delta()).empty(), "final delta was not drained");
        expect(g_chat_msgs.size() == 1, "assistant turn was not recorded once");
        finalize_assistant_turn();
        expect(g_chat_msgs.size() == 1, "repeated finalization duplicated history");
        llama_runner_core_finalize_generation();
        llama_runner_core_finalize_generation();
        expect(std::string(llama_runner_core_get_reasoning()) == "thought" &&
            std::string(llama_runner_core_get_content()) == "answer!",
            "public finalization erased the final parsed response");
        expect(std::string(llama_runner_core_get_reasoning_delta()).empty() &&
            std::string(llama_runner_core_get_content_delta()).empty(),
            "public finalization changed already drained deltas");
        expect(g_chat_msgs.size() == 1, "public finalization duplicated history");
        auto idle = g_operation_gate.try_lock();
        expect(idle.has_value(), "public finalization did not release the session");
    }

    g_chat_templates = common_chat_templates_init(nullptr,
        "{% for message in messages %}"
        "{% if (message['role'] == 'user') != (loop.index0 % 2 == 0) %}"
        "{{ raise_exception('Conversation roles must alternate user/assistant') }}{% endif %}"
        "{{ message['role'] + ':' + message['content'] + '\\n' }}{% endfor %}"
        "{% if add_generation_prompt %}assistant:{% endif %}");
    for (const int reason : {STOP_EOG, STOP_CANCELLED}) {
        auto admission = g_operation_gate.begin_session();
        expect(admission.has_value(), "empty test session did not begin");
        admission.reset();
        reset_delta_offsets();
        g_assistant_buffer.clear();
        g_reasoning_accum.clear();
        g_content_accum.clear();
        common_chat_msg user;
        user.role = ROLE_USER;
        user.content = "First question";
        g_chat_msgs = {user};
        g_stop_reason = reason;

        // Validate that this template really rejects the broken history.
        std::vector<common_chat_msg> consecutive_users = g_chat_msgs;
        user.content = "Next question";
        consecutive_users.push_back(user);
        expect(!try_apply_full_template(consecutive_users, true).has_value(),
            "fixture must reject consecutive user roles");

        llama_runner_core_finalize_generation();
        expect(g_chat_msgs.size() == 2 && g_chat_msgs.back().role == ROLE_ASSISTANT &&
            g_chat_msgs.back().content.empty(), "empty admitted turn was not finalized");
        llama_runner_core_finalize_generation();
        expect(g_chat_msgs.size() == 2, "empty turn finalization was not idempotent");
        expect(std::string(llama_runner_core_get_reasoning_delta()).empty() &&
            std::string(llama_runner_core_get_content_delta()).empty(),
            "empty turn emitted spurious text");
        g_chat_msgs.push_back(user);
        expect(try_apply_full_template(g_chat_msgs, true).has_value(),
            "next user cannot render after empty assistant turn");
    }
    // Use real pinned templates, including forced-open and generated reasoning starts.
    struct Fixture { const char *file; const char *answer; const char *partial; };
    for (const Fixture &fixture : {
        Fixture{"openbmb-MiniCPM5-1B.jinja", "Brief reasoning.</think>\n\nHere is the final answer.", "Unfinished reasoning"},
        Fixture{"Qwen-Qwen3-0.6B.jinja", "<think>Brief reasoning.</think>\n\nHere is the final answer.", "<think>Unfinished reasoning"},
        Fixture{"openai-gpt-oss-120b.jinja", "<|channel|>analysis<|message|>Brief reasoning.<|end|><|start|>assistant<|channel|>final<|message|>Here is the final answer.", "<|channel|>analysis<|message|>Unfinished reasoning"},
    }) {
        std::ifstream file(std::string(CARAML_TEST_TEMPLATE_DIR) + "/" + fixture.file);
        expect(file.good(), "pinned chat template unavailable");
        std::ostringstream source;
        source << file.rdbuf();
        g_chat_templates = common_chat_templates_init(nullptr, source.str());
        common_chat_msg question;
        question.role = ROLE_USER;
        question.content = "Explain dark matter";
        auto turn = try_apply_chat_template({question}, true);
        expect(turn.has_value(), "reasoning template did not render");
        expect(!turn->thinking_start_tag.empty() && !turn->thinking_end_tags.empty(),
            "reasoning controls must come from the rendered template");
        capture_parser_params(*turn);
        g_assistant_buffer = fixture.answer;
        reparse_assistant_buffer(false);
        expect(g_reasoning_accum.find("Brief reasoning.") != std::string::npos,
            "reasoning was not parsed as reasoning");
        expect(g_content_accum.find("Here is the final answer.") != std::string::npos,
            "answer after the reasoning delimiter remained in thoughts");
        g_assistant_buffer = fixture.partial;
        reparse_assistant_buffer(false);
        expect(g_content_accum.empty(), "reasoning-only truncation must not become a fabricated answer");
    }

    // The upstream sampler must close reasoning, preserve an answer phase, and
    // start with a fresh budget next turn (including a zero-budget tiny context).
    for (const int budget : {2, 0, 2}) {
        llama_sampler *sampler = common_reasoning_budget_init(nullptr, {{10}}, {{20, 21}}, {20, 21}, budget);
        llama_sampler_accept(sampler, 10); // Same prefill as common_sampler_init(generation_prompt).
        for (int i = 0; i < budget; ++i) llama_sampler_accept(sampler, 11);
        expect(common_reasoning_budget_get_state(sampler) == REASONING_BUDGET_FORCING,
            "reasoning exhausted its allowance without entering delimiter forcing");
        for (const int forced : {20, 21}) {
            llama_token_data tokens[] = {{11, 10.0f, 0.0f}, {20, 0.0f, 0.0f}, {21, 0.0f, 0.0f}};
            llama_token_data_array candidates = {tokens, 3, -1, false};
            llama_sampler_apply(sampler, &candidates);
            for (const auto &candidate : tokens) {
                expect((candidate.id == forced) == std::isfinite(candidate.logit),
                    "reasoning limiter did not force the exact model delimiter");
            }
            llama_sampler_accept(sampler, forced);
        }
        expect(common_reasoning_budget_get_state(sampler) == REASONING_BUDGET_DONE,
            "reasoning delimiter did not release the answer phase");
        llama_sampler_free(sampler);
    }
    return 0;
}
