package biz.tujia.ime.perf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.concurrent.CountDownLatch;

/** A separate editor process. No benchmark hooks are compiled into the IME. */
public final class PerfEditor extends Activity {
    MeasuredField words, direct;
    static final class Sample {
        final CountDownLatch drawn = new CountDownLatch(1);
        final String expected;
        volatile long changedAt, drawnAt;
        Sample(String expected) { this.expected = expected; }
    }

    static final class MeasuredField extends EditText {
        Sample sample;
        MeasuredField(Context context, int type) {
            super(context);
            setSingleLine(true);
            setInputType(type);
        }

        @Override protected void onTextChanged(CharSequence value, int start, int before, int count) {
            if (sample != null && sample.changedAt == 0 && sample.expected.contentEquals(value))
                sample.changedAt = System.nanoTime();
            super.onTextChanged(value, start, before, count);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (sample != null && sample.changedAt != 0 && sample.drawnAt == 0) {
                sample.drawnAt = System.nanoTime();
                sample.drawn.countDown();
            }
        }
    }

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setFocusableInTouchMode(true);
        words = new MeasuredField(this, InputType.TYPE_CLASS_TEXT);
        direct = new MeasuredField(this, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        form.addView(words);
        form.addView(direct);
        setContentView(form);
        form.requestFocus();
    }

    void show(MeasuredField field) {
        field.requestFocus();
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT);
    }

    void hide() {
        ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(words.getWindowToken(), 0);
    }
}
