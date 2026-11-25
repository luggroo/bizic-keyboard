#include "lexicon.hpp"
#include "handwords.hpp"

#include <algorithm>
#include <array>
#include <climits>
#include <cstdint>
#include <limits>
#include <stdexcept>
#include <string_view>
#include <unordered_map>
#include <unordered_set>
#include <utility>

namespace tujia {
namespace {

using WordIds = std::vector<size_t>;
using WordIndex = std::unordered_map<std::string, WordIds>;

std::vector<std::string> split(const std::string& text, char separator) {
    std::vector<std::string> parts;
    size_t begin = 0;
    for (;;) {
        size_t end = text.find(separator, begin);
        parts.push_back(text.substr(begin, end == std::string::npos ? end : end - begin));
        if (end == std::string::npos) return parts;
        begin = end + 1;
    }
}

bool readLine(std::string_view& rest, std::string_view& line) {
    if (rest.empty()) return false;
    const size_t end = rest.find('\n');
    line = rest.substr(0, end);
    rest.remove_prefix(end == rest.npos ? rest.size() : end + 1);
    if (!line.empty() && line.back() == '\r') line.remove_suffix(1);
    return true;
}

using Fields = std::array<std::string_view, 15>;
void readFields(std::string_view row, size_t count, Fields& fields) {
    size_t start = 0, column = 0;
    for (size_t i = 0; i < row.size(); ++i) {
        if (row[i] == '\t') {
            if (column >= count - 1) throw std::invalid_argument("Too many dictionary fields");
            fields[column++] = row.substr(start, i - start);
            start = i + 1;
        }
    }
    if (column != count - 1) throw std::invalid_argument("Missing dictionary fields");
    fields[column] = row.substr(start);
}

// Decode exactly one Unicode scalar; reject overlong encodings and UTF-16 surrogates.
bool nextScalar(std::string_view s, size_t& pos, char32_t& cp) {
    const auto lead = static_cast<unsigned char>(s[pos++]);
    if (lead < 0x80) {
        cp = lead;
        return true;
    }
    if (lead < 0xc2 || lead > 0xf4) return false;
    int continuation;
    if (lead < 0xe0) { cp = lead & 0x1f; continuation = 1; }
    else if (lead < 0xf0) { cp = lead & 0x0f; continuation = 2; }
    else { cp = lead & 0x07; continuation = 3; }
    if (s.size() - pos < static_cast<size_t>(continuation)) return false;
    for (int i = 0; i < continuation; ++i) {
        const auto byte = static_cast<unsigned char>(s[pos++]);
        if ((byte & 0xc0) != 0x80) return false;
        cp = (cp << 6) | (byte & 0x3f);
    }
    return !(continuation == 2 && cp < 0x800) && !(continuation == 3 && cp < 0x10000) &&
           !(cp >= 0xd800 && cp <= 0xdfff) && cp <= 0x10ffff;
}

size_t countScalars(std::string_view text) {
    size_t count = 0;
    for (size_t pos = 0; pos < text.size(); ++count) {
        char32_t cp;
        if (!nextScalar(text, pos, cp)) throw std::invalid_argument("Invalid UTF-8 in dictionary");
    }
    return count;
}

std::u32string dictionaryScalars(const std::string& text) {
    std::u32string out;
    for (size_t pos = 0; pos < text.size();) {
        char32_t cp;
        if (!nextScalar(text, pos, cp)) throw std::invalid_argument("Invalid UTF-8 in dictionary");
        out.push_back(cp);
    }
    return out;
}

bool space(char32_t c) {
    // Match the editor's whitespace handling. Non-breaking spaces are literal text.
    return (c >= 9 && c <= 13) || (c >= 0x1c && c <= 0x20) || c == 0x1680 ||
           (c >= 0x2000 && c <= 0x200a && c != 0x2007) || c == 0x2028 || c == 0x2029 ||
           c == 0x205f || c == 0x3000;
}

bool punctuation(char32_t c) {
    static const std::u32string marks = U"，。！？、,.!?";
    return marks.find(c) != std::u32string::npos;
}

bool hanCharacter(char32_t c) {
    return (c >= 0x3400 && c <= 0x4dbf) || (c >= 0x4e00 && c <= 0x9fff) ||
           (c >= 0xf900 && c <= 0xfaff) || (c >= 0x20000 && c <= 0x2ee5f) ||
           (c >= 0x2f800 && c <= 0x2fa1f) || (c >= 0x30000 && c <= 0x3347f) || c == 0x3007;
}

// These are Unicode spellings of the same typed character. Lexical readings
// (including the tone-free forms) always come from the source dictionary.
char32_t lowerLatin(char32_t c) {
    if (c >= U'A' && c <= U'Z') return c + 32;
    if (c < 0x00c0 || c > 0x1eff) return c;
    constexpr std::u32string_view upper = U"ĀÁǍÀĒÉĚÈĪÍǏÌŌÓǑÒŪÚǓÙÜǕǗǙǛŃŇǸḾ";
    constexpr std::u32string_view lower = U"āáǎàēéěèīíǐìōóǒòūúǔùüǖǘǚǜńňǹḿ";
    const auto i = upper.find(c);
    return i == upper.npos ? c : lower[i];
}

char32_t composedVowel(char32_t base, char32_t mark) {
    if (mark == 0x0308 && base == U'u') return U'ü';
    constexpr std::u32string_view marks = U"\u0304\u0301\u030c\u0300";
    constexpr std::u32string_view bases = U"aeiouünm";
    constexpr std::u32string_view forms = U"āáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜ ńňǹ ḿ  ";
    const auto row = bases.find(base), column = marks.find(mark);
    if (row == bases.npos || column == marks.npos) return 0;
    const char32_t composed = forms[row * 4 + column];
    return composed == U' ' ? 0 : composed;
}

void appendUtf8(std::string& out, char32_t c) {
    if (c <= 0x7f) { out.push_back(static_cast<char>(c)); return; }
    int bits = c <= 0x7ff ? 6 : c <= 0xffff ? 12 : 18;
    const unsigned head = bits == 6 ? 0xc0 : bits == 12 ? 0xe0 : 0xf0;
    out.push_back(static_cast<char>(head | (c >> bits)));
    while (bits > 0) {
        bits -= 6;
        out.push_back(static_cast<char>(0x80 | ((c >> bits) & 0x3f)));
    }
}

struct Query {
    std::string folded;
    std::u32string scalars;
    std::vector<size_t> byteAt{0};
    std::vector<int> utf16At{0};

    bool read(const std::string& input) {
        if (input.size() > INT_MAX) return false;
        folded.reserve(input.size());
        for (size_t pos = 0; pos < input.size();) {
            char32_t cp;
            if (!nextScalar(input, pos, cp)) return false;
            const int consumed = utf16At.back() + (cp >= 0x10000 ? 2 : 1);
            cp = lowerLatin(cp);
            if (cp >= 0x0300 && cp <= 0x030c && !scalars.empty()) {
                char32_t composed = composedVowel(scalars.back(), cp);
                if (composed) {
                    folded.resize(byteAt[scalars.size() - 1]);
                    appendUtf8(folded, composed);
                    scalars.back() = composed;
                    byteAt.back() = folded.size();
                    utf16At.back() = consumed;
                    continue;
                }
            }
            appendUtf8(folded, cp);
            scalars.push_back(cp);
            byteAt.push_back(folded.size());
            utf16At.push_back(consumed);
        }
        return true;
    }

    std::string slice(size_t begin, size_t end) const {
        return folded.substr(byteAt[begin], byteAt[end] - byteAt[begin]);
    }

    size_t skipSpaces(size_t pos) const {
        while (pos < scalars.size() && space(scalars[pos])) ++pos;
        return pos;
    }
};

struct Entry {
    std::string id, word, meaning, han, shortMeaning, diacritic;
    uint64_t frequency;
    std::vector<std::u32string> variants;
    std::vector<std::u32string> units{};
    std::vector<int> joinedRanges{};
    int hanLength = 0;
    std::vector<std::string> writtenForms{};
    bool plainHanLoan = false;
    std::string lastSyllable{};
    size_t hanSenseGroup = 0;

    void setUnits(const std::string& field) {
        std::string combined;
        for (const auto& unit : split(field, '|')) {
            if (unit.empty()) throw std::invalid_argument("Empty Han unit");
            combined += unit;
            auto symbols = dictionaryScalars(unit);
            const int start = hanLength;
            for (auto cp : symbols) hanLength += cp >= 0x10000 ? 2 : 1;
            if (symbols.size() == 2 && hanCharacter(symbols[0]) && hanCharacter(symbols[1])) {
                joinedRanges.push_back(start);
                joinedRanges.push_back(hanLength);
            }
            units.push_back(std::move(symbols));
        }
        if (combined != han) throw std::invalid_argument("Han units do not reconstruct recommended form: " + id);
    }

