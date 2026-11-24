package biz.tujia.ime;

/** Wrap each pair of Han characters that represents one syllable. */
final class HanUnitStyle {
    static CharSequence forKeyboard(String text, int[] ranges) {
        return wrap(text, ranges, 0);
    }

    static CharSequence forEditor(String text, int[] ranges, int offset) {
        return wrap(text, ranges, offset);
    }

    private static String wrap(String text, int[] ranges, int offset) {
        if (ranges == null || ranges.length == 0) return text;
        StringBuilder result = new StringBuilder(text.length() + ranges.length);
        int previous = 0;
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int first = ranges[i] + offset;
            int last = ranges[i + 1] + offset;
            if (first < previous || last <= first || last > text.length()) continue;
            result.append(text, previous, first).append('˻')
                    .append(text, first, last).append('˼');
            previous = last;
        }
        return result.append(text, previous, text.length()).toString();
    }

    private HanUnitStyle() { }
}
