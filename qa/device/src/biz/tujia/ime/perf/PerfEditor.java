package biz.tujia.ime.perf;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import java.util.concurrent.CountDownLatch;

/** A separate editor process. No benchmark hooks are compiled into the IME. */
public final class PerfEditor extends Activity {
    MeasuredField words, direct;
    volatile int editorActions, lastAction;
    static final class Sample {
        final CountDownLatch drawn = new CountDownLatch(1);
        final String expected;
        volatile long changedAt, drawnAt;
        Sample(String expected) { this.expected = expected; }
    }

    static final class MeasuredField extends EditText {
        Sample sample;
        int commitCalls;
        String lastCommit = "";
        boolean lastAccepted;

        @Override public InputConnection onCreateInputConnection(EditorInfo info) {
            InputConnection target = super.onCreateInputConnection(info);
            if (target == null) return null;
            return new InputConnectionWrapper(target, false) {
                @Override public boolean commitText(CharSequence text, int newPosition) {
                    commitCalls++;
                    lastCommit = String.valueOf(text);
                    lastAccepted = super.commitText(text, newPosition);
                    return lastAccepted;
                }
            };
        }
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
        words.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        words.setOnEditorActionListener((field, action, event) -> {
            editorActions++; lastAction = action; return true;
        });
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
