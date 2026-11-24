package biz.tujia.ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class SetupActivity extends Activity {
    private static final int INK = 0xff382b26;
    private static final int MUTED = 0xff755b50;
    private static final int RED = 0xffa43827;
    private InputMethodManager keyboard;
    private TextView status;
    private Button enable;
    private Button select;
    private Button spelling;
    private EditText trial;
    private android.widget.CheckBox record;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(0xfffffaf5);
        getWindow().setStatusBarColor(0xfffffaf5);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        scroll.setFillViewport(true);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets sides = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                v.setPadding(sides.left, sides.top, sides.right, sides.bottom);
                return insets;
            });
        }
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(24), dp(27), dp(24), dp(24));
        // The system keyboard opens when the reader explicitly taps the trial editor.
        body.setFocusableInTouchMode(true);
        scroll.addView(body);
        setContentView(scroll);

        TextView title = label("土家语输入法", 26, INK, true);
        body.addView(title);
        addSpaced(body, label("北部土家语  ·  安卓键盘", 14, MUTED, false), 5);
        LinearLayout card = section(body, 24);
        card.setBackground(shape(0xfff8e5d8, Color.TRANSPARENT, 16));
        card.setPadding(dp(16), dp(18), dp(16), dp(12));
        status = label("", 16, INK, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(status);
        enable = action("在系统设置中启用", true, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            } catch (ActivityNotFoundException e) {
                status.setText("请打开系统设置，在键盘管理中启用土家语输入法。");
            }
        });
        addSpaced(card, enable, 12);
        select = action("选择土家语键盘", false, v -> keyboard.showInputMethodPicker());
        addSpaced(card, select, 8);

        LinearLayout editor = section(body, 24);
        editor.addView(label("试打", 18, INK, true));
        addSpaced(editor, label("点键盘上的「更多」，选「汉字记音」。输入 bizika，点“土家族”下面的「币兹咔」。", 14, MUTED, false), 6);
        trial = new EditText(this);
        trial.setId(R.id.trial_editor);
        trial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        trial.setTextColor(0xff223541);
        trial.setHintTextColor(0xff536671);
        trial.setHint("点这里，打开键盘");
        trial.setMinLines(2);
        trial.setMaxLines(5);
        trial.setGravity(Gravity.TOP | Gravity.START);
        trial.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        trial.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        trial.setBackground(shape(0xfff3f6f7, 0xff8c9ea7, 10));
        trial.setPadding(dp(12), dp(10), dp(12), dp(10));
        addSpaced(editor, trial, 12);
        record = new android.widget.CheckBox(this);
        record.setText("记录本次试打，供崩溃时排查");
        record.setSaveEnabled(false);
        record.setOnCheckedChangeListener((button, checked) -> CrashReports.recordTrial(checked));
        addSpaced(editor, record, 8);
        addSpaced(editor, label("仅记录此框内最近 64 次输入、选词等操作。崩溃时保存在本机，可在分享前查看。", 14, MUTED, false), 4);
        addSpaced(editor, action("查看上次崩溃报告", false, v -> showCrash()), 8);
        spelling = action("", false, v -> chooseSpelling());
        addSpaced(body, spelling, 16);
        Button info = action("关于词库与手写字形", false, v -> showSources());
        addSpaced(body, info, 20);
        if (state == null) body.requestFocus();
    }

    @Override protected void onStop() {
        record.setChecked(false);
        super.onStop();
    }

    private void showCrash() {
        String report = CrashReports.read(this);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this).setTitle("上次崩溃报告")
                .setMessage(report.isEmpty() ? "暂无崩溃报告。" : report)
                .setNegativeButton("关闭", null);
        if (!report.isEmpty()) {
            dialog.setNeutralButton("删除", (d, w) -> CrashReports.clear(this));
            dialog.setPositiveButton("分享", (d, w) -> {
                Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, report);
                startActivity(Intent.createChooser(send, "分享崩溃报告"));
            });
        }
        dialog.show();
    }

    private void refresh() {
        if (status == null) return;
        boolean enabled = false;
        for (InputMethodInfo item : keyboard.getEnabledInputMethodList()) {
            if (item.getPackageName().equals(getPackageName())) {
                enabled = true;
                break;
            }
        }
        String setting = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        ComponentName current = setting == null ? null : ComponentName.unflattenFromString(setting);
        boolean selected = current != null && getPackageName().equals(current.getPackageName());
        status.setText(selected ? "正在使用土家语键盘" : enabled ? "已启用，请选择土家语键盘" : "先在系统设置中启用键盘");
        enable.setVisibility(enabled ? View.GONE : View.VISIBLE);
        select.setEnabled(enabled);
        select.setAlpha(enabled ? 1f : .55f);
        select.setText(selected ? "切换系统键盘" : "选择土家语键盘");
        boolean toneMarks = KeyboardSettings.spelling(this) == CandidateEngine.Orthography.DIACRITIC;
        spelling.setText(toneMarks ? "拼写输出 · 带调号拼音" : "拼写输出 · 叶氏拼音");
    }

    private void chooseSpelling() {
        String[] names = {"带调号拼音", "叶氏拼音"};
        int selected = KeyboardSettings.spelling(this) == CandidateEngine.Orthography.DIACRITIC ? 0 : 1;
        new AlertDialog.Builder(this).setTitle("拼写输出")
                .setSingleChoiceItems(names, selected, (dialog, index) -> {
                    KeyboardSettings.setSpelling(this, index == 0
                            ? CandidateEngine.Orthography.DIACRITIC : CandidateEngine.Orthography.YE);
                    refresh();
                    dialog.dismiss();
                }).setNegativeButton("取消", null).show();
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) refresh();
    }

    private void showSources() {
        new AlertDialog.Builder(this).setTitle("词库与手写字形")
                .setMessage("北部土家语词条来自毕兹学堂试用词表。候选上方显示汉义，点选后写入选中的拼写或记音字。\n\n手写字形采用 Hanzi Writer Data 笔画资料；识别和候选匹配在设备上运行。")
                .setNeutralButton("字形授权", (p,b) -> showAsset("手写字形授权", "handwriting/NOTICE.txt", "handwriting/ARPHICPL.TXT"))
                .setNegativeButton("方言字音授权", (p,b) -> showAsset("方言字音授权", "dialect-data-LICENSE.txt"))
                .setPositiveButton("关闭", null).show();
    }

    private void showAsset(String title, String... paths) {
        StringBuilder text = new StringBuilder();
        try {
            for (String path : paths) {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(getAssets().open(path), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) text.append(line).append('\n');
                }
                text.append('\n');
            }
        } catch (IOException e) {
            text.append("无法读取授权文件。");
        }
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton("关闭", null).show();
    }

    private LinearLayout section(LinearLayout parent, int top) {
        LinearLayout panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        addSpaced(parent, panel, top);
        return panel;
    }

    private void addSpaced(LinearLayout parent, View child, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = dp(top);
        parent.addView(child, lp);
    }

    private Button action(String title, boolean primary, View.OnClickListener listener) {
        Button b = new Button(this);
        b.setText(title);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        b.setSingleLine(false);
        b.setAllCaps(false);
        b.setMinHeight(dp(48));
        b.setTextColor(primary ? Color.WHITE : RED);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setGravity(primary ? Gravity.CENTER : Gravity.START | Gravity.CENTER_VERTICAL);
        b.setPadding(primary ? dp(16) : 0, dp(4), dp(12), dp(4));
        b.setStateListAnimator(null);
        b.setBackgroundTintList(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x30aa412d),
                shape(primary ? RED : Color.TRANSPARENT, Color.TRANSPARENT, 10),
                shape(Color.WHITE, Color.TRANSPARENT, 10)));
        b.setOnClickListener(listener);
        return b;
    }

    private TextView label(String value, int size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(value);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private GradientDrawable shape(int fill, int line, int radius) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(fill);
        shape.setCornerRadius(dp(radius));
        if (line != Color.TRANSPARENT) shape.setStroke(dp(1), line);
        return shape;
    }
    private int dp(int value) { return Math.round(getResources().getDisplayMetrics().density * value); }
}
