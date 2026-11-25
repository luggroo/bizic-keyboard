package biz.tujia.ime.e2e;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.SystemClock;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises public editor contracts through actual taps on the separately installed IME. */
final class HostCompatibility {
    private final KeyboardTest keys;
    private final EditorActivity host;

    HostCompatibility(KeyboardTest keys, EditorActivity host) {
        this.keys = keys;
        this.host = host;
    }

    void run() throws Exception {
        editorAction(EditorInfo.IME_ACTION_SEARCH, "搜索");
        editorAction(EditorInfo.IME_ACTION_SEND, "发送");
        editorAction(EditorInfo.IME_ACTION_NEXT, "下一项");
        editorAction(EditorInfo.IME_ACTION_PREVIOUS, "上一项");
        passwordFields();
        webEditors();
    }

    private void configure(int inputType, int action, boolean multiline) {
        keys.runOnMainSync(() -> host.nativeScenario(inputType, action, multiline));
        keys.waitForIdleSync();
    }

    private void commitEating() {
        keys.press("谐音字");
        keys.type("ga");
        keys.pressCandidate("吃、咬，架", false);
    }

    void window() {
        keys.stage("Native multiline editor, newline, hide and reopen without losing content");
        configure(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION, true);
        keys.tapField(host.first);
        commitEating();
        keys.press("换行");
        keys.type("bangricai");
        keys.pressCandidate("中午，半日猜", false);
        keys.expectText(host.first, "架\n半日猜");
        keys.assertEditorAboveKeyboard(host.first, "打字");
        keys.press("隐藏键盘");
        keys.waitAbsent("隐藏键盘");
        keys.expectText(host.first, "架\n半日猜");
        keys.tapField(host.first);
        keys.waitForNode("隐藏键盘", false);
        keys.stage("Landscape preserves an editable host above a fixed handwriting pad");
        try {
            rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE);
            keys.tapField(host.first);
            keys.press("手写");
            keys.setHandwritingOutput(true);
            keys.assertEditorAboveKeyboard(host.first, "手写");
            Rect before = keys.waitForNode("汉字手写区", false);
            keys.drawThree(before);
            keys.expectText(host.first, "架\n半日猜三");
            if (!before.equals(keys.waitForNode("汉字手写区", false)))
                throw new AssertionError("Landscape handwriting pad moved");
        } finally {
            rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT);
        }
        keys.expectText(host.first, "架\n半日猜三");
    }

    private void rotate(int requested, int expected) {
        keys.runOnMainSync(() -> host.setRequestedOrientation(requested));
        await("screen orientation " + expected, () -> host.getResources().getConfiguration().orientation == expected);
        keys.waitForIdleSync();
    }

    private void editorAction(int action, String caption) {
        keys.stage("Native editor action and accessibility label: " + caption);
        configure(InputType.TYPE_CLASS_TEXT, action, false);
        EditText active = action == EditorInfo.IME_ACTION_PREVIOUS ? host.second : host.first;
        keys.tapField(active);
        keys.waitForNode(caption, false);
        commitEating();
        keys.expectText(active, "架");
        // The key must announce the same available action the host requested.
        keys.assertBottomRowAboveNavigation(caption);
        keys.pressAction(caption);
        await(caption + " callback", () -> host.lastEditorAction == action);
        if (!"架".equals(host.lastSubmitted)) throw new AssertionError(caption + " callback lost input");
        if (action == EditorInfo.IME_ACTION_NEXT || action == EditorInfo.IME_ACTION_PREVIOUS) {
            EditText destination = active == host.first ? host.second : host.first;
            await(caption + " focus", destination::hasFocus);
            keys.waitForNode("完成", false);
            keys.type("hxif");
            keys.pressCandidate(KeyboardTest.WALK_HAN, false);
            keys.expectText(destination, "诶");
            keys.expectText(active, "架");
        }
    }

    private void passwordFields() {
        keys.stage("Masked native text and numeric passwords use direct input");
        for (boolean number : new boolean[] {false, true}) {
            configure(number ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD
                    : InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    EditorInfo.IME_ACTION_DONE, false);
            keys.tapField(host.first);
            String first = number ? "1" : "g", second = number ? "2" : "a";
            keys.waitForNode(number ? "数字" : "英文直输", false);
            keys.awaitKeyboardAtRest();
            keys.press(first);
            keys.press(second);
            String expected = first + second;
            keys.expectText(host.first, expected);
            if (keys.pollNode("设置", false, 200) != null || keys.pollNode("吃、咬，架", false, 200) != null)
                throw new AssertionError("Lexical prediction visible on a password field");
            AtomicReference<String> transformed = new AtomicReference<>();
            keys.runOnMainSync(() -> transformed.set(host.first.getTransformationMethod()
                    .getTransformation(host.first.getText(), host.first).toString()));
            if (expected.equals(transformed.get())) throw new AssertionError("Native password is unmasked");
        }
    }

    void webEditors() throws Exception {
        keys.stage("Android System WebView: input, textarea, DOM focus change and password");
        openWeb();
        tapDom("word");
        commitEating();
        expectDom("word", "架");
        keys.type("ga");
        tapDom("note");
        keys.waitAbsent("待选拼写 ga");
        keys.press("谐音字");
        keys.type("bangricai");
        keys.pressCandidate("中午，半日猜", false);
        expectDom("note", "半日猜");
        keys.press("换行");
        keys.type("hxif");
        keys.pressCandidate(KeyboardTest.WALK_HAN, false);
        expectDom("note", "半日猜\n诶");
        expectDom("word", "架");
        keys.press("隐藏键盘");
        keys.waitAbsent("隐藏键盘");
        tapDom("secret");
        keys.press("g");
        keys.press("a");
        expectDom("secret", "ga");
        if (keys.pollNode("设置", false, 200) != null)
            throw new AssertionError("Lexical mode on a WebView password");
    }

    void ipaWeb() throws Exception {
        keys.stage("IPA completion in a WebView editor and password isolation");
        openWeb();
        tapDom("note");
        keys.press("音标");
        keys.press("m");
        keys.press("a");
        expectDom("note", "ma");
        keys.press("补全 奶，mã²¹");
        expectDom("note", "mã²¹");
        keys.press("换行");
        keys.press("n");
        expectDom("note", "mã²¹\nn");
        tapDom("secret");
        keys.waitForNode("英文直输", false);
        keys.press("m");
        keys.press("a");
        keys.waitAbsent("补全 奶，mã²¹");
        expectDom("secret", "ma");
        expectDom("note", "mã²¹\nn");
    }

    private void openWeb() throws Exception {
        keys.runOnMainSync(host::webScenario);
        long deadline = SystemClock.uptimeMillis() + 20000;
        while (!"true".equals(js("!!document.getElementById('secret')"))) {
            if (SystemClock.uptimeMillis() > deadline) throw new AssertionError("WebView document did not load");
            SystemClock.sleep(80);
        }
        CountDownLatch painted = new CountDownLatch(1);
        keys.runOnMainSync(() -> host.web.postVisualStateCallback(1L, new android.webkit.WebView.VisualStateCallback() {
            @Override public void onComplete(long requestId) { painted.countDown(); }
        }));
        if (!painted.await(10, TimeUnit.SECONDS)) throw new AssertionError("WebView did not present the form");
    }

    private String js(String expression) throws Exception {
        AtomicReference<String> value = new AtomicReference<>();
        CountDownLatch complete = new CountDownLatch(1);
        keys.runOnMainSync(() -> host.web.evaluateJavascript(expression, text -> {
            value.set(text);
            complete.countDown();
        }));
        if (!complete.await(5, TimeUnit.SECONDS)) throw new AssertionError("WebView evaluation timed out");
        return value.get();
    }

    private void tapDom(String id) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 10000;
        String previous = "", target = "";
        long stableAt = 0;
        int x = 0, y = 0;
        do {
            target = js("(function(){var r=document.getElementById('" + id + "').getBoundingClientRect(),"
                    + "x=r.left+r.width/2,y=r.top+r.height/2,e=document.elementFromPoint(x,y);"
                    + "return [e&&e.id,x*devicePixelRatio,y*devicePixelRatio]})()");
            JSONArray point = new JSONArray(target);
            Rect area = new Rect();
            int[] origin = new int[2];
            keys.runOnMainSync(() -> {
                host.web.getLocationOnScreen(origin);
                if (host.hasWindowFocus() && host.web.getLocalVisibleRect(area)) area.offset(origin[0], origin[1]);
            });
            x = origin[0] + (int)Math.round(point.getDouble(1));
            y = origin[1] + (int)Math.round(point.getDouble(2));
            String observed = target + area.toShortString();
            long now = SystemClock.uptimeMillis();
            if (!host.imeAnimating && id.equals(point.optString(0)) && area.contains(x, y)) {
                if (previous.equals(observed) && now - stableAt >= 250) break;
                if (!previous.equals(observed)) { stableAt = now; previous = observed; }
            } else previous = "";
            if (now > deadline) throw new AssertionError("DOM field did not settle: " + id + ", target=" + observed);
            SystemClock.sleep(50);
        } while (true);
        keys.tap(x, y);
        deadline = SystemClock.uptimeMillis() + 6500;
        while (!JSONObject.quote(id).equals(js("document.activeElement.id"))) {
            if (SystemClock.uptimeMillis() > deadline) throw new AssertionError("Touch did not focus DOM " + id
                    + " at " + x + "," + y + "; target=" + target + ", focus=" + js("document.activeElement.id"));
            SystemClock.sleep(65);
        }
        keys.awaitKeyboardAtRest();
    }

    private void expectDom(String id, String text) throws Exception {
        long deadline = SystemClock.uptimeMillis() + 8000;
        String got;
        do {
            got = js("document.getElementById('" + id + "').value");
            if (JSONObject.quote(text).equals(got)) return;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Unexpected DOM value for " + id + ": " + got + "; DOM focus/content="
                + js("[document.activeElement.id, document.getElementById('word').value, document.getElementById('note').value]"));
    }

    private interface Condition { boolean ready(); }

    private void await(String message, Condition condition) {
        long deadline = SystemClock.uptimeMillis() + 10000;
        do {
            if (condition.ready()) return;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Timed out waiting for " + message);
    }
}
