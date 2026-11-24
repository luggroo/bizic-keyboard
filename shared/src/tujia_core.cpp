#include "tujia_core.h"
#include "edit.hpp"
#include "lexicon.hpp"
#include "strokes.hpp"

#include <cstdlib>
#include <cstring>
#include <limits>
#include <memory>
#include <utility>

struct TJIWords { tujia::Lexicon model; };
struct TJIPen { tujia::Strokes model; };
struct TJIChoices { std::vector<tujia::Candidate> items; };

TJIWords *tji_words_open(const uint8_t *data, size_t length) {
    return tji_words_open_with_ngrams(data, length, nullptr, 0);
}
TJIWords *tji_words_open_with_ngrams(const uint8_t *data, size_t length, const uint8_t *grams, size_t size) {
    return tji_words_open_full(data, length, grams, size, nullptr, 0);
}
TJIWords *tji_words_open_full(const uint8_t *data, size_t length, const uint8_t *grams, size_t size,
                              const uint8_t *sounds, size_t soundSize) {
    if (!data || !length || (size && !grams) || (soundSize && !sounds)) return nullptr;
    try { return new TJIWords{tujia::Lexicon(std::string(reinterpret_cast<const char*>(data), length),
                                             size ? std::string(reinterpret_cast<const char*>(grams), size) : "",
                                             soundSize ? std::string(reinterpret_cast<const char*>(sounds), soundSize) : "")}; }
    catch (...) { return nullptr; }
}
void tji_words_close(TJIWords *words) { delete words; }

TJIChoices *tji_lookup(const TJIWords *words, const char *input, int32_t mode) {
    return tji_lookup_with_orthography(words, input, mode, TJI_YE);
}
TJIChoices *tji_lookup_with_orthography(const TJIWords *words, const char *input,
                                        int32_t mode, int32_t orthography) {
    if (!words || !input || mode < TJI_PINYIN || mode > TJI_HANDWRITING ||
        orthography < TJI_YE || orthography > TJI_DIACRITIC) return nullptr;
    try { return new TJIChoices{words->model.lookup(input, static_cast<tujia::Mode>(mode),
                                                    static_cast<tujia::Orthography>(orthography))}; }
    catch (...) { return nullptr; }
}

TJIChoices *tji_handwriting_suggestions(const TJIWords *words, const char *glyphs, int32_t mode, int32_t style) {
    if (!words || !glyphs) return nullptr;
    try { return new TJIChoices{words->model.handwritingSuggestions(glyphs, static_cast<tujia::Mode>(mode), static_cast<tujia::Orthography>(style))}; }
    catch (...) { return nullptr; }
}
TJIChoices *tji_next_words(const TJIWords *words, const char *ids, int32_t mode, int32_t style) {
    if (!words || !ids) return nullptr;
    try { return new TJIChoices{words->model.nextWords(ids, static_cast<tujia::Mode>(mode), static_cast<tujia::Orthography>(style))}; }
    catch (...) { return nullptr; }
}
TJIChoices *tji_next_syllables(const TJIWords *words, const char *context, int32_t style) {
    if (!words || !context) return nullptr;
    try { return new TJIChoices{words->model.nextSyllables(context, static_cast<tujia::Orthography>(style))}; }
    catch (...) { return nullptr; }
}
int32_t tji_score_handwriting_prefix(const TJIWords *words, const char *glyphs) {
    if (!words || !glyphs) return 0;
    try { return words->model.scoreHandwritingPrefix(glyphs); } catch (...) { return 0; }
}

TJIPen *tji_pen_open(const uint8_t *data, size_t length) {
    if (!data || !length) return nullptr;
    try { return new TJIPen{tujia::Strokes(std::string(reinterpret_cast<const char*>(data), length))}; }
    catch (...) { return nullptr; }
}
void tji_pen_close(TJIPen *pen) { delete pen; }
size_t tji_glyph_count(const TJIPen *pen) { return pen ? static_cast<size_t>(pen->model.size()) : 0; }

TJIChoices *tji_recognize(const TJIPen *pen, const float *xy, size_t point_count,
                         const size_t *ends, size_t count, int32_t limit) {
    if (!pen || (point_count && !xy) || (count && !ends) ||
        point_count > std::numeric_limits<size_t>::max() / 2) return nullptr;
    try {
        std::vector<std::vector<float>> strokes;
        size_t start = 0;
        for (size_t i = 0; i < count; ++i) {
            if (ends[i] <= start || ends[i] > point_count) return nullptr;
            strokes.emplace_back(xy + start * 2, xy + ends[i] * 2);
            start = ends[i];
        }
        if (start != point_count) return nullptr;
        auto result = std::make_unique<TJIChoices>();
        for (auto& glyph : pen->model.recognize(strokes, limit))
            result->items.push_back({std::move(glyph), {}, 0});
        return result.release();
    } catch (...) { return nullptr; }
}

size_t tji_choices_count(const TJIChoices *r) { return r ? r->items.size() : 0; }
const char *tji_choice_text(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() ? r->items[i].text.c_str() : nullptr;
}
const char *tji_choice_meaning(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() ? r->items[i].meaning.c_str() : nullptr;
}
const char *tji_choice_short_meaning(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() ? r->items[i].shortMeaning.c_str() : nullptr;
}
const char *tji_choice_lexeme_ids(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() ? r->items[i].lexemeIds.c_str() : nullptr;
}
const char *tji_choice_last_syllable(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() ? r->items[i].lastSyllable.c_str() : nullptr;
}
int32_t tji_choice_plain_han_loan(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() && r->items[i].plainHanLoan;
}
int32_t tji_choice_is_syllable(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() && r->items[i].syllableOnly;
}
int32_t tji_choice_kind(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() ? static_cast<int32_t>(r->items[i].kind) : -1;
}
int32_t tji_choice_consumed_utf16(const TJIChoices *r, size_t i) {
    return r && i < r->items.size() ? r->items[i].consumed : 0;
}
const int32_t *tji_choice_joined_han_ranges(const TJIChoices *r, size_t i, size_t *count) {
    if (!count) return nullptr;
    *count = 0;
    if (!r || i >= r->items.size()) return nullptr;
    const auto& ranges = r->items[i].joinedHanRanges;
    *count = ranges.size();
    return ranges.empty() ? nullptr : ranges.data();
}
void tji_choices_close(TJIChoices *choices) { delete choices; }

static char *copyText(const std::string& in) {
    char *out = static_cast<char*>(std::malloc(in.size() + 1));
    if (out) std::memcpy(out, in.c_str(), in.size() + 1);
    return out;
}
char *tji_suffix_after_choice(const char *draft, int32_t consumed) {
    if (!draft) return nullptr;
    try { return copyText(tujia::suffixAfterChoice(draft, consumed)); }
    catch (...) { return nullptr; }
}
char *tji_space_word(const char *text, uint16_t before, uint16_t after) {
    if (!text) return nullptr;
    try { return copyText(tujia::spaceWord(text, before, after)); }
    catch (...) { return nullptr; }
}
void tji_text_free(char *value) { std::free(value); }