    bool matchesVariants(const Query& query, size_t start, size_t end) const {
        if (!units.empty()) {
            if (end - start < units.size()) return false;
            // An alternative occupies one whole syllable. Never match just
            // the first/second character of a canonical two-Han unit.
            std::vector<size_t> positions{start};
            for (size_t i = 0; i < units.size(); ++i) {
                std::vector<size_t> next;
                for (size_t pos : positions) {
                    if (i < variants.size() && pos < end &&
                        variants[i].find(query.scalars[pos]) != std::u32string::npos)
                        next.push_back(pos + 1);
                    const auto& unit = units[i];
                    if (unit.size() <= end - pos && query.scalars.compare(pos, unit.size(), unit) == 0)
                        next.push_back(pos + unit.size());
                }
                if (next.empty()) return false;
                std::sort(next.begin(), next.end());
                next.erase(std::unique(next.begin(), next.end()), next.end());
                positions.swap(next);
            }
            return std::find(positions.begin(), positions.end(), end) != positions.end();
        }
        if (variants.size() != end - start) return false;
        for (size_t i = 0; i < variants.size(); ++i) {
            if (variants[i].find(query.scalars[start + i]) == std::u32string::npos) return false;
        }
        return true;
    }

    bool extends(const Query& q) const {
        for (const auto& form : writtenForms) {
            if (form.size() > q.folded.size() && form.compare(0, q.folded.size(), q.folded) == 0) return true;
        }
        const auto length = q.scalars.size();
        const size_t groups = units.empty() ? variants.size() : units.size();
        std::vector<size_t> positions{0};
        for (size_t i = 0; i < groups; ++i) {
            std::vector<size_t> next;
            for (auto pos : positions) {
                if (pos == length) return true; // remaining syllable(s)
                if (i < variants.size() && variants[i].find(q.scalars[pos]) != std::u32string::npos)
                    next.push_back(pos + 1);
                if (i < units.size()) {
                    const auto& unit = units[i];
                    size_t take = std::min(unit.size(), length - pos);
                    if (q.scalars.compare(pos, take, unit, 0, take) == 0) {
                        if (take < unit.size()) return true; // started a real two-Han unit
                        next.push_back(pos + take);
                    }
                }
            }
            if (next.empty()) break;
            std::sort(next.begin(), next.end());
            next.erase(std::unique(next.begin(), next.end()), next.end());
            positions.swap(next);
        }
        return false;
    }

    const std::string& output(Mode mode, Orthography orthography) const {
        if (mode == Mode::Han) return han;
        return orthography == Orthography::Diacritic ? diacritic : word;
    }
};

uint64_t frequencyOf(std::string_view field) {
    if (field.empty()) throw std::invalid_argument("Missing frequency");
    uint64_t result = 0;
    for (char c : field) {
        if (c < '0' || c > '9' || result > (std::numeric_limits<uint64_t>::max() - (c - '0')) / 10)
            throw std::invalid_argument("Invalid frequency");
        result = result * 10 + (c - '0');
    }
    return result;
}

struct ChineseWord {
    std::string text;
    uint64_t frequency;
};

const WordIds& bucket(const WordIndex& index, const std::string& key) {
    static const WordIds empty;
    auto it = index.find(key);
    return it == index.end() ? empty : it->second;
}

void addUnique(WordIds& matches, size_t id) {
    if (std::find(matches.begin(), matches.end(), id) == matches.end()) matches.push_back(id);
}

using Segments = std::vector<std::pair<std::string, std::string>>;

bool romanSeparator(char32_t c) { return space(c) || c == U'-' || c == U'\'' || c == 0x2019; }

bool nasalA(char32_t c) { return c == U'a' || c == U'ā' || c == U'á' || c == U'ǎ' || c == U'à'; }

constexpr char32_t optionalG = 0x110000; // an internal index edge, never a Unicode input character
constexpr char32_t optionalU = 0x110001; // o/uo are alternate input spellings after an onset

bool onsetLetter(char32_t c) {
    return std::u32string_view(U"bcdfghjklmnpqrstvwxyz").find(c) != std::u32string_view::npos;
}

// Do not expose the glide in an adjacent uo spelling as a separate completed
// u syllable. A space or apostrophe continues to mark an intentional split.
bool insideUo(const Query& q, size_t start, size_t end) {
    return end > start + 1 && end < q.scalars.size() && q.scalars[end - 1] == U'u' &&
           onsetLetter(q.scalars[end - 2]) &&
           std::u32string_view(U"oōóǒò").find(q.scalars[end]) != std::u32string_view::npos;
}

std::u32string plainSyllable(const std::u32string& ye) {
    return !ye.empty() && std::u32string_view(U"xfrv").find(ye.back()) != std::u32string_view::npos
            ? ye.substr(0, ye.size() - 1) : ye;
}

// Each optional g is at the end of an attested syllable, before its Ye tone
// letter. In an+ga the initial of the SECOND syllable is always compulsory.
std::u32string nasalPattern(const std::u32string& form, const Segments& segments, bool alternateO = true) {
    for (int style = 0; style < 3; ++style) {
        std::u32string pattern;
        size_t at = 0, matched = 0;
        for (const auto& part : segments) {
            while (at < form.size() && romanSeparator(form[at])) pattern.push_back(form[at++]);
            auto syllable = dictionaryScalars(style == 1 ? part.second : part.first);
            const auto plain = plainSyllable(dictionaryScalars(part.first));
            if (style == 2) syllable = plain;
            if (syllable.empty() || form.compare(at, syllable.size(), syllable) != 0) break;
            at += syllable.size();
            ++matched;
            const size_t end = style == 0 && syllable.size() > plain.size() ? syllable.size() - 1 : syllable.size();
            const size_t coda = end && syllable[end - 1] == U'g' ? end - 1 : end;
            size_t onset = plain.size();
            if (onset && plain.back() == U'o') {
                --onset;
                if (onset && plain[onset - 1] == U'u') --onset;
            } else onset = 0;
            const bool hasU = onset < syllable.size() && syllable[onset] == U'u';
            const size_t vowel = onset + (hasU ? 1 : 0);
            const bool simpleO = onset && vowel + 1 == end &&
                    std::u32string_view(U"oōóǒò").find(syllable[vowel]) != std::u32string_view::npos &&
                    std::all_of(plain.begin(), plain.begin() + onset, onsetLetter);
            if (alternateO && simpleO) {
                pattern.append(syllable, 0, onset);
                pattern.push_back(optionalU);
                pattern.append(syllable, onset + (hasU ? 1 : 0), syllable.size() - onset - (hasU ? 1 : 0));
            } else if (coda >= 2 && nasalA(syllable[coda - 2]) && syllable[coda - 1] == U'n') {
                pattern.append(syllable, 0, coda);
                pattern.push_back(optionalG);
                pattern.append(syllable, end, syllable.size() - end);
            } else pattern += syllable;
        }
        if (matched == segments.size() && at == form.size() &&
            (pattern.find(optionalG) != pattern.npos || pattern.find(optionalU) != pattern.npos)) return pattern;
    }
    return {};
}

constexpr char32_t optionalTone = 0x110100;

char32_t optionalLetter(char32_t edge) {
    if (edge == optionalG) return U'g';
    if (edge == optionalU) return U'u';
    return edge >= optionalTone ? edge - optionalTone : 0;
}

std::u32string yeTonePattern(const Segments& parts) {
    if (parts.size() < 2) return {};
    std::u32string pattern;
    for (const auto& part : parts) {
        auto syllable = dictionaryScalars(part.first);
        auto alternate = nasalPattern(syllable, Segments{part});
        pattern += alternate.empty() ? syllable : alternate;
        pattern.back() += optionalTone;
    }
    return pattern;
}

std::string nasalBucket(std::u32string_view form);

std::string toneBucket(std::u32string_view form) {
    std::u32string key;
    for (auto cp : form) {
        if (cp < optionalTone && std::u32string_view(U"xfrv").find(cp) == std::u32string_view::npos)
            key.push_back(cp);
    }
    return nasalBucket(key);
}

bool patternMatches(std::u32string_view pattern, std::u32string_view input, bool prefix) {
    std::vector<size_t> positions{0}, next;
    for (size_t i = 0;; ++i) {
        for (size_t p = 0; p < positions.size(); ++p) {
            size_t pos = positions[p];
            if (pos < pattern.size() && optionalLetter(pattern[pos]) &&
                std::find(positions.begin(), positions.end(), pos + 1) == positions.end())
                positions.push_back(pos + 1);
        }
        if (i == input.size()) return !positions.empty() &&
                (prefix || std::find(positions.begin(), positions.end(), pattern.size()) != positions.end());
        next.clear();
        for (size_t pos : positions) {
            if (pos < pattern.size() && (pattern[pos] == input[i] || (optionalLetter(pattern[pos]) && optionalLetter(pattern[pos]) == input[i])))
                next.push_back(pos + 1);
        }
        if (next.empty()) return false;
        positions.swap(next);
    }
}

// A coarse hash only locates possible patterns. The optional-edge matcher
// above verifies all mandatory letters; no Cartesian product of aliases is built.
std::string nasalBucket(std::u32string_view form) {
    std::string key;
    bool afterAn = false, previousA = false;
    for (size_t i = 0; i < form.size(); ++i) {
        char32_t cp = form[i];
        const bool afterOnset = i > 0 && onsetLetter(form[i - 1]);
        const bool beforeO = i + 1 == form.size() || std::u32string_view(U"oōóǒò").find(form[i + 1]) != std::u32string_view::npos;
        if (cp == optionalG || cp == optionalU || (afterAn && cp == U'g') ||
            (cp == U'u' && afterOnset && beforeO)) continue;
        appendUtf8(key, cp);
        afterAn = previousA && cp == U'n';
        previousA = nasalA(cp);
    }
    return key;
}

struct OptionalIndex {
    using BucketKey = std::string (*)(std::u32string_view);
    explicit OptionalIndex(BucketKey key = nasalBucket) : bucketKey(key) {}
    BucketKey bucketKey;
    struct Form { std::u32string pattern; size_t id; };
    std::vector<Form> forms;
    WordIndex exact, prefixes;

