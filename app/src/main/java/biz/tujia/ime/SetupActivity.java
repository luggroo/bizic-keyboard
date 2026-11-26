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
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
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
import android.util.JsonReader;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
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
import java.nio.charset.StandardCharsets;
import java.util.Locale;

public final class SetupActivity extends Activity {
    private int INK;
    private int MUTED;
    private int RED;
    private int surface;
    private int cardSurface;
    private int fieldSurface;
    private int fieldBorder;
    private boolean dark;
    private static final String BUG_EMAIL = "yulouzhou+bizic@qq.com";
    private InputMethodManager keyboard;
    private Context strings;
    private TextView status;
    private Button enable;
    private Button select;
    private Button spelling;
    private Button ipaCompletion;

    @Override public void onCreate(Bundle state) {
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        dark = getIntent().getBooleanExtra("biz.tujia.ime.READER_DARK", systemDark);
        if (dark) setTheme(R.style.DarkSetupTheme);
        super.onCreate(state);
        strings = this;
        if (getIntent().hasExtra("biz.tujia.ime.READER_EN")) {
            Configuration language = new Configuration(getResources().getConfiguration());
            language.setLocale(getIntent().getBooleanExtra("biz.tujia.ime.READER_EN", false)
                    ? Locale.ENGLISH : Locale.SIMPLIFIED_CHINESE);
            strings = createConfigurationContext(language);
        }
        keyboard = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        INK = dark ? 0xfff8eeeb : 0xff392b27;
        MUTED = dark ? 0xffd8c3bf : 0xff745950;
        RED = dark ? 0xffffafa0 : 0xffab3929;
        surface = dark ? 0xff211d1d : 0xfffffaf5;
        cardSurface = dark ? 0xff3b2725 : 0xfffae9df;
        fieldSurface = dark ? 0xff302a29 : 0xfffff1e8;
        fieldBorder = dark ? 0xff806661 : 0xffddc5b7;
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(surface);
        getWindow().setStatusBarColor(surface);
        getWindow().getDecorView().setSystemUiVisibility(dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        if (Build.VERSION.SDK_INT >= 26) getWindow().setNavigationBarColor(surface);
        scroll.setFillViewport(true);
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            scroll.setOnApplyWindowInsetsListener((v, insets) -> {
                android.graphics.Insets sides = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout() | WindowInsets.Type.ime());
                // Resize the viewport so ScrollView keeps the focused editor above the IME.
                ViewGroup.MarginLayoutParams layout = (ViewGroup.MarginLayoutParams) v.getLayoutParams();
                if (layout.leftMargin != sides.left || layout.topMargin != sides.top
                        || layout.rightMargin != sides.right || layout.bottomMargin != sides.bottom) {
                    layout.setMargins(sides.left, sides.top, sides.right, sides.bottom);
                    v.setLayoutParams(layout);
                }
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

        Button back = action(strings.getString(R.string.setup_back_button), false, v -> finish());
        back.setContentDescription(strings.getString(R.string.setup_back));
        body.addView(back);
        TextView title = label(strings.getString(R.string.setup_keyboard_title), 26, INK, true);
        body.addView(title);
        addSpaced(body, label(strings.getString(R.string.setup_keyboard_subtitle), 14, MUTED, false), 5);
        LinearLayout card = section(body, 24);
        card.setBackground(shape(cardSurface, Color.TRANSPARENT, 16));
        card.setPadding(dp(16), dp(18), dp(16), dp(12));
        status = label("", 16, INK, true);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        card.addView(status);
        enable = action(strings.getString(R.string.setup_enable_system), true, v -> {
            try {
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
            } catch (ActivityNotFoundException e) {
                status.setText(strings.getString(R.string.setup_enable_hint));
            }
        });
        addSpaced(card, enable, 12);
        select = action(strings.getString(R.string.setup_select_keyboard), false, v -> keyboard.showInputMethodPicker());
        addSpaced(card, select, 8);

        LinearLayout editor = section(body, 24);
        editor.addView(label(strings.getString(R.string.setup_trial_title), 18, INK, true));
        addSpaced(editor, label(strings.getString(R.string.setup_trial_instructions), 14, MUTED, false), 6);
        EditText trial = new EditText(this);
        trial.setId(R.id.trial_editor);
        trial.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        trial.setTextColor(INK);
        trial.setHintTextColor(MUTED);
        trial.setHint(strings.getString(R.string.setup_trial_hint));
        trial.setMinLines(2);
        trial.setMaxLines(5);
        trial.setGravity(Gravity.TOP | Gravity.START);
        trial.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        trial.setImeOptions(EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        trial.setBackground(shape(fieldSurface, fieldBorder, 10));
        trial.setPadding(dp(12), dp(10), dp(12), dp(10));
        addSpaced(editor, trial, 12);
        spelling = action("", false, v -> chooseSpelling());
        addSpaced(body, spelling, 16);
        ipaCompletion = action("", false, v -> chooseIpaCompletion());
        addSpaced(body, ipaCompletion, 8);

        LinearLayout personal = section(body, 24);
        personal.addView(label(strings.getString(R.string.setup_personal_title), 18, INK, true));
        CheckBox learn = new CheckBox(this);
        learn.setText(strings.getString(R.string.setup_personal_switch));
        learn.setTextColor(INK);
        learn.setMinHeight(dp(48));
        learn.setChecked(KeyboardSettings.personalLearning(this));
        learn.setOnCheckedChangeListener((button, checked) ->
                KeyboardSettings.setPersonalLearning(this, checked));
        addSpaced(personal, learn, 8);
        addSpaced(personal, label(strings.getString(R.string.setup_personal_hint), 14, MUTED, false), 4);
        addSpaced(personal, action(strings.getString(R.string.setup_personal_clear), false, v ->
                new AlertDialog.Builder(this).setTitle(strings.getString(R.string.setup_personal_confirm))
                        .setMessage(strings.getString(R.string.setup_personal_warning))
                        .setNegativeButton(strings.getString(R.string.setup_cancel), null)
                        .setPositiveButton(strings.getString(R.string.setup_clear), (dialog, which) -> {
                            android.content.SharedPreferences words = getSharedPreferences("user_dictionary", MODE_PRIVATE);
                            android.content.SharedPreferences.Editor wordEditor = words.edit();
                            for (String key : words.getAll().keySet()) wordEditor.remove(key);
                            wordEditor.apply();
                            Toast.makeText(this, strings.getString(R.string.setup_personal_cleared), Toast.LENGTH_SHORT).show();
                        }).show()), 4);

        LinearLayout diagnostics = section(body, 24);
        diagnostics.addView(label(strings.getString(R.string.setup_diagnostics_title), 18, INK, true));
        addSpaced(diagnostics, label(strings.getString(R.string.reader_privacy), 14, MUTED, false), 6);
        CheckBox record = new CheckBox(this);
        record.setText(strings.getString(R.string.setup_record_actions));
        record.setTextColor(INK);
        record.setMinHeight(dp(48));
        record.setSaveEnabled(false);
        record.setChecked(KeyboardSettings.crashRecording(this));
        record.setOnCheckedChangeListener((button, checked) -> {
            KeyboardSettings.setCrashRecording(this, checked);
            CrashReports.setRecording(checked);
        });
        addSpaced(diagnostics, record, 8);
        addSpaced(diagnostics, label(strings.getString(R.string.setup_sensitive_fields),
                14, INK, true), 4);
        addSpaced(diagnostics, label(strings.getString(R.string.setup_diagnostics_detail, CrashReports.EVENT_LIMIT), 14, MUTED, false), 6);
        addSpaced(diagnostics, action(strings.getString(R.string.setup_view_crash), false, v -> showCrash()), 8);
        TextView contact = label(BUG_EMAIL, 14, MUTED, false);
        contact.setTextIsSelectable(true);
        addSpaced(diagnostics, contact, 4);
        Button info = action(Handwriting.ENABLED ? strings.getString(R.string.setup_version_sources_hand) : strings.getString(R.string.setup_version_sources), false, v -> showSources());
        addSpaced(body, info, 20);
        if (state == null) body.requestFocus();
    }

    private void showCrash() {
        String report = CrashReports.read(this);
        AlertDialog.Builder builder = new AlertDialog.Builder(this).setTitle(strings.getString(R.string.setup_crash_title));
        if (report.isEmpty()) {
            builder.setMessage(strings.getString(R.string.setup_no_crash)).setPositiveButton(strings.getString(R.string.setup_close), null).show();
            return;
        }
        AlertDialog dialog = builder.setMessage(report)
                .setNeutralButton(strings.getString(R.string.setup_delete), (d, w) -> CrashReports.clear(this))
                .setNegativeButton(strings.getString(R.string.setup_email), null)
                .setPositiveButton(strings.getString(R.string.setup_copy), null).show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> copyCrash(report));
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> emailCrash(report));
    }

    private void copyCrash(String report) {
        ClipData clip = ClipData.newPlainText(strings.getString(R.string.setup_crash_label), report);
        if (Build.VERSION.SDK_INT >= 33) {
            PersistableBundle extras = new PersistableBundle();
            extras.putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true);
            clip.getDescription().setExtras(extras);
        }
        getSystemService(ClipboardManager.class).setPrimaryClip(clip);
        if (Build.VERSION.SDK_INT < 33) {
            Toast.makeText(this, strings.getString(R.string.setup_crash_copied), Toast.LENGTH_SHORT).show();
        }
    }

