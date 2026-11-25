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
        contains(keyboard.complete("", both), "mã²¹", "奶");
        contains(keyboard.complete("", south), "a²¹ dʑe²¹ be²¹", "手指");
        check(keyboard.complete("", 0).isEmpty(), "disabled dictionary suggested a starter");
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
        check(candidates.size() <= IpaKeyboard.COLUMNS, "too many completions");
        check(candidates.stream().anyMatch(entry -> entry.ipa.equals(ipa) && entry.gloss.equals(gloss)),
                "missing candidate " + ipa + " " + gloss);
    }

    private static void check(boolean passed, String assertion) {
        if (!passed) throw new AssertionError(assertion);
    }
}
