package com.debanshu777.runner

/**
 * GBNF grammar that constrains the model to emit an optional `<think>...</think>`
 * (or `<thinking>...</thinking>`) reasoning block followed by a required
 * `<output>...</output>` block containing the final markdown answer.
 *
 * Why this shape:
 *  - Optional thinking lets us support both reasoning and non-reasoning models
 *    without a separate code path.
 *  - The mandatory `<output>` block gives the chat UI a precise boundary at
 *    which to stop showing the "Thinking…" disclosure and start streaming the
 *    final answer.
 *  - `body ::= [^<]*` forbids stray `<` inside the bodies, which keeps the
 *    state-machine parser unambiguous; markdown that needs HTML can use
 *    backtick code fences instead.
 */
val STRICT_THINKING_OUTPUT_GRAMMAR: String = """
root           ::= thinking-block? "<output>" body "</output>"
thinking-block ::= "<think>" body "</think>" | "<thinking>" body "</thinking>"
body           ::= [^<]*
""".trimIndent()

/**
 * System-prompt suffix that explains the structured-output contract to the
 * model. Append to the user-supplied / model-default system prompt so the
 * model knows *what* the grammar is asking for, not just *that* its tokens
 * are being filtered.
 */
fun structuredOutputSystemPromptSuffix(): String = """

# Response Format
You MUST format every reply as follows:
1. Optionally, begin with a `<think>...</think>` block containing your private chain-of-thought reasoning. Use plain prose; do not include any markdown formatting inside the thinking block.
2. Then emit a `<output>...</output>` block containing your final answer to the user. Inside `<output>`, use GitHub-flavored markdown (headings, lists, fenced code blocks, bold/italic) freely.
3. Do not write anything outside these two blocks. Do not nest blocks. Avoid the literal `<` character inside either block except as the opening of the closing tag. If you must reference a literal `<` inside an output block (e.g. in a code example), escape it as `&lt;`.

Example:
<think>The user is asking about X. I should explain Y because Z.</think><output>Here is the answer in **markdown**.</output>
""".trimIndent()
