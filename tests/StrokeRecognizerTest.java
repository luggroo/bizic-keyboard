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
        if (model.size() != 3561) throw new AssertionError("Dictionary coverage");
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
        System.out.println("Handwriting model: "+model.size()+" offline glyphs.");
    }
}
