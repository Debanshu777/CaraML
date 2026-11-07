package com.debanshu777.caraml.sdk.plugin.onnx

import kotlinx.serialization.json.*

/** The byte-level BPE subset used by GPT-2, OPT, Qwen2 and Llama/SmolLM exports.
 * Unsupported tokenizer pipelines fail at load instead of silently changing token IDs. */
internal class HuggingFaceBpeTokenizer(text: String) {
    private val root = Json.parseToJsonElement(text).jsonObject
    private val model = root.getValue("model").jsonObject
    private val vocab = model.getValue("vocab").jsonObject.mapValues { it.value.jsonPrimitive.int }
    private val inverse = vocab.entries.associate { (text, id) -> id to text }
    private val byteChars: List<Char> = buildList {
        val direct = (33..126).toSet() + (161..172) + (174..255)
        var next = 256
        repeat(256) { add(if (it in direct) it.toChar() else (next++).toChar()) }
    }
    private val charBytes = byteChars.withIndex().associate { it.value to it.index.toByte() }
    private val ranks = model.getValue("merges").jsonArray.mapIndexed { index, value ->
        val pair = if (value is JsonArray) value.map { it.jsonPrimitive.content }
        else value.jsonPrimitive.content.split(' ')
        require(pair.size == 2) { "Unsupported BPE merge" }
        (pair[0] to pair[1]) to index
    }.toMap()
    private val added = root["added_tokens"]?.jsonArray.orEmpty().map { it.jsonObject }
    private val addedIds = added.associate { it.getValue("content").jsonPrimitive.content to it.getValue("id").jsonPrimitive.int }
    private val specialIds = added.filter { it["special"]?.jsonPrimitive?.booleanOrNull == true }
        .mapTo(hashSetOf()) { it.getValue("id").jsonPrimitive.int }
    private val addedById = addedIds.entries.associate { it.value to it.key }
    private val specialPattern = addedIds.keys.takeIf { it.isNotEmpty() }?.sortedByDescending(String::length)
        ?.joinToString("|", transform = Regex::escape)?.let(::Regex)
    private val cache = LinkedHashMap<String, IntArray>()
    private val ignoreMerges = model["ignore_merges"]?.jsonPrimitive?.booleanOrNull == true

