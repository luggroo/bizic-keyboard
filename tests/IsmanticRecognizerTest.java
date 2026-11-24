package biz.tujia.ime;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public final class IsmanticRecognizerTest {
    private static float[] p(float... points) { return points; }
    private static void expect(IsmanticRecognizer model, String wanted, int top, float[]... strokes) {
        List<String> result = model.recognize(Arrays.asList(strokes), 12, 100, 100);
        System.out.println(wanted + " -> " + result);
        if (result.indexOf(wanted) < 0 || result.indexOf(wanted) >= top)
            throw new AssertionError(wanted + " absent from top " + top);
    }
    private static void expectTemplate(IsmanticRecognizer model, String wanted, String medians) throws Exception {
        for (String line : Files.readAllLines(Paths.get(medians))) {
            if (!line.startsWith(wanted + "\t")) continue;
            String[] paths = line.substring(line.indexOf('\t') + 1).split(";");
            float[][] strokes = new float[paths.length][];
            for (int i = 0; i < paths.length; i++) {
                String[] coordinates = paths[i].split("[ ,]");
                strokes[i] = new float[coordinates.length];
                for (int j = 0; j < coordinates.length; j++) {
                    strokes[i][j] = Float.parseFloat(coordinates[j]) / 10;
                }
            }
            expect(model, wanted, 12, strokes);
            return;
        }
        throw new AssertionError("Missing template for " + wanted);
    }

    public static void main(String[] args) throws Exception {
        try (IsmanticRecognizer model = new IsmanticRecognizer(new FileInputStream(args[0]),
                new FileInputStream(args[1]), new FileInputStream(args[2]))) {
            if (!model.recognize(Collections.singletonList(p(8, 8)), 12, 100, 100).isEmpty())
                throw new AssertionError("A tap must not be committed as a character");
            expect(model, "十", 3, p(15,40,45,39,85,39), p(48,8,46,90));
            expect(model, "三", 3, p(32,19,70,17), p(27,48,75,47), p(10,84,89,83));
            expect(model, "木", 4, p(20,39,80,39), p(49,10,49,93), p(45,44,32,63,13,81), p(54,47,69,64,87,82));
            expectTemplate(model, "咔", args[2]);
            expectTemplate(model, "诶", args[2]);
            expect(model, "尅", 12, p(8,22,52,21),p(30,7,29,37),p(13,34,13,54),
                    p(13,34,49,34,49,54),p(13,54,49,54),p(23,55,19,71,5,88),
                    p(36,55,36,83,39,89,83,89,91,86,92,79),p(52,31,94,30),p(78,9,78,75,71,72),p(57,47,66,59));
        }
    }
}
