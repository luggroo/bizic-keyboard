package biz.tujia.ime;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowInsets;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;

/** Runs alongside the IME so its diagnostic preference and event buffer have one owner. */
public final class PrivacyActivity extends Activity {
    static final String FIRST_LAUNCH = "biz.tujia.ime.FIRST_DISCLOSURE";

    @Override protected void onCreate(Bundle state) {
        boolean systemDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        boolean dark = getIntent().getBooleanExtra(LearningActivity.EXTRA_READER_DARK, systemDark);
        int theme = dark ? R.style.DisclosureDark : R.style.DisclosureLight;
        setTheme(theme);
        super.onCreate(state);
        boolean first = getIntent().getBooleanExtra(FIRST_LAUNCH, false);
        boolean english = getIntent().getBooleanExtra(LearningActivity.EXTRA_READER_EN, ReaderLanguage.english(this));
        Context strings = ReaderLanguage.localized(this, english);
        Context ui = new ContextThemeWrapper(strings, theme);
        setTitle(strings.getText(R.string.privacy_title));
        setContentView(LayoutInflater.from(ui).inflate(R.layout.academy_privacy, null));
        View root = findViewById(R.id.privacy_screen);
        int canvas = getColor(dark ? R.color.academy_dark_canvas : R.color.academy_light_canvas);
        if (Build.VERSION.SDK_INT >= 26) {
            getWindow().setNavigationBarColor(canvas);
            getWindow().getDecorView().setSystemUiVisibility(dark ? 0 :
                    View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        }
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets sides = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.displayCutout());
                view.setPadding(sides.left, sides.top, sides.right, sides.bottom);
                return WindowInsets.CONSUMED;
            });
            root.requestApplyInsets();
        }
        TextView detail = findViewById(R.id.privacy_diagnostic_detail);
        detail.setText(strings.getString(R.string.privacy_diagnostic, CrashReports.EVENT_LIMIT));
        CheckBox record = findViewById(R.id.privacy_diagnostics);
        // The Android view hierarchy restores unsaved edits after a configuration change.
        record.setChecked(KeyboardSettings.crashRecording(this));
        Button cancel = findViewById(R.id.privacy_cancel);
        cancel.setText(first ? R.string.privacy_leave : android.R.string.cancel);
        cancel.setOnClickListener(v -> finish());
        Button accept = findViewById(R.id.privacy_accept);
        accept.setText(first ? R.string.privacy_continue : R.string.privacy_save);
        accept.setBackgroundTintList(ColorStateList.valueOf(getColor(dark ? R.color.academy_dark_accent : R.color.academy_red)));
        accept.setTextColor(dark ? getColor(R.color.academy_ink) : android.graphics.Color.WHITE);
        accept.setOnClickListener(v -> {
            boolean enabled = record.isChecked();
            KeyboardSettings.setCrashRecording(this, enabled);
            CrashReports.setRecording(enabled);
            setResult(RESULT_OK);
            finish();
        });
    }
}