    void add(std::u32string pattern, size_t id, size_t& longest, bool complete) {
        if (pattern.empty()) return;
        const size_t key = forms.size();
        auto& existing = exact[bucketKey(pattern)];
        for (size_t index : existing) if (forms[index].id == id && forms[index].pattern == pattern) return;
        existing.push_back(key);
        longest = std::max(longest, pattern.size());
        if (complete) for (size_t end = 1; end < pattern.size(); ++end)
            addUnique(prefixes[bucketKey(std::u32string_view(pattern).substr(0, end))], key);
        forms.push_back({std::move(pattern), id});
    }

    void find(std::u32string_view input, WordIds& out, bool prefix = false) const {
        for (size_t key : bucket(prefix ? prefixes : exact, bucketKey(input))) {
            const auto& f = forms[key];
            if (patternMatches(f.pattern, input, prefix)) addUnique(out, f.id);
        }
    }
};

// Read tonic syllables directly from a lexeme's TWO attested spellings. Each
// letter of the underlying base must align; each tone letter must correspond
// to the actual accent in that syllable. This also recovers source ciex/ciē
// in 茶 and the documented Longshan forms not in the 308-base reference list.
Segments sourceSyllables(const std::string& word, const std::string& accent, size_t count = 0) {
    Query ye, marked;
    if (!ye.read(word) || !marked.read(accent)) return {};
    constexpr std::u32string_view marks = U"\u0304\u0301\u030c\u0300";
    constexpr std::u32string_view letters = U"xfrv";
    const auto delimiter = [](char32_t c) { return space(c) || c == U'-' || c == U'\'' || c == 0x2019; };
    std::vector<std::pair<std::string, std::string>> result;
    size_t y = 0, a = 0;
    while (y < ye.scalars.size()) {
        while (y < ye.scalars.size() && delimiter(ye.scalars[y])) ++y;
        while (a < marked.scalars.size() && delimiter(marked.scalars[a])) ++a;
        const size_t yStart = y, aStart = a;
        char32_t tone = 0;
        while (y < ye.scalars.size() && ye.scalars[y] != tone) {
            if (a == marked.scalars.size() || delimiter(ye.scalars[y]) || delimiter(marked.scalars[a])) return {};
            const auto base = ye.scalars[y++], accented = marked.scalars[a++];
            if (base == accented) continue;
            if (tone) return {};
            for (size_t i = 0; i < marks.size(); ++i) {
                if (composedVowel(base, marks[i]) == accented) tone = letters[i];
            }
            if (!tone) return {};
        }
        if (!tone || y == ye.scalars.size()) return {};
        ++y; // consume the matched tone letter
        result.emplace_back(ye.slice(yStart, y), marked.slice(aStart, a));
    }
    if (a != marked.scalars.size() || (count && result.size() != count)) return {};
    return result;
}


} // namespace

bool matchesSpellingPrefix(const std::string& input, const std::string& ye, const std::string& marked, bool allowYe) {
    Query typed, standard, accented;
    if (!typed.read(input) || !standard.read(ye) || !accented.read(marked)) return false;
    auto compact = [](std::u32string value) {
        value.erase(std::remove_if(value.begin(), value.end(), space), value.end());
        return value;
    };
    const auto prefix = compact(typed.scalars);
    const auto plain = [](std::u32string form) {
        constexpr std::u32string_view bases = U"aeiouünm";
        constexpr std::u32string_view marks = U"\u0304\u0301\u030c\u0300";
        for (auto& cp : form) {
            for (auto base : bases) for (auto tone : marks) {
                if (cp && cp == composedVowel(base, tone)) cp = base;
            }
        }
        return form;
    };
    auto segments = sourceSyllables(ye, marked);
    std::vector<std::u32string> forms{compact(accented.scalars), compact(plain(accented.scalars))};
    if (allowYe) {
        forms.push_back(compact(standard.scalars));
        auto pattern = yeTonePattern(segments);
        if (!pattern.empty() && patternMatches(pattern, prefix, true)) return true;
    }
    if (!segments.empty()) {
        std::u32string unspacedMarked, unmarked;
        for (const auto& part : segments) {
            unspacedMarked += dictionaryScalars(part.second);
            unmarked += plainSyllable(dictionaryScalars(part.first));
        }
        forms.push_back(std::move(unspacedMarked));
        forms.push_back(std::move(unmarked));
    }
    for (const auto& form : forms) {
        if (form.empty()) continue;
        if (form.compare(0, prefix.size(), prefix) == 0) return true;
        auto pattern = nasalPattern(form, segments);
        if (!pattern.empty() && patternMatches(pattern, prefix, true)) return true;
    }
    return false;
}

struct Lexicon::Impl {
    std::vector<Entry> entries;
    HandwordIndex handwriting;
    WordIndex spellings, shortKeys, hanWords, aliases, spellingPrefixes;
    WordIndex markedWords, markedPrefixes, markedSyllables;
    OptionalIndex markedNasalWords, markedNasalSyllables;
    OptionalIndex yeTones{toneBucket};
    WordIndex syllables;
    OptionalIndex nasalWords, nasalShortKeys, nasalSyllables, nasalChinese;
    std::unordered_map<char32_t, WordIds> variantInitials;
    WordIndex chineseKeys;
    std::unordered_map<std::string, size_t> lexicalIds;
    struct Following { size_t id; uint64_t count; };
    bool rankedPredictions = false;
    std::vector<uint64_t> predictionFrequency;
    std::unordered_map<std::string, std::vector<Following>> ngrams;
    std::unordered_set<std::string> chineseWritten;
    std::vector<ChineseWord> chineseWords;
    struct SyllableSense {
        std::string gloss, lexemeId, han;
        std::vector<int> joinedRanges;
        bool plainHanLoan = false;
    };
    std::unordered_map<std::string, SyllableSense> syllableSenses;
    std::unordered_map<std::string, size_t> standardHan;
    struct SyllableArc { std::string next; uint64_t rank; };
    std::unordered_map<std::string, std::vector<SyllableArc>> syllableArcs;
    std::unordered_map<std::string, std::string> accents, contextForms;
    size_t maxToneBytes = 0;
    size_t maxChinese = 0, maxWritten = 0;
    size_t maxKey = 0, maxSyllable = 0;

