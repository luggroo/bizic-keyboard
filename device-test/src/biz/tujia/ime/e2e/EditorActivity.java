package biz.tujia.ime.e2e;

import android.app.Activity;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.LinearLayout;

/** Independent host. It knows nothing about the implementation of the keyboard. */
public final class EditorActivity extends Activity {
    EditText first;
    EditText second;
    WebView web;
    private LinearLayout panel;
    volatile int lastEditorAction;
    volatile String lastSubmitted;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setFitsSystemWindows(true);
        panel.setFocusableInTouchMode(true);
        setContentView(panel);
        nativeScenario(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                EditorInfo.IME_ACTION_DONE, false);
    }

    void nativeScenario(int type, int action, boolean multiline) {
        panel.removeAllViews();
        if (web != null) { web.destroy(); web = null; }
        first = editor("正文一");
        second = editor("正文二");
        first.setNextFocusForwardId(second.getId());
        EditText active = action == EditorInfo.IME_ACTION_PREVIOUS ? second : first;
        active.setSingleLine(!multiline);
        active.setInputType(type);
        active.setImeOptions(action);
        if (multiline) { active.setMinLines(2); active.setMaxLines(3); }
        lastEditorAction = EditorInfo.IME_NULL;
        lastSubmitted = "";
        active.setOnEditorActionListener((v, received, event) -> {
            lastEditorAction = received;
            lastSubmitted = v.getText().toString();
            return received == EditorInfo.IME_ACTION_SEND || received == EditorInfo.IME_ACTION_SEARCH;
        });
        panel.addView(first);
        panel.addView(second);
        panel.requestFocus();
    }

    void webScenario() {
        panel.removeAllViews();
        web = new WebView(this);
        web.getSettings().setJavaScriptEnabled(true);
        panel.addView(web, -1, -1);
        String page = "<!doctype html><meta name=viewport content='width=device-width, initial-scale=1'>"
                + "<style>input,textarea{display:block;font:24px sans-serif;margin:8px;width:88%}</style>"
                + "<input id='word' aria-label='网页词语'><textarea id='note' rows='2' aria-label='网页正文'></textarea>"
                + "<input id='secret' type='password' aria-label='网页密码'>";
        web.loadDataWithBaseURL("https://fixture.invalid/", page, "text/html", "UTF-8", null);
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
