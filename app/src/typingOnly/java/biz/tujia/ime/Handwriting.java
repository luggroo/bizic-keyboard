package biz.tujia.ime;

import android.content.Context;

final class Handwriting {
    static final boolean ENABLED = false;

    static HandwritingPad create(Context context, HandwritingPad.Listener listener) {
        throw new UnsupportedOperationException("Handwriting is unavailable in this edition");
    }

    private Handwriting() { }
}
