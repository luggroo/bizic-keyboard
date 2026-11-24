package biz.tujia.storynotes;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.InputType;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.UnderlineSpan;
import android.text.style.ScaleXSpan;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Locale;
import java.text.SimpleDateFormat;

public final class NotesActivity extends Activity {
    private static final int PAPER=0xfffcfbf7, INK=0xff272e2b, ACCENT=0xff36554f, MUTED=0xff636c66;
    private LinearLayout panel;
    private SharedPreferences notes;
    EditText editor;
    TextView action;
    long currentId;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(PAPER);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        SharedPreferences settings = getSharedPreferences("notebook", MODE_PRIVATE);
        String notebook = getIntent().getStringExtra("notebook");
        if (notebook != null && notebook.matches("reading-[0-9]+")) {
            settings.edit().putString("name",notebook).apply();
        } else {
            notebook = settings.getString("name","notes");
        }
        notes = getSharedPreferences(notebook, MODE_PRIVATE);
        if (state != null && state.getLong("id") > 0) {
            openNote(state.getLong("id"), state.getString("draft", ""));
        } else showList();
    }

    @Override public void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        if (editor != null) {
            state.putLong("id", currentId);
            state.putString("draft", editor.getText().toString());
        }
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextSize(sp);
        v.setTextColor(color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return v;
    }

    private void screen(String heading, String button, Runnable handler) {
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setBackgroundColor(PAPER);
        panel.setFocusableInTouchMode(true);
        setContentView(panel);
        LinearLayout bar = new LinearLayout(this);
        bar.setPadding(dp(24), 0, dp(16), 0);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(bar, -1, dp(60));
        if (currentId!=0) {
            TextView back=label("‹",34,ACCENT,false);
            back.setContentDescription("返回笔记列表");
            back.setGravity(Gravity.CENTER_VERTICAL);
            back.setOnClickListener(v -> save());
            bar.addView(back,dp(42),-1);
        }
        TextView title=label(heading,24,INK,false);
        title.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        bar.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        action = label(button,18,ACCENT,false);
        action.setGravity(Gravity.CENTER);
        action.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        action.setPadding(dp(14),0,dp(10),0);
        action.setOnClickListener(v -> handler.run());
        bar.addView(action, -2, dp(48));
        View edge=new View(this);
        edge.setBackgroundColor(0xffdedfd7);
        panel.addView(edge,-1,1);
        panel.requestFocus();
    }

    void showList() {
        editor = null;
        currentId = 0;
        screen("记事本", "新建笔记", () -> openNote(System.currentTimeMillis(), ""));
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setPadding(dp(20),dp(17),dp(20),dp(16));
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        panel.addView(scroll, -1, -1);
        ArrayList<String> ids = new ArrayList<>(notes.getAll().keySet());
        Collections.sort(ids, Collections.reverseOrder());
        if (ids.isEmpty()) list.addView(label("还没有笔记", 19, MUTED, false));
        for (String id : ids) {
            if (id.startsWith("style:")) continue;
            String body = notes.getString(id, "");
            TextView item = label(body, 20, INK, false);
            item.setMaxLines(3);
            item.setEllipsize(android.text.TextUtils.TruncateAt.END);
            item.setPadding(0, dp(16), 0, dp(16));
            item.setContentDescription("打开笔记 " + id);
            item.setOnClickListener(v -> openNote(Long.parseLong(id), body));
            list.addView(item,-1,-2);
            View line = new View(this);
            line.setBackgroundColor(0xffd9e2e4);
            list.addView(line,-1,dp(1));
        }
    }

    private void openNote(long id, String body) {
        currentId = id;
        screen("记事本", "保存", this::save);
        String created=new SimpleDateFormat("M月d日  EEEE",Locale.SIMPLIFIED_CHINESE).format(new Date(id));
        TextView date=label(created,15,MUTED,false);
        date.setPadding(dp(26),dp(21),dp(26),dp(4));
        panel.addView(date,-1,-2);
        editor = new EditText(this);
        editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editor.setImeOptions(EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        editor.setGravity(Gravity.TOP | Gravity.START);
        editor.setTextColor(INK);
        editor.setHintTextColor(MUTED);
        editor.setTextSize(27);
        editor.setTypeface(Typeface.create("serif",Typeface.NORMAL));
        editor.setLineSpacing(dp(8),1.04f);
        editor.setPadding(dp(26),dp(8),dp(26),dp(16));
        editor.setBackgroundColor(PAPER);
        SpannableString styled=new SpannableString(body);
        String metadata=notes.getString("style:"+id,"");
        if (body.equals(saved(id)) && !metadata.isEmpty()) {
            for (String record:metadata.split(";")) {
                String[] attrs=record.split(",");
                int start=Integer.parseInt(attrs[0]), end=Integer.parseInt(attrs[1]);
                if (start<0 || start>=end || end>styled.length()) continue;
                Object span="u".equals(attrs[2]) ? new UnderlineSpan() : new ScaleXSpan(Float.parseFloat(attrs[2]));
                styled.setSpan(span,start,end,Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        editor.setText(styled);
        editor.setHint("写点什么…");
        panel.addView(editor,new LinearLayout.LayoutParams(-1,0,1));
        panel.requestFocus();
    }

    String saved(long id) { return notes.getString(Long.toString(id), null); }

    private boolean persist() {
        if (editor==null) return false;
        Spanned body=editor.getText();
        StringBuilder styles=new StringBuilder();
        for (Object span:body.getSpans(0,body.length(),Object.class)) {
            if (!(span instanceof UnderlineSpan) && !(span instanceof ScaleXSpan)) continue;
            styles.append(body.getSpanStart(span)).append(',').append(body.getSpanEnd(span)).append(',')
                  .append(span instanceof UnderlineSpan ? "u" : String.valueOf(((ScaleXSpan)span).getScaleX())).append(';');
        }
        return notes.edit().putString(Long.toString(currentId),body.toString())
                .putString("style:"+currentId,styles.toString()).commit();
    }

    void storeDraft() {
        if (!persist()) throw new IllegalStateException("Could not preserve the current note");
    }

    private void save() {
        if (!persist()) {
            android.widget.Toast.makeText(this,"未能保存",android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(editor.getWindowToken(), 0);
        showList();
    }

    @Override public void onBackPressed() {
        if (editor == null) super.onBackPressed(); else save();
    }
}
