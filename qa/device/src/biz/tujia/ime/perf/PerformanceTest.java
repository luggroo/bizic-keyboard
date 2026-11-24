package biz.tujia.ime.perf;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class PerformanceTest extends Instrumentation {
    private static final String PKG = "biz.tujia.ime";
    private UiAutomation ui;
    private PerfEditor host;
    private int keys, cold, warm, pace, misses;
    private boolean onlyLetters, checkGestures, freshKeys, keysOnly, startupOnly;
    private volatile long clickEvents, lastClickTime;
    private long sentDown, sentUp;
    private volatile String lastClick = "";
    private String baseline;
    private AccessibilityNodeInfo root;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        keys = Integer.parseInt(args.getString("keys", "32"));
        cold = Integer.parseInt(args.getString("cold", "3"));
        warm = Integer.parseInt(args.getString("warm", "8"));
        baseline = args.getString("baseline");
        onlyLetters = "letters".equals(args.getString("profile"));
        checkGestures = "gestures".equals(args.getString("profile"));
        keysOnly = "keys".equals(args.getString("profile"));
        startupOnly = "startup".equals(args.getString("profile"));
        freshKeys = "true".equals(args.getString("fresh"));
        pace = Integer.parseInt(args.getString("pace", "0"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            if (baseline == null || !baseline.matches("[A-Za-z0-9_./]+") || baseline.startsWith(PKG + "/"))
                throw new IllegalArgumentException("Pass another enabled IME component as baseline");
            ui = getUiAutomation();
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.notificationTimeout = 0;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            ui.setServiceInfo(info);
            ui.setOnAccessibilityEventListener(e -> {
                if (e.getEventType() == AccessibilityEvent.TYPE_VIEW_CLICKED && PKG.contentEquals(e.getPackageName())) {
                    lastClick = String.valueOf(e.getContentDescription()) + " " + e.getText();
                    lastClickTime = e.getEventTime();
                    clickEvents++;
                }
            });
            openHost();
            if (onlyLetters || checkGestures || keysOnly) {
                shell("ime set biz.tujia.ime/.TujiaIme");
                if (keysOnly) { runOnMainSync(() -> host.show(host.words)); selectHan(awaitTypingHeader()); }
            } else {
                if (cold == 0) {
                    shell("ime set biz.tujia.ime/.TujiaIme");
                    runOnMainSync(() -> host.show(host.words));
                    showKnownWord(awaitTypingHeader());
                    clearKnownDraft();
                }
                for (int i = 0; i < cold; i++) coldRound(i);
                for (int i = 0; i < warm; i++) warmRound(i);
            }
            shell("dumpsys gfxinfo " + PKG + " reset");
            if (checkGestures) verifyGestures();
            else if (!startupOnly) {
                if (!onlyLetters) timeSelections();
                timeDirectKeys();
            }
            dump("gfx", shell("dumpsys gfxinfo " + PKG));
            dump("mem", shell("dumpsys meminfo " + PKG));
            if (misses != 0) throw new AssertionError(misses + " input(s) missing from editor");
            result.putString("outcome", "PASS");
            code = Activity.RESULT_OK;
        } catch (Throwable error) {
            result.putString("failure", Log.getStackTraceString(error));
        } finally {
            if (root != null) root.recycle();
            if (host != null) runOnMainSync(host::finish);
            finish(code, result);
        }
    }

    private void verifyGestures() {
        runOnMainSync(() -> host.show(host.direct));
        await("英文直输", true);
        Rect a = await("a", true);
        tap(a);
        expectText(host.direct, "a");
        long down = SystemClock.uptimeMillis();
        send(a, down, MotionEvent.ACTION_DOWN);
        Rect outside = new Rect(a);
        outside.offset(a.width() * 2, 0);
        send(outside, down, MotionEvent.ACTION_MOVE);
        send(outside, down, MotionEvent.ACTION_UP);
        down = SystemClock.uptimeMillis();
        send(a, down, MotionEvent.ACTION_DOWN);
        send(a, down, MotionEvent.ACTION_CANCEL);
        tap(a);
        expectText(host.direct, "aa");
        Rect erase = await("退格", true);
        tap(erase); tap(erase);
        expectText(host.direct, "");

        runOnMainSync(() -> host.show(host.words));
        selectHan(awaitTypingHeader());
        hold(await("a", true));
        tap(await("ā", true));
        await("待选拼写 ā", true);
        tap(await("退格", true));
        tap(await("g", true));
        tap(await("a", true));
        hold(await("吃、咬，架", true));
        await("返回候选", true);
        expectText(host.words, "");
        tap(await("返回候选", true));
        tap(await("吃、咬，架", true));
        expectText(host.words, "架");
        tap(await("搜索", true));
        long end = SystemClock.uptimeMillis() + 8000;
        while (host.editorActions == 0 && SystemClock.uptimeMillis() < end) SystemClock.sleep(20);
        if (host.editorActions != 1 || host.lastAction != EditorInfo.IME_ACTION_SEARCH)
            throw new AssertionError("Search action calls=" + host.editorActions + " id=" + host.lastAction);
        dump("gestures", "PASS slide-out, cancellation, repeated delete, held vowel, held candidate, candidate commit and search action");
    }

    private void hold(Rect r) {
        long down = SystemClock.uptimeMillis();
        send(r, down, MotionEvent.ACTION_DOWN);
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250);
        send(r, down, MotionEvent.ACTION_UP);
    }

    private void expectText(PerfEditor.MeasuredField field, String expected) {
        long end = SystemClock.uptimeMillis() + 8000;
        String[] actual = new String[1];
        do {
            runOnMainSync(() -> actual[0] = field.getText().toString());
            if (expected.equals(actual[0])) return;
            SystemClock.sleep(20);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Expected [" + expected + "] but editor contained [" + actual[0] + "]");
    }

    private void openHost() {
        if (root != null) { root.recycle(); root = null; }
        if (host != null) runOnMainSync(host::finish);
        host = (PerfEditor) startActivitySync(new Intent(getTargetContext(), PerfEditor.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
        waitForIdleSync();
        runOnMainSync(() -> host.show(host.words));
    }

    private String imeWindowPackage() {
        for (AccessibilityWindowInfo w : ui.getWindows()) {
            try {
                if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo current = w.getRoot();
                if (current != null) {
                    try { return String.valueOf(current.getPackageName()); }
                    finally { current.recycle(); }
                }
            } finally { w.recycle(); }
        }
        return "";
    }

    private void coldRound(int i) throws Exception {
        String switched = shell("ime set " + baseline).trim();
        openHost();
        String basePackage = baseline.substring(0, baseline.indexOf('/'));
        long end = SystemClock.uptimeMillis() + 15000;
        while (!basePackage.equals(imeWindowPackage()) && SystemClock.uptimeMillis() < end) {
            runOnMainSync(() -> host.show(host.words));
            SystemClock.sleep(80);
        }
        if (!basePackage.equals(imeWindowPackage())) throw new AssertionError("Baseline keyboard not served: " + imeWindowPackage());
        String stopped = shell("am force-stop " + PKG).trim();
        String pid = "";
        for (int attempt = 0; attempt < 20; attempt++) {
            pid = shell("pidof " + PKG).trim();
            if (pid.isEmpty()) break;
            SystemClock.sleep(50);
        }
        if (!pid.isEmpty()) throw new AssertionError("Cold process still present: " + pid + ", switch=" + switched + ", stop=" + stopped);
        long start = System.nanoTime();
        shell("ime set biz.tujia.ime/.TujiaIme");
        runOnMainSync(() -> host.show(host.words));
        Rect mode = awaitTypingHeader();
        metric("cold_visible", i, System.nanoTime() - start);
        showKnownWord(mode);
        metric("cold_lexical", i, System.nanoTime() - start);
        clearKnownDraft();
    }

    private Rect awaitTypingHeader() {
        long limit = SystemClock.uptimeMillis() + 90000;
        do {
            Rect visible = find("打字");
            if (visible != null) return visible;
            visible = find("汉字记音");  // 0.3.1/0.3.2 persistent tabs
            if (visible != null) return visible;
            SystemClock.sleep(12);
        } while (SystemClock.uptimeMillis() < limit);
        throw new AssertionError("Typing header not visible; IME=" + imeWindowPackage());
    }

    private void selectHan(Rect mode) {
        tap(mode);
        if (find("更多") != null) {
            tap(await("更多", true));
            tap(await("汉字记音", true));
        }
    }

    private void showKnownWord(Rect mode) {
        selectHan(mode);
        tap(await("g", true));
        tap(await("a", true));
        await("吃、咬，架", true);
    }

    private void clearKnownDraft() {
        tap(await("退格", true));
        tap(await("退格", true));
    }

    private void warmRound(int i) {
        runOnMainSync(host::hide);
        await("隐藏键盘", false);
        long[] start = new long[1];
        runOnMainSync(() -> { start[0] = System.nanoTime(); host.show(host.words); });
        awaitTypingHeader();
        metric("warm_visible", i, System.nanoTime() - start[0]);
    }

    private void timeSelections() throws Exception {
        Rect g = await("g", true), a = await("a", true);
        StringBuilder text = new StringBuilder();
        for (int i = -2; i < keys; i++) {
            tap(g);
            tap(a);
            Rect choice = await("吃、咬，架", true);
            text.append("架");
            measure("choice", i, choice, host.words, text.toString());
        }
    }

    private void timeDirectKeys() throws Exception {
        runOnMainSync(() -> host.show(host.direct));
        await("英文直输", true);
        Rect a = await("a", true);
        StringBuilder text = new StringBuilder();
        for (int i = -2; i < keys; i++) {
            text.append('a');
            if (pace > 0) SystemClock.sleep(pace);
            String observed = measure("letter", i, freshKeys ? await("a", true) : a, host.direct, text.toString());
            if (observed != null) { text.setLength(0); text.append(observed); }
        }
    }

    private String measure(String name, int i, Rect key, PerfEditor.MeasuredField field, String expected) throws Exception {
        PerfEditor.Sample sample = new PerfEditor.Sample(expected);
        int[] calls = new int[1];
        long previousClicks = clickEvents;
        runOnMainSync(() -> { field.sample = sample; calls[0] = field.commitCalls; });
        long start = tap(key);
        if (!sample.drawn.await(onlyLetters ? 3 : 15, TimeUnit.SECONDS)) {
            String[] actual = new String[1], state = new String[1];
            runOnMainSync(() -> {
                actual[0] = field.getText().toString();
                state[0] = "commitCalls=" + (field.commitCalls - calls[0]) + ", lastCommit=" + field.lastCommit
                        + ", lastAccepted=" + field.lastAccepted + ", editorFocus=" + field.hasFocus()
                        + ", windowFocus=" + field.hasWindowFocus() + ", selection=" + field.getSelectionStart() + "," + field.getSelectionEnd();
            });
            Rect liveKey = find(name.equals("letter") ? "a" : "吃、咬，架");
            dump("missing", name + "," + i + ", changed=" + (sample.changedAt != 0) + ", expected=[" + expected + "], actual=[" + actual[0]
                    + "], " + state[0] + ", sentAt=" + key + ", currentKey=" + liveKey
                    + ", down=" + sentDown + ", up=" + sentUp + ", IMEclickEvents=" + (clickEvents - previousClicks) + ", lastClickTime=" + lastClickTime + ", lastClick=" + lastClick);
            misses++;
            if (!name.equals("letter")) throw new AssertionError("Expected selection was not drawn");
            return actual[0];
        }
        if (i >= 0) {
            metric(name + "_text", i, sample.changedAt - start);
            metric(name + "_draw", i, sample.drawnAt - start);
        }
        return null;
    }

    // Return the guest monotonic timestamp immediately before ACTION_UP enters Android's dispatcher.
    private long tap(Rect r) {
        long down = sentDown = SystemClock.uptimeMillis();
        send(r, down, MotionEvent.ACTION_DOWN);
        SystemClock.sleep(18);
        long up = System.nanoTime();
        sentUp = SystemClock.uptimeMillis();
        send(r, down, MotionEvent.ACTION_UP);
        return up;
    }

    private void send(Rect r, long down, int action) {
        MotionEvent event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, r.centerX(), r.centerY(), 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        try {
            if (!ui.injectInputEvent(event, true)) throw new AssertionError("Rejected touch");
        } finally { event.recycle(); }
    }

    private Rect await(String label, boolean present) {
        long end = SystemClock.uptimeMillis() + 90000;
        do {
            Rect r = find(label);
            if ((r != null) == present) return r;
            SystemClock.sleep(12);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Timed out on " + label + " present=" + present + ", IME window package=" + imeWindowPackage());
    }

    private Rect find(String label) {
        if (root != null && !root.refresh()) { root.recycle(); root = null; }
        if (root == null) {
            for (AccessibilityWindowInfo w : ui.getWindows()) {
                try {
                    if (w.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                    AccessibilityNodeInfo candidate = w.getRoot();
                    if (candidate == null) continue;
                    if (PKG.contentEquals(candidate.getPackageName())) { root = candidate; break; }
                    candidate.recycle();
                } finally { w.recycle(); }
            }
        }
        if (root == null) return null;
        for (AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByText(label)) {
            try {
                if (!n.refresh() || !n.isVisibleToUser()) continue;
                if (!label.equals(String.valueOf(n.getContentDescription())) && !label.equals(String.valueOf(n.getText()))) continue;
                Rect bounds = new Rect();
                n.getBoundsInScreen(bounds);
                if (!bounds.isEmpty()) return bounds;
            } finally { n.recycle(); }
        }
        return null;
    }

    private String shell(String cmd) throws Exception {
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(ui.executeShellCommand(cmd))) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] bytes = new byte[8192];
            int len;
            while ((len = in.read(bytes)) != -1) out.write(bytes, 0, len);
            return out.toString("UTF-8");
        }
    }

    private void dump(String name, String text) {
        Bundle data = new Bundle();
        data.putString(name, text);
        sendStatus(1, data);
    }

    private void metric(String name, int sample, long nanos) {
        if (nanos < 0) throw new AssertionError("Unexpected timestamp order");
        dump("sample", String.format(Locale.US, "%s,%d,%.3f", name, sample, nanos / 1e6));
    }
}