    explicit Impl(const std::string& tsv, const std::string& ngramData, const std::string& syllableData,
                  const std::string& senseData, const std::string& handData) {
        if (!handData.empty()) {
            std::string_view rows(handData), row;
            if (!readLine(rows, row) || row != "han\tjishou\tchongqing\tnasal")
                throw std::invalid_argument("Unexpected handwriting readings header");
            Fields columns{};
            while (readLine(rows, row)) {
                readFields(row, 4, columns);
                auto c = dictionaryScalars(std::string(columns[0]));
                if (c.size() != 1) throw std::invalid_argument("Invalid regional character");
                auto& groups = handwriting.readings[c.front()];
                for (size_t i = 0; i < groups.size(); ++i) {
                    if (columns[i + 1] != "-") groups[i] = dictionaryScalars(std::string(columns[i + 1]));
                }
            }
        }
        std::string_view lines(tsv), line;
        const std::string legacyHeader = "id\tword\tkey\tmeaning\trecommended\taliases\tvariants";
        if (!readLine(lines, line)) throw std::invalid_argument("Empty dictionary");
        const auto rankedHeader = legacyHeader + "\tfrequency\tkind\tshortGloss";
        const auto toneHeader = rankedHeader + "\tplain\tdiacritic";
        const auto romanHeader = toneHeader + "\thanUnits\tromanAliases";
        const bool hasPresentation = line == romanHeader + "\tpresentation";
        const bool hasRomanAliases = hasPresentation || line == romanHeader;
        const bool hasUnits = hasRomanAliases || line == toneHeader + "\thanUnits";
        const bool tones = hasUnits || line == toneHeader;
        const bool ranked = tones || line == rankedHeader;
        if (line != legacyHeader && !ranked) {
            throw std::invalid_argument("Unexpected dictionary header");
        }
        const size_t fieldCount = hasPresentation ? 15 : hasRomanAliases ? 14 : hasUnits ? 13 : tones ? 12 : ranked ? 10 : 7;
        Fields fields{};
        std::unordered_map<std::string, size_t> hanSenseGroups;
        while (readLine(lines, line)) {
            readFields(line, fieldCount, fields);
            const bool phoneticOnly = ranked && fields[8] == "phonetic";
            if (fields[1].empty() || (fields[4].empty() && !phoneticOnly)) {
                throw std::invalid_argument("Invalid dictionary row");
            }
            const uint64_t frequency = ranked ? frequencyOf(fields[7]) : 0;
            if (ranked && fields[8] == "zh") {
                // Mandarin supplies most rows; only its written form, key and
                // frequency enter the index. Do not allocate thirteen strings per row.
                const size_t id = chineseWords.size();
                chineseWritten.emplace(fields[4]);
                maxWritten = std::max(maxWritten, countScalars(fields[4]));
                const bool single = countScalars(fields[4]) == 1;
                addChineseReading(std::string(fields[1]), id, single);
                if (!fields[2].empty() && fields[2] != fields[1])
                    addChineseReading(std::string(fields[2]), id, single);
                if (hasRomanAliases && !fields[13].empty()) {
                    for (const auto& reading : split(std::string(fields[13]), ' ')) addChineseReading(reading, id, single);
                }
                chineseWords.push_back({std::string(fields[4]), frequency});
                continue;
            }
            std::vector<std::string> f(fields.begin(), fields.begin() + fieldCount);
            const bool phrase = ranked && f[8] == "phrase";
            const bool syllable = ranked && f[8] == "syllable";
            const bool grammar = ranked && f[8] == "grammar";
            const bool collectedUnits = hasUnits && !f[12].empty();
            if ((ranked && !phoneticOnly && !phrase && !syllable && !grammar && f[8] != "tujia") ||
                (!collectedUnits && !phoneticOnly && !phrase && !syllable && !grammar && f[6].empty()))
                throw std::invalid_argument("Invalid Tujia entry");
            const size_t id = entries.size();
            Entry entry{f[0], f[1], f[3], f[4], !ranked || f[9].empty() ? f[3] : f[9],
                        tones && !f[11].empty() ? f[11] : f[1], frequency, {}};
            if (hasPresentation && !f[14].empty()) {
                if (f[14] != "plainHanLoan" || syllable || grammar)
                    throw std::invalid_argument("Unknown lexical presentation: " + f[14]);
                entry.plainHanLoan = true;
            }
            dictionaryScalars(entry.meaning);
            dictionaryScalars(entry.shortMeaning);
            if (collectedUnits) entry.setUnits(f[12]);
            else for (auto cp : dictionaryScalars(entry.han)) entry.hanLength += cp >= 0x10000 ? 2 : 1;
            if (syllable || (entry.units.size() == 1 && !grammar)) addSyllable(entry.word, entry.diacritic);
            Segments parts = sourceSyllables(entry.word, entry.diacritic, entry.units.size());
            if (parts.empty() && (phoneticOnly || syllable || (entry.units.size() == 1 || (!hasUnits && f[6].find(' ') == f[6].npos))))
                parts.push_back({entry.word, entry.diacritic});
            if (syllable) {
                // Only an exact reference reading licenses a default Han unit. An
                // untoned alias may point to several different tone-specific units.
                standardHan.emplace(entry.word, id);
                for (const auto& form : {entry.word, tones ? f[10] : std::string(), entry.diacritic, entry.han}) {
                    Query normalized;
                    if (!normalized.read(form)) throw std::invalid_argument("Invalid syllable");
                    if (form.empty()) continue;
                    if (form == f[10] || form == entry.diacritic) {
                        addUnique(markedSyllables[normalized.folded], id);
                        markedNasalSyllables.add(nasalPattern(normalized.scalars, parts), id, maxSyllable, false);
                    }
                    addUnique(syllables[normalized.folded], id);
                    nasalSyllables.add(nasalPattern(normalized.scalars, parts), id, maxSyllable, false);
                    maxSyllable = std::max(maxSyllable, normalized.scalars.size());
                }
                entries.push_back(std::move(entry));
                continue;
            }
            yeTones.add(yeTonePattern(parts), id, maxKey, true);
            lexicalIds.emplace(entry.id, id);
            entry.hanSenseGroup = id;
            if (!entry.meaning.empty()) {
                Query fullWord;
                if (!fullWord.read(tones && !f[10].empty() ? f[10] : entry.word))
                    throw std::invalid_argument("Invalid dictionary spelling: " + entry.id);
                // Use the complete reading and definition. Unrelated homophones and
                // longer completions can have identical one-character display glosses.
                const auto group = hanSenseGroups.emplace(fullWord.folded + '\t' + entry.meaning, id);
                entry.hanSenseGroup = group.first->second;
            }
            if (hasRomanAliases && !f[13].empty()) {
                for (const auto& alias : split(f[13], ' ')) addSpelling(alias, id, parts);
            }
            addSpelling(entry.word, id, parts, !tones || entry.word == entry.diacritic);
            const bool suffix = grammar && entry.word.front() == '-';
            if (tones) {
                if (!suffix || f[10].find('-') == 0) addSpelling(f[10], id, parts, true);
                if (!suffix || f[11].find('-') == 0) addSpelling(f[11], id, parts, true);
            }
            if (!f[2].empty() && (!suffix || f[2].front() == '-') &&
                (bucket(spellings, f[2]).empty() || bucket(spellings, f[2]).back() != id)) {
                index(shortKeys, f[2], id, maxKey);
                nasalShortKeys.add(nasalPattern(dictionaryScalars(f[2]), parts), id, maxKey, false);
            }
            if (phoneticOnly) {
                entries.push_back(std::move(entry));
                continue;
            }
            index(hanWords, entry.han, id, maxKey);
            entry.writtenForms.push_back(entry.han);
            for (const auto& alias : split(f[5], ' ')) {
                if (alias.empty()) continue;
                index(aliases, alias, id, maxKey);
                entry.writtenForms.push_back(alias);
            }
            for (const auto& form : entry.writtenForms) {
                size_t pos = 0;
                char32_t first;
                if (nextScalar(form, pos, first) && hanCharacter(first)) addUnique(variantInitials[first], id);
            }
            if (!f[6].empty()) {
                for (const auto& choices : split(f[6], ' ')) {
                    if (choices.empty()) throw std::invalid_argument("Missing syllable variants");
                    entry.variants.push_back(dictionaryScalars(choices));
                }
                // Keep the index proportional to the recorded forms, not their Cartesian product.
                for (char32_t initial : entry.variants.front()) addUnique(variantInitials[initial], id);
                maxKey = std::max(maxKey, entry.variants.size());
            }
            if (!entry.units.empty()) {
                if (!entry.variants.empty() && entry.units.size() != entry.variants.size())
                    throw std::invalid_argument("Variant groups do not align with Han units: " + entry.id);
                addUnique(variantInitials[entry.units.front().front()], id);
            }
            if (!phrase) {
                std::vector<std::u32string> written;
                for (size_t i = 1; i < entry.writtenForms.size(); ++i)
                    written.push_back(dictionaryScalars(entry.writtenForms[i]));
                handwriting.add(id, frequency, dictionaryScalars(entry.han), entry.units, entry.variants, std::move(written));
            }
            entries.push_back(std::move(entry));
        }
        // The LS-60 text originally split three contextual glosses into separate
        // lexemes. Accept their saved IDs without reintroducing those word senses.
        for (const auto& alias : {std::pair{"ls60-126", "ls60-119"},
                                  std::pair{"ls60-155", "ls60-18"},
                                  std::pair{"ls60-127", "ls60-51"}}) {
            const auto target = lexicalIds.find(alias.second);
            if (target != lexicalIds.end()) lexicalIds.emplace(alias.first, target->second);
        }
        for (auto& group : chineseKeys) {
            auto& words = group.second;
            if (words.size() < 2) continue;
            auto moreFrequent = [&](size_t a, size_t b) {
                return chineseWords[a].frequency > chineseWords[b].frequency;
            };
            if (!std::is_sorted(words.begin(), words.end(), moreFrequent))
                std::stable_sort(words.begin(), words.end(), moreFrequent);
            std::unordered_set<std::string_view> forms;
            words.erase(std::remove_if(words.begin(), words.end(), [&](size_t id) {
                return !forms.insert(chineseWords[id].text).second;
            }), words.end());
        }
        for (auto& entry : entries) {
            if (entry.units.size() <= 1) continue;
            const auto aligned = sourceSyllables(entry.word, entry.diacritic, entry.units.size());
            for (const auto& syllable : aligned) addSyllable(syllable.first, syllable.second);
            if (!aligned.empty()) entry.lastSyllable = aligned.back().first;
        }
        for (auto& entry : entries) if (entry.lastSyllable.empty()) entry.lastSyllable = finalSyllable(entry);
        for (const auto& keyed : spellings) {
            Query query;
            query.read(keyed.first);
            for (size_t n = 1; n < query.scalars.size(); ++n) {
                auto& ids = spellingPrefixes[query.slice(0, n)];
                for (size_t id : keyed.second) addUnique(ids, id);
            }
        }
        for (const auto& keyed : markedWords) {
            Query query;
            query.read(keyed.first);
            for (size_t n = 1; n < query.scalars.size(); ++n) {
                auto& ids = markedPrefixes[query.slice(0, n)];
                for (size_t id : keyed.second) addUnique(ids, id);
            }
        }
        if (!ngramData.empty()) loadNgrams(ngramData);
        if (!syllableData.empty()) loadSyllableBigrams(syllableData);
        if (!senseData.empty()) loadSyllableSenses(senseData);
    }

    void addSyllable(const std::string& ye, const std::string& accent) {
        // Only actual tone-marked lexical readings create contexts. Bound grammar signs
        // such as -i and -u retain an empty context. Never infer tones from Han initials.
        if (ye.size() < 2 || accent.empty() || ye.find_first_not_of("abcdefghijklmnopqrstuvwxyz") != ye.npos ||
            std::string_view("xfrv").find(ye.back()) == std::string_view::npos) return;
        addSourceForm(ye, accent);
    }

