package biz.tujia.ime.e2e;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Independent host. It knows nothing about the implementation of the keyboard. */
public final class EditorActivity extends Activity {
    EditText first;
    EditText second;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setFitsSystemWindows(true);
        panel.setFocusableInTouchMode(true);
        first = editor("正文一");
        second = editor("正文二");
        panel.addView(first);
        panel.addView(second);
        setContentView(panel);
        panel.requestFocus();
    }

    private EditText editor(String hint) {
        EditText field = new EditText(this);
        field.setId(View.generateViewId());
        field.setSingleLine(true);
        field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE);
        field.setHint(hint);
        return field;
    }
}
