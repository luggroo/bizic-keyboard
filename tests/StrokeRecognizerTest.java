package biz.tujia.ime;

import java.io.FileInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class StrokeRecognizerTest {
    private static float[] p(float... coordinates) { return coordinates; }

    private static void expect(StrokeRecognizer model, String target, int top, float[]... strokes) {
        List<String> results = model.recognize(Arrays.asList(strokes),12);
        int rank = results.indexOf(target);
        System.out.println(target+" -> "+results);
        if (rank < 0 || rank >= top) throw new AssertionError(target+" absent from top "+top);
    }

    public static void main(String[] args) throws Exception {
        StrokeRecognizer model = new StrokeRecognizer(new FileInputStream(args[0]));
        if (model.size() != 3565) throw new AssertionError("Dictionary coverage");
        if (!model.recognize(Collections.emptyList(),5).isEmpty()) throw new AssertionError("Blank pad");
        if (!model.recognize(Collections.singletonList(p(8,8)),5).isEmpty()) throw new AssertionError("Tap");
        // Independent finger-stroke samples, normalized by the matcher at any position/size.
        expect(model,"十",3,p(15,40,45,39,85,39),p(48,8,46,90));
        expect(model,"三",3,p(32,19,70,17),p(27,48,75,47),p(10,84,89,83));
        expect(model,"木",4,p(20,39,80,39),p(49,10,49,93),p(45,44,32,63,13,81),p(54,47,69,64,87,82));
        expect(model,"叶",6,p(10,20,10,64),p(11,19,36,19,35,64),p(10,64,36,64),
                p(42,39,91,37),p(67,9,66,92));
        expect(model,"尅",6,p(8,22,52,21),p(30,7,29,37),p(13,34,13,54),
                p(13,34,49,34,49,54),p(13,54,49,54),p(23,55,19,71,5,88),
                p(36,55,36,83,39,89,83,89,91,86,92,79),p(52,31,94,30),p(78,9,78,75,71,72),p(57,47,66,59));
        List<float[]> rare = Arrays.asList(p(13,31,21,31,42,26), p(25,8,29,13,25,47,18,64,6,81),
                p(32,38,34,41,33,64,35,76,44,83,63,85,83,79,89,75,90,62),
                p(57,7,61,12,51,33,40,47), p(61,21,72,34,81,41,93,43),
                p(50,43,54,47,53,59,49,67,43,72), p(65,40,68,44,68,77));
        if (!"尬".equals(model.strongMatch(rare))) throw new AssertionError("Clear rare glyph should match");
        if (model.strongMatch(Arrays.asList(p(15,40,45,39,85,39),p(48,8,46,90))) != null)
            throw new AssertionError("Simple marks should not use strong matching");
        System.out.println("Handwriting model: "+model.size()+" offline glyphs.");
    }
}
