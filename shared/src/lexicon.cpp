#include "lexicon.hpp"

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

using Fields = std::array<std::string_view, 13>;
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

} // namespace

struct Lexicon::Impl {
    std::vector<Entry> entries;
    WordIndex spellings, shortKeys, hanWords, aliases;
    WordIndex syllables;
    std::unordered_map<char32_t, WordIds> variantInitials;
    WordIndex chineseKeys;
    std::unordered_set<std::string> chineseWritten;
    std::vector<ChineseWord> chineseWords;
    size_t maxChinese = 0, maxWritten = 0;
    size_t maxKey = 0, maxSyllable = 0;

    explicit Impl(const std::string& tsv) {
        std::string_view lines(tsv), line;
        const std::string legacyHeader = "id\tword\tkey\tmeaning\trecommended\taliases\tvariants";
        if (!readLine(lines, line)) throw std::invalid_argument("Empty dictionary");
        const auto rankedHeader = legacyHeader + "\tfrequency\tkind\tshortGloss";
        const auto toneHeader = rankedHeader + "\tplain\tdiacritic";
        const bool hasUnits = line == toneHeader + "\thanUnits";
        const bool tones = hasUnits || line == toneHeader;
        const bool ranked = tones || line == rankedHeader;
        if (line != legacyHeader && !ranked) {
            throw std::invalid_argument("Unexpected dictionary header");
        }
        const size_t fieldCount = hasUnits ? 13 : tones ? 12 : ranked ? 10 : 7;
        Fields fields{};
        while (readLine(lines, line)) {
            readFields(line, fieldCount, fields);
            if (fields[1].empty() || fields[4].empty()) {
                throw std::invalid_argument("Invalid dictionary row");
            }
            const uint64_t frequency = ranked ? frequencyOf(fields[7]) : 0;
            if (ranked && fields[8] == "zh") {
                // Mandarin supplies most rows; only its written form, key and
                // frequency enter the index. Do not allocate thirteen strings per row.
                const size_t id = chineseWords.size();
                chineseWritten.emplace(fields[4]);
                maxWritten = std::max(maxWritten, countScalars(fields[4]));
                index(chineseKeys, std::string(fields[1]), id, maxChinese);
                if (!fields[2].empty() && fields[2] != fields[1])
                    index(chineseKeys, std::string(fields[2]), id, maxChinese);
                chineseWords.push_back({std::string(fields[4]), frequency});
                continue;
            }
            std::vector<std::string> f(fields.begin(), fields.begin() + fieldCount);
            const bool phrase = ranked && f[8] == "phrase";
            const bool syllable = ranked && f[8] == "syllable";
            const bool grammar = ranked && f[8] == "grammar";
            const bool collectedUnits = hasUnits && !f[12].empty();
            if ((ranked && !phrase && !syllable && !grammar && f[8] != "tujia") ||
                (!collectedUnits && !phrase && !syllable && !grammar && f[6].empty()))
                throw std::invalid_argument("Invalid Tujia entry");
            const size_t id = entries.size();
            Entry entry{f[0], f[1], f[3], f[4], !ranked || f[9].empty() ? f[3] : f[9],
                        tones && !f[11].empty() ? f[11] : f[1], frequency, {}};
            dictionaryScalars(entry.meaning);
            dictionaryScalars(entry.shortMeaning);
            if (collectedUnits) entry.setUnits(f[12]);
            else for (auto cp : dictionaryScalars(entry.han)) entry.hanLength += cp >= 0x10000 ? 2 : 1;
            if (syllable) {
                for (const auto& form : {entry.word, tones ? f[10] : std::string(), entry.diacritic, entry.han}) {
                    Query normalized;
                    if (!normalized.read(form)) throw std::invalid_argument("Invalid syllable");
                    if (form.empty()) continue;
                    addUnique(syllables[normalized.folded], id);
                    maxSyllable = std::max(maxSyllable, normalized.scalars.size());
                }
                entries.push_back(std::move(entry));
                continue;
            }
            addSpelling(entry.word, id);
            const bool suffix = grammar && entry.word.front() == '-';
            if (tones) {
                if (!suffix || f[10].find('-') == 0) addSpelling(f[10], id);
                if (!suffix || f[11].find('-') == 0) addSpelling(f[11], id);
            }
            if (!f[2].empty() && (!suffix || f[2].front() == '-') &&
                (bucket(spellings, f[2]).empty() || bucket(spellings, f[2]).back() != id))
                index(shortKeys, f[2], id, maxKey);
            index(hanWords, entry.han, id, maxKey);
            for (const auto& alias : split(f[5], ' ')) {
                if (!alias.empty()) index(aliases, alias, id, maxKey);
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
            entries.push_back(std::move(entry));
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
    }

    void addSpelling(const std::string& form, size_t id) {
        if (form.empty()) return;
        Query normalized;
        if (!normalized.read(form)) throw std::invalid_argument("Invalid dictionary spelling");
        auto key = normalized.folded;
        auto& words = spellings[key];
        if (words.empty() || words.back() != id) words.push_back(id);
        maxKey = std::max(maxKey, normalized.scalars.size());
        if (key.find(' ') != std::string::npos) {
            key.erase(std::remove(key.begin(), key.end(), ' '), key.end());
            addUnique(spellings[key], id);
        }
    }

    static void index(WordIndex& map, const std::string& key, size_t id, size_t& longest) {
        map[key].push_back(id);
        longest = std::max(longest, countScalars(key));
    }

    WordIds match(const Query& q, size_t start, size_t end, Mode mode) const {
        const char32_t first = q.scalars[start];
        if (!hanCharacter(first)) {
            const auto form = q.slice(start, end);
            // Explicit delimiters on bound grammar signs belong to the form.
            if (mode == Mode::Handwriting) return bucket(hanWords, form);
            auto matches = bucket(spellings, form);
            if (mode == Mode::Han) {
                const auto& shorter = bucket(shortKeys, form);
                matches.insert(matches.end(), shorter.begin(), shorter.end());
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
            if (!out.shortMeaning.empty()) out.shortMeaning += " · ";
            out.text += entries[id].output(mode, orthography);
            out.meaning += entries[id].meaning;
            out.shortMeaning += entries[id].shortMeaning;
        }
        out.text += tail;
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
                auto choices = match(q, i, end, mode);
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
        // Several input forms can name the same lexeme. In "ngaf", committing
        // the "nga" alias would leave the tone letter stranded in the editor.
        std::unordered_set<size_t> offered;
        for (size_t end = std::min(start + maxKey, q.scalars.size()); end > start; --end) {
            const size_t batchStart = result.size();
            auto choices = match(q, start, end, mode);
            if (!choices.empty()) lexicalLengths.insert(q.utf16At[q.skipSpaces(end)]);
            std::stable_sort(choices.begin(), choices.end(), [&](size_t a, size_t b) {
                return entries[a].frequency > entries[b].frequency;
            });
            for (size_t id : choices) {
                if (!offered.insert(id).second) continue;
                const auto& entry = entries[id];
                const auto& text = entry.output(mode, orthography);
                auto duplicate = std::find_if(result.begin() + batchStart, result.end(), [&](const Candidate& c) {
                    return c.text == text && c.meaning == entry.meaning;
                });
                if (duplicate == result.end()) {
                    result.push_back({text, entry.meaning, q.utf16At[q.skipSpaces(end)], entry.shortMeaning});
                    if (mode == Mode::Han) result.back().joinedHanRanges = entry.joinedRanges;
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
        const bool latin = mode != Mode::Handwriting && q.scalars[start] >= U'a' && q.scalars[start] <= U'z';
        if (written || latin) appendChinese(result, q, start, written);
        return result;
    }

    void appendSyllables(std::vector<Candidate>& result, const Query& q, size_t start, Mode mode,
                         Orthography orthography, const std::unordered_set<int>& lexicalLengths) const {
        const bool written = hanCharacter(q.scalars[start]);
        if ((written && mode == Mode::Pinyin) || (!written && mode == Mode::Handwriting)) return;
        for (size_t end = std::min(start + maxSyllable, q.scalars.size()); end > start; --end) {
            const auto spelling = q.slice(start, end);
            const auto& defaults = bucket(syllables, spelling);
            if (defaults.empty()) continue;
            const int consumed = q.utf16At[q.skipSpaces(end)];
            const bool lexical = lexicalLengths.count(consumed);
            for (size_t id : defaults) {
                const auto& e = entries[id];
                if (lexical && (written || (spelling != e.word && spelling != e.diacritic))) continue;
                const auto& text = e.output(mode, orthography);
                if (std::any_of(result.begin(), result.end(), [&](const Candidate& c) {
                        return c.consumed == consumed && c.text == text;
                    })) continue;
                result.push_back({text, e.meaning, consumed, e.shortMeaning});
                result.back().syllableOnly = true;
                if (mode == Mode::Han) result.back().joinedHanRanges = e.joinedRanges;
            }
        }
    }

    void appendChinese(std::vector<Candidate>& result, const Query& q, size_t start, bool written) const {
        // Each candidate class has its own longest-first ordering. Keep all Tujia
        // matches visible ahead of Mandarin, even if a Mandarin word consumes more.
        size_t maxLength = written ? std::max(maxWritten, size_t(1)) : maxChinese;
        for (size_t end = std::min(start + maxLength, q.scalars.size()); end > start; --end) {
            const auto form = q.slice(start, end);
            const int consumed = q.utf16At[q.skipSpaces(end)];
            if (written) {
                if (chineseWritten.count(form) || end == start + 1)
                    result.push_back({form, "", consumed, "", CandidateKind::Chinese});
            } else {
                for (size_t id : bucket(chineseKeys, form))
                    result.push_back({chineseWords[id].text, "", consumed, "", CandidateKind::Chinese});
            }
        }
    }
};

Lexicon::Lexicon(const std::string& tsv) : impl_(new Impl(tsv)) {}
Lexicon::~Lexicon() = default;

std::vector<Candidate> Lexicon::lookup(const std::string& input, Mode mode, Orthography orthography) const {
    if (mode != Mode::Pinyin && mode != Mode::Han && mode != Mode::Handwriting) return {};
    if (orthography != Orthography::Ye && orthography != Orthography::Diacritic) return {};
    return impl_->lookup(input, mode, orthography);
}

} // namespace tujia
