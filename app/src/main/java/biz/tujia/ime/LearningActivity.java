package biz.tujia.ime;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.res.Configuration;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.webkit.CookieManager;
import android.webkit.ServiceWorkerController;
import android.webkit.ServiceWorkerWebSettings;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Contains the shared reader. Keyboard setup and composition run in the original app process. */
public final class LearningActivity extends Activity {
    private static final String HISTORY = "learning.web.history";
    private FrameLayout frame;
    private WebView browser;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        frame = new FrameLayout(this);
        setContentView(frame);
        fitSystemBars();
        showReader(state == null ? null : state.getBundle(HISTORY));
        if (Build.VERSION.SDK_INT >= 33) {
            getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::backFromReader);
        }
    }

    private void fitSystemBars() {
        int surface = getColor(R.color.reader_surface);
        frame.setBackgroundColor(surface);
        boolean light = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                != Configuration.UI_MODE_NIGHT_YES;
        int flags = light ? View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR : 0;
        if (Build.VERSION.SDK_INT >= 26 && light) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        getWindow().getDecorView().setSystemUiVisibility(flags);
        getWindow().setStatusBarColor(surface);
        if (Build.VERSION.SDK_INT >= 26) getWindow().setNavigationBarColor(surface);
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
        if (history == null || browser.restoreState(history) == null) browser.loadUrl(LearningOrigin.START_URL);
    }

    void openKeyboard() {
        startActivity(new Intent(this, SetupActivity.class));
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
        if (browser != null) {
            Bundle history = new Bundle();
            browser.saveState(history);
            state.putBundle(HISTORY, history);
        }
    }

    private void backFromReader() {
        if (browser != null && browser.canGoBack()) browser.goBack();
        else finish();
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
        frame.removeAllViews();
        if (old != null) old.destroy();
    }

    @Override protected void onDestroy() {
        OptionalResources.finish(this);
        releaseReader();
        super.onDestroy();
    }
}
