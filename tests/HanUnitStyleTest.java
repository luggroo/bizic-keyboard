package biz.tujia.ime;

public final class HanUnitStyleTest {
    private static void check(String actual, String expected) {
        if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        check(HanUnitBrackets.format("克移", new int[]{0, 2}, 0).text, "˻克移˼");
        check(HanUnitBrackets.format(" 阿岸格锿波俐", new int[]{0, 2, 2, 4}, 1).text,
                " ˻阿岸˼˻格锿˼波俐");
        check(HanUnitBrackets.format("波俐", new int[0], 0).text, "波俐");
        check(HanUnitBrackets.format("𠀀克移", new int[]{2, 4}, 0).text, "𠀀˻克移˼");
        if (!java.util.Arrays.equals(HanUnitBrackets.format("阿岸格锿", new int[]{0, 2, 2, 4}, 0).ranges, new int[]{1, 3, 5, 7})) throw new AssertionError("Wrong span ranges");
        System.out.println("Han syllable brackets and UTF-16 ranges passed");
    }
}