    void addSourceForm(const std::string& ye, const std::string& accent) {
        // The explicit source column also documents untoned syllables such as a/le.
        if (ye.empty() || accent.empty() || ye.front() == '<') return;
        accents.emplace(ye, accent); // the lexical spelling has first priority
        contextForms.emplace(ye, ye);
        Query marked;
        if (marked.read(accent)) contextForms.emplace(marked.folded, ye);
        maxToneBytes = std::max(maxToneBytes, ye.size());
    }


    std::string finalSyllable(const Entry& entry) const {
        const auto& word = entry.word;
        const size_t count = entry.units.size();
        if (count <= 1) return accents.count(word) ? word : "";
        // Use the attested number of syllables and spelling boundaries. A tone
        // letter such as x can itself open the next syllable; splitting at every
        // x/f/r/v or choosing the longest suffix would confuse those two uses.
        std::vector<size_t> positions{0};
        std::string terminal;
        for (size_t i = 0; i < count; ++i) {
            std::vector<size_t> next;
            for (auto pos : positions) {
                while (pos < word.size() && (word[pos] == ' ' || word[pos] == '-' || word[pos] == '\'')) ++pos;
                for (size_t end = pos + 1; end <= std::min(word.size(), pos + maxToneBytes); ++end) {
                    const auto reading = word.substr(pos, end-pos);
                    auto it = accents.find(reading);
                    if (it == accents.end()) continue;
                    if (i + 1 < count) next.push_back(end);
                    else if (end == word.size()) {
                        if (!terminal.empty() && terminal != reading) return {}; // unresolved source segmentation
                        terminal = reading;
                    }
                }
            }
            std::sort(next.begin(), next.end());
            next.erase(std::unique(next.begin(), next.end()), next.end());
            positions.swap(next);
        }
        return terminal;
    }

    void loadSyllableBigrams(const std::string& data) {
        std::string_view lines(data), line;
        if (!readLine(lines, line)) throw std::invalid_argument("Missing syllable bigram header");
        const bool sourceForms = line == "context\tnext\tcount\trank\tcontextAccent\tnextAccent";
        if (!sourceForms && line != "context\tnext\tcount\trank")
            throw std::invalid_argument("Unexpected syllable bigram header");
        Fields fields{};
        while (readLine(lines, line)) {
            readFields(line, sourceForms ? 6 : 4, fields);
            const std::string context(fields[0]), next(fields[1]);
            const uint64_t rank = frequencyOf(fields[3]);
            if (!rank || context.empty() || next.empty()) throw std::invalid_argument("Invalid syllable bigram");
            if (!fields[2].empty()) frequencyOf(fields[2]); // omitted counts are valid
            if (sourceForms) {
                addSourceForm(context, std::string(fields[4]));
                addSourceForm(next, std::string(fields[5]));
            }
            // The table may include an end marker or readings yet to be documented
            // in the dictionary. Only offer syllables with a known written form.
            if (!accents.count(next) || (context != "<s>" && !accents.count(context))) continue;
            syllableArcs[context].push_back({next, rank});
        }
        for (auto& group : syllableArcs) {
            auto& arcs = group.second;
            std::stable_sort(arcs.begin(), arcs.end(), [](const SyllableArc& a, const SyllableArc& b) { return a.rank < b.rank; });
            std::unordered_set<std::string> used;
            arcs.erase(std::remove_if(arcs.begin(), arcs.end(), [&](const SyllableArc& a) {
                return !used.insert(a.next).second;
            }), arcs.end());
        }
    }

    void loadSyllableSenses(const std::string& data) {
        std::string_view lines(data), line;
        if (!readLine(lines, line) || line != "syllable\tshortGloss\tlexemeId\than")
            throw std::invalid_argument("Unexpected syllable sense header");
        Fields fields{};
        while (readLine(lines, line)) {
            readFields(line, 4, fields);
            const std::string syllable(fields[0]);
            SyllableSense sense{std::string(fields[1]), std::string(fields[2]), std::string(fields[3]), {}};
            // An attested writing for a regional reading can be known without a
            // unique meaning. Only a reviewed sense may enter lexical history.
            if (syllable.empty() || (sense.gloss.empty() && (sense.han.empty() || !sense.lexemeId.empty())))
                throw std::invalid_argument("Incomplete syllable evidence");
            dictionaryScalars(sense.gloss);
            if (!sense.lexemeId.empty()) {
                const auto id = lexicalIds.find(sense.lexemeId);
                if (id == lexicalIds.end()) throw std::invalid_argument("Unknown linked syllable lexeme: " + sense.lexemeId);
                const auto& entry = entries[id->second];
                const auto& readings = bucket(spellings, syllable);
                const size_t unitCount = entry.units.empty() ? entry.variants.size() : entry.units.size();
                if (unitCount != 1 || std::find(readings.begin(), readings.end(), id->second) == readings.end() ||
                    (!sense.han.empty() && sense.han != entry.han))
                    throw std::invalid_argument("Incompatible linked reading: " + syllable + " " + sense.lexemeId);
                sense.han = entry.han;
                sense.plainHanLoan = entry.plainHanLoan;
            }
            if (!sense.han.empty()) {
                Query glyphs;
                if (!glyphs.read(sense.han) || glyphs.scalars.size() > 2 ||
                    !std::all_of(glyphs.scalars.begin(), glyphs.scalars.end(), hanCharacter))
                    throw std::invalid_argument("Invalid recommended Han unit: " + syllable);
                if (glyphs.scalars.size() == 2) sense.joinedRanges = {0, glyphs.utf16At.back()};
            }
            if (!syllableSenses.emplace(syllable, std::move(sense)).second)
                throw std::invalid_argument("Duplicate unique-sense row: " + syllable);
        }
    }

    std::vector<Candidate> nextSyllables(const std::string& input, Mode output, Orthography style) const {
        std::string context = input;
        if (input != "<s>") {
            Query query;
            if (!query.read(input)) return {};
            const auto it = contextForms.find(query.folded);
            if (it == contextForms.end()) return {};
            context = it->second;
        }
        const auto found = syllableArcs.find(context);
        if (found == syllableArcs.end()) return {};
        std::vector<Candidate> out;
        for (const auto& arc : found->second) {
            Candidate c{"", "", 0};
            c.lastSyllable = arc.next;
            c.syllableOnly = true;
            const auto it = syllableSenses.find(arc.next);
            if (it != syllableSenses.end()) {
                c.meaning = it->second.gloss;
                c.shortMeaning = it->second.gloss;
                c.lexemeIds = it->second.lexemeId;
            }
            if (output != Mode::Han) {
                c.text = style == Orthography::Ye ? arc.next : accents.at(arc.next);
            } else if (it != syllableSenses.end() && !it->second.han.empty()) {
                const auto& sense = it->second;
                c.text = sense.han;
                if (!sense.lexemeId.empty()) {
                    const auto& entry = entries[lexicalIds.at(sense.lexemeId)];
                    c.hanSenseId = entries[entry.hanSenseGroup].id;
                }
                c.joinedHanRanges = sense.joinedRanges;
                c.plainHanLoan = sense.plainHanLoan;
                if (sense.plainHanLoan) c.shortMeaning.clear();
            } else {
                const auto reference = standardHan.find(arc.next);
                if (reference == standardHan.end()) continue;
                const auto& entry = entries[reference->second];
                c.text = entry.han;
                c.joinedHanRanges = entry.joinedRanges;
            }
            out.push_back(std::move(c));
        }
        return out;
    }

    std::vector<std::string> historyIds(const std::string& context) const {
        auto ids = split(context, ' ');
        for (auto& id : ids) {
            const auto entry = lexicalIds.find(id);
            if (entry != lexicalIds.end()) id = entries[entry->second].id;
        }
        return ids;
    }

    void loadNgrams(const std::string& data) {
        std::string_view lines(data), line;
        if (!readLine(lines, line)) throw std::invalid_argument("Missing prediction header");
        rankedPredictions = line == "context\tnext\tscore";
        if (!rankedPredictions && line != "context\tnext\tcount\texample")
            throw std::invalid_argument("Unexpected ngram header");
        if (rankedPredictions) predictionFrequency.resize(entries.size());
        Fields fields{};
        while (readLine(lines, line)) {
            readFields(line, rankedPredictions ? 3 : 4, fields);
            const auto history = historyIds(std::string(fields[0]));
            if (history.empty() || history.size() > 2) throw std::invalid_argument("Invalid context length");
            const auto context = history.size() == 1 ? history.front() : history.front() + " " + history.back();
            const bool special = rankedPredictions && (context == "<s>" || context == "@prior");
            if (!special) for (const auto& id : history)
                if (!lexicalIds.count(id)) throw std::invalid_argument("Unknown context ID: " + id);
            const auto next = lexicalIds.find(std::string(fields[1]));
            const auto count = frequencyOf(fields[2]);
            if (next == lexicalIds.end() || !count) throw std::invalid_argument("Invalid following lexeme");
            if (context == "@prior") {
                auto& prior = predictionFrequency[next->second];
                prior = std::max(prior, count);
            } else {
                auto& arcs = ngrams[context];
                const auto existing = std::find_if(arcs.begin(), arcs.end(),
                    [&](const Following& arc) { return arc.id == next->second; });
                if (existing == arcs.end()) arcs.push_back({next->second, count});
                else existing->count = std::max(existing->count, count);
            }
        }
        for (auto& pair : ngrams) {
            auto& next = pair.second;
            std::sort(next.begin(), next.end(), [&](const Following& a, const Following& b) {
                if (a.count != b.count) return a.count > b.count;
                const auto& x = entries[a.id]; const auto& y = entries[b.id];
                if (!rankedPredictions && x.frequency != y.frequency) return x.frequency > y.frequency;
                return x.id < y.id;
            });
        }
    }