    private void emailCrash(String report) {
        Uri mail = Uri.parse("mailto:" + BUG_EMAIL
                + "?subject=" + Uri.encode(strings.getString(R.string.setup_crash_label))
                + "&body=" + Uri.encode(report.replace("\n", "\r\n")));
        try {
            startActivity(new Intent(Intent.ACTION_SENDTO, mail));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, strings.getString(R.string.setup_email_unavailable), Toast.LENGTH_LONG).show();
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
        status.setText(selected ? strings.getString(R.string.setup_status_selected) : enabled ? strings.getString(R.string.setup_status_enabled) : strings.getString(R.string.setup_status_disabled));
        enable.setVisibility(enabled ? View.GONE : View.VISIBLE);
        select.setEnabled(enabled);
        select.setAlpha(enabled ? 1f : .55f);
        select.setText(selected ? strings.getString(R.string.setup_switch_keyboard) : strings.getString(R.string.setup_select_keyboard));
        spelling.setText(strings.getString(R.string.setup_spelling) + " · " + outputName(KeyboardSettings.typingOutput(this)));
        int dialects = KeyboardSettings.ipaDialects(this);
        String ipaSummary = dialects == IpaKeyboard.ALL_DIALECTS ? strings.getString(R.string.setup_north_south)
                : dialects == IpaKeyboard.NORTH ? strings.getString(R.string.setup_north) : dialects == IpaKeyboard.SOUTH ? strings.getString(R.string.setup_south) : strings.getString(R.string.setup_disabled);
        ipaCompletion.setText(strings.getString(R.string.setup_ipa_completion) + " · " + ipaSummary);
    }

