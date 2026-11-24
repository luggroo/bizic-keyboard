package biz.tujia.ime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Only UTF-8 asset data and UTF-16 editor offsets cross this boundary. */
final class NativeCore {
    static { System.loadLibrary("tujia_core"); }

    private NativeCore() { }

    static byte[] readAsset(InputStream source) throws IOException {
        try (InputStream input = source; ByteArrayOutputStream contents = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int size;
            while ((size = input.read(buffer)) != -1) contents.write(buffer, 0, size);
            return contents.toByteArray();
        }
    }

    static byte[] encode(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    static native long loadLexicon(byte[] data, byte[] ngrams, byte[] syllableBigrams, byte[] syllableSenses) throws IOException;
    static native void freeLexicon(long model);
    static native CandidateEngine.Candidate[] lookup(long model, byte[] input, int mode, int orthography, int operation, byte[] history);
    static native boolean matchesSpellingPrefix(byte[] input, byte[] ye, byte[] marked);
    static native int scoreHandwritingPrefix(long model, byte[] glyphs);
    static native String suffix(byte[] draft, int consumedUTF16);
    static native String space(byte[] candidate, char before, char after);

    static native long loadStrokes(byte[] data) throws IOException;
    static native void freeStrokes(long model);
    static native int strokeCount(long model);
    static native String[] recognize(long model, float[][] strokes, int limit);
}
