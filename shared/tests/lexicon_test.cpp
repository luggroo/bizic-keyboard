#include "lexicon.hpp"

#include <algorithm>
#include <climits>
#include <fstream>
#include <iostream>
#include <map>
#include <sstream>
#include <stdexcept>

using tujia::Candidate;
using tujia::CandidateKind;
using tujia::Lexicon;
using tujia::Mode;
using tujia::Orthography;

namespace {

void check(bool condition, const std::string& message) {
    if (!condition) throw std::runtime_error(message);
}

std::vector<std::string> split(const std::string& value, char separator) {
    std::istringstream stream(value);
    std::vector<std::string> result;
    for (std::string part; std::getline(stream, part, separator);) result.push_back(part);
    if (!value.empty() && value.back() == separator) result.emplace_back();
    return result;
}

// Test fixtures and the bundled asset contain valid UTF-8.
int editorLength(const std::string& text) {
    int length = 0;
    for (unsigned char c : text) {
        if ((c & 0xc0) != 0x80) length += c >= 0xf0 ? 2 : 1;
    }
    return length;
}

bool offers(const std::vector<Candidate>& choices, const std::string& text,
            const std::string& meaning, int consumed) {
    return std::any_of(choices.begin(), choices.end(), [&](const Candidate& c) {
        return c.text == text && c.meaning == meaning && c.consumed == consumed;
    });
}

Candidate first(const Lexicon& lexicon, const std::string& query, Mode mode) {
    auto choices = lexicon.lookup(query, mode);
    check(!choices.empty(), "No candidates for " + query);
    int previous = INT_MAX;
    CandidateKind previousKind = choices.front().kind;
    bool defaults = false;
    for (const auto& choice : choices) {
        if (choice.kind != previousKind) {
            check(previousKind == CandidateKind::Tujia && choice.kind == CandidateKind::Chinese,
                  "Unexpected candidate class order: " + query);
            previous = INT_MAX;
            previousKind = choice.kind;
        }
        if (!defaults && choice.syllableOnly) {
            defaults = true;
            previous = INT_MAX;
        }
        check(choice.consumed > 0 && choice.consumed <= previous, "Unexpected prefix order: " + query);
        previous = choice.consumed;
    }
    return choices.front();
}

void checkCorpus(const Lexicon& lexicon, const std::string& asset) {
    std::istringstream rows(asset);
    std::string line, threeSyllables;
    std::getline(rows, line);
    unsigned count = 0, ordinary = 0, syllables = 0;
    while (std::getline(rows, line)) {
        const auto fields = split(line, '\t');
        check(fields.size() == 7 || fields.size() == 10 || fields.size() == 12 || fields.size() == 13 || fields.size() == 14,
              "Invalid test asset");
        const auto& id = fields[0];
        const auto& word = fields[1];
        const auto& key = fields[2];
        const auto& meaning = fields[3];
        const auto& han = fields[4];
        if (fields.size() >= 10 && fields[8] == "zh") {
            check(offers(lexicon.lookup(word, Mode::Han), han, "", editorLength(word)), id + ": Mandarin");
            ++ordinary;
            continue;
        }
        if (fields.size() >= 10 && fields[8] == "syllable") {
            auto candidates = lexicon.lookup(fields[11], Mode::Han);
            int length = editorLength(fields[11]);
            bool identical = std::any_of(candidates.begin(), candidates.end(), [&](const Candidate& c) {
                return c.consumed == length && c.kind == CandidateKind::Tujia && c.meaning != meaning && c.text == han;
            });
            check(identical != offers(candidates, han, meaning, length), "Exact tone default or identical word: " + id);
            ++syllables;
            continue;
        }
        auto verify = [&](Mode mode, const std::string& query, const std::string& output,
                          Orthography form = Orthography::Ye) {
            check(offers(lexicon.lookup(query, mode, form), output, meaning, editorLength(query)), id + ": " + query);
        };
        verify(Mode::Pinyin, word, word);
        verify(Mode::Han, word, han);
        verify(Mode::Han, key, han);
        verify(Mode::Handwriting, han, word);
        if (fields.size() == 14) {
            for (const auto& alias : split(fields[13], ' ')) {
                if (alias.empty()) continue;
                check(offers(lexicon.lookup(alias, Mode::Pinyin), word, meaning, editorLength(alias)),
                      id + ": original romanization " + alias);
            }
        }
        if (fields.size() >= 12) {
            const auto& plain = fields[10];
            const auto& marked = fields[11];
            check(!plain.empty() && !marked.empty(), "Incomplete Tujia spelling pair for " + id);
            verify(Mode::Pinyin, plain, word);
            verify(Mode::Han, plain, han);
            verify(Mode::Pinyin, plain, marked, Orthography::Diacritic);
            verify(Mode::Pinyin, marked, word);
            verify(Mode::Handwriting, han, marked, Orthography::Diacritic);
        }
        for (const auto& alias : split(fields[5], ' ')) verify(Mode::Handwriting, alias, word);
        std::string combination;
        for (const auto& choices : split(fields[6], ' ')) {
            size_t last = choices.size() - 1;
            while ((static_cast<unsigned char>(choices[last]) & 0xc0) == 0x80) --last;
            combination += choices.substr(last);
        }
        if (!combination.empty()) verify(Mode::Handwriting, combination, word);
        if (id == "w2643") threeSyllables = han;
        ++count;
    }
    check(count >= 345, "Missing original Tujia words");
    std::cout << "Checked " << count << " Tujia lexical/grammar entries, " << syllables << " syllable readings and "
              << ordinary << " Mandarin readings.\n";
    const std::string fourSyllables = "zaovguxdiexgaf";
    Candidate three = first(lexicon, fourSyllables, Mode::Han);
    check(three.text == threeSyllables && fourSyllables.substr(three.consumed) == "gaf",
          "Three-syllable head plus uncommitted suffix");
    check(offers(lexicon.lookup(fourSyllables.substr(three.consumed), Mode::Han), "架", "吃、咬", 3),
          "Still offers the remaining syllable");
}

void checkSentences(const Lexicon& lexicon) {
    auto unknown = first(lexicon, "早箍得龘", Mode::Handwriting);
    check(unknown.text == "zaovguxdiex" && unknown.consumed == 3, "Unknown handwritten suffix");
    check(first(lexicon, "ngafyergaf。", Mode::Pinyin).text == "ngaf yer gaf。", "Known sentence");
    check(offers(lexicon.lookup("ngafyergaf。", Mode::Pinyin), "ngaf", "我", 4), "Shorter prefix");
    check(first(lexicon, "ngafyergafhxif。", Mode::Pinyin).text == "ngaf yer gaf hxif。", "Dynamic sentence");
    auto ambiguous = first(lexicon, "俄耶尬尬。", Mode::Handwriting);
    check(ambiguous.text == "ngaf yer gaf" && ambiguous.consumed == 3, "Ambiguous fourth glyph");
    check(first(lexicon, "。", Mode::Handwriting).text == "。", "Trailing punctuation");
    check(first(lexicon, " NGAF YER GAF。", Mode::Pinyin).text == "ngaf yer gaf。", "Case and whitespace");
    auto mealHan = first(lexicon, "ngaf", Mode::Han).text + first(lexicon, "yer", Mode::Han).text +
                   first(lexicon, "gaf", Mode::Han).text + "。";
    check(first(lexicon, " NGAF YER GAF。", Mode::Han).text == mealHan, "Han sentence");
    auto homophones = lexicon.lookup("jir", Mode::Han);
    check(offers(homophones, "及", "脚", 3) && offers(homophones, "迹", "步、脚步", 3) &&
          offers(homophones, "基", "下面", 3), "Homophones remain selectable");
    auto human = lexicon.lookup("倮", Mode::Handwriting);
    check(offers(human, "lov", "人", 1) && !offers(human, "lov", "暖和", 1), "Word-specific glyph");
    check(offers(lexicon.lookup("hxif", Mode::Han), "诶", "走", 4), "Customary syllable");
    for (auto [syllable, han] : {std::pair{"hxex","呃"}, {"hxef","轭"}, {"hxer","额"}, {"hxev","呃"}}) {
        auto options = lexicon.lookup(syllable, Mode::Han);
        check(std::any_of(options.begin(), options.end(), [&](const Candidate& c) {
            return c.syllableOnly && c.text == han && c.joinedHanRanges.empty();
        }), std::string("Single-character hxe default: ") + syllable);
    }
    for (auto [key, han] : {std::pair{"xi","兮"}, {"xige","兮膈"}}) {
        auto options = lexicon.lookup(key, Mode::Han);
        check(std::any_of(options.begin(), options.end(), [&](const Candidate& c) {
            return c.lexemeIds == std::string("grammar-") + key && c.text == han;
        }), std::string("Grammatical reading: ") + key);
    }
    check(lexicon.lookup("gaf", Mode::Handwriting).empty(), "Latin via handwriting");
    check(lexicon.lookup("龘", Mode::Pinyin).empty(), "Unknown pinyin");
    check(lexicon.lookup("", Mode::Pinyin).empty(), "Empty composition");
    check(lexicon.lookup(std::string(1, char(0xff)), Mode::Pinyin).empty(), "Invalid UTF-8");
}

void checkApicalWords(const Lexicon& lexicon) {
    // The first two syllables of “noon” preserve the Chinese loan 半日.
    for (const auto& key : {"bangricai", "bangfrixcaix", "bángrīcāi"}) {
        const auto choices = lexicon.lookup(key, Mode::Han);
        const auto noon = std::find_if(choices.begin(), choices.end(), [](const Candidate& c) {
            return c.meaning == "中午";
        });
        check(noon != choices.end() && noon->text == "半日猜" && noon->joinedHanRanges.empty(),
              std::string("Three single Han syllables in noon: ") + key);
        check(std::none_of(choices.begin(), choices.end(), [](const Candidate& c) {
            return c.text == "棒日衣猜";
        }), "The former written form is a reverse-lookup alias only");
    }
    check(offers(lexicon.lookup("棒日衣猜", Mode::Handwriting), "bangfrixcaix", "中午", 4),
          "Previously written noon remains searchable");
    for (const auto* marked : {"rī", "rí", "rǐ", "rì"}) {
        const auto choices = lexicon.lookup(marked, Mode::Han);
        check(std::any_of(choices.begin(), choices.end(), [](const Candidate& c) {
            return c.text == "日" && c.joinedHanRanges.empty();
        }), std::string("Single Han glyph for ") + marked);
    }
}

void checkUtf16Offsets() {
    Lexicon fixture("id\tword\tkey\tmeaning\trecommended\taliases\tvariants\r\n"
                    "t1\tab\tab\t甲乙\t甲乙\t\t甲𠀀 乙𠀁\r\n");
    auto choice = first(fixture, "　𠀀𠀁  丙", Mode::Handwriting);
    check(choice.text == "ab" && choice.consumed == 7, "UTF-16 index for grouped astral aliases");
    choice = first(fixture, "　！？𠀀", Mode::Handwriting);
    check(choice.text == "！？" && choice.consumed == 3, "Punctuation after ideographic space");
}

void checkRankingAndOrthography() {
    Lexicon fixture(
        "id\tword\tkey\tmeaning\trecommended\taliases\tvariants\tfrequency\tkind\tshortGloss\tplain\tdiacritic\n"
        "w684\tgaf\tga\t吃、咬\t架\t尬\t架嘎\t120\ttujia\t吃\tga\tgá\n"
        "w687\tgar\tga\t干、渴\t夹\t尬\t夹嘎\t5\ttujia\t干\tga\tgǎ\n"
        "w1585\tngaf\te\t我\t压\t俄\t压\t30\ttujia\t我\tnga\tngá\n"
        "w2548\tyer\tye\t饭\t叶\t耶\t叶\t7\ttujia\t饭\tye\tyě\n"
        "a\tlaf\tla\t一\t拉\t\t拉\t3\ttujia\t一\tla\tlá\n"
        "u\tüf\tyu\t测试\t鱼\t\t鱼\t1\ttujia\t测试\tü\tǘ\n"
        "phrase\tgaf laf\tgala\t同一个词组\t架拉\t\t\t1\tphrase\t词组\tga la\tgá lá\n"
        "z1\tga\tga\t\t旮\t\t\t10\tzh\t\t\t\n"
        "z2\tga\tga\t\t嘎\t\t\t35\tzh\t\t\t\n"
        "z3\tgaha\tgaha\t\t干啥\t\t\t85\tzh\t\t\t\n"
        "z4\tnihao\tnihao\t\t你好\t\t\t90\tzh\t\t\t\n");
    const auto mixed = fixture.lookup("gaha", Mode::Han);
    check(mixed.size() == 5, "All partial Tujia and Mandarin candidates");
    check(mixed[0].text == "架" && mixed[0].shortMeaning == "吃" && mixed[0].meaning == "吃、咬" &&
          mixed[1].text == "夹", "Frequency order and short/full Tujia gloss");
    check(mixed[2].kind == CandidateKind::Chinese && mixed[2].text == "干啥" &&
          mixed[2].consumed == 4 && mixed[3].text == "嘎" && mixed[4].text == "旮", "Layered frequency order");
    check(mixed[3].meaning.empty() && mixed[3].shortMeaning.empty(), "No false Tujia gloss on ordinary Han");
    const auto marked = fixture.lookup("ga", Mode::Pinyin, Orthography::Diacritic);
    check(marked[0].text == "gá" && marked[1].text == "gǎ", "Separate senses and marked outputs");
    check(offers(fixture.lookup("nga", Mode::Pinyin), "ngaf", "我", 3) &&
          !offers(fixture.lookup("e", Mode::Pinyin), "ngaf", "我", 1), "Tujia plain form vs Mandarin key");
    check(offers(fixture.lookup("GÁ", Mode::Pinyin), "gaf", "吃、咬", 2) &&
          !offers(fixture.lookup("gá", Mode::Pinyin), "gar", "干、渴", 2), "Explicit tone disambiguates");
    check(offers(fixture.lookup("ga\u0301后", Mode::Pinyin), "gaf", "吃、咬", 3), "Decomposed input UTF-16 length");
    check(offers(fixture.lookup("U\u0308\u0301", Mode::Pinyin), "üf", "测试", 3), "Combining tone after diaeresis");
    check(offers(fixture.lookup("尬", Mode::Handwriting, Orthography::Diacritic), "gǎ", "干、渴", 1),
          "Handwriting selected orthography");
    check(fixture.lookup("ngayega。", Mode::Pinyin, Orthography::Diacritic).front().text == "ngá yě gá。",
          "Sentence uses same orthography for each word");
    check(offers(fixture.lookup("gala", Mode::Pinyin, Orthography::Diacritic), "gá lá", "同一个词组", 4),
          "Compact phrase key and original word boundaries");
    check(first(fixture, "nihao", Mode::Pinyin).text == "你好" &&
          first(fixture, "你好", Mode::Handwriting).text == "你好", "Ordinary Mandarin word in both paths");
    check(first(fixture, "𠀀", Mode::Handwriting).consumed == 2, "Direct unlisted handwritten character");
}

void checkHanUnitsAndDefaults() {
    Lexicon lex(
        "id\tword\tkey\tmeaning\trecommended\taliases\tvariants\tfrequency\tkind\tshortGloss\tplain\tdiacritic\thanUnits\n"
        "e1\tpx\tp\t甲词\t甲乙\t\t皮\t3\ttujia\t甲\tp\tpā\t甲乙\n"
        "e2\tqf\tq\t乙词\t𠀀\t\t\t1\ttujia\t乙\tq\tqá\t𠀀\n"
        "e3\trr\tr\t丙词\t丙丁\t\t\t2\ttujia\t丙\tr\trǎ\t丙丁\n"
        "e4\tkx\tk\t组合\t甲乙𠀀\t\t皮 戊\t1\ttujia\t组合\tk\tkā\t甲乙|𠀀\n"
        "word-syl\tlx\tl\t音节\t词\t\t\t1\ttujia\t音节\tl\tlā\t词\n"
        "d1\tfx\tf\t音节\t上声\t\t\t0\tsyllable\t音节\tf\tfā\t上声\n"
        "d2\tpx\tp\t音节\t另\t\t\t0\tsyllable\t音节\tp\tpā\t另\n");
    const auto word = first(lex, "lx", Mode::Han);
    const auto fallback = first(lex, "fx", Mode::Han);
    check(!word.syllableOnly && fallback.syllableOnly && word.meaning == fallback.meaning,
          "Only source-classified spelling fallbacks have no semantic gloss");
    auto exact = first(lex, "px", Mode::Han);
    check(exact.joinedHanRanges == std::vector<int>{0,2}, "Explicit paired glyph");
    check(offers(lex.lookup("pā", Mode::Han), "另", "音节", 2) &&
          first(lex, "pā", Mode::Han).text == "甲乙" && !offers(lex.lookup("p", Mode::Han), "另", "音节", 1),
          "An explicit tone unlocks a distinct spelling after lexical candidates");
    check(first(lex, "fā", Mode::Han).joinedHanRanges == std::vector<int>{0,2}, "Bare syllable default range");
    check(!offers(lex.lookup("fxfxfx", Mode::Han), "上声上声上声", "音节 · 音节 · 音节", 6), "No synthetic sentence");
    auto sentence = first(lex, "pxqfrr。", Mode::Han);
    check(sentence.text == "甲乙𠀀丙丁。" && sentence.joinedHanRanges == std::vector<int>{0,2,4,6},
          "Sentence offsets across an astral single character");
    check(first(lex, "甲乙戊", Mode::Handwriting).text == "kx" &&
          first(lex, "皮𠀀", Mode::Handwriting).text == "kx", "Mixed handwriting alternatives by syllable");
    check(!offers(lex.lookup("甲戊", Mode::Handwriting), "kx", "组合", 2), "No half of a paired glyph");
    auto marked = first(lex, "PA\u0304qf", Mode::Han);
    check(marked.text == "甲乙" && marked.consumed == 3, "Exact normalized mark and boundary");
    check(first(lex, "px", Mode::Pinyin).joinedHanRanges.empty(), "No underline on spelling output");
}

void checkContext(const Lexicon& lex) {
    const auto han = Mode::Han;
    const auto ye = Orthography::Ye;
    const auto marked = Orthography::Diacritic;
    check(offers(lex.handwritingSuggestions("玻", han, ye), "波俐", "小孩、儿童", 1), "Alias prefix as complete word");
    check(offers(lex.handwritingSuggestions("波", Mode::Handwriting, ye), "boxlir", "小孩、儿童", 1), "Handwriting to spelling completion");
    check(!offers(lex.handwritingSuggestions("波俐", han, ye), "波俐", "小孩、儿童", 2), "Exact lexeme is not a completion");
    check(lex.scoreHandwritingPrefix("波俐") == 2 && lex.scoreHandwritingPrefix("玻") > 0 &&
          lex.scoreHandwritingPrefix("波。") == 0 && lex.scoreHandwritingPrefix("") == 0, "Recognition evidence and boundaries");
    check(!lex.nextWords("w2548",han,ye).empty() && lex.nextWords("w2548",han,ye).front().lexemeIds == "w684", "Attested 饭→吃");
    check(offers(lex.nextWords("w2548",Mode::Pinyin,marked), "gá", "吃、咬", 0), "Prediction consumes zero, uses chosen orthography");
    check(lex.nextWords("w2548 。",han,ye).empty() && lex.nextWords("unknown",han,ye).empty(), "No cross-boundary inference");
    check(lex.lookup("ngafyergaf。", Mode::Pinyin).front().lexemeIds.empty() &&
          lex.lookup("ngafyergaf", Mode::Pinyin).front().lexemeIds == "w1585 w2548 w684", "Sentence context reset after punctuation");
    for (const auto& input : {std::string("rixi"), std::string("xixian")}) {
        const auto possibilities = lex.lookup(input, Mode::Han);
        const std::string id = input == "rixi" ? "w1932" : "xu213-4-2";
        check(std::any_of(possibilities.begin(), possibilities.end(), [&](const Candidate& c) {
            return c.lexemeIds == id && c.consumed == 2;
        }), "Do not consume the next syllable's x: " + input);
    }
    const auto completeTone = lex.lookup("ngaf", Mode::Han);
    check(std::none_of(completeTone.begin(), completeTone.end(), [](const Candidate& c) {
        return c.lexemeIds == "w1585" && c.consumed == 3;
    }), "A terminal tone letter stays with its syllable");
    Lexicon fixture("id\tword\tkey\tmeaning\trecommended\taliases\tvariants\tfrequency\tkind\tshortGloss\tplain\tdiacritic\thanUnits\tromanAliases\n"
                    "one\ta\ta\t甲\t甲\t\t甲\t1\ttujia\t甲\ta\ta\t甲\talf\n"
                    "two\tb\tb\t乙\t乙\t\t乙\t20\ttujia\t乙\tb\tb\t乙\t\n"
                    "three\tc\tc\t丙\t𠀀汉人\t见里\t山 人\t4\ttujia\t丙\tc\tc\t𠀀汉|人\t\n",
                    "context\tnext\tcount\texample\none\ttwo\t8\tx\none\tthree\t1\tx\ntwo one\tthree\t1\tx\n");
    check(fixture.nextWords("one",han,ye).front().lexemeIds == "two" &&
          fixture.nextWords("two one",han,ye).front().lexemeIds == "three" && fixture.nextWords("two one",han,ye).size()==2,
          "Observed trigram precedes high-frequency bigram, no double suggestion");
    auto partial = fixture.handwritingSuggestions("𠀀",han,ye);
    check(partial.size() == 1 && partial.front().consumed == 2 && partial.front().joinedHanRanges == std::vector<int>{0,3} &&
          fixture.scoreHandwritingPrefix("𠀀") == 1, "Half of two-Han unit is a strict completion, UTF-16");
    check(fixture.scoreHandwritingPrefix("见") == 1 && fixture.scoreHandwritingPrefix("山人") == 2, "Historical alias and mixed alternatives");
    check(fixture.lookup("alf",Mode::Pinyin).front().lexemeIds == "one", "Source roman aliases retain canonical output");
}

void checkSyllableBigrams(const Lexicon& lex, const std::string& data) {
    struct Arc { int rank; std::string next, accent; };
    std::map<std::string, std::vector<Arc>> wanted;
    std::istringstream stream(data);
    std::string line;
    std::getline(stream, line);
    size_t edgeCount = 0;
    while (std::getline(stream, line)) {
        const auto f = split(line, '\t');
        check(f.size() == 6, "Source rows carry both accent spellings");
        wanted[f[0]].push_back({std::stoi(f[3]), f[1], f[5]});
        ++edgeCount;
    }
    check(edgeCount == 1000 && wanted["<s>"].size() == 175, "Published syllable ranking and sentence starts");
    for (auto& pair : wanted) {
        std::stable_sort(pair.second.begin(), pair.second.end(), [](const Arc& x, const Arc& y) { return x.rank < y.rank; });
        const auto ye = lex.nextSyllables(pair.first, Orthography::Ye);
        const auto marked = lex.nextSyllables(pair.first, Orthography::Diacritic);
        check(ye.size() == pair.second.size() && marked.size() == ye.size(), "Full ranked coverage for " + pair.first);
        for (size_t i=0; i<ye.size(); ++i) {
            const auto& expected = pair.second[i];
            check(ye[i].text == expected.next && marked[i].lastSyllable == expected.next &&
                  marked[i].text == expected.accent && ye[i].consumed == 0 && ye[i].lexemeIds.empty() &&
                  ye[i].syllableOnly && ye[i].meaning == "音节", "Syllable rank/style: " + pair.first + " " + expected.next);
        }
    }
    check(lex.nextSyllables("", Orthography::Ye).empty() && lex.nextSyllables("。", Orthography::Ye).empty() &&
          lex.nextSyllables("ngaf yer", Orthography::Ye).empty(), "Only an explicit boundary or ONE known prior syllable predicts");
    for (const auto& p : std::vector<std::pair<std::string,std::string>>{{"boxlir","lir"},{"bangfrixcaix","caix"},{"arciex","ciex"}}) {
        const auto result = lex.lookup(p.first, Mode::Pinyin);
        check(!result.empty() && result.front().lastSyllable == p.second, "Multisyllabic source ending for " + p.first);
    }
    check(lex.lookup("ngafyergaf。",Mode::Pinyin).front().lastSyllable.empty(), "Sentence stop clears syllable context");
    check(lex.lookup("ngafyergaf",Mode::Pinyin).front().lastSyllable == "gaf", "Composed sentence last syllable");
}

void checkSyllableFixture(const std::string& words) {
    const auto table="context\tnext\tcount\trank\n<s>\tgaf\t\t3\n<s>\tyer\t4\t1\n<s>\tngaf\t1\t3\n"
                     "<s>\tgaf\t\t5\nlir\tgaf\t\t2\nlir\tyer\t\t1\n";
    Lexicon sample(words, "", table);
    const auto start=sample.nextSyllables("<s>",Orthography::Ye);
    check(start.size()==3 && start[0].text=="yer" && start[1].text=="gaf" && start[2].text=="ngaf", "Rank, equal-rank source order and distinct next syllables");
    const auto toned=sample.nextSyllables("LI\u030c",Orthography::Diacritic);
    check(toned.size()==2 && toned[0].text=="yě" && toned[1].text=="gá", "Decomposed/uppercase syllable context");
    check(sample.nextWords("w2548",Mode::Han,Orthography::Ye).empty(), "Syllable arcs never create word pairs");
}

} // namespace

