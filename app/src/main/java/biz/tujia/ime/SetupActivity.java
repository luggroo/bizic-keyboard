package biz.tujia.ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipDescription;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PersistableBundle;
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
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public final class SetupActivity extends Activity {
    private static final int INK = 0xff382b26;
    private static final int MUTED = 0xff755b50;
    private static final int RED = 0xffa43827;
    private static final String BUG_EMAIL = "ikuroucju+keyboardbug@gmail.com";
    private InputMethodManager keyboard;
    private TextView status;
    private Button enable;
    private Button select;
    private Button spelling;
    private Button ipaCompletion;

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
        addSpaced(editor, label("点键盘上的「设置」，选「谐音字」。输入 bizika，点“土家族”下面的「秘兹佧」。", 14, MUTED, false), 6);
        EditText trial = new EditText(this);
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
        spelling = action("", false, v -> chooseSpelling());
        addSpaced(body, spelling, 16);
        ipaCompletion = action("", false, v -> chooseIpaCompletion());
        addSpaced(body, ipaCompletion, 8);

        LinearLayout diagnostics = section(body, 24);
        diagnostics.addView(label("崩溃排查", 18, INK, true));
        CheckBox record = new CheckBox(this);
        record.setText("记录键盘操作");
        record.setSaveEnabled(false);
        record.setChecked(KeyboardSettings.crashRecording(this));
        record.setOnCheckedChangeListener((button, checked) -> {
            KeyboardSettings.setCrashRecording(this, checked);
            CrashReports.setRecording(checked);
        });
        addSpaced(diagnostics, record, 8);
        addSpaced(diagnostics, label("密码、数字、邮箱地址、网址等输入框会自动跳过记录。"
                + "应用标记为私密的输入框也受保护。", 14, INK, true), 4);
        addSpaced(diagnostics, label("勾选后，键盘在普通输入框里只保留最近 " + CrashReports.EVENT_LIMIT
                + " 次操作，包括按键和选词。崩溃日志保存在本机，你可以先预览，再决定是否发送。"
                + "你可以随时取消勾选。", 14, MUTED, false), 6);
        addSpaced(diagnostics, action("查看上次崩溃日志", false, v -> showCrash()), 8);
        TextView contact = label(BUG_EMAIL, 14, MUTED, false);
        contact.setTextIsSelectable(true);
        addSpaced(diagnostics, contact, 4);
        Button info = action("关于词库与手写字形", false, v -> showSources());
        addSpaced(body, info, 20);
        if (state == null) body.requestFocus();
    }

    private void showCrash() {
        String report = CrashReports.read(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle("上次崩溃日志");
        if (report.isEmpty()) {
            builder.setMessage("暂无崩溃日志。").setPositiveButton("关闭", null).show();
            return;
        }
        AlertDialog dialog = builder.setMessage(report)
                .setNeutralButton("删除", (d, w) -> CrashReports.clear(this))
                .setNegativeButton("发邮件", null)
                .setPositiveButton("复制日志", null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> copyCrash(report));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> emailCrash(report));
    }

    private void copyCrash(String report) {
        ClipData clip = ClipData.newPlainText("土家语输入法崩溃日志", report);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, "已复制崩溃日志", Toast.LENGTH_SHORT).show();
        }
    }

    private void emailCrash(String report) {
        Uri mail = Uri.parse("mailto:" + BUG_EMAIL
                + "?subject=" + Uri.encode("土家语输入法崩溃日志")
                + "&body=" + Uri.encode(report.replace("\n", "\r\n")));
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, mail));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "没有可用的邮件应用，请复制日志后发送。", Toast.LENGTH_LONG).show();
        }
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
        spelling.setText("打字输出 · " + KeyboardSettings.typingOutput(this).name);
        int dialects = KeyboardSettings.ipaDialects(this);
        String ipaSummary = dialects == IpaKeyboard.ALL_DIALECTS ? "北部、南部"
                : dialects == IpaKeyboard.NORTH ? "北部" : dialects == IpaKeyboard.SOUTH ? "南部" : "关闭";
        ipaCompletion.setText("音标补全 · " + ipaSummary);
    }

    private void chooseSpelling() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(10), dp(8), dp(10), dp(6));
        int textHeight = (int)Math.ceil(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()) * 2.8f);
        row.setMinimumHeight(Math.max(dp(72), textHeight + dp(14)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("打字输出").setView(row)
                .setNegativeButton("取消", null).create();
        OutputForm selected = KeyboardSettings.typingOutput(this);
        for (OutputForm form : OutputForm.values()) {
            form.addButton(row, form == selected, false, () -> {
                KeyboardSettings.setTypingOutput(this, form);
                refresh();
                dialog.dismiss();
            });
        }
        dialog.show();
    }

    private void chooseIpaCompletion() {
        int dialects = KeyboardSettings.ipaDialects(this);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(24), dp(8), dp(24), 0);
        CheckBox north = ipaDialectOption(options, "北部", (dialects & IpaKeyboard.NORTH) != 0);
        CheckBox south = ipaDialectOption(options, "南部", (dialects & IpaKeyboard.SOUTH) != 0);
        new AlertDialog.Builder(this).setTitle("音标补全词库")
                .setView(options)
                .setNegativeButton("取消", null)
                .setPositiveButton("确定", (dialog, choice) -> {
                    int selected = (north.isChecked() ? IpaKeyboard.NORTH : 0) | (south.isChecked() ? IpaKeyboard.SOUTH : 0);
                    KeyboardSettings.setIpaDialects(this, selected);
                    refresh();
                }).show();
    }

    private CheckBox ipaDialectOption(LinearLayout parent, String label, boolean checked) {
        CheckBox option = new CheckBox(this);
        option.setMinHeight(dp(48));
        option.setText(label);
        option.setChecked(checked);
        parent.addView(option, new LinearLayout.LayoutParams(-1, -2));
        return option;
    }

    @Override protected void onResume() { super.onResume(); refresh(); }
    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus) refresh();
    }

    private void showSources() {
        new AlertDialog.Builder(this).setTitle("词库与手写字形")
                .setMessage("北部土家语词条来自tujia.biz 北部词典及补充语料。候选上方显示汉义，点选后写入选中的拼写或记音字。\n\n手写字形采用 Hanzi Writer Data 笔画资料；识别和候选匹配在设备上运行。")
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