    void addSpelling(const std::string& form, size_t id, const Segments& parts, bool marked = false) {
        if (form.empty()) return;
        Query normalized;
        if (!normalized.read(form)) throw std::invalid_argument("Invalid dictionary spelling");
        auto key = normalized.folded;
        auto pattern = nasalPattern(normalized.scalars, parts);
        if (marked) markedNasalWords.add(pattern, id, maxKey, true);
        nasalWords.add(pattern, id, maxKey, true);
        if (std::find(pattern.begin(), pattern.end(), U' ') != pattern.end()) {
            pattern.erase(std::remove(pattern.begin(), pattern.end(), U' '), pattern.end());
            if (marked) markedNasalWords.add(pattern, id, maxKey, true);
            nasalWords.add(std::move(pattern), id, maxKey, true);
        }
        if (marked) addUnique(markedWords[key], id);
        auto& words = spellings[key];
        if (words.empty() || words.back() != id) words.push_back(id);
        maxKey = std::max(maxKey, normalized.scalars.size());
        if (key.find(' ') != std::string::npos) {
            key.erase(std::remove(key.begin(), key.end(), ' '), key.end());
            addUnique(spellings[key], id);
            if (marked) addUnique(markedWords[key], id);
        }
    }

    void addChineseReading(const std::string& reading, size_t id, bool single) {
        if (reading.empty()) return;
        index(chineseKeys, reading, id, maxChinese);
        // Mandarin s is never a syllable coda: "sh" always starts a syllable.
        std::string flat = reading;
        for (size_t at = 0; (at = flat.find("sh", at)) != flat.npos; ++at)
            flat.erase(at + 1, 1);
        const std::string flatSegmented = flat;
        if (flat != reading) {
            flat.erase(std::remove(flat.begin(), flat.end(), '\''), flat.end());
            index(chineseKeys, flat, id, maxChinese);
        }
        const bool splitReading = reading.find('\'') != reading.npos;
        if (!single && !splitReading) return;
        std::string compact = reading;
        if (splitReading) {
            compact.erase(std::remove(compact.begin(), compact.end(), '\''), compact.end());
            index(chineseKeys, compact, id, maxChinese);
        }
        if (reading.find("an") == reading.npos && reading.find('o') == reading.npos &&
            reading.find_first_not_of("abcdefghijklmnopqrstuvwxyz'") == reading.npos) return;
        const auto addOptionalReading = [&](const std::string& value) {
            Segments parts;
            for (const auto& syllable : split(value, '\'')) parts.emplace_back(syllable, syllable);
            nasalChinese.add(nasalPattern(dictionaryScalars(value), parts), id, maxChinese, false);
            if (value.find('\'') != value.npos) {
                std::string joined = value;
                joined.erase(std::remove(joined.begin(), joined.end(), '\''), joined.end());
                nasalChinese.add(nasalPattern(dictionaryScalars(joined), parts), id, maxChinese, false);
            }
        };
        addOptionalReading(reading);
        if (flatSegmented != reading) addOptionalReading(flatSegmented);
    }

    static void index(WordIndex& map, const std::string& key, size_t id, size_t& longest) {
        map[key].push_back(id);
        longest = std::max(longest, countScalars(key));
    }

    WordIds match(const Query& q, size_t start, size_t end, Mode mode, Orthography style = Orthography::Ye) const {
        if (insideUo(q, start, end)) return {};
        const char32_t first = q.scalars[start];
        if (!hanCharacter(first)) {
            const auto form = q.slice(start, end);
            // Explicit delimiters on bound grammar signs belong to the form.
            if (mode == Mode::Handwriting) return bucket(hanWords, form);
            bool marked = mode == Mode::Pinyin && style == Orthography::Diacritic;
            auto matches = bucket(marked ? markedWords : spellings, form);
            (marked ? markedNasalWords : nasalWords).find(std::u32string_view(q.scalars).substr(start, end-start), matches);
            if (!marked)
                yeTones.find(std::u32string_view(q.scalars).substr(start, end-start), matches);
            if (mode == Mode::Han) {
                matches.erase(std::remove_if(matches.begin(), matches.end(), [&](size_t id) { return entries[id].han.empty(); }), matches.end());
                const auto& shorter = bucket(shortKeys, form);
                for (size_t id : shorter) addUnique(matches, id);
                nasalShortKeys.find(std::u32string_view(q.scalars).substr(start, end-start), matches);
                for (auto id : bucket(hanWords, form)) addUnique(matches, id);
            }
            return matches;
        }
        if (mode == Mode::Pinyin) return {};
        const auto form = q.slice(start, end);
        auto matches = bucket(hanWords, form);
        for (size_t id : bucket(aliases, form)) addUnique(matches, id);
        auto it = variantInitials.find(first);
        if (it != variantInitials.end()) {
            for (size_t id : it->second) {
                if (entries[id].matchesVariants(q, start, end)) addUnique(matches, id);
            }
        }
        return matches;
    }

    Candidate joined(const WordIds& selected, Mode mode, Orthography orthography,
                     int consumed, const std::string& tail) const {
        Candidate out{"", "", consumed};
        int hanOffset = 0;
        for (size_t id : selected) {
            if (mode == Mode::Han) {
                for (int offset : entries[id].joinedRanges) out.joinedHanRanges.push_back(hanOffset + offset);
                hanOffset += entries[id].hanLength;
            }
            if (!out.text.empty() && mode != Mode::Han) out.text += ' ';
            if (!out.meaning.empty()) out.meaning += " · ";
            if (!out.lexemeIds.empty()) out.lexemeIds += ' ';
            out.lexemeIds += entries[id].id;
            out.lastSyllable = entries[id].lastSyllable;
            out.text += entries[id].output(mode, orthography);
            out.meaning += entries[id].meaning;
            if (mode != Mode::Han || !entries[id].plainHanLoan) {
                if (!out.shortMeaning.empty()) out.shortMeaning += " · ";
                out.shortMeaning += entries[id].shortMeaning;
            }
        }
        out.plainHanLoan = mode == Mode::Han && !selected.empty() && out.shortMeaning.empty();
        out.text += tail;
        if (!tail.empty()) { out.lexemeIds.clear(); out.lastSyllable.clear(); }
        return out;
    }

    // Prefer coverage, then fewer words, then the longer first word on ties.
    bool sentence(const Query& q, size_t start, Mode mode, Orthography orthography, Candidate& candidate) const {
        struct Step {
            size_t missing = 0, count = 0, next = 0;
            WordIds choices;
        };
        const size_t length = q.scalars.size();
        size_t stop = start;
        while (stop < length && !punctuation(q.scalars[stop])) ++stop;
        size_t markEnd = stop;
        while (markEnd < length && punctuation(q.scalars[markEnd])) ++markEnd;
        std::vector<Step> path(stop + 1);
        for (size_t i = stop; i-- > start;) {
            Step& best = path[i];
            best.next = i + 1;
            best.missing = path[i + 1].missing;
            best.count = path[i + 1].count;
            if (space(q.scalars[i])) continue;
            ++best.missing;
            ++best.count;
            for (size_t end = std::min(i + maxKey, stop); end > i; --end) {
                const auto& suffix = path[end];
                if (suffix.missing > best.missing ||
                    (suffix.missing == best.missing && suffix.count + 1 >= best.count)) continue;
                auto choices = match(q, i, end, mode, orthography);
                if (choices.empty()) continue;
                best = {suffix.missing, suffix.count + 1, end, std::move(choices)};
            }
        }
        static const std::array<std::string, 3> meal{"w1585", "w2548", "w684"};
        bool certain = true, mealPrefix = true;
        size_t mealEnd = 0;
        WordIds picked;
        for (size_t i = start; i < stop; i = path[i].next) {
            if (space(q.scalars[i])) continue;
            const auto& choices = path[i].choices;
            if (choices.empty()) break;
            size_t id = choices.front();
            if (mealPrefix && picked.size() < meal.size()) {
                const auto& needed = meal[picked.size()];
                auto it = std::find_if(choices.begin(), choices.end(),
                                      [&](size_t key) { return entries[key].id == needed; });
                mealPrefix = it != choices.end();
                if (mealPrefix) id = *it;
            }
            certain = certain && choices.size() == 1;
            picked.push_back(id);
            if (mealPrefix && picked.size() == meal.size()) mealEnd = q.skipSpaces(path[i].next);
        }
        if (certain && path[start].missing == 0 && picked.size() >= 3 && markEnd == length) {
            candidate = joined(picked, mode, orthography, q.utf16At[markEnd], q.slice(stop, markEnd));
            return true;
        }
        if (!mealEnd) return false;
        picked.resize(meal.size());
        const bool withTail = mealEnd == stop;
        candidate = joined(picked, mode, orthography, q.utf16At[withTail ? markEnd : mealEnd],
                           withTail ? q.slice(stop, markEnd) : "");
        return true;
    }

