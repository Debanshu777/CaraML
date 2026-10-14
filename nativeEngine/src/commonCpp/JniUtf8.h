#pragma once

#include <jni.h>

#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace caraml::jni_utf8 {

// Inputs arrive as UTF-16 in Java. Convert them to standard UTF-8 and reject
// malformed surrogate pairs and embedded NULs before passing them to C APIs.
inline bool utf16_to_utf8(const jchar *input, size_t length, std::string &output,
                          bool reject_nul = true) {
    output.clear();
    if (input == nullptr && length != 0) return false;
    output.reserve(length);
    for (size_t i = 0; i < length; ++i) {
        uint32_t cp = input[i];
        if (cp == 0 && reject_nul) return false;
        if (cp >= 0xD800 && cp <= 0xDBFF) {
            if (++i >= length) return false;
            const uint32_t low = input[i];
            if (low < 0xDC00 || low > 0xDFFF) return false;
            cp = 0x10000 + ((cp - 0xD800) << 10) + (low - 0xDC00);
        } else if (cp >= 0xDC00 && cp <= 0xDFFF) {
            return false;
        }
        if (cp <= 0x7F) {
            output.push_back(static_cast<char>(cp));
        } else if (cp <= 0x7FF) {
            output.push_back(static_cast<char>(0xC0 | (cp >> 6)));
            output.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else if (cp <= 0xFFFF) {
            output.push_back(static_cast<char>(0xE0 | (cp >> 12)));
            output.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        } else {
            output.push_back(static_cast<char>(0xF0 | (cp >> 18)));
            output.push_back(static_cast<char>(0x80 | ((cp >> 12) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | ((cp >> 6) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | (cp & 0x3F)));
        }
    }
    return true;
}

// Native token deltas can end in the middle of a UTF-8 code point. Keep only
// that incomplete suffix for the next call; malformed complete bytes become
// U+FFFD. finish() replaces a trailing incomplete sequence at stream end.
class Utf8StreamDecoder {
public:
    bool append(const char *bytes, size_t length, std::vector<jchar> &output) {
        if (bytes == nullptr && length != 0) return false;
        if (length != 0) pending_.append(bytes, length);
        return decode(false, output);
    }

    bool finish(std::vector<jchar> &output) { return decode(true, output); }
    void reset() { pending_.clear(); }
    bool has_pending() const { return !pending_.empty(); }

private:
    static void append_codepoint(uint32_t cp, std::vector<jchar> &out) {
        if (cp <= 0xFFFF) {
            out.push_back(static_cast<jchar>(cp));
        } else {
            cp -= 0x10000;
            out.push_back(static_cast<jchar>(0xD800 + (cp >> 10)));
            out.push_back(static_cast<jchar>(0xDC00 + (cp & 0x3FF)));
        }
    }

    bool decode(bool final, std::vector<jchar> &output) {
        size_t i = 0;
        while (i < pending_.size()) {
            const auto lead = static_cast<uint8_t>(pending_[i]);
            uint32_t cp = 0;
            size_t width = 0;
            if (lead <= 0x7F) { cp = lead; width = 1; }
            else if (lead >= 0xC2 && lead <= 0xDF) { cp = lead & 0x1F; width = 2; }
            else if (lead >= 0xE0 && lead <= 0xEF) { cp = lead & 0x0F; width = 3; }
            else if (lead >= 0xF0 && lead <= 0xF4) { cp = lead & 0x07; width = 4; }
            else { append_codepoint(0xFFFD, output); ++i; continue; }

            const size_t available = pending_.size() - i;
            const size_t checked = available < width ? available : width;
            bool valid = true;
            for (size_t j = 1; j < checked; ++j) {
                const auto next = static_cast<uint8_t>(pending_[i + j]);
                if ((next & 0xC0) != 0x80) { valid = false; break; }
                if (j == 1 && ((lead == 0xE0 && next < 0xA0) ||
                               (lead == 0xED && next > 0x9F) ||
                               (lead == 0xF0 && next < 0x90) ||
                               (lead == 0xF4 && next > 0x8F))) {
                    valid = false;
                    break;
                }
                cp = (cp << 6) | (next & 0x3F);
            }
            if (!valid) { append_codepoint(0xFFFD, output); ++i; continue; }
            if (available < width) {
                if (!final) break;
                append_codepoint(0xFFFD, output);
                i += available;
                continue;
            }
            if (valid && ((width == 3 && cp < 0x800) || (width == 4 && cp < 0x10000) ||
                          cp > 0x10FFFF || (cp >= 0xD800 && cp <= 0xDFFF))) valid = false;
            if (!valid) { append_codepoint(0xFFFD, output); ++i; continue; }
            append_codepoint(cp, output);
            i += width;
        }
        pending_.erase(0, i);
        return true;
    }

    std::string pending_;
};

inline bool from_java(JNIEnv *env, jstring value, std::string &output,
                      bool reject_nul = true) {
    if (value == nullptr) { output.clear(); return true; }
    const jsize length = env->GetStringLength(value);
    if (env->ExceptionCheck()) return false;
    const jchar *chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr || env->ExceptionCheck()) return false;
    const bool ok = utf16_to_utf8(chars, static_cast<size_t>(length), output, reject_nul);
    env->ReleaseStringChars(value, chars);
    return ok && !env->ExceptionCheck();
}

inline jstring to_java(JNIEnv *env, const char *bytes, size_t length,
                       Utf8StreamDecoder *stream = nullptr, bool final = true) {
    std::vector<jchar> chars;
    Utf8StreamDecoder local;
    Utf8StreamDecoder &decoder = stream ? *stream : local;
    if (!decoder.append(bytes, length, chars)) return nullptr;
    if (final && !decoder.finish(chars)) return nullptr;
    static const jchar empty = 0;
    return env->NewString(chars.empty() ? &empty : chars.data(), static_cast<jsize>(chars.size()));
}

} // namespace caraml::jni_utf8
