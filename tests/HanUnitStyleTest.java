package biz.tujia.ime;

public final class HanUnitStyleTest {
    private static void check(String actual, String expected) {
        if (!actual.equals(expected)) throw new AssertionError(actual + " != " + expected);
    }

    public static void main(String[] args) {
        check(HanUnitStyle.forEditor("克移", new int[]{0, 2}, 0).toString(), "˻克移˼");
        check(HanUnitStyle.forEditor(" 阿岸格锿波俐", new int[]{0, 2, 2, 4}, 1).toString(),
                " ˻阿岸˼˻格锿˼波俐");
        check(HanUnitStyle.forKeyboard("波俐", new int[0]).toString(), "波俐");
        check(HanUnitStyle.forKeyboard("𠀀克移", new int[]{2, 4}).toString(), "𠀀˻克移˼");
        System.out.println("Han syllable brackets and UTF-16 ranges passed");
    }
}