    bool startsAnotherWord(const Query& query, size_t start, Mode mode, Orthography style) const {
        start = query.skipSpaces(start);
        if (start == query.scalars.size() || punctuation(query.scalars[start])) return false;
        for (size_t end = std::min(start + maxKey, query.scalars.size()); end > start; --end) {
            if (!match(query, start, end, mode, style).empty()) return true;
        }
        return false;
    }

    std::vector<Candidate> lookup(const std::string& input, Mode mode, Orthography orthography) const {
        Query q;
        if (!q.read(input)) return {};
        const size_t start = q.skipSpaces(0);
        if (start == q.scalars.size()) return {};
        size_t markEnd = start;
        while (markEnd < q.scalars.size() && punctuation(q.scalars[markEnd])) ++markEnd;
        if (markEnd > start)
            return {{q.slice(start, markEnd), "标点", q.utf16At[markEnd], "标点", CandidateKind::Punctuation}};

        std::vector<Candidate> result;
        std::unordered_set<int> lexicalLengths;
        const bool typedHan = mode == Mode::Han && !hanCharacter(q.scalars[start]);
        const auto choiceId = [&](size_t id) { return typedHan ? entries[id].hanSenseGroup : id; };
        // When two spellings consume different lengths for the same lexeme, resolve
        // their boundary against the next word. ri|xi leaves a real word, rix|i does
        // not. A terminal tone letter in ngaf always stays with its own syllable.
        struct Prefix { size_t end; WordIds choices; bool validBoundary = false; };
        std::vector<Prefix> prefixes;
        std::unordered_set<size_t> seen, repeated, withBoundary;
        for (size_t end = std::min(start + maxKey, q.scalars.size()); end > start; --end) {
            auto choices = match(q, start, end, mode, orthography);
            if (choices.empty()) continue;
            for (auto id : choices) if (!seen.insert(choiceId(id)).second) repeated.insert(choiceId(id));
            prefixes.push_back({end, std::move(choices)});
        }
        for (auto& prefix : prefixes) {
            if (std::none_of(prefix.choices.begin(), prefix.choices.end(), [&](size_t id) { return repeated.count(choiceId(id)); })) continue;
            const size_t next = q.skipSpaces(prefix.end);
            prefix.validBoundary = next == q.scalars.size() || punctuation(q.scalars[next]) || startsAnotherWord(q, next, mode, orthography);
            if (prefix.validBoundary) {
                for (auto id : prefix.choices) if (repeated.count(choiceId(id))) withBoundary.insert(choiceId(id));
            }
        }
        std::unordered_set<size_t> offered;
        for (auto& prefix : prefixes) {
            const size_t end = prefix.end, batchStart = result.size();
            auto& choices = prefix.choices;
            lexicalLengths.insert(q.utf16At[q.skipSpaces(end)]);
            std::stable_sort(choices.begin(), choices.end(), [&](size_t a, size_t b) {
                return entries[a].frequency != entries[b].frequency
                        ? entries[a].frequency > entries[b].frequency : a < b;
            });
            for (size_t id : choices) {
                const size_t key = choiceId(id);
                if (withBoundary.count(key)) {
                    if (!prefix.validBoundary) continue;
                } else if (!offered.insert(key).second) continue;
                const auto& entry = entries[id];
                const auto& text = entry.output(mode, orthography);
                auto duplicate = std::find_if(result.begin() + batchStart, result.end(), [&](const Candidate& c) {
                    if (typedHan && !c.hanSenseId.empty() && c.hanSenseId == entries[key].id) return true;
                    return c.text == text && c.meaning == entry.meaning && c.plainHanLoan == (mode == Mode::Han && entry.plainHanLoan);
                });
                if (duplicate == result.end()) {
                    result.push_back(lexical(id, mode, orthography, q.utf16At[q.skipSpaces(end)]));
                }
            }
        }
        if (!result.empty()) {
            Candidate full;
            if (sentence(q, start, mode, orthography, full)) {
                auto pos = std::find_if(result.begin(), result.end(), [&](const Candidate& c) {
                    return c.consumed < full.consumed;
                });
                lexicalLengths.insert(full.consumed);
                result.insert(pos, std::move(full));
            }
        }
        appendSyllables(result, q, start, mode, orthography, lexicalLengths);
        const bool written = mode != Mode::Pinyin && hanCharacter(q.scalars[start]);
        constexpr std::u32string_view romanAccents = U"āáǎàēéěèīíǐìōóǒòūúǔùüǖǘǚǜńňǹḿ";
        const char32_t first = q.scalars[start];
        const bool latin = mode != Mode::Handwriting && ((first >= U'a' && first <= U'z') || romanAccents.find(first) != romanAccents.npos);
        if (written || latin) appendChinese(result, q, start, written);
        return result;
    }

    Candidate lexical(size_t id, Mode output, Orthography style, int consumed) const {
        const auto& e = entries[id];
        Candidate c{e.output(output, style), e.meaning, consumed, e.shortMeaning};
        c.lexemeIds = e.id;
        c.lastSyllable = e.lastSyllable;
        c.plainHanLoan = output == Mode::Han && e.plainHanLoan;
        if (c.plainHanLoan) c.shortMeaning.clear();
        if (output == Mode::Han) {
            c.joinedHanRanges = e.joinedRanges;
            if (!e.meaning.empty()) c.hanSenseId = entries[e.hanSenseGroup].id;
        }
        return c;
    }

    bool glyphQuery(const std::string& input, Query& q) const {
        return !input.empty() && q.read(input) &&
               std::all_of(q.scalars.begin(), q.scalars.end(), hanCharacter);
    }

    std::vector<Candidate> handSuffixes(const std::string& input, Mode mode, Orthography style, bool next) const {
        size_t byteStart = input.size();
        for (size_t n = 0; n < HandwordIndex::contextCharacters && byteStart > 0; ++n) {
            --byteStart;
            while (byteStart > 0 && (static_cast<unsigned char>(input[byteStart]) & 0xc0) == 0x80) --byteStart;
        }
        Query q;
        if (!q.read(input.substr(byteStart)) || q.scalars.empty()) return {};
        size_t start = q.scalars.size();
        while (start > 0 && q.scalars.size() - start < HandwordIndex::contextCharacters && hanCharacter(q.scalars[start - 1])) --start;
        const auto hits = handwriting.lookup(std::u32string_view(q.scalars).substr(start), next);
        std::vector<Candidate> result;
        std::unordered_set<char32_t> emitted;
        for (const auto& hit : hits) {
            if (next) {
                if (!hanCharacter(hit.next) || !emitted.insert(hit.next).second) continue;
                Candidate c{"", "", 0};
                appendUtf8(c.text, hit.next);
                result.push_back(std::move(c));
            } else {
                const int consumed = q.utf16At.back() - q.utf16At[q.scalars.size() - hit.length];
                result.push_back(lexical(hit.id, mode, style, consumed));
            }
        }
        return result;
    }

    WordIds completions(const Query& q) const {
        WordIds matches;
        auto it = variantInitials.find(q.scalars.front());
        if (it == variantInitials.end()) return matches;
        for (auto id : it->second) if (entries[id].extends(q)) matches.push_back(id);
        std::stable_sort(matches.begin(), matches.end(), [&](size_t a, size_t b) {
            return entries[a].frequency > entries[b].frequency;
        });
        return matches;
    }

    std::vector<Candidate> handwritingSuggestions(const std::string& glyphs, Mode output, Orthography style) const {
        Query q;
        if (!glyphQuery(glyphs, q)) return {};
        const auto exact = match(q, 0, q.scalars.size(), Mode::Handwriting);
        std::vector<Candidate> out;
        for (auto id : completions(q)) {
            if (std::find(exact.begin(), exact.end(), id) == exact.end())
                out.push_back(lexical(id, output, style, q.utf16At.back()));
        }
        return out;
    }

    int scoreHandwritingPrefix(const std::string& glyphs) const {
        Query q;
        if (!glyphQuery(glyphs, q)) return 0;
        if (!match(q, 0, q.scalars.size(), Mode::Handwriting).empty()) return 2;
        return completions(q).empty() ? 0 : 1;
    }

    WordIds predictedIds(const std::string& history) const {
        const auto ids = historyIds(history);
        if (ids.back() != "<s>" && !lexicalIds.count(ids.back())) return {};
        WordIds ranked;
        if (ids.size() >= 2) {
            const auto pair = ngrams.find(ids[ids.size() - 2] + " " + ids.back());
            if (pair != ngrams.end()) for (const auto& arc : pair->second) ranked.push_back(arc.id);
        }
        if (!rankedPredictions || ranked.empty()) {
            const auto single = ngrams.find(ids.back());
            if (single != ngrams.end()) for (const auto& arc : single->second) addUnique(ranked, arc.id);
        }
        if (!rankedPredictions || ids.size() < 3) return ranked;

        // Only past confirmed choices in this editor are supplied by the caller.
        // Promote at most one repeated continuation; ties favour its most recent use.
        std::unordered_map<size_t, unsigned> counts;
        size_t local = 0;
        unsigned best = 0;
        const size_t begin = ids.size() > 32 ? ids.size() - 32 : 0;
        for (size_t i = begin; i + 1 < ids.size(); ++i) {
            if (ids[i] != ids.back()) continue;
            const auto next = lexicalIds.find(ids[i + 1]);
            if (next == lexicalIds.end()) continue;
            const auto count = ++counts[next->second];
            if (count >= best) { best = count; local = next->second; }
        }
        if (!best || (!ranked.empty() && ranked.front() == local)) return ranked;
        const size_t limit = std::max<size_t>(ranked.size(), 2);
        ranked.erase(std::remove(ranked.begin(), ranked.end(), local), ranked.end());
        const bool first = best >= 2 || ranked.empty();
        ranked.insert(ranked.begin() + (first ? 0 : 1), local);
        if (ranked.size() > limit) ranked.resize(limit);
        return ranked;
    }

