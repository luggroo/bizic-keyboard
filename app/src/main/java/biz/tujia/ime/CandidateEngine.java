package biz.tujia.ime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Android adapter for the shared Tujia word and sentence engine. */
public final class CandidateEngine implements AutoCloseable {
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
        /** Flattened UTF-16 start/end-exclusive ranges of two-Han syllables in text. */
        public final int[] joinedHanRanges;

        private Candidate(String text, String meaning, String shortMeaning, int kind, int consumed,
                          int[] joinedHanRanges, boolean syllableOnly) {
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
        model = NativeCore.loadLexicon(NativeCore.readAsset(input));
    }

    public List<Candidate> lookup(String input, Mode mode) {
        return lookup(input, mode, Orthography.YE);
    }

    public synchronized List<Candidate> lookup(String input, Mode mode, Orthography orthography) {
        if (model == 0) throw new IllegalStateException("Dictionary closed");
        if (input == null || input.isEmpty() || mode == null || orthography == null) return Collections.emptyList();
        return Arrays.asList(NativeCore.lookup(model, NativeCore.encode(input), mode.ordinal(), orthography.ordinal()));
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
