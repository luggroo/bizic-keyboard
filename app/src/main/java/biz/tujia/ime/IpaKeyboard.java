package biz.tujia.ime;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Layout and offline prefix matching for literal IPA input. */
final class IpaKeyboard {
    private static final int MAX_PREFIX = 64;
    static final int MAX_VERBATIM_PREFIX = 256;
    static final int NORTH = 1, SOUTH = 2, ALL_DIALECTS = NORTH | SOUTH;
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
        final int dialects;

        Entry(int dialects, String ipa, String gloss) {
            this.dialects = dialects;
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
                if (line.isEmpty() || line.startsWith("#")) continue;
                int tab = line.indexOf('\t', 2);
                if (tab <= 2 || line.charAt(1) != '\t' || line.charAt(0) < '1' || line.charAt(0) > '3')
                    throw new IOException("Invalid IPA dictionary row");
                entries.add(new Entry(line.charAt(0) - '0', line.substring(2, tab), line.substring(tab + 1)));
            }
        }
    }

    static String prefix(CharSequence before, CharSequence after) {
        String prefix = completionPrefix(before, after);
        return prefix == null ? "" : prefix;
    }

    static String completionPrefix(CharSequence before, CharSequence after) {
        String input = candidatePrefix(before, after);
        if (input == null || input.isEmpty()) return input;
        return input.length() <= MAX_PREFIX && HAS_LETTER.matcher(input).find() ? input : null;
    }

    static String candidatePrefix(CharSequence before, CharSequence after) {
        if (before == null || after == null || NEXT.matcher(after).lookingAt()) return null;
        Matcher match = WORD.matcher(before);
        if (match.find()) return match.start() == 0 && before.length() > MAX_VERBATIM_PREFIX ? null : match.group();
        if (before.length() == 0) return "";
        int last = Character.offsetByCodePoints(before, before.length(), -1);
        return NEXT.matcher(before.subSequence(last, before.length())).lookingAt() ? null : "";
    }

    static List<Entry> withVerbatim(String input, IpaKeyboard lexicon, int dialects) {
        return withVerbatim(input, lexicon, dialects, Collections.emptyList());
    }

    static List<Entry> withVerbatim(String input, IpaKeyboard lexicon, int dialects, List<String> learned) {
        List<Entry> ranked = Collections.emptyList();
        Set<String> glosses = new LinkedHashSet<>();
        if (lexicon != null && dialects != 0 && input.length() <= MAX_PREFIX && HAS_LETTER.matcher(input).find()) {
            ranked = lexicon.complete(input, dialects);
            for (Entry entry : lexicon.entries) {
                if ((entry.dialects & dialects) != 0 && CandidateEngine.sameForm(entry.ipa, input) && !entry.gloss.isEmpty()) {
                    glosses.add(entry.gloss);
                }
            }
        }
        if (!learned.isEmpty()) {
            List<Entry> personal = new ArrayList<>();
            Set<String> known = new HashSet<>();
            for (Entry entry : ranked) known.add(entry.spelling);
            for (String word : learned) {
                Entry candidate = new Entry(0, word, "");
                if (known.add(candidate.spelling)) personal.add(candidate);
            }
            personal.addAll(ranked);
            ranked = personal;
        }
        StringJoiner gloss = new StringJoiner("；");
        for (String meaning : glosses) gloss.add(meaning);
        return CandidateEngine.rankOriginal(new Entry(dialects, input, gloss.toString()), !glosses.isEmpty(), ranked,
                entry -> entry.ipa);
    }

    static boolean continuesWord(String text) {
        return NEXT.matcher(text).lookingAt();
    }

    List<Entry> complete(String prefix, int dialects) {
        if (prefix == null || prefix.isEmpty() || dialects == 0) return Collections.emptyList();
        String current = normalize(prefix);
        Matcher exact = completionPattern(current, false).matcher("");
        Matcher relaxed = completionPattern(current, true).matcher("");
        List<Entry> results = new ArrayList<>();
        List<Entry> secondary = new ArrayList<>();
        for (Entry entry : entries) {
            if ((entry.dialects & dialects) == 0 || entry.spelling.equals(current)) continue;
            if (exact.reset(entry.spelling).lookingAt()) {
                results.add(entry);
            } else if (relaxed.reset(entry.spelling).lookingAt()) {
                secondary.add(entry);
            }
        }
        results.addAll(secondary);
        return results;
    }

    List<Entry> starters(List<CandidateEngine.Candidate> ranked, int dialects) {
        List<Entry> results = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CandidateEngine.Candidate candidate : ranked) {
            if ((dialects & NORTH) != 0) {
                if (seen.add(candidate.text))
                    results.add(new Entry(NORTH, candidate.text, candidate.shortMeaning));
            } else if ((dialects & SOUTH) != 0 && !candidate.shortMeaning.isEmpty()) {
                for (Entry entry : entries) {
                    if ((entry.dialects & SOUTH) != 0 && !entry.ipa.startsWith("-")
                            && entry.gloss.equals(candidate.shortMeaning) && seen.add(entry.ipa)) {
                        results.add(entry);
                    }
                }
            }
        }
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
        if (!value.isEmpty() && value.chars().allMatch(c -> DIGITS.indexOf(c) >= 0)) {
            StringBuilder digits = new StringBuilder();
            for (int i = 0; i < value.length(); i++) digits.append(DIGITS.indexOf(value.charAt(i)) + 1);
            return digits.toString();
        }
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
                if (!value.isEmpty() && value.chars().allMatch(c -> DIGITS.indexOf(c) >= 0)) {
                    StringBuilder tone = new StringBuilder("声调 ");
                    for (int i = 0; i < value.length(); i++) tone.append(DIGITS.indexOf(value.charAt(i)) + 1);
                    return tone.toString();
                }
                return value;
        }
    }
}
