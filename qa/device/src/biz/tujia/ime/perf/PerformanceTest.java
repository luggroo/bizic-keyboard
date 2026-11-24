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
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

public final class PerformanceTest extends Instrumentation {
    private static final String PKG = "biz.tujia.ime";
    private UiAutomation ui;
    private PerfEditor host;
    private int keys, cold, warm;
    private String baseline;
    private AccessibilityNodeInfo root;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        keys = Integer.parseInt(args.getString("keys", "32"));
        cold = Integer.parseInt(args.getString("cold", "3"));
        warm = Integer.parseInt(args.getString("warm", "8"));
        baseline = args.getString("baseline");
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
            ui.setServiceInfo(info);
            host = (PerfEditor) startActivitySync(new Intent(getTargetContext(), PerfEditor.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            runOnMainSync(() -> host.show(host.words));
            for (int i = 0; i < cold; i++) coldRound(i);
            for (int i = 0; i < warm; i++) warmRound(i);
            shell("dumpsys gfxinfo " + PKG + " reset");
            timeSelections();
            timeDirectKeys();
            dump("gfx", shell("dumpsys gfxinfo " + PKG));
            dump("mem", shell("dumpsys meminfo " + PKG));
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

    private void coldRound(int i) throws Exception {
        String switched = shell("ime set " + baseline).trim();
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
        Rect mode = await("汉字记音", true);
        metric("cold_visible", i, System.nanoTime() - start);
        tap(mode);
        tap(await("g", true));
        tap(await("a", true));
        await("吃、咬，架", true);
        metric("cold_lexical", i, System.nanoTime() - start);
        tap(await("退格", true));
        tap(await("退格", true));
    }

    private void warmRound(int i) {
        runOnMainSync(host::hide);
        await("隐藏键盘", false);
        long[] start = new long[1];
        runOnMainSync(() -> { start[0] = System.nanoTime(); host.show(host.words); });
        await("汉字记音", true);
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
            measure("letter", i, a, host.direct, text.toString());
        }
    }

    private void measure(String name, int i, Rect key, PerfEditor.MeasuredField field, String expected) throws Exception {
        PerfEditor.Sample sample = new PerfEditor.Sample(expected);
        runOnMainSync(() -> field.sample = sample);
        long start = tap(key);
        if (!sample.drawn.await(15, TimeUnit.SECONDS)) {
            String[] actual = new String[1];
            runOnMainSync(() -> actual[0] = field.getText().toString());
            throw new AssertionError(name + " not drawn within 15 s after touch " + i
                    + ", editor changed=" + (sample.changedAt != 0) + ", expected=[" + expected + "], actual=[" + actual[0] + "]");
        }
        if (i >= 0) {
            metric(name + "_text", i, sample.changedAt - start);
            metric(name + "_draw", i, sample.drawnAt - start);
        }
    }

    // Return the guest monotonic timestamp immediately before ACTION_UP enters Android's dispatcher.
    private long tap(Rect r) {
        long down = SystemClock.uptimeMillis();
        send(r, down, MotionEvent.ACTION_DOWN);
        SystemClock.sleep(18);
        long up = System.nanoTime();
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
        throw new AssertionError("Timed out on " + label + " present=" + present);
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
