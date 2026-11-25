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
        options("t", "ts", "tsʰ", "tɕ", "tɕʰ", "tʰ");
        options("i", "ɪ", "ɨ", "ɿ", "ʅ", "ĩ");
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
        check(keyboard.complete("").isEmpty(), "empty input generated a completion");
        check(keyboard.complete("qqqqqq").isEmpty(), "an unknown prefix generated a completion");
        contains(keyboard.complete("ma"), "mã²¹", "奶");
        contains(keyboard.complete("ma\u0303²"), "mã²¹", "奶");
        contains(keyboard.complete("ʔali"), "ʔa¹³lie⁵⁵", "蛋");
        contains(keyboard.complete("ʔa¹³li"), "ʔa¹³lie⁵⁵", "蛋");
        check(keyboard.complete("ʔa²¹li").stream().noneMatch(entry -> entry.ipa.equals("ʔa¹³lie⁵⁵")),
                "a supplied tone was ignored");
        check(keyboard.complete("mã²¹").stream().noneMatch(entry -> entry.ipa.equals("mã²¹")),
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
