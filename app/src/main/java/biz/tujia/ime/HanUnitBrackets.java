package biz.tujia.ime;

import java.util.ArrayList;
import java.util.List;

/** Insert visible delimiters and translate the original UTF-16 ranges. */
final class HanUnitBrackets {
    final String text;
    final int[] ranges;

    private HanUnitBrackets(String text, int[] ranges) {
        this.text = text;
        this.ranges = ranges;
    }

    static HanUnitBrackets format(String text, int[] ranges, int offset) {
        if (ranges == null || ranges.length == 0) return new HanUnitBrackets(text, new int[0]);
        StringBuilder result = new StringBuilder(text.length() + ranges.length);
        List<Integer> positions = new ArrayList<>();
        int previous = 0;
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int first = ranges[i] + offset;
            int last = ranges[i + 1] + offset;
            if (first < previous || last <= first || last > text.length()) continue;
            result.append(text, previous, first).append('˻');
            positions.add(result.length());
            result.append(text, first, last);
            positions.add(result.length());
            result.append('˼');
            previous = last;
        }
        result.append(text, previous, text.length());
        int[] adjusted = new int[positions.size()];
        for (int i = 0; i < adjusted.length; i++) adjusted[i] = positions.get(i);
        return new HanUnitBrackets(result.toString(), adjusted);
    }
}
