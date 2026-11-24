package biz.tujia.ime;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Offline recognition with Ismantic/Handwritten, supplemented by the keyboard's stroke templates. */
final class IsmanticRecognizer implements AutoCloseable {
    private final OrtEnvironment environment = OrtEnvironment.getEnvironment();
    private final OrtSession session;
    private final List<String> characters = new ArrayList<>();
    private final Set<String> known;
    private final StrokeRecognizer templates;

    IsmanticRecognizer(InputStream model, InputStream labels, InputStream medians) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(labels, StandardCharsets.UTF_8))) {
            String line = reader.readLine();
            if (line == null || line.codePointCount(0, line.length()) != 3755 || reader.readLine() != null) {
                throw new IOException("Invalid Ismantic character index");
            }
            for (int at = 0; at < line.length();) {
                int next = at + Character.charCount(line.codePointAt(at));
                characters.add(line.substring(at, next));
                at = next;
            }
        }
        known = new HashSet<>(characters);
        templates = new StrokeRecognizer(medians);
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            session = environment.createSession(NativeCore.readAsset(model), options);
        } catch (IOException | OrtException ex) {
            templates.close();
            throw new IOException("Unable to load Ismantic handwriting model", ex);
        }
    }

    List<String> recognize(List<float[]> strokes, int limit, int padWidth, int padHeight) {
        if (limit <= 0 || padWidth <= 0 || padHeight <= 0) return Collections.emptyList();
        float[][] image = HandwritingImage.preprocess(strokes, padWidth, padHeight);
        if (image == null) return Collections.emptyList();
        try (OnnxTensor input = OnnxTensor.createTensor(environment, new float[][][][]{{image}});
             OrtSession.Result result = session.run(Collections.singletonMap("image", input))) {
            float[] scores = ((float[][]) result.get(0).getValue())[0];
            Integer[] order = new Integer[scores.length];
            for (int i = 0; i < order.length; i++) order[i] = i;
            Arrays.sort(order, (a, b) -> Float.compare(scores[b], scores[a]));

            List<String> extra = new ArrayList<>();
            for (String character : templates.recognize(strokes, 12)) {
                if (!known.contains(character) && !extra.contains(character)) extra.add(character);
                if (extra.size() == Math.min(2, limit)) break;
            }
            List<String> candidates = new ArrayList<>();
            for (int i = 0; i < Math.min(order.length, limit - extra.size()); i++) {
                candidates.add(characters.get(order[i]));
            }
            candidates.addAll(extra);
            return candidates;
        } catch (OrtException ex) {
            throw new IllegalStateException("Handwriting inference failed", ex);
        }
    }

    @Override public void close() {
        templates.close();
        try { session.close(); } catch (OrtException ignored) { }
    }
}
