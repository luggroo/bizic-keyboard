package biz.tujia.ime;

/** Tone order is up, right, down, left; -1 means the centre. */
final class ToneFlick {
    static int direction(float dx, float dy, float threshold) {
        if (Math.max(Math.abs(dx), Math.abs(dy)) < threshold) return -1;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 1 : 3;
        return dy < 0 ? 0 : 2;
    }
}
