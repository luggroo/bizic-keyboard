#pragma once

#include <memory>
#include <string>
#include <vector>

namespace tujia {

enum class Mode { Pinyin = 0, Han = 1, Handwriting = 2 };
enum class Orthography { Ye = 0, Diacritic = 1 };
enum class CandidateKind { Tujia = 0, Chinese = 1, Literal = 2, Punctuation = 3 };

struct Candidate {
    std::string text;
    std::string meaning;
    // Number of UTF-16 code units consumed from the original editor input.
    int consumed;
    std::string shortMeaning{};
    CandidateKind kind = CandidateKind::Tujia;
    // Flat UTF-16 start/end-exclusive pairs in text for two-Han syllables.
    std::vector<int> joinedHanRanges{};
    // An orthographic spelling fallback, independent of a lexical word or meaning.
    bool syllableOnly = false;
    // Space-delimited source IDs in selection order. Empty ends lexical context.
    std::string lexemeIds{};
    // Canonical Ye tone-letter spelling of the selected final syllable.
    std::string lastSyllable{};
    // This Han choice uses the loanword’s original Chinese spelling without a compact gloss.
    bool plainHanLoan = false;
    // Shared by lexical Han choices with the same full toneless reading and meaning.
    // lexemeIds still identifies the actual selected reading for prediction.
    std::string hanSenseId{};
    // UTF-16 end of the existing word in a full handwriting extension.
    int handPrefixLength = 0;
    // Appending the candidate completes a word with an unchanged, nonempty written prefix.
    bool exactHandCompletion = false;
};

bool matchesSpellingPrefix(const std::string& input, const std::string& ye, const std::string& marked, bool allowYe = true);

class Lexicon {
public:
    explicit Lexicon(const std::string& tsv, const std::string& ngrams = {},
                     const std::string& syllableBigrams = {}, const std::string& syllableSenses = {},
                     const std::string& handwritingReadings = {});
    ~Lexicon();

    std::vector<Candidate> lookup(const std::string& input, Mode mode,
                                  Orthography orthography = Orthography::Ye) const;
    std::vector<Candidate> spellingOnSpace(const std::string& input, Orthography style) const;

    std::vector<Candidate> handwritingSuggestions(const std::string& glyphs, Mode output, Orthography style) const;
    std::vector<Candidate> handwrittenWords(const std::string& beforeCaret, Mode output, Orthography style,
                                            bool includeExtensions = false) const;
    std::vector<Candidate> nextHandwrittenGlyphs(const std::string& beforeCaret) const;
    int handwritingContextLength() const;
    std::vector<Candidate> nextSyllables(const std::string& context, Orthography style) const;
    std::vector<Candidate> nextSyllables(const std::string& context, Mode output, Orthography style) const;
    int scoreHandwritingPrefix(const std::string& glyphs) const;
    std::vector<Candidate> spellingCompletions(const std::string& prefix, const std::string& previousIds,
                                               Mode output, Orthography style) const;
    std::vector<Candidate> nextWords(const std::string& previousIds, Mode output, Orthography style) const;

private:
    struct Impl;
    std::unique_ptr<const Impl> impl_;
};

} // namespace tujia
