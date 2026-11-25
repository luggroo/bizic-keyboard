package biz.tujia.ime;

/** Tone order is up, right, down, left; -1 means the centre. */
final class ToneFlick {
    static int direction(float dx, float dy, float threshold) {
        return direction(dx, dy, threshold, 0);
    }

    static int direction(float dx, float dy, float threshold, int edge) {
        // At the edge, the option that would be off-screen is above the
        // opposite side. The diagonal selects the same option as a tap there.
        if (dy <= -threshold * .7f && Math.abs(dx) >= threshold * .7f) {
            if (edge < 0 && dx > 0) return 3;
            if (edge > 0 && dx < 0) return 1;
        }
        if (Math.max(Math.abs(dx), Math.abs(dy)) < threshold) return -1;
        if (Math.abs(dx) > Math.abs(dy)) return dx > 0 ? 1 : 3;
        return dy < 0 ? 0 : 2;
    }
}
