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
};

class Lexicon {
public:
    explicit Lexicon(const std::string& tsv);
    ~Lexicon();

    std::vector<Candidate> lookup(const std::string& input, Mode mode,
                                  Orthography orthography = Orthography::Ye) const;

private:
    struct Impl;
    std::unique_ptr<const Impl> impl_;
};

} // namespace tujia
