package biz.tujia.ime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Layout and offline prefix matching for literal IPA input. */
final class IpaKeyboard {
    static final int COLUMNS = 3, MAX_PREFIX = 64;
    static final String NASAL = "\u0303", SYLLABIC = "\u0329", TIE = "\u0361";
    private static final String DIGITS = "¹²³⁴⁵";
    private static final String LETTER = "[\\p{sc=Latin}\\p{Lm}]";
    private static final String SOUND = "[\\p{sc=Latin}\\p{Lm}\\p{M}" + DIGITS + "]";
    private static final Pattern WORD = Pattern.compile(SOUND + "+(?:[-'’]" + SOUND + "+)*\\z");
    private static final Pattern NEXT = Pattern.compile("[-'’\\p{sc=Latin}\\p{Lm}\\p{M}" + DIGITS + "]");
    private static final Pattern PART = Pattern.compile(LETTER + "\\p{M}*|[" + DIGITS + "]+|[-']");
    private static final Pattern HAS_LETTER = Pattern.compile(LETTER);
    private static final String VOWELS = "aeiouyɑɐəɛɜɤɨɪɔʊʉɯøʌ";

    static final String[][][] LETTERS = {
        {{"q"}, {"w"}, {"e", "ə", "ɛ", "ẽ"}, {"r", "²¹"}, {"t", "ts", "tsʰ", "tɕ", "tɕʰ", "tʰ", "ʈ", "ʈʰ"},
            {"y", "ɤ", "ɥ", "ỹ"}, {"u", "ɯ", "ʊ", "ʉ", "ũ"}, {"i", "ɪ", "ɨ", "ɿ", "ʅ", "ĩ"},
            {"o", "ɔ", "ø", "õ"}, {"p", "ɸ", "pʰ"}},
        {{"a", "ɑ", "ʌ", "ã"}, {"s", "ʂ", "ʃ"}, {"d", "dz", "dʑ", "ɖ"}, {"f", "³⁵"}, {"g", "ɣ", "ɡ", "ʁ"},
            {"h", "ʰ", "ħ", "ʕ"}, {"j", "³³"}, {"k", "kʰ"}, {"l", "¹³"}},
        {{"z", "ʐ", "ʒ", "ʑ"}, {"x", "⁵⁵"}, {"c", "ɕ"}, {"v", "⁵³"}, {"b"}, {"n", "ŋ", "ȵ", "ɲ"}, {"m", "m̩"}}
    };
    static final String[][][] SYMBOLS = {
        {{"¹"}, {"²"}, {"³"}, {"⁴"}, {"⁵"}, {"/"}, {"["}, {"]"}, {"("}, {")"}},
        {{NASAL}, {SYLLABIC}, {TIE}, {"\u0325"}, {"\u032c"}, {"-"}, {"."}, {"ʰ"}, {"ɿ"}, {"ʅ"}},
        {{"ħ"}, {"ʕ"}, {"ʁ"}, {"ɣ"}, {"ȵ"}, {"ʉ"}, {"ɯ"}, {"ʑ"}}
    };

    static final class Entry {
        final String ipa, gloss, spelling;

        Entry(String ipa, String gloss) {
            this.ipa = ipa;
            this.gloss = gloss;
            spelling = normalize(ipa);
        }
    }

    private final List<Entry> entries;

    IpaKeyboard(InputStream source) throws IOException {
        entries = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(source, StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                if (line.startsWith("#")) continue;
                int tab = line.indexOf('\t');
                if (tab > 0) entries.add(new Entry(line.substring(0, tab), line.substring(tab + 1)));
            }
        }
    }

    static String prefix(CharSequence before, CharSequence after) {
        if (before == null || after == null || after.length() > 0 && NEXT.matcher(after).lookingAt()) return "";
        Matcher match = WORD.matcher(before);
        if (!match.find() || match.start() == 0 && before.length() > MAX_PREFIX
                || !HAS_LETTER.matcher(match.group()).find()) return "";
        return match.group();
    }

    List<Entry> complete(String prefix) {
        if (prefix.isEmpty()) return Collections.emptyList();
        String current = normalize(prefix);
        Matcher exact = completionPattern(current, false).matcher("");
        Matcher relaxed = completionPattern(current, true).matcher("");
        List<Entry> results = new ArrayList<>(COLUMNS);
        List<Entry> secondary = new ArrayList<>(COLUMNS);
        for (Entry entry : entries) {
            if (entry.spelling.equals(current)) continue;
            if (exact.reset(entry.spelling).lookingAt()) {
                results.add(entry);
                if (results.size() == COLUMNS) return results;
            } else if (secondary.size() < COLUMNS && relaxed.reset(entry.spelling).lookingAt()) {
                secondary.add(entry);
            }
        }
        results.addAll(secondary.subList(0, Math.min(secondary.size(), COLUMNS - results.size())));
        return results;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
                .replace(" ", "").replace('g', 'ɡ').replace('’', '\'');
    }

    private static Pattern completionPattern(String prefix, boolean allowNasal) {
        StringBuilder expression = new StringBuilder();
        Matcher parts = PART.matcher(prefix);
        boolean previousLetter = false;
        while (parts.find()) {
            String part = parts.group();
            boolean letter = HAS_LETTER.matcher(part).lookingAt();
            if (previousLetter && (letter || part.equals("-"))) expression.append("[¹²³⁴⁵]*");
            expression.append(Pattern.quote(part));
            if (allowNasal && part.length() == 1 && VOWELS.indexOf(part.charAt(0)) >= 0) {
                expression.append(NASAL).append("?");
            }
            previousLetter = letter;
        }
        return Pattern.compile(expression.toString());
    }

    static String display(String value) {
        if (value.equals(TIE)) return "◌͡◌";
        if (value.length() == 1 && Character.getType(value.charAt(0)) == Character.NON_SPACING_MARK)
            return "◌" + value;
        return value;
    }

    static String description(String value) {
        switch (value) {
            case NASAL: return "鼻化";
            case SYLLABIC: return "成音节";
            case TIE: return "连音弧";
            case "\u0325": return "清化";
            case "\u032c": return "浊化";
            default:
                if (value.length() > 1 && value.chars().allMatch(c -> DIGITS.indexOf(c) >= 0)) {
                    StringBuilder tone = new StringBuilder("声调 ");
                    for (int i = 0; i < value.length(); i++) tone.append(DIGITS.indexOf(value.charAt(i)) + 1);
                    return tone.toString();
                }
                return value;
        }
    }
}