    init {
        require(model["type"]?.jsonPrimitive?.content == "BPE") { "Only byte-level BPE ONNX tokenizers are supported" }
        require(root["normalizer"] == null || root["normalizer"] == JsonNull ||
            root["normalizer"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "NFC") { "Unsupported tokenizer normalizer" }
        require(model["byte_fallback"]?.jsonPrimitive?.booleanOrNull != true) { "SentencePiece byte fallback is not supported" }
        require(model["continuing_subword_prefix"] in listOf(null, JsonNull, JsonPrimitive("")))
        require(model["end_of_word_suffix"] in listOf(null, JsonNull, JsonPrimitive("")))
        require(root["decoder"]?.jsonObject?.get("type")?.jsonPrimitive?.content == "ByteLevel") { "Unsupported tokenizer decoder" }
        validatePreTokenizer(root.getValue("pre_tokenizer").jsonObject)
        require(added.none { it["lstrip"]?.jsonPrimitive?.booleanOrNull == true ||
            it["rstrip"]?.jsonPrimitive?.booleanOrNull == true || it["single_word"]?.jsonPrimitive?.booleanOrNull == true }) {
            "Unsupported added-token whitespace rules"
        }
    }

    fun has(token: String) = token in vocab || token in addedIds
    fun tokenId(token: String): Int? = addedIds[token] ?: vocab[token]

    fun encode(text: String): IntArray {
        val result = ArrayList<Int>()
        val matches = specialPattern?.findAll(text).orEmpty()
        var offset = 0
        for (match in matches) {
            encodeOrdinary(text.substring(offset, match.range.first), result)
            result += addedIds.getValue(match.value)
            offset = (match.range.last + 1)
        }
        encodeOrdinary(text.substring(offset), result)
        return result.toIntArray()
    }

    fun decode(ids: List<Int>): String {
        val bytes = ArrayList<Byte>()
        ids.filterNot { it in specialIds }.forEach { id ->
            val token = inverse[id] ?: addedById[id] ?: error("Unknown token ID $id")
            token.forEach { bytes.add(charBytes[it] ?: error("Non byte-level token")) }
        }
        val data = bytes.toByteArray()
        // A token may end in a partial UTF-8 character. Wait for its continuation bytes.
        var complete = data.size
        var lead = data.lastIndex
        while (lead >= 0 && data[lead].toInt() and 0xc0 == 0x80) lead--
        if (lead >= 0) {
            val first = data[lead].toInt() and 255
            val expected = when { first < 0x80 -> 1; first < 0xe0 -> 2; first < 0xf0 -> 3; else -> 4 }
            if (data.size - lead < expected) complete = lead
        }
        return data.decodeToString(0, complete)
    }

    private fun encodeOrdinary(text: String, result: MutableList<Int>) {
        if (text.isEmpty()) return
        val normalized = if (root["normalizer"] != null && root["normalizer"] != JsonNull) normalizeOnnxText(text) else text
        preTokenize(listOf(normalized), root.getValue("pre_tokenizer").jsonObject).forEach { part ->
            val encoded = part.encodeToByteArray().joinToString("") { byteChars[it.toInt() and 255].toString() }
            val tokens = cache[encoded] ?: bpe(encoded).also {
                if (cache.size >= 4096) cache.clear()
                cache[encoded] = it
            }
            tokens.forEach(result::add)
        }
    }

    private fun bpe(word: String): IntArray {
        if (ignoreMerges) vocab[word]?.let { return intArrayOf(it) }
        val pieces = word.map(Char::toString).toMutableList()
        while (pieces.size > 1) {
            var best = Int.MAX_VALUE
            var at = -1
            for (i in 0 until pieces.lastIndex) {
                val rank = ranks[pieces[i] to pieces[i + 1]] ?: continue
                if (rank < best) { best = rank; at = i }
            }
            if (at < 0) break
            pieces[at] += pieces.removeAt(at + 1)
        }
        return pieces.map { vocab[it] ?: error("Tokenizer vocabulary is missing a byte token") }.toIntArray()
    }

    private fun validatePreTokenizer(spec: JsonObject) {
        when (spec.getValue("type").jsonPrimitive.content) {
            "Sequence" -> spec.getValue("pretokenizers").jsonArray.forEach { validatePreTokenizer(it.jsonObject) }
            "ByteLevel", "Digits" -> Unit
            "Split" -> {
                require(spec["behavior"]?.jsonPrimitive?.content == "Isolated" && spec["invert"]?.jsonPrimitive?.booleanOrNull != true)
                onnxUnicodeRegex(spec.getValue("pattern").jsonObject.getValue("Regex").jsonPrimitive.content)
            }
            else -> error("Unsupported tokenizer pre-tokenizer")
        }
    }

    private fun preTokenize(parts: List<String>, spec: JsonObject): List<String> = when (spec.getValue("type").jsonPrimitive.content) {
        "Sequence" -> spec.getValue("pretokenizers").jsonArray.fold(parts) { current, step -> preTokenize(current, step.jsonObject) }
        "Digits" -> parts.flatMap { splitKeepingMatches(it, onnxUnicodeRegex(if (spec["individual_digits"]?.jsonPrimitive?.booleanOrNull == true) "\\p{N}" else "\\p{N}+")) }
        "Split" -> parts.flatMap { splitKeepingMatches(it, onnxUnicodeRegex(spec.getValue("pattern").jsonObject.getValue("Regex").jsonPrimitive.content)) }
        "ByteLevel" -> parts.flatMap { part ->
            val prefixed = if (spec["add_prefix_space"]?.jsonPrimitive?.booleanOrNull == true && !part.startsWith(' ')) " $part" else part
            if (spec["use_regex"]?.jsonPrimitive?.booleanOrNull == false) listOf(prefixed)
            else splitKeepingMatches(prefixed, BYTE_LEVEL_PATTERN)
        }
        else -> error("Unsupported tokenizer pre-tokenizer")
    }

    private fun splitKeepingMatches(text: String, pattern: OnnxTextPattern): List<String> = buildList {
        val matches = pattern.findAll(text)
        var offset = 0
        for (match in matches) {
            if (match.range.first > offset) add(text.substring(offset, match.range.first))
            if ((match.range.last + 1) > match.range.first) add(match.value)
            offset = (match.range.last + 1)
        }
        if (offset < text.length) add(text.substring(offset))
    }

    companion object {
        private val BYTE_LEVEL_PATTERN = onnxUnicodeRegex("'s|'t|'re|'ve|'m|'ll|'d| ?\\p{L}+| ?\\p{N}+| ?[^\\s\\p{L}\\p{N}]+|\\s+(?!\\S)|\\s+")
    }
}
