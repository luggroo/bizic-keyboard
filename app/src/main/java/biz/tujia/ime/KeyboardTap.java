package biz.tujia.ime;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

/** Tracks one real gesture on each IME key independently of its drawable's pressed animation. */
final class KeyboardTap implements View.OnTouchListener, View.OnAttachStateChangeListener, Runnable {
    private final View key;
    private final int slop;
    private int pointer = -1;
    private boolean held;
    private Flick flick;
    private float downX, downY;

    interface Flick {
        default void start(float x, float y) { }
        void move(float dx, float dy);
        void finish(float dx, float dy);
        void cancel();
    }

    static void bind(View key, Runnable click) {
        key.setOnClickListener(v -> click.run());
        KeyboardTap gesture = new KeyboardTap(key);
        key.setOnTouchListener(gesture);
        key.addOnAttachStateChangeListener(gesture);
    }

    static void bindFlick(View key, Flick flick) {
        KeyboardTap gesture = new KeyboardTap(key);
        gesture.flick = flick;
        key.setOnTouchListener(gesture);
        key.addOnAttachStateChangeListener(gesture);
    }

    private KeyboardTap(View key) {
        this.key = key;
        slop = ViewConfiguration.get(key.getContext()).getScaledTouchSlop();
    }

    @Override public boolean onTouch(View view, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                cancel();
                if (!key.isEnabled()) return false;
                pointer = event.getPointerId(0);
                downX = event.getX();
                downY = event.getY();
                key.drawableHotspotChanged(event.getX(), event.getY());
                key.setPressed(true);
                if (key.isLongClickable()) key.postDelayed(this, ViewConfiguration.getLongPressTimeout());
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index < 0) {
                    if (held && flick != null) flick.cancel();
                    cancel();
                } else if (held && flick != null) {
                    flick.move(event.getX(index) - downX, event.getY(index) - downY);
                } else if (!inside(event.getX(index), event.getY(index))) cancel();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                int up = event.getActionIndex();
                if (event.getPointerId(up) == pointer) {
                    boolean click = !held && key.isShown() && key.isEnabled()
                            && inside(event.getX(up), event.getY(up));
                    if (held && flick != null) flick.finish(event.getX(up) - downX, event.getY(up) - downY);
                    cancel();
                    if (click) key.performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                if (held && flick != null) flick.cancel();
                cancel();
                return true;
            default:
                return true;
        }
    }

    private boolean inside(float x, float y) {
        return x >= -slop && y >= -slop && x < key.getWidth() + slop && y < key.getHeight() + slop;
    }

    @Override public void run() {
        if (pointer >= 0 && key.isEnabled() && key.isShown()) {
            held = key.performLongClick();
            if (held && flick != null) flick.start(downX, downY);
        }
    }

    private void cancel() {
        key.removeCallbacks(this);
        pointer = -1;
        held = false;
        key.setPressed(false);
    }

    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) {
        if (held && flick != null) flick.cancel();
        cancel();
    }
}
