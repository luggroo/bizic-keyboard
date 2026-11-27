package biz.tujia.ime;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.BiConsumer;

/** Contains the shared reader. Keyboard setup and composition run in the original app process. */
public final class LearningActivity extends Activity {
    static final String EXTRA_READER_EN = "biz.tujia.ime.READER_EN";
    static final String EXTRA_READER_DARK = "biz.tujia.ime.READER_DARK";
    static final String EXTRA_DICTIONARY_ENTRY_ID = "biz.tujia.ime.DICTIONARY_ENTRY_ID";
    private static final int FIRST_DISCLOSURE = 1;
    private static final String DISCLOSURE_OPEN = "learning.disclosure.open";
    private static final String HISTORY = "learning.web.history";
    private static final String READER_PREFERENCES = "reader_disclosure";
    private static final String ACKNOWLEDGED = "acknowledged_2026_11_26";
    private AlertDialog externalDialog;
    private FrameLayout frame;
    private WebView browser;
    private boolean backPending;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        frame = new FrameLayout(this);
        setContentView(frame);
        fitSystemBars();
        if (getSharedPreferences(READER_PREFERENCES, MODE_PRIVATE).getBoolean(ACKNOWLEDGED, false)) {
            showReader(state == null ? null : state.getBundle(HISTORY));
        } else if (state == null || !state.getBoolean(DISCLOSURE_OPEN)) {
            Intent privacy = new Intent(this, PrivacyActivity.class).putExtra(PrivacyActivity.FIRST_LAUNCH, true);
            startActivityForResult(privacy, FIRST_DISCLOSURE);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::backFromReader);
        }
    }

    private void fitSystemBars() {
        setReaderSurface(systemDark());
        if (Build.VERSION.SDK_INT >= 35) {
            getWindow().setDecorFitsSystemWindows(false);
            frame.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets sides = insets.getInsets(WindowInsets.Type.systemBars()
                        | WindowInsets.Type.ime() | WindowInsets.Type.displayCutout());
                view.setPadding(sides.left, sides.top, sides.right, sides.bottom);
                return WindowInsets.CONSUMED;
            });
            frame.requestApplyInsets();
        }
    }

    private boolean systemDark() {
        return (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private void setReaderSurface(boolean dark) {
        int color = getColor(dark ? R.color.academy_dark_canvas : R.color.academy_light_canvas);
        frame.setBackgroundColor(color);
        int flags = dark ? 0 : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
        if (Build.VERSION.SDK_INT >= 26 && !dark) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().setStatusBarColor(color);
        if (Build.VERSION.SDK_INT >= 26) getWindow().setNavigationBarColor(color);
    }

    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != FIRST_DISCLOSURE) return;
        if (result != RESULT_OK) {
            finish();
            return;
        }
        // Acknowledgement belongs to this process. Only the native keyboard process
        // writes the separate diagnostics or personal dictionary preferences.
        getSharedPreferences(READER_PREFERENCES, MODE_PRIVATE).edit().putBoolean(ACKNOWLEDGED, true).apply();
        if (browser == null) showReader(null);
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (browser != null && intent != null && intent.hasExtra(EXTRA_DICTIONARY_ENTRY_ID)) {
            browser.loadUrl(LearningOrigin.dictionaryEntryUrl(intent.getStringExtra(EXTRA_DICTIONARY_ENTRY_ID)));
        }
    }

    @SuppressWarnings("deprecation")
    private void showReader(Bundle history) {
        frame.removeAllViews();
        try {
            browser = new WebView(this);
        } catch (RuntimeException | LinkageError unavailable) {
            showReaderError(null);
            return;
        }
        WebView.setWebContentsDebuggingEnabled((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0);
        WebSettings settings = browser.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setAllowFileAccessFromFileURLs(false);
        settings.setAllowUniversalAccessFromFileURLs(false);
        settings.setBlockNetworkLoads(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(false);
        settings.setJavaScriptCanOpenWindowsAutomatically(false);
        settings.setUseWideViewPort(true);
        settings.setBuiltInZoomControls(true);
        settings.setDisplayZoomControls(false);
        settings.setTextZoom(Math.round(100 * getResources().getConfiguration().fontScale));
        ServiceWorkerWebSettings workers = ServiceWorkerController.getInstance().getServiceWorkerWebSettings();
        workers.setBlockNetworkLoads(true);
        workers.setAllowFileAccess(false);
        workers.setAllowContentAccess(false);
        CookieManager.getInstance().setAcceptCookie(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(browser, false);
        browser.setBackgroundColor(getColor(R.color.reader_surface));
        browser.setWebViewClient(new LearningWebClient(this));
        frame.addView(browser);
        if (history == null || browser.restoreState(history) == null) {
            String entryId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_DICTIONARY_ENTRY_ID);
            browser.loadUrl(LearningOrigin.dictionaryEntryUrl(entryId));
        }
    }

    private void withReaderAppearance(BiConsumer<Boolean, Boolean> action) {
        WebView current = browser;
        if (isFinishing() || isDestroyed()) return;
        boolean systemEnglish = ReaderLanguage.english(this);
        if (current == null) {
            action.accept(systemDark(), systemEnglish);
            return;
        }
        // Read only the two presentation preferences. No Java object is exposed to scripts.
        String presentation = "(function(r){return {theme:r.getAttribute('data-theme'),language:r.lang};})(document.documentElement)";
        current.evaluateJavascript(presentation, value -> {
            if (current != browser || isFinishing() || isDestroyed()) return;
            boolean dark = systemDark();
            boolean english = systemEnglish;
            try {
                JSONObject appearance = new JSONObject(value == null ? "" : value);
                String theme = appearance.optString("theme");
                if ("dark".equals(theme) || "light".equals(theme)) dark = "dark".equals(theme);
                String language = appearance.optString("language");
                if ("en".equals(language) || language.startsWith("en-")) english = true;
                if ("zh".equals(language) || language.startsWith("zh-")) english = false;
            } catch (JSONException ignored) {
                // A document that is still opening uses the primary system locale and theme.
            }
            action.accept(dark, english);
        });
    }

    void refreshReaderTheme() { withReaderAppearance((dark, english) -> setReaderSurface(dark)); }

    private void openReaderScreen(Class<? extends Activity> screen) {
        withReaderAppearance((dark, english) -> startActivity(new Intent(this, screen)
                .putExtra(EXTRA_READER_DARK, dark).putExtra(EXTRA_READER_EN, english)));
    }

    void openKeyboard() { openReaderScreen(SetupActivity.class); }

    void openPrivacy() { openReaderScreen(PrivacyActivity.class); }

    void enableKeyboard() {
        try {
            startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS));
        } catch (ActivityNotFoundException | SecurityException unavailable) {
            Toast.makeText(this, R.string.ime_settings_unavailable, Toast.LENGTH_LONG).show();
        }
    }

    void showKeyboardPicker() {
        getSystemService(InputMethodManager.class).showInputMethodPicker();
    }

    void confirmExternalLink(Uri uri) {
        withReaderAppearance((dark, english) -> {
            if (externalDialog != null && externalDialog.isShowing()) return;
            Context strings = ReaderLanguage.localized(this, english);
            externalDialog = new AlertDialog.Builder(this, dark ? R.style.AcademyDialogDark : R.style.AcademyDialogLight)
                    .setTitle(strings.getText(R.string.external_link_title))
                    .setMessage(strings.getString(R.string.external_link_message, uri.toString()))
                    .setNegativeButton(strings.getText(android.R.string.cancel), null)
                    .setPositiveButton(strings.getText(R.string.external_link_open), (d, which) -> {
                        try {
                            Intent open = new Intent(Intent.ACTION_VIEW, uri).addCategory(Intent.CATEGORY_BROWSABLE);
                            // A verified app link may match VIEW. Select a browser while delivering
                            // the original HTTPS address, including its query and fragment.
                            open.setSelector(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER));
                            startActivity(open);
                        } catch (ActivityNotFoundException | SecurityException unavailable) {
                            Toast.makeText(this, R.string.no_link_handler, Toast.LENGTH_LONG).show();
                        }
                    }).create();
            externalDialog.show();
        });
    }

    @Override public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (focus && browser != null) {
            browser.evaluateJavascript("window.dispatchEvent(new Event('bizic-ime-status-change'))", null);
            refreshReaderTheme();
        }
    }

    void showReaderError(WebView failed) {
        if (failed != browser || isFinishing() || isDestroyed()) return;
        releaseReader();
        LinearLayout panel = new LinearLayout(this);
        panel.setGravity(Gravity.CENTER);
        panel.setOrientation(LinearLayout.VERTICAL);
        int margin = Math.round(24 * getResources().getDisplayMetrics().density);
        panel.setPadding(margin, margin, margin, margin);
        TextView message = new TextView(this);
        message.setText(R.string.reader_unavailable);
        message.setTextSize(17);
        panel.addView(message);
        Button retry = new Button(this);
        retry.setText(R.string.retry_reader);
        retry.setOnClickListener(v -> showReader(null));
        panel.addView(retry);
        Button keyboard = new Button(this);
        keyboard.setText(R.string.keyboard_settings);
        keyboard.setOnClickListener(v -> openKeyboard());
        panel.addView(keyboard);
        frame.addView(panel);
    }

    @Override protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean(DISCLOSURE_OPEN, !getSharedPreferences(READER_PREFERENCES, MODE_PRIVATE)
                .getBoolean(ACKNOWLEDGED, false));
        if (browser != null) {
            Bundle history = new Bundle();
            browser.saveState(history);
            state.putBundle(HISTORY, history);
        }
    }

    private void backFromReader() {
        WebView current = browser;
        if (current == null) {
            finish();
            return;
        }
        // A keyboard entry opens in its own task. On the initial word, Back
        // returns directly to the editor; deeper reader navigation retains its
        // ordinary history until the user reaches that word again.
        Intent intent = getIntent();
        if (intent != null && intent.hasExtra(EXTRA_DICTIONARY_ENTRY_ID)) {
            String id = intent.getStringExtra(EXTRA_DICTIONARY_ENTRY_ID);
            String address = current.getUrl();
            if (address == null || (id != null && LearningOrigin.dictionaryEntryUrl(id).equals(address))) {
                finish();
                return;
            }
        }
        if (backPending) return;
        backPending = true;
        current.evaluateJavascript("typeof window.bizicReaderBack === 'function' && window.bizicReaderBack() === true",
                handled -> {
                    if (current != browser) return;
                    backPending = false;
                    if (isFinishing() || isDestroyed() || !hasWindowFocus() || "true".equals(handled)) return;
                    if (current.canGoBack()) current.goBack();
                    else finish();
                });
    }

    @Override public void onBackPressed() { backFromReader(); }

    @Override protected void onPause() {
        if (browser != null) browser.onPause();
        super.onPause();
    }

    @Override protected void onResume() {
        super.onResume();
        if (browser != null) browser.onResume();
    }

    private void releaseReader() {
        WebView old = browser;
        browser = null;
        backPending = false;
        frame.removeAllViews();
        if (old != null) old.destroy();
    }

    @Override protected void onDestroy() {
        if (externalDialog != null) externalDialog.dismiss();
        releaseReader();
        super.onDestroy();
    }
}
