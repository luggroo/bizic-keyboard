package biz.tujia.ime;

import android.content.Context;

final class Handwriting {
    static final boolean ENABLED = true;

    static HandwritingPad create(Context context, HandwritingPad.Listener listener) {
        return new OfflineHandwritingPad(context, listener);
    }

    private Handwriting() { }
}
