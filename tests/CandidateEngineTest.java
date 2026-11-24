import biz.tujia.ime.CandidateEngine;
import biz.tujia.ime.CandidateEngine.Candidate;
import biz.tujia.ime.CandidateEngine.Mode;
import biz.tujia.ime.CandidateEngine.Orthography;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Arrays;

/** Run with the repository's test-engine.sh script using only a JDK. */
public final class CandidateEngineTest {
    private static void check(boolean pass, String message) {
        if (!pass) throw new AssertionError(message);
    }

    private static boolean offers(List<Candidate> choices, String value, String meaning, int consumed) {
        for (Candidate choice : choices) {
            if (choice.text.equals(value) && choice.meaning.equals(meaning) && choice.consumed == consumed) return true;
        }
        return false;
    }

    private static Candidate first(CandidateEngine engine, String input, Mode mode) {
        List<Candidate> choices = engine.lookup(input, mode);
        check(!choices.isEmpty(), "No match for " + input);
        int previousLength = Integer.MAX_VALUE;
        for (Candidate choice : choices) {
            if (choice.kind != CandidateEngine.Kind.TUJIA || choice.syllableOnly) continue;
            check(choice.consumed <= previousLength, "Candidates out of order for " + input);
            previousLength = choice.consumed;
        }
        return choices.get(0);
    }

