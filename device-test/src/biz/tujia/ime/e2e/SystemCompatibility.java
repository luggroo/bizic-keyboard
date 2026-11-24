package biz.tujia.ime.e2e;

import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.ContactsContract;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

/** Optional checks against the stock applications installed in the AOSP system image. */
final class SystemCompatibility {
    private static final String CONTACTS = "com.android.contacts";
    private static final String BROWSER = "org.chromium.webview_shell";
    private final KeyboardTest keys;

    SystemCompatibility(KeyboardTest keys) { this.keys = keys; }

    void run() {
        keys.stage("Preinstalled AOSP Contacts: enter a name in an unsaved new contact");
        launch(new Intent(Intent.ACTION_INSERT, ContactsContract.Contacts.CONTENT_URI).setPackage(CONTACTS));
        Rect field = appNode(CONTACTS, "editable", "");
        keys.tap(field.centerX(), field.centerY());
        keys.press("谐音字");
        keys.type("bangricai");
        keys.pressCandidate("中午，半日猜", false);
        appNode(CONTACTS, "editable", "半日猜");
        keys.press("隐藏键盘");
        // Close via the editor's cancellation control; there must be no save action.
        Rect close = appNode(CONTACTS, "cancel", "");
        keys.tap(close.centerX(), close.centerY());
        discardIfAsked();

        keys.stage("Preinstalled Chromium WebView Shell: direct text into the browser address bar");
        Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse("about:blank"));
        browser.setClassName(BROWSER, BROWSER + ".WebViewBrowserActivity");
        launch(browser);
        Rect url = appNode(BROWSER, "editable", "");
        keys.tap(url.centerX(), url.centerY());
        appNode(BROWSER, "select-address", "");
        keys.press("g");
        keys.press("a");
        appNode(BROWSER, "editable", "ga");
        if (keys.pollNode("更多", false, 250) != null)
            throw new AssertionError("URL field exposed lexical conversion mode");
        keys.press("隐藏键盘");
        keys.stage("System checks completed without submitting, navigating or saving");
    }

    private void launch(Intent intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        keys.getContext().startActivity(intent);
    }

    private void discardIfAsked() {
        long end = SystemClock.uptimeMillis() + 3000;
        do {
            Rect button = locate(CONTACTS, "discard", "");
            if (button != null) { keys.tap(button.centerX(), button.centerY()); return; }
            if (locate(CONTACTS, "editable", "半日猜") == null) return;
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Contact editor still shows an undiscarded draft");
    }

    private Rect appNode(String pkg, String type, String value) {
        long end = SystemClock.uptimeMillis() + 18000;
        do {
            Rect r = locate(pkg, type, value);
            if (r != null) return r;
            SystemClock.sleep(120);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Missing external control: " + pkg + " " + type + " " + value);
    }

    private Rect locate(String pkg, String type, String value) {
        for (AccessibilityWindowInfo window : keys.getUiAutomation().getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                if (!pkg.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) continue;
                Rect r = visit(root, type, value);
                if (r != null) return r;
            } finally { root.recycle(); }
        }
        return null;
    }

    private Rect visit(AccessibilityNodeInfo node, String type, String value) {
        String text = node.getText() == null ? "" : node.getText().toString();
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        String id = node.getViewIdResourceName() == null ? "" : node.getViewIdResourceName();
        boolean matches;
        if (type.equals("editable")) {
            matches = node.isEditable() && (value.isEmpty() || text.equals(value));
        } else if (type.equals("select-address")) {
            matches = node.isEditable() && node.isFocused() && node.isVisibleToUser();
            if (matches) {
                Bundle range = new Bundle();
                range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0);
                range.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, text.length());
                matches = node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, range);
            }
        } else if (type.equals("cancel")) {
            matches = (desc.equals("取消") || desc.equals("关闭") || desc.equals("向上导航")
                    || desc.equals("Navigate up") || desc.equals("Cancel") || id.endsWith("/menu_cancel"));
            // The stock Contacts editor uses the toolbar navigation image for its discard flow.
            Rect bounds = new Rect(); node.getBoundsInScreen(bounds);
            matches |= "android.widget.ImageButton".contentEquals(node.getClassName()) && bounds.left < 20 && bounds.top < 90;
        } else {
            matches = text.equals("放弃") || text.equals("舍弃") || text.equals("不保存") || text.equalsIgnoreCase("discard");
        }
        if (matches && node.isVisibleToUser() && node.isEnabled()) {
            Rect r = new Rect(); node.getBoundsInScreen(r); if (!r.isEmpty()) return r;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try { Rect r = visit(child, type, value); if (r != null) return r; }
            finally { child.recycle(); }
        }
        return null;
    }
}
