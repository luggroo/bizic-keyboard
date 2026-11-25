package biz.tujia.ime;

import android.content.Context;
import android.view.View;
import java.util.List;

abstract class HandwritingPad extends View {
    HandwritingPad(Context context) { super(context); }

    public interface Listener {
        void onStrokeStarted(boolean firstStroke);
        void onCandidates(List<String> characters);
        void onSettled();
    }

    /** Drawing only. The IME owns the editor identity and the pending commit anchor. */
    static final class InkSnapshot {
        final int width, height;
        final List<float[]> strokes;

        InkSnapshot(int width, int height, List<float[]> strokes) {
            this.width = width;
            this.height = height;
            this.strokes = strokes;
        }
    }

    abstract boolean hasInk();
    abstract boolean awaitingResult();
    abstract InkSnapshot takeInk();
    abstract void restoreInk(InkSnapshot snapshot);
    abstract void clearInk();
    abstract void clear();
    abstract void undoStroke();
}
