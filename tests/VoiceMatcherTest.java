package biz.tujia.ime;

import java.util.Arrays;
import java.util.List;

public final class VoiceMatcherTest {
    public static void main(String[] args) {
        List<IpaKeyboard.Entry> entries = Arrays.asList(
            new IpaKeyboard.Entry(IpaKeyboard.NORTH, "kʰo⁵⁵pa⁵⁵", "头"),
            new IpaKeyboard.Entry(IpaKeyboard.NORTH, "kʰo²¹pa⁵⁵", "同音词"),
            new IpaKeyboard.Entry(IpaKeyboard.SOUTH, "kʰo⁵⁵pa⁵⁵", "南部"),
            new IpaKeyboard.Entry(IpaKeyboard.NORTH, "sɿ⁵⁵", "其他"));
        List<IpaKeyboard.Entry> exact = VoiceMatcher.match("kʰo55 pa55", entries);
        check(!exact.isEmpty() && exact.get(0).gloss.equals("头"), "exact tone first");
        check(exact.stream().noneMatch(e -> e.gloss.equals("南部")), "north only");
        check(VoiceMatcher.match("kʰo55 ba55", entries).stream().anyMatch(e -> e.gloss.equals("头")), "one substitution");
        check(VoiceMatcher.match("a b c d e f", entries).isEmpty(), "long utterance stays verbatim");
        check(VoiceMatcher.match("x", entries).isEmpty(), "short noise not matched");
        check(VoiceMatcher.key("gã55", true).equals(VoiceMatcher.key("ɡã⁵⁵", true)), "normalization");
        System.out.println("VoiceMatcherTest passed");
    }
    private static void check(boolean ok, String message) { if (!ok) throw new AssertionError(message); }
}
