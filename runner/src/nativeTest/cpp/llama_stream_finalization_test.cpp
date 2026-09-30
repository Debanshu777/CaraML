// Include the implementation to exercise its private turn state without a model
// or production test API. This target must not also compile the core separately.
#include "llama_runner_core.cpp"

#include <stdexcept>

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
    return 0;
}