int main(int argc, char** argv) {
    try {
        check(argc == 2, "Provide the path to words.tsv");
        std::ifstream input(argv[1], std::ios::binary);
        check(input.good(), "Cannot open dictionary");
        std::ostringstream contents;
        contents << input.rdbuf();
        const std::string asset = contents.str();
        std::string assetPath(argv[1]);
        std::ifstream gramFile(assetPath.substr(0,assetPath.find_last_of("/\\")+1)+"ngrams.tsv");
        check(gramFile.good(), "Cannot open ngram data");
        std::ostringstream ngrams; ngrams << gramFile.rdbuf();
        std::ifstream soundFile(assetPath.substr(0,assetPath.find_last_of("/\\")+1)+"syllable_bigrams.tsv");
        check(soundFile.good(), "Cannot open syllable ranking");
        std::ostringstream sounds; sounds << soundFile.rdbuf();
        Lexicon lexicon(asset, ngrams.str(), sounds.str());
        checkSyllableBigrams(lexicon, sounds.str());
        checkSyllableFixture(asset);
        checkContext(lexicon);
        checkCorpus(lexicon, asset);
        checkSentences(lexicon);
        checkApicalWords(lexicon);
        checkUtf16Offsets();
        checkRankingAndOrthography();
        checkHanUnitsAndDefaults();
        std::cout << "Passed segmentation, ranking, orthography, handwriting and UTF-16 offsets.\n";
    } catch (const std::exception& e) {
        std::cerr << e.what() << '\n';
        return 1;
    }
}