    std::vector<Candidate> nextWords(const std::string& context, Mode output, Orthography style) const {
        std::vector<Candidate> out;
        std::unordered_set<size_t> senses;
        for (auto id : predictedIds(context)) {
            if (output == Mode::Han && entries[id].han.empty()) continue;
            if (output == Mode::Han && !senses.insert(entries[id].hanSenseGroup).second) continue;
            out.push_back(lexical(id, output, style, 0));
        }
        return out;
    }

    std::vector<Candidate> spellingCompletions(const std::string& input, const std::string& context,
                                               Mode output, Orthography style) const {
        Query q;
        if (input.empty() || input.size() > maxKey * 4 || !q.read(input)) return {};
        bool marked = output == Mode::Pinyin && style == Orthography::Diacritic;
        WordIds possible = bucket(marked ? markedPrefixes : spellingPrefixes, q.folded);
        (marked ? markedNasalWords : nasalWords).find(q.scalars, possible, true);
        if (!marked) yeTones.find(q.scalars, possible, true);
        if (possible.empty()) return {};
        auto exact = bucket(marked ? markedWords : spellings, q.folded);
        (marked ? markedNasalWords : nasalWords).find(q.scalars, exact);
        if (!marked) yeTones.find(q.scalars, exact);
        possible.erase(std::remove_if(possible.begin(), possible.end(), [&](size_t id) {
            return (output == Mode::Han && entries[id].han.empty()) || std::find(exact.begin(), exact.end(), id) != exact.end();
        }), possible.end());
        const auto predicted = predictedIds(context);
        const auto order = [&](size_t id) { return std::find(predicted.begin(), predicted.end(), id); };
        std::sort(possible.begin(), possible.end(), [&](size_t a, size_t b) {
            const auto x = order(a), y = order(b);
            if (x != y) return x < y;
            const auto fa = rankedPredictions ? predictionFrequency[a] : entries[a].frequency;
            const auto fb = rankedPredictions ? predictionFrequency[b] : entries[b].frequency;
            if (fa != fb) return fa > fb;
            return entries[a].id < entries[b].id;
        });
        std::vector<Candidate> out;
        for (auto id : possible) {
            const auto& entry = entries[id];
            auto same = [&](const Candidate& c) {
                if (output == Mode::Han && !c.hanSenseId.empty() && c.hanSenseId == entries[entry.hanSenseGroup].id) return true;
                return c.text == entry.output(output, style) && c.meaning == entry.meaning;
            };
            if (std::none_of(out.begin(), out.end(), same))
                out.push_back(lexical(id, output, style, q.utf16At.back()));
            if (out.size() == 4) break;
        }
        return out;
    }

    void appendSyllables(std::vector<Candidate>& result, const Query& q, size_t start, Mode mode,
                         Orthography orthography, const std::unordered_set<int>& lexicalLengths) const {
        const bool written = hanCharacter(q.scalars[start]);
        if ((written && mode == Mode::Pinyin) || (!written && mode == Mode::Handwriting)) return;
        for (size_t end = std::min(start + maxSyllable, q.scalars.size()); end > start; --end) {
            if (insideUo(q, start, end)) continue;
            const auto spelling = q.slice(start, end);
            bool marked = mode == Mode::Pinyin && orthography == Orthography::Diacritic;
            auto defaults = bucket(marked ? markedSyllables : syllables, spelling);
            (marked ? markedNasalSyllables : nasalSyllables).find(std::u32string_view(q.scalars).substr(start, end-start), defaults);
            if (defaults.empty()) continue;
            std::sort(defaults.begin(), defaults.end());
            const int consumed = q.utf16At[q.skipSpaces(end)];
            const bool lexical = lexicalLengths.count(consumed);
            for (size_t id : defaults) {
                const auto& e = entries[id];
                if (lexical && written) continue;
                if (lexical && spelling != e.word && spelling != e.diacritic) {
                    const Segments single{{e.word, e.diacritic}};
                    const auto input = std::u32string_view(q.scalars).substr(start, end-start);
                    if (!patternMatches(nasalPattern(dictionaryScalars(e.word), single), input, false)
                            && !patternMatches(nasalPattern(dictionaryScalars(e.diacritic), single), input, false)) continue;
                }
                const auto& text = e.output(mode, orthography);
                if (std::any_of(result.begin(), result.end(), [&](const Candidate& c) {
                        return c.consumed == consumed && c.text == text;
                    })) continue;
                result.push_back({text, e.meaning, consumed, e.shortMeaning});
                result.back().syllableOnly = true;
                result.back().lastSyllable = e.lastSyllable;
                if (mode == Mode::Han) result.back().joinedHanRanges = e.joinedRanges;
            }
        }
    }

    void appendChinese(std::vector<Candidate>& result, const Query& q, size_t start, bool written) const {
        // Each candidate class has its own longest-first ordering. Keep all Tujia
        // matches visible ahead of Mandarin, even if a Mandarin word consumes more.
        size_t maxLength = written ? std::max(maxWritten, size_t(1)) : maxChinese;
        for (size_t end = std::min(start + maxLength, q.scalars.size()); end > start; --end) {
            if (insideUo(q, start, end)) continue;
            const auto form = q.slice(start, end);
            const int consumed = q.utf16At[q.skipSpaces(end)];
            if (written) {
                if (chineseWritten.count(form) || end == start + 1)
                    result.push_back({form, "", consumed, "", CandidateKind::Chinese});
            } else {
                auto matches = bucket(chineseKeys, form);
                nasalChinese.find(std::u32string_view(q.scalars).substr(start, end-start), matches);
                std::stable_sort(matches.begin(), matches.end(), [&](size_t a, size_t b) {
                    return chineseWords[a].frequency != chineseWords[b].frequency
                            ? chineseWords[a].frequency > chineseWords[b].frequency : a < b;
                });
                for (size_t id : matches)
                    result.push_back({chineseWords[id].text, "", consumed, "", CandidateKind::Chinese});
            }
        }
    }
};

Lexicon::Lexicon(const std::string& tsv, const std::string& ngrams, const std::string& sounds,
                 const std::string& senses, const std::string& readings)
    : impl_(new Impl(tsv, ngrams, sounds, senses, readings)) {}
Lexicon::~Lexicon() = default;

std::vector<Candidate> Lexicon::lookup(const std::string& input, Mode mode, Orthography orthography) const {
    if (mode != Mode::Pinyin && mode != Mode::Han && mode != Mode::Handwriting) return {};
    if (orthography != Orthography::Ye && orthography != Orthography::Diacritic) return {};
    return impl_->lookup(input, mode, orthography);
}

std::vector<Candidate> Lexicon::handwritingSuggestions(const std::string& input, Mode mode, Orthography style) const {
    if (mode < Mode::Pinyin || mode > Mode::Handwriting || style < Orthography::Ye || style > Orthography::Diacritic) return {};
    return impl_->handwritingSuggestions(input, mode, style);
}

std::vector<Candidate> Lexicon::handwrittenWords(const std::string& before, Mode mode, Orthography style) const {
    if (mode < Mode::Pinyin || mode > Mode::Handwriting || style < Orthography::Ye || style > Orthography::Diacritic) return {};
    return impl_->handSuffixes(before, mode, style, false);
}

std::vector<Candidate> Lexicon::nextHandwrittenGlyphs(const std::string& before) const {
    return impl_->handSuffixes(before, Mode::Han, Orthography::Ye, true);
}

int Lexicon::handwritingContextLength() const {
    // A regional homophone may be outside the BMP even when its canonical graph is not.
    return static_cast<int>(std::min(impl_->handwriting.longest, HandwordIndex::contextCharacters) * 2);
}

std::vector<Candidate> Lexicon::nextSyllables(const std::string& context, Orthography style) const {
    return nextSyllables(context, Mode::Pinyin, style);
}

std::vector<Candidate> Lexicon::nextSyllables(const std::string& context, Mode mode, Orthography style) const {
    if (mode < Mode::Pinyin || mode > Mode::Handwriting || style < Orthography::Ye || style > Orthography::Diacritic) return {};
    return impl_->nextSyllables(context, mode, style);
}

int Lexicon::scoreHandwritingPrefix(const std::string& glyphs) const {
    return impl_->scoreHandwritingPrefix(glyphs);
}

std::vector<Candidate> Lexicon::spellingCompletions(const std::string& prefix, const std::string& history, Mode mode, Orthography style) const {
    if ((mode != Mode::Pinyin && mode != Mode::Han) || (style != Orthography::Ye && style != Orthography::Diacritic)) return {};
    return impl_->spellingCompletions(prefix, history, mode, style);
}

std::vector<Candidate> Lexicon::nextWords(const std::string& ids, Mode mode, Orthography style) const {
    if (mode < Mode::Pinyin || mode > Mode::Handwriting || style < Orthography::Ye || style > Orthography::Diacritic) return {};
    return impl_->nextWords(ids, mode, style);
}

} // namespace tujia
