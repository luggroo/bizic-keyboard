#include "edit.hpp"
#include <stdexcept>

namespace tujia {
namespace {
bool wordLetter(char32_t c) {
    return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') ||
           (c >= 0xc0 && c <= 0x2af && c != 0xd7 && c != 0xf7) ||
           (c >= 0x300 && c <= 0x36f) || (c >= 0x1e00 && c <= 0x1eff);
}

// The core emits UTF-8. Decode only the character touching the editor boundary.
char32_t scalarAt(const std::string& text, size_t start) {
    unsigned char lead = text[start];
    if (lead < 0x80) return lead;
    int extra = lead < 0xe0 ? 1 : lead < 0xf0 ? 2 : 3;
    if (start + extra >= text.size() || lead < 0xc2 || lead > 0xf4) return 0;
    char32_t scalar = lead & (0x7f >> (extra + 1));
    for (int i = 1; i <= extra; ++i) {
        unsigned char next = text[start + i];
        if ((next & 0xc0) != 0x80) return 0;
        scalar = (scalar << 6) | (next & 0x3f);
    }
    return scalar;
}

char32_t lastScalar(const std::string& text) {
    size_t start = text.size() - 1;
    while (start > 0 && (static_cast<unsigned char>(text[start]) & 0xc0) == 0x80) --start;
    return scalarAt(text, start);
}
}

std::string suffixAfterChoice(const std::string& draft, int consumedUTF16) {
    if (consumedUTF16 < 0) throw std::invalid_argument("Negative selection length");
    std::size_t offset = 0;
    int units = 0;
    while (units < consumedUTF16 && offset < draft.size()) {
        unsigned char first = static_cast<unsigned char>(draft[offset]);
        int length = first < 0x80 ? 1 : (first & 0xe0) == 0xc0 ? 2 :
                     (first & 0xf0) == 0xe0 ? 3 : (first & 0xf8) == 0xf0 ? 4 : 0;
        if (!length || offset + length > draft.size()) throw std::invalid_argument("Invalid UTF-8");
        for (int i = 1; i < length; ++i) {
            if ((static_cast<unsigned char>(draft[offset+i]) & 0xc0) != 0x80)
                throw std::invalid_argument("Invalid UTF-8");
        }
        offset += length;
        units += length == 4 ? 2 : 1;
    }
    if (units != consumedUTF16) throw std::invalid_argument("Selection crosses character boundary");
    offset = draft.find_first_not_of(" \t\r\n\v\f", offset);
    return offset == std::string::npos ? std::string() : draft.substr(offset);
}

std::string spaceWord(const std::string& candidate, char16_t before, char16_t after) {
    if (candidate.empty()) return {};
    std::string result;
    if (wordLetter(before) && wordLetter(scalarAt(candidate, 0))) result += ' ';
    result += candidate;
    if (wordLetter(after) && wordLetter(lastScalar(candidate))) result += ' ';
    return result;
}
}
