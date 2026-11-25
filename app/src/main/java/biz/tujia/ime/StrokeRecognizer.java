package biz.tujia.ime;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Android adapter for local stroke-template matching. */
final class StrokeRecognizer implements AutoCloseable {
    private long model;

    StrokeRecognizer(InputStream input) throws IOException {
        model = NativeCore.loadStrokes(NativeCore.readAsset(input));
    }

    synchronized int size() {
        if (model == 0) throw new IllegalStateException("Handwriting model closed");
        return NativeCore.strokeCount(model);
    }

    synchronized List<String> recognize(List<float[]> strokes, int limit) {
        if (model == 0) throw new IllegalStateException("Handwriting model closed");
        if (strokes == null || strokes.isEmpty() || limit <= 0) return Collections.emptyList();
        return Arrays.asList(NativeCore.recognize(model, strokes.toArray(new float[0][]), limit));
    }

    synchronized String strongMatch(List<float[]> strokes) {
        if (model == 0) throw new IllegalStateException("Handwriting model closed");
        if (strokes == null || strokes.isEmpty()) return null;
        return NativeCore.recognizeStrongStroke(model, strokes.toArray(new float[0][]));
    }

    @Override public synchronized void close() {
        if (model != 0) {
            NativeCore.freeStrokes(model);
            model = 0;
        }
    }
}
