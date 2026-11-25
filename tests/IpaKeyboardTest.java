package biz.tujia.ime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

public final class IpaKeyboardTest {
    public static void main(String[] args) throws IOException {
        IpaKeyboard keyboard = new IpaKeyboard(Files.newInputStream(Paths.get(args[0])));
        String[][] tones = {{"v", "⁵³"}, {"x", "⁵⁵"}, {"r", "²¹"}, {"f", "³⁵"}, {"j", "³³"}, {"l", "¹³"}};
        for (String[] shortcut : tones) options(shortcut);
        options("s", "ʂ", "ʃ");
        options("z", "ʐ", "ʒ", "ʑ");
        options("t", "ts", "tsʰ", "tɕ", "tɕʰ", "tʰ", "ʈ", "ʈʰ");
        options("d", "dz", "dʑ", "ɖ");
        options("a", "ɑ", "ʌ", "ã");
        options("e", "ə", "ɛ", "ẽ");
        options("i", "ɪ", "ɨ", "ɿ", "ʅ", "ĩ");
        options("o", "ɔ", "ø", "õ");
        options("u", "ɯ", "ʊ", "ʉ", "ũ");
        options("y", "ɤ", "ɥ", "ỹ");
        check(Arrays.stream(IpaKeyboard.SYMBOLS).flatMap(Arrays::stream).flatMap(Arrays::stream)
                .noneMatch(value -> value.matches("[a-zA-Z]")), "ordinary letter on the IPA symbols page");
        check(IpaKeyboard.description("\u0325").equals("清化"), "unvoiced diacritic label");
        check(IpaKeyboard.description("\u032c").equals("浊化"), "voiced diacritic label");

        prefix("前 /ma", " 后", "ma");
        prefix("前 /m", "a 后", "");
        prefix("前 /mã\n", "", "");
        prefix("前 /ʔa¹³li", "]", "ʔa¹³li");
        prefix("ma", null, "");
        prefix("³⁵", "", "");
        prefix(new String(new char[65]).replace('\0', 'a'), "", "");
        prefix(new String(new char[65]).replace('\0', 'a') + " ma", "", "ma");
        check("".equals(IpaKeyboard.completionPrefix("前 / ", "")), "missing starters after a space");
        check(IpaKeyboard.completionPrefix("前 /m", "a 后") == null, "starters inside a word");
        check(IpaKeyboard.completionPrefix("²¹", "") == null, "starters after a standalone tone");
        int north = IpaKeyboard.NORTH, south = IpaKeyboard.SOUTH, both = IpaKeyboard.ALL_DIALECTS;
        check("²¹".equals(IpaKeyboard.candidatePrefix("²¹", "")), "standalone tone can appear as original input");
        String longInput = new String(new char[65]).replace('\0', 'a');
        check(longInput.equals(IpaKeyboard.candidatePrefix(longInput, "")), "long original IPA stays visible");
        check(IpaKeyboard.withVerbatim(longInput, keyboard, both).size() == 1, "long original bypasses lookup");
        List<IpaKeyboard.Entry> literal = IpaKeyboard.withVerbatim("ma", keyboard, north);
        check(literal.get(0).ipa.equals("ma") && literal.get(0).gloss.isEmpty(), "unmatched IPA goes first without a gloss");
        contains(literal.subList(1, literal.size()), "mã²¹", "奶");
        List<IpaKeyboard.Entry> word = IpaKeyboard.withVerbatim("mã²¹", keyboard, north);
        check(word.get(0).ipa.equals("mã²¹") && word.get(0).gloss.equals("奶")
                && word.stream().filter(entry -> entry.ipa.equals("mã²¹")).count() == 1, "exact original IPA has one card and the dictionary gloss");
        check(IpaKeyboard.withVerbatim("ma\u0303²¹", keyboard, north).get(0).gloss.isEmpty(), "normalized matches cannot label verbatim input");
        check(IpaKeyboard.withVerbatim("ma", keyboard, 0).size() == 1
                && IpaKeyboard.withVerbatim("ma", null, north).size() == 1, "original survives a disabled or loading dictionary");
        check(keyboard.complete("ma", 0).isEmpty(), "disabled dictionary suggested a completion");
        check(keyboard.complete("qqqqqq", both).isEmpty(), "an unknown prefix generated a completion");
        contains(keyboard.complete("ma", north), "mã²¹", "奶");
        contains(keyboard.complete("ma\u0303²", north), "mã²¹", "奶");
        check(keyboard.complete("ma", south).stream().noneMatch(entry -> entry.ipa.equals("mã²¹")),
                "a Northern completion leaked into Southern suggestions");
        contains(keyboard.complete("ʔa²¹le", south), "ʔa²¹ le⁵⁵", "蛋");
        contains(keyboard.complete("ba³³n", south), "ba³³nõ²¹", "乌鸦");
        check(keyboard.complete("ba³³n", north).stream().noneMatch(entry -> entry.ipa.equals("ba³³nõ²¹")),
                "a Southern completion leaked into Northern suggestions");
        check(keyboard.complete("ʔa¹³le", both).stream().noneMatch(entry -> entry.ipa.equals("ʔa²¹ le⁵⁵")),
                "a supplied tone was ignored");
        check(keyboard.complete("mã²¹", both).stream().noneMatch(entry -> entry.ipa.equals("mã²¹")),
                "an already complete spelling was suggested again");
        try (CandidateEngine engine = new CandidateEngine(Files.newInputStream(Paths.get(args[1])),
                Files.newInputStream(Paths.get(args[3])), Files.newInputStream(Paths.get(args[4])),
                Files.newInputStream(Paths.get(args[5])), null, Files.newInputStream(Paths.get(args[2])))) {
            List<CandidateEngine.Candidate> ranked = engine.ipaOpenings();
            List<IpaKeyboard.Entry> opening = keyboard.starters(ranked, both);
            check(opening.size() > 4, "IPA sentence starters should support a second page");
            String[] first = {"ai⁵⁵", "ŋa²⁴", "kuɔ²⁴", "ȵi²⁴"};
            for (int i = 0; i < first.length; i++)
                check(first[i].equals(opening.get(i).ipa), "wrong frequency order in the IPA starter page");
            contains(keyboard.starters(ranked, south), "ŋo³³", "我");
            check(keyboard.starters(ranked, south).stream().allMatch(entry -> (entry.dialects & south) != 0),
                    "a Northern starter leaked into Southern suggestions");
            check(keyboard.starters(ranked, 0).isEmpty(), "disabled dictionary suggested a starter");
            check(engine.handwritingSuggestions("波", CandidateEngine.Mode.HANDWRITING, CandidateEngine.Orthography.IPA)
                    .stream().anyMatch(entry -> entry.text.equals("pɔ⁵⁵li²¹")), "missing handwritten IPA word");
            check(engine.lookup("波俐", CandidateEngine.Mode.HANDWRITING, CandidateEngine.Orthography.YE)
                    .stream().anyMatch(entry -> entry.text.equals("boxlir")), "missing handwritten Ye output");
            check(engine.lookup("波俐", CandidateEngine.Mode.HANDWRITING, CandidateEngine.Orthography.DIACRITIC)
                    .stream().anyMatch(entry -> entry.text.equals("bōlǐ")), "missing handwritten diacritic output");
        }
        System.out.println("IPA shortcuts, symbol keys, editor boundaries and dictionary completions passed.");
    }

    private static void options(String... expected) {
        check(Arrays.stream(IpaKeyboard.LETTERS).flatMap(Arrays::stream)
                .anyMatch(key -> Arrays.equals(key, expected)), "unexpected alternatives for " + expected[0]);
    }

    private static void prefix(String before, String after, String expected) {
        String actual = IpaKeyboard.prefix(before, after);
        check(expected.equals(actual), "expected prefix " + expected + ", got " + actual);
    }

    private static void contains(List<IpaKeyboard.Entry> candidates, String ipa, String gloss) {
        check(candidates.stream().anyMatch(entry -> entry.ipa.equals(ipa) && entry.gloss.equals(gloss)),
                "missing candidate " + ipa + " " + gloss);
    }

    private static void check(boolean passed, String assertion) {
        if (!passed) throw new AssertionError(assertion);
    }
}
