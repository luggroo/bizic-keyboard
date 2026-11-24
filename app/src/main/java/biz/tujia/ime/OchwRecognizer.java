package biz.tujia.ime;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Offline single-character ochwpro inference. Accessed only on the handwriting worker. */
final class OchWRecognizer implements AutoCloseable {
    private static final int LENGTH = 200;
    private final OrtEnvironment env;
    private final OrtSession session;
    private final List<String> characters = new ArrayList<>();
    private final Set<String> known = new HashSet<>();
    private final StrokeRecognizer fallback;

    OchWRecognizer(InputStream model, InputStream labels, InputStream templates) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(labels, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) characters.add(line);
        }
        if (characters.size() != 7356) throw new IOException("Invalid ochwpro character index");
        known.addAll(characters);
        fallback = new StrokeRecognizer(templates);
        env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setIntraOpNumThreads(1);
            options.setInterOpNumThreads(1);
            session = env.createSession(NativeCore.readAsset(model), options);
        } catch (OrtException ex) {
            fallback.close();
            throw new IOException("Unable to load ochwpro", ex);
        }
    }

    List<String> recognize(List<float[]> strokes, int limit) {
        if (strokes.isEmpty() || limit <= 0) return Collections.emptyList();
        float[][][] features = new float[1][LENGTH][5];
        boolean[][] mask = new boolean[1][LENGTH];
        int count = features(strokes, features[0], mask[0]);
        if (count < 2) return Collections.emptyList();
        try (OnnxTensor input = OnnxTensor.createTensor(env, features);
             OnnxTensor valid = OnnxTensor.createTensor(env, mask)) {
            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input", input);
            inputs.put("mask", valid);
            try (OrtSession.Result result = session.run(inputs)) {
                float[] logits = ((float[][]) result.get(0).getValue())[0];
                Integer[] order = new Integer[logits.length];
                for (int i = 0; i < order.length; i++) order[i] = i;
                Arrays.sort(order, Comparator.comparingDouble((Integer i) -> logits[i]).reversed());
                List<String> output = new ArrayList<>();
                for (int i : order) {
                    String c = characters.get(i);
                    if (Character.UnicodeScript.of(c.codePointAt(0)) == Character.UnicodeScript.HAN) {
                        output.add(c);
                        if (output.size() == limit) break;
                    }
                }
                // Keep project-specific glyphs absent from ochwpro, such as 尅, reachable.
                List<String> extra = new ArrayList<>();
                for (String c : fallback.recognize(strokes, 12)) {
                    if (!known.contains(c) && !extra.contains(c)) extra.add(c);
                    if (extra.size() == Math.min(2, limit)) break;
                }
                while (output.size() + extra.size() > limit) output.remove(output.size() - 1);
                output.addAll(extra);
                return output;
            }
        } catch (OrtException ex) {
            throw new IllegalStateException("Handwriting inference failed", ex);
        }
    }

    /** Uniformly reduce dense touch points while keeping each stroke's endpoints. */
    static int features(List<float[]> strokes, float[][] out, boolean[] mask) {
        int total = 0;
        for (float[] stroke : strokes) total += stroke.length / 2;
        if (total == 0) return 0;
        int[] counts = new int[strokes.size()];
        int retained = 0;
        for (int i = 0; i < counts.length; i++) {
            int n = strokes.get(i).length / 2;
            counts[i] = n == 0 ? 0 : Math.min(n, n == 1 ? 1 : 2);
            retained += counts[i];
        }
        while (retained < Math.min(total, LENGTH)) {
            int best = -1; double bestRatio = -1;
            for (int i = 0; i < counts.length; i++) {
                int n = strokes.get(i).length / 2;
                if (counts[i] < n && (double)n / (counts[i] + 1) > bestRatio) {
                    best = i; bestRatio = (double)n / (counts[i] + 1);
                }
            }
            if (best < 0) break;
            counts[best]++; retained++;
        }
        float[] xs = new float[retained], ys = new float[retained];
        boolean[] end = new boolean[retained];
        int p = 0;
        for (int i = 0; i < counts.length; i++) {
            float[] s = strokes.get(i); int n = s.length / 2, k = counts[i];
            for (int j = 0; j < k; j++) {
                int at = k == 1 ? 0 : j * (n - 1) / (k - 1);
                xs[p] = s[2 * at]; ys[p] = s[2 * at + 1]; end[p] = j == k - 1; p++;
            }
        }
        float minX = xs[0], maxX = xs[0], minY = ys[0], maxY = ys[0];
        for (int i = 1; i < p; i++) {
            minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
            minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
        }
        float dx = Math.max(1, maxX - minX), dy = Math.max(1, maxY - minY);
        for (int i = 0; i < p; i++) {
            out[i][0] = (xs[i] - minX) / dx; out[i][1] = (ys[i] - minY) / dy;
            out[i][2] = i == 0 ? 0 : (xs[i] - xs[i-1]) / dx;
            out[i][3] = i == 0 ? 0 : (ys[i] - ys[i-1]) / dy;
            out[i][4] = end[i] ? 0 : 1; mask[i] = true;
        }
        return p;
    }

    @Override public void close() {
        fallback.close();
        try { session.close(); } catch (OrtException ignored) { }
    }
}
