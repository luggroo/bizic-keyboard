#ifndef TUJIA_INPUT_CORE_H
#define TUJIA_INPUT_CORE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Inputs are UTF-8. Dictionary and stroke assets are copied when opened.
   All returned objects are owned by the caller. */
typedef struct TJIWords TJIWords;
typedef struct TJIPen TJIPen;
typedef struct TJIChoices TJIChoices;

enum { TJI_PINYIN = 0, TJI_HAN = 1, TJI_HANDWRITING = 2 };
enum { TJI_TUJIA_WORD = 0, TJI_CHINESE_WORD = 1, TJI_LITERAL = 2, TJI_PUNCTUATION = 3 };
enum { TJI_YE = 0, TJI_DIACRITIC = 1 };
TJIWords *tji_words_open(const uint8_t *data, size_t length);
TJIWords *tji_words_open_with_ngrams(const uint8_t *data, size_t length, const uint8_t *ngrams, size_t ngram_length);
void tji_words_close(TJIWords *words);
TJIChoices *tji_lookup(const TJIWords *words, const char *input, int32_t mode);
TJIChoices *tji_lookup_with_orthography(const TJIWords *words, const char *input,
                                        int32_t mode, int32_t orthography);

/* Strict lexical completions consume the supplied glyphs. Next-word suggestions consume zero.
   Context is the last one or two selected word IDs, separated by an ASCII space. */
TJIChoices *tji_handwriting_suggestions(const TJIWords *words, const char *glyphs, int32_t mode, int32_t orthography);
TJIChoices *tji_next_words(const TJIWords *words, const char *context, int32_t mode, int32_t orthography);
int32_t tji_score_handwriting_prefix(const TJIWords *words, const char *glyphs);

TJIPen *tji_pen_open(const uint8_t *data, size_t length);
void tji_pen_close(TJIPen *pen);
size_t tji_glyph_count(const TJIPen *pen);
/* xy holds 2 * point_count floats. Each stroke end is an exclusive point index. */
TJIChoices *tji_recognize(const TJIPen *pen, const float *xy, size_t point_count,
                         const size_t *stroke_ends, size_t stroke_count, int32_t limit);

/* Pointers to choice strings stay valid until tji_choices_close. */
size_t tji_choices_count(const TJIChoices *choices);
const char *tji_choice_text(const TJIChoices *choices, size_t index);
const char *tji_choice_meaning(const TJIChoices *choices, size_t index);
const char *tji_choice_short_meaning(const TJIChoices *choices, size_t index);
/* Returns a TJI_ word kind, or -1 for an invalid choice. */
const char *tji_choice_lexeme_ids(const TJIChoices *choices, size_t index);
int32_t tji_choice_kind(const TJIChoices *choices, size_t index);
int32_t tji_choice_consumed_utf16(const TJIChoices *choices, size_t index);
/* Borrowed flat UTF-16 start/end-exclusive pairs, valid while choices is alive.
   Number of offsets (twice the range count) is written to count. */
const int32_t *tji_choice_joined_han_ranges(const TJIChoices *choices, size_t index, size_t *count);
void tji_choices_close(TJIChoices *choices);

/* Both operations return owned UTF-8 strings; use tji_text_free. A zero
   neighboring code unit means there is no character next to the selection. */
char *tji_suffix_after_choice(const char *draft, int32_t consumed_utf16);
char *tji_space_word(const char *text, uint16_t before, uint16_t after);
void tji_text_free(char *value);

#ifdef __cplusplus
}
#endif
#endif