    public static void main(String[] args) throws Exception {
        Path asset = Paths.get(args[0]);
        CandidateEngine engine = new CandidateEngine(new FileInputStream(asset.toFile()), new FileInputStream(asset.resolveSibling("ngrams.tsv").toFile()),
                new FileInputStream(asset.resolveSibling("syllable_bigrams.tsv").toFile()),
                new FileInputStream(asset.resolveSibling("syllable_senses.tsv").toFile()));
        String decomposed = "bánrīcāi";
        List<Candidate> nasalSentence = engine.lookup(decomposed + "gaf", Mode.HAN);
        Candidate noon = nasalSentence.stream().filter(c -> c.text.equals("半日猜") && c.meaning.equals("中午")).findFirst().get();
        check(noon.consumed == decomposed.length() && CandidateEngine.suffixAfterChoice(decomposed + "gaf", noon.consumed).equals("gaf"),
              "Folded nasal coda consumes the original UTF-16 prefix including its combining accent");
        check(CandidateEngine.matchesSpellingPrefix("áng", "anf", "án") && !CandidateEngine.matchesSpellingPrefix("ang", "enr", "ěn")
                && !CandidateEngine.matchesSpellingPrefix("jia", "gaf", "gá"), "Shared nasal filter keeps distinct readings apart");
        List<Candidate> jia = engine.lookup("jia", Mode.HAN);
        check(jia.stream().anyMatch(c -> c.text.equals("架") && c.kind == CandidateEngine.Kind.CHINESE && c.lexemeIds.isEmpty() && c.meaning.isEmpty())
                && jia.stream().noneMatch(c -> c.lexemeIds.equals("w684")) &&
                engine.spellingCompletions("jia", "w2548", Mode.HAN, Orthography.YE).stream().noneMatch(c -> c.lexemeIds.equals("w684")),
              "Mandarin jia has no eating sense; a meal context cannot assign it through the shared glyph");
        Candidate song = engine.lookup("伤哥", Mode.HAN).stream().filter(c -> c.lexemeIds.equals("xu213-2-1")).findFirst().get();
        check(song.text.equals("山歌") && song.plainHanLoan && song.shortMeaning.isEmpty() && song.meaning.equals("山歌") && song.kind == CandidateEngine.Kind.TUJIA,
              "Handwritten loan uses Chinese original with native priority");
        Candidate spokenSong = engine.lookup("伤哥", Mode.HANDWRITING).stream().filter(c -> c.lexemeIds.equals("xu213-2-1")).findFirst().get();
        check(!spokenSong.plainHanLoan && spokenSong.text.equals("sangxgox") && spokenSong.shortMeaning.equals("山歌"), "Loanword spelling retains visible gloss");
        List<Candidate> startSounds = engine.nextSyllables("<s>", Orthography.DIACRITIC);
        check(startSounds.size() == 175 && startSounds.stream().allMatch(c -> c.syllableOnly && c.consumed == 0 && !c.lastSyllable.isEmpty()), "Attested sentence-start syllables");
        List<Candidate> hanSounds = engine.nextSyllables("<s>", Mode.HAN, Orthography.DIACRITIC);
        check(!hanSounds.isEmpty() && hanSounds.stream().allMatch(c -> c.text.codePoints().allMatch(Character::isIdeographic)
              && c.consumed == 0 && !c.lastSyllable.isEmpty()), "Direct Han sound commits contain only approved Han units");
        check(startSounds.get(0).text.equals("gó") && hanSounds.stream().anyMatch(c -> c.meaning.isEmpty()), "Spelling and unknown senses");
        Candidate tight = hanSounds.stream().filter(c -> c.lastSyllable.equals("jinv")).findFirst().get();
        check(tight.text.equals("经") && tight.shortMeaning.equals("紧") && tight.consumed == 0,
              "Attested standalone sense on its commit-ready Han suggestion");
        List<Candidate> afterKa = engine.nextSyllables("kǎ", Mode.PINYIN, Orthography.DIACRITIC);
        List<Candidate> hanAfterKa = engine.nextSyllables("kar", Mode.HAN, Orthography.YE);
        for (String sound : Arrays.asList("mǒng", "mǔn")) {
            Candidate part = afterKa.stream().filter(c -> c.text.equals(sound)).findFirst().get();
            check(part.shortMeaning.isEmpty() && part.meaning.isEmpty() && part.lexemeIds.isEmpty(),
                  "A constituent of a longer word has no universal standalone meaning: " + sound);
            check(hanAfterKa.stream().anyMatch(c -> c.lastSyllable.equals(part.lastSyllable) &&
                  c.text.equals("萌") && c.meaning.isEmpty()), "Regional readings may share a reference glyph");
        }
        String earthBranch = "yubao-60757-v-2498";
        Candidate chosenBranch = engine.lookup("mǒng", Mode.PINYIN, Orthography.DIACRITIC).stream()
                .filter(c -> c.lexemeIds.equals(earthBranch)).findFirst().get();
        check(chosenBranch.shortMeaning.equals("地支午") && chosenBranch.text.equals("mǒng"),
              "A known word and its sound-only hint remain distinct");
        check(engine.nextWords("w309 " + chosenBranch.lexemeIds + " w309", Mode.HAN, Orthography.YE).stream()
              .anyMatch(c -> c.lexemeIds.equals(earthBranch) && c.shortMeaning.equals("地支午") &&
                             c.text.equals("萌") && c.consumed == 0),
              "The same confirmed local pairing can suggest the specific dictionary sense");
        check(engine.nextSyllables("", Orthography.YE).isEmpty(), "Unknown context is not sentence start");
        check(engine.lookup("arciex", Mode.HAN).get(0).lastSyllable.equals("ciex"), "Attested ending outside default table");
        check(engine.scoreHandwritingPrefix("波俐") == 2, "Complete handwriting path");
        check(engine.handwritingSuggestions("玻", Mode.HAN, Orthography.YE).stream().anyMatch(c ->
                c.text.equals("波俐") && c.lexemeIds.equals("w309") && c.consumed == 1), "Handwritten initial alias completes whole word");
        Candidate nextMeal = engine.nextWords("w2548", Mode.HAN, Orthography.YE).get(0);
        check(nextMeal.lexemeIds.equals("w684") && nextMeal.consumed == 0 && nextMeal.text.equals("架"), "Attested next lexeme");
        List<String> rows = Files.readAllLines(asset, StandardCharsets.UTF_8);
        int words = 0;
        int compactGlosses = 0;
        String threeSyllables = "";
        for (String line : rows.subList(1, rows.size())) {
            String[] f = line.split("\t", -1);
            if (f.length >= 9 && (f[8].equals("zh") || f[8].equals("syllable"))) continue;
            words++;
            String id = f[0], spelled = f[1], key = f[2], meaning = f[3], han = f[4];
            if (id.equals("w2643")) threeSyllables = han;
            List<Candidate> nativeWords = engine.lookup(spelled, Mode.PINYIN);
            String compact = f.length >= 10 && !f[9].isEmpty() ? f[9] : meaning;
            boolean hasGloss = false;
            for (Candidate candidate : nativeWords) {
                if (candidate.text.equals(spelled) && candidate.meaning.equals(meaning) &&
                    candidate.shortMeaning.equals(compact) && candidate.kind == CandidateEngine.Kind.TUJIA &&
                    candidate.consumed == spelled.length()) hasGloss = true;
            }
            check(hasGloss, id + ": spelling, kind and glosses");
            if (!compact.equals(meaning)) compactGlosses++;
            if (f.length >= 13 && !f[12].isEmpty()) {
                java.util.ArrayList<Integer> offsets = new java.util.ArrayList<>();
                int pos = 0;
                for (String unit : f[12].split("\\|")) {
                    if (unit.codePointCount(0, unit.length()) == 2 &&
                        Character.UnicodeScript.of(unit.codePointAt(0)) == Character.UnicodeScript.HAN &&
                        Character.UnicodeScript.of(unit.codePointBefore(unit.length())) == Character.UnicodeScript.HAN) {
                        offsets.add(pos);
                        offsets.add(pos + unit.length());
                    }
                    pos += unit.length();
                }
                Candidate selected = engine.lookup(spelled, Mode.HAN).stream().filter(c -> c.text.equals(han) &&
                        c.meaning.equals(meaning) && c.consumed == spelled.length()).findFirst().get();
                check(Arrays.equals(selected.joinedHanRanges, offsets.stream().mapToInt(Integer::intValue).toArray()),
                      id + ": joined Han UTF-16 ranges");
            }
            check(offers(engine.lookup(spelled, Mode.HAN), han, meaning, spelled.length()), id + ": phonetic Han");
            if (!key.isEmpty()) {
                Candidate source = engine.lookup(spelled, Mode.HAN).stream().filter(c ->
                        c.text.equals(han) && c.meaning.equals(meaning) && c.consumed == spelled.length()).findFirst().get();
                check(!source.hanSenseId.isEmpty() && engine.lookup(key, Mode.HAN).stream().anyMatch(c ->
                        c.hanSenseId.equals(source.hanSenseId) && c.meaning.equals(meaning) && c.consumed == key.length()),
                        id + ": short key reaches the same sense");
            }
            check(offers(engine.lookup(han, Mode.HANDWRITING), spelled, meaning, han.length()), id + ": handwriting");
            for (String alias : f[5].split(" ")) {
                if (!alias.isEmpty()) {
                    check(offers(engine.lookup(alias, Mode.HANDWRITING), spelled, meaning, alias.length()), id + ": " + alias);
                }
            }
            StringBuilder combined = new StringBuilder();
            if (!f[6].isEmpty()) {
                for (String group : f[6].split(" ")) combined.appendCodePoint(group.codePointBefore(group.length()));
                check(offers(engine.lookup(combined.toString(), Mode.HANDWRITING), spelled, meaning, combined.length()),
                      id + ": per-syllable combination");
            }
            if (f.length >= 12 && !f[10].isEmpty() && !f[11].isEmpty()) {
                check(offers(engine.lookup(f[10], Mode.PINYIN, CandidateEngine.Orthography.DIACRITIC),
                             f[11], meaning, f[10].length()), id + ": unmarked spelling to diacritics");
                check(offers(engine.lookup(f[10], Mode.PINYIN), spelled, meaning, f[10].length()),
                      id + ": same unmarked spelling to Ye");
                check(offers(engine.lookup(han, Mode.HANDWRITING, CandidateEngine.Orthography.DIACRITIC),
                             f[11], meaning, han.length()), id + ": handwriting to diacritics");
            }
        }
        check(words >= 345, "Missing native entries: " + words);
        check(compactGlosses > 0, "No compact glosses verified");
        check(CandidateEngine.spaceWord("á", 'ě', 'à').equals(" á "), "Tone-mark spelling word boundaries");
        for (Mode mode : new Mode[] { Mode.PINYIN, Mode.HAN }) {
            boolean found = false;
            for (Candidate c : engine.lookup("nihao", mode)) {
                if (c.kind == CandidateEngine.Kind.CHINESE && c.text.equals("你好") && c.consumed == 5) {
                    check(c.meaning.isEmpty() && c.shortMeaning.isEmpty(), "Plain Chinese has no Tujia gloss");
                    found = true;
                }
            }
            check(found, "Missing ordinary Chinese phrase via " + mode);
        }
        String four = "zaovguxdiexgaf";
        Candidate prefix = first(engine, four, Mode.HAN);
        check(prefix.text.equals(threeSyllables) && four.substring(prefix.consumed).equals("gaf"), "Three plus one");
        check(offers(engine.lookup(four.substring(prefix.consumed), Mode.HAN), "架", "吃、咬", 3), "Suffix lookup");
        Candidate unknown = first(engine, "早箍得龘", Mode.HANDWRITING);
        check(unknown.text.equals("zaovguxdiex") && unknown.consumed == 3, "Unrecognized suffix");
        List<Candidate> meal = engine.lookup("ngafyergaf。", Mode.PINYIN);
        check(meal.get(0).text.equals("ngaf yer gaf。"), "Whole sentence");
        check(offers(meal, "ngaf", "我", 4), "Alternative shorter prefix");
        check(first(engine, "ngafyergafhxif。", Mode.PINYIN).text.equals("ngaf yer gaf hxif。"), "Dynamic fourth word");
        Candidate markedSentence = engine.lookup("ngafyergaf。", Mode.PINYIN, CandidateEngine.Orthography.DIACRITIC).get(0);
        check(markedSentence.text.equals("ngá yě gá。") && markedSentence.consumed == "ngafyergaf。".length(),
              "Spelling output on a complete sentence");
        Candidate partial = first(engine, "俄耶尬尬。", Mode.HANDWRITING);
        check(partial.text.equals("ngaf yer gaf") && partial.consumed == 3, "Ambiguous fourth glyph");
        check(first(engine, "。", Mode.HANDWRITING).text.equals("。"), "Trailing punctuation");
        check(first(engine, "NGAF YER GAF。", Mode.PINYIN).text.equals("ngaf yer gaf。"), "Case and spaces");
        String mealHan = first(engine, "ngaf", Mode.HAN).text + first(engine, "yer", Mode.HAN).text +
                         first(engine, "gaf", Mode.HAN).text + "。";
        check(first(engine, "NGAF YER GAF。", Mode.HAN).text.equals(mealHan), "Han sentence");
        check(offers(engine.lookup("jir", Mode.HAN), "及", "脚", 3), "Foot vs step");
        check(offers(engine.lookup("jir", Mode.HAN), "迹", "步、脚步", 3), "Step vs foot");
        check(offers(engine.lookup("jir", Mode.HAN), "基", "下面", 3), "Third sense");
        check(offers(engine.lookup("倮", Mode.HANDWRITING), "lov", "人", 1), "Word-specific glyph");
        check(!offers(engine.lookup("倮", Mode.HANDWRITING), "lov", "暖和", 1), "Avoid cross-sense borrowing");
        check(offers(engine.lookup("hxif", Mode.HAN), "诶", "走", 4), "Customary syllable");
        check(engine.lookup("notaword", Mode.HANDWRITING).isEmpty(), "Handwriting should not accept Latin");
        check(engine.lookup("龘", Mode.PINYIN).isEmpty(), "No false matches");
        System.out.println("Passed all " + words + " words, keys, handwritten forms, segmentation, homophones, and sentence checks.");
    }
}
