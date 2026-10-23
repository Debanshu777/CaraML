package com.debanshu777.caraml.sdk.plugin.onnx

internal fun formatOnnxPrompt(messages: List<OnnxChatMessage>, codec: HuggingFaceBpeTokenizer,
                              template: String?, modelType: String?): String {
    require(messages.isNotEmpty() && messages.all { it.role in setOf("system", "user", "assistant") })
    return when {
        codec.has("<|im_start|>") -> messages.joinToString("") {
            val content = if (modelType == "qwen3" && it.role == "assistant")
                it.content.substringAfterLast("</think>").trimStart('\n') else it.content
            "<|im_start|>${it.role}\n$content<|im_end|>\n"
        } + "<|im_start|>assistant\n"
        codec.has("<|start_header_id|>") -> {
            val system = messages.firstOrNull()?.takeIf { it.role == "system" }
            val dated = template?.contains("Cutting Knowledge Date: December 2023") == true
            val prefix = if (dated) "<|start_header_id|>system<|end_header_id|>\n\nCutting Knowledge Date: December 2023\nToday Date: ${onnxPromptDate()}\n\n${system?.content?.trim().orEmpty()}<|eot_id|>" else ""
            val conversation = if (dated && system != null) messages.drop(1) else messages
            "<|begin_of_text|>" + prefix + conversation.joinToString("") {
                "<|start_header_id|>${it.role}<|end_header_id|>\n\n${it.content.trim()}<|eot_id|>"
            } + "<|start_header_id|>assistant<|end_header_id|>\n\n"
        }
        else -> {
            require(template == null) { "Unsupported ONNX chat template" }
            messages.filter { it.role != "system" }.joinToString("\n") { it.content }
        }
    }
}
