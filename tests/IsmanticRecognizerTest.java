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
    private static void expectTemplate(IsmanticRecognizer model, String wanted, String medians, int top) throws Exception {
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
            expect(model, wanted, top, strokes);
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
            List<String> wide = model.recognize(Arrays.asList(p(193, 36, 247, 36), p(199, 62, 241, 62),
                    p(180, 94, 260, 94)), 12, 440, 130);
            if (wide.isEmpty() || !"三".equals(wide.get(0)))
                throw new AssertionError("Wide handwriting pad lost the expected first candidate: " + wide);
            // This character is missing from the neural model; a distinctive
            // template match must recover it without changing common inputs.
            expect(model, "尬", 1, p(13,31,21,31,42,26), p(25,8,29,13,25,47,18,64,6,81),
                    p(32,38,34,41,33,64,35,76,44,83,63,85,83,79,89,75,90,62),
                    p(57,7,61,12,51,33,40,47), p(61,21,72,34,81,41,93,43),
                    p(50,43,54,47,53,59,49,67,43,72), p(65,40,68,44,68,77));
            expectTemplate(model, "吖", args[2], 1);
            expectTemplate(model, "咔", args[2], 12);
            expectTemplate(model, "诶", args[2], 12);
            expect(model, "尅", 12, p(8,22,52,21),p(30,7,29,37),p(13,34,13,54),
                    p(13,34,49,34,49,54),p(13,54,49,54),p(23,55,19,71,5,88),
                    p(36,55,36,83,39,89,83,89,91,86,92,79),p(52,31,94,30),p(78,9,78,75,71,72),p(57,47,66,59));
        }
    }
}
