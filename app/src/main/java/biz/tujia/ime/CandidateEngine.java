package biz.tujia.ime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Android adapter for the shared Tujia word and sentence engine. */
public final class CandidateEngine implements AutoCloseable {
    static final int HANDWRITING_WINDOW = 4;
    public enum Mode { PINYIN, HAN, HANDWRITING }
    public enum Kind { TUJIA, CHINESE, LITERAL, PUNCTUATION }
    public enum Orthography { YE, DIACRITIC }

    public static final class Candidate {
        private static final Kind[] KINDS = Kind.values();
        public final String text;
        public final String meaning;
        public final String shortMeaning;
        public final Kind kind;
        public final int consumed;
        public final boolean syllableOnly;
        /** Source IDs in commit order. Empty for punctuation, defaults and Chinese fallback. */
        public final String lexemeIds;
        /** Groups typed Han variants of one complete reading and sense. */
        public final String hanSenseId;
        /** Canonical Ye spelling of the last syllable, for the syllable-level context table. */
        public final String lastSyllable;
        /** The current Han candidate uses the original loan characters without a gloss line. */
        public final boolean plainHanLoan;
        /** Flattened UTF-16 start/end-exclusive ranges of two-Han syllables in text. */
        public final int[] joinedHanRanges;

        private Candidate(String text, String meaning, String shortMeaning, int kind, int consumed,
                          int[] joinedHanRanges, boolean syllableOnly, String lexemeIds, String lastSyllable,
                          boolean plainHanLoan, String hanSenseId) {
            this.hanSenseId = hanSenseId;
            this.plainHanLoan = plainHanLoan;
            this.lastSyllable = lastSyllable;
            this.lexemeIds = lexemeIds;
            this.syllableOnly = syllableOnly;
            this.text = text;
            this.meaning = meaning;
            this.shortMeaning = shortMeaning;
            this.kind = KINDS[kind];
            this.consumed = consumed;
            this.joinedHanRanges = joinedHanRanges;
        }
    }

    private long model;

    public CandidateEngine(InputStream input) throws IOException {
        this(input, null);
    }

    public CandidateEngine(InputStream input, InputStream ngrams) throws IOException {
        this(input, ngrams, null);
    }

    public CandidateEngine(InputStream input, InputStream ngrams, InputStream syllableBigrams) throws IOException {
        this(input, ngrams, syllableBigrams, null);
    }

    public CandidateEngine(InputStream input, InputStream ngrams, InputStream syllableBigrams, InputStream syllableSenses) throws IOException {
        this(input, ngrams, syllableBigrams, syllableSenses, null);
    }

    public final int handwritingContextLength;

    public CandidateEngine(InputStream input, InputStream ngrams, InputStream syllableBigrams, InputStream syllableSenses,
                           InputStream regionalReadings) throws IOException {
        try (InputStream context = ngrams; InputStream sounds = syllableBigrams; InputStream senses = syllableSenses;
             InputStream readings = regionalReadings) {
            model = NativeCore.loadLexicon(NativeCore.readAsset(input),
                    context == null ? new byte[0] : NativeCore.readAsset(context),
                    sounds == null ? new byte[0] : NativeCore.readAsset(sounds),
                    senses == null ? new byte[0] : NativeCore.readAsset(senses),
                    readings == null ? new byte[0] : NativeCore.readAsset(readings));
            handwritingContextLength = NativeCore.handwritingContextLength(model);
        }
    }

    public List<Candidate> lookup(String input, Mode mode) {
        return lookup(input, mode, Orthography.YE);
    }

    public List<Candidate> lookup(String input, Mode mode, Orthography orthography) {
        return candidates(input, mode, orthography, 0);
    }

    public List<Candidate> handwritingSuggestions(String acceptedHan, Mode outputMode, Orthography style) {
        return candidates(acceptedHan, outputMode, style, 1);
    }

    public List<Candidate> handwrittenWords(String beforeCaret, Mode outputMode, Orthography style) {
        return candidates(handwritingTail(beforeCaret), outputMode, style, 5);
    }

    public List<Candidate> nextHandwrittenGlyphs(String beforeCaret) {
        return candidates(handwritingTail(beforeCaret), Mode.HAN, Orthography.YE, 6);
    }

    static String handwritingTail(String text) {
        if (text == null) return "";
        int start = text.length();
        for (int i = 0; i < HANDWRITING_WINDOW && start > 0; i++)
            start -= Character.charCount(text.codePointBefore(start));
        return text.substring(start);
    }

    public synchronized List<Candidate> spellingCompletions(String prefix, String previousIds, Mode outputMode, Orthography style) {
        if (model == 0) throw new IllegalStateException("Dictionary closed");
        if (prefix == null || prefix.isEmpty() || outputMode == null || style == null) return Collections.emptyList();
        return Arrays.asList(NativeCore.lookup(model, NativeCore.encode(prefix), outputMode.ordinal(), style.ordinal(), 4,
                NativeCore.encode(previousIds == null ? "" : previousIds)));
    }

    public List<Candidate> nextWords(String previousIds, Mode outputMode, Orthography style) {
        return candidates(previousIds, outputMode, style, 2);
    }

    /** Pass "<s>" only at a known sentence start. Empty or ambiguous context has no suggestions. */
    public List<Candidate> nextSyllables(String previousSyllable, Orthography style) {
        return nextSyllables(previousSyllable, Mode.PINYIN, style);
    }

    public List<Candidate> nextSyllables(String previousSyllable, Mode output, Orthography style) {
        return candidates(previousSyllable, output, style, 3);
    }

    public synchronized int scoreHandwritingPrefix(String acceptedHan) {
        if (model == 0) throw new IllegalStateException("Dictionary closed");
        return acceptedHan == null || acceptedHan.isEmpty() ? 0 : NativeCore.scoreHandwritingPrefix(model, NativeCore.encode(acceptedHan));
    }

    private synchronized List<Candidate> candidates(String input, Mode mode, Orthography orthography, int operation) {
        if (model == 0) throw new IllegalStateException("Dictionary closed");
        if (input == null || input.isEmpty() || mode == null || orthography == null) return Collections.emptyList();
        return Arrays.asList(NativeCore.lookup(model, NativeCore.encode(input), mode.ordinal(), orthography.ordinal(), operation, null));
    }

    public static boolean matchesSpellingPrefix(String input, String ye, String marked) {
        return matchesSpellingPrefix(input, ye, marked, true);
    }

    public static boolean matchesSpellingPrefix(String input, String ye, String marked, boolean allowYe) {
        if (input == null || ye == null || marked == null) return false;
        return NativeCore.matchesSpellingPrefix(NativeCore.encode(input), NativeCore.encode(ye), NativeCore.encode(marked), allowYe);
    }

    public static String suffixAfterChoice(String draft, int consumedUTF16) {
        return NativeCore.suffix(NativeCore.encode(draft), consumedUTF16);
    }

    public static String spaceWord(String candidate, char before, char after) {
        return NativeCore.space(NativeCore.encode(candidate), before, after);
    }

    @Override public synchronized void close() {
        if (model != 0) {
            NativeCore.freeLexicon(model);
            model = 0;
        }
    }
}