    private String outputName(OutputForm form) {
        if (form == OutputForm.YE) return strings.getString(R.string.setup_ye);
        if (form == OutputForm.MARKED) return strings.getString(R.string.setup_marked);
        return strings.getString(R.string.setup_han);
    }

    private void chooseSpelling() {
        LinearLayout row = new LinearLayout(this);
        row.setPadding(dp(10), dp(8), dp(10), dp(6));
        int textHeight = (int)Math.ceil(android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_SP, 16, getResources().getDisplayMetrics()) * 2.8f);
        row.setMinimumHeight(Math.max(dp(72), textHeight + dp(14)));
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle(strings.getString(R.string.setup_spelling)).setView(row)
                .setNegativeButton(strings.getString(R.string.setup_cancel), null).create();
        OutputForm selected = KeyboardSettings.typingOutput(this);
        for (OutputForm form : OutputForm.values()) {
            form.addButton(row, form == selected, false, () -> {
                KeyboardSettings.setTypingOutput(this, form);
                refresh();
                dialog.dismiss();
            });
            if (dark || strings.getResources().getConfiguration().locale.getLanguage().equals("en")) {
                Button option = (Button) row.getChildAt(row.getChildCount() - 1);
                option.setText(form.example + "\n" + outputName(form));
                option.setContentDescription(outputName(form) + ", " + form.example);
                if (dark) option.setTextColor(form == selected ? 0xffffafa0 : 0xfff8eeeb);
            }
        }
        dialog.show();
    }

    private void chooseIpaCompletion() {
        int dialects = KeyboardSettings.ipaDialects(this);
        LinearLayout options = new LinearLayout(this);
        options.setOrientation(LinearLayout.VERTICAL);
        options.setPadding(dp(24), dp(8), dp(24), 0);
        CheckBox north = ipaDialectOption(options, strings.getString(R.string.setup_north), (dialects & IpaKeyboard.NORTH) != 0);
        CheckBox south = ipaDialectOption(options, strings.getString(R.string.setup_south), (dialects & IpaKeyboard.SOUTH) != 0);
        new AlertDialog.Builder(this).setTitle(strings.getString(R.string.setup_ipa_dictionary))
                .setView(options)
                .setNegativeButton(strings.getString(R.string.setup_cancel), null)
                .setPositiveButton(strings.getString(R.string.setup_confirm), (dialog, choice) -> {
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
        StringBuilder versions = new StringBuilder();
        try {
            String version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
            versions.append(strings.getString(R.string.app_version, version)).append('\n');
        } catch (PackageManager.NameNotFoundException ignored) {}
        try (JsonReader reader = new JsonReader(new InputStreamReader(
                getAssets().open("learning/offline-manifest.json"), StandardCharsets.UTF_8))) {
            reader.beginObject();
            while (reader.hasNext()) {
                if (reader.nextName().equals("contentVersion")) {
                    versions.append(strings.getString(R.string.content_version, reader.nextString())).append('\n');
                    break;
                }
                reader.skipValue();
            }
        } catch (IOException ignored) {}
        String source = versions + "\n" + strings.getString(R.string.setup_lexicon_source);
        AlertDialog.Builder dialog = new AlertDialog.Builder(this);
        if (Handwriting.ENABLED) {
            dialog.setTitle(strings.getString(R.string.setup_lexicon_hand_title));
            source += "\n\n" + strings.getString(R.string.setup_handwriting_source);
            dialog.setNeutralButton(strings.getString(R.string.setup_handwriting_license), (p,b) -> showAsset(strings.getString(R.string.setup_handwriting_license_title), "handwriting/NOTICE.txt", "handwriting/ARPHICPL.TXT"));
        } else {
            dialog.setTitle(strings.getString(R.string.setup_lexicon_title));
        }
        dialog.setMessage(source)
                .setNegativeButton(strings.getString(R.string.setup_dialect_license), (p,b) -> showAsset(strings.getString(R.string.setup_dialect_license), "dialect-data-LICENSE.txt"))
                .setPositiveButton(strings.getString(R.string.setup_close), null).show();
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
            text.append(strings.getString(R.string.setup_license_error));
        }
        new AlertDialog.Builder(this).setTitle(title).setMessage(text).setPositiveButton(strings.getString(R.string.setup_close), null).show();
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
        b.setTextColor(primary ? (dark ? 0xff3a1712 : Color.WHITE) : RED);
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
