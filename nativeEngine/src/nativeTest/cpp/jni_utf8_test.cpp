#include "JniUtf8.h"

#include <cstdlib>
#include <string>
#include <vector>

// Checks must execute in Release builds as well as Debug builds.
static void check(bool condition) {
    if (!condition) std::abort();
}

int main() {
    using caraml::jni_utf8::Utf8StreamDecoder;
    using caraml::jni_utf8::utf16_to_utf8;

    const jchar text[] = {u'A', 0x0939, 0xD83D, 0xDE00, 0x0301};
    std::string encoded;
    check(utf16_to_utf8(text, 5, encoded));
    check(encoded == "A\xE0\xA4\xB9\xF0\x9F\x98\x80\xCC\x81");

    const jchar unpaired[] = {0xD83D};
    check(!utf16_to_utf8(unpaired, 1, encoded));
    const jchar embedded_nul[] = {u'a', 0, u'b'};
    check(!utf16_to_utf8(embedded_nul, 3, encoded));

    Utf8StreamDecoder decoder;
    std::vector<jchar> decoded;
    const char first[] = {static_cast<char>(0xF0), static_cast<char>(0x9F)};
    const char second[] = {static_cast<char>(0x98), static_cast<char>(0x80)};
    check(decoder.append(first, sizeof(first), decoded));
    check(decoded.empty());
    check(decoder.append(second, sizeof(second), decoded));
    check(decoded.size() == 2 && decoded[0] == 0xD83D && decoded[1] == 0xDE00);

    decoder.reset();
    decoded.clear();
    const char malformed[] = {static_cast<char>(0xC0), 'x'};
    check(decoder.append(malformed, sizeof(malformed), decoded));
    check(decoded.size() == 2 && decoded[0] == 0xFFFD && decoded[1] == u'x');

    decoder.reset();
    decoded.clear();
    check(decoder.append(first, sizeof(first), decoded));
    check(decoder.finish(decoded));
    check(decoded.size() == 1 && decoded[0] == 0xFFFD);

    decoder.reset();
    decoded.clear();
    const char malformed_prefix[] = {static_cast<char>(0xE2), 'A'};
    check(decoder.append(malformed_prefix, sizeof(malformed_prefix), decoded));
    check(decoded.size() == 2 && decoded[0] == 0xFFFD && decoded[1] == u'A');
    return 0;
}
