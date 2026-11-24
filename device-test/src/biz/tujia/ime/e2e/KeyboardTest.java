package biz.tujia.ime.e2e;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.SystemClock;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.ScaleXSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.EditText;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Cross-process test. All key and candidate selection is through the installed soft keyboard. */
public final class KeyboardTest extends Instrumentation {
    private static final String IME_PACKAGE = "biz.tujia.ime";
    private static final long WAIT_MS = 15000;
    private UiAutomation ui;
    private AccessibilityNodeInfo imeRoot;
    private EditorActivity host;
    private String suite;
    private boolean captureFailure;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        suite = args == null ? "all" : args.getString("suite", "all");
        captureFailure = args != null && "1".equals(args.getString("captureFailure"));
        start();
    }

    @Override public void onStart() {
        Bundle result = new Bundle();
        int code = Activity.RESULT_CANCELED;
        try {
            ui = getUiAutomation();
            AccessibilityServiceInfo info = ui.getServiceInfo();
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                    | AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS;
            info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
            info.notificationTimeout = 0;
            ui.setServiceInfo(info);

            Intent open = new Intent(getTargetContext(), EditorActivity.class);
            open.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            host = (EditorActivity) startActivitySync(open);
            waitForIdleSync();
            if (suite.equals("all") || suite.equals("core")) {
                testFirstInput();
                String originalStyle = setStyleFromLauncher("叶氏拼音");
                try {
                    testEditor();
                    testSpellingStyle();
                    testJoinedForms();
                    testApicalVowels();
                    testContinuousHandwriting();
                } catch (Throwable failure) {
                    saveFailureFrame(result);
                    throw failure;
                } finally {
                    setStyleFromLauncher(originalStyle);
                }
            }
            if (suite.equals("all") || suite.equals("host")) new HostCompatibility(this, host).run();
            if (suite.equals("all") || suite.equals("window")) new HostCompatibility(this, host).window();
            if (suite.equals("web")) new HostCompatibility(this, host).webEditors();
            if (suite.equals("system")) new SystemCompatibility(this).run();
            if (suite.equals("sounds") || suite.equals("core") || suite.equals("all")) testNextSounds();
            result.putString("outcome", "PASS");
            code = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("outcome", "FAIL");
            result.putString("failure", Log.getStackTraceString(failure));
            saveFailureFrame(result);
        } finally {
            if (imeRoot != null) imeRoot.recycle();
            if (host != null) runOnMainSync(host::finish);
            finish(code, result);
        }
    }

    private void saveFailureFrame(Bundle result) {
        if (!captureFailure || ui == null || result.containsKey("failureFrame")) return;
        android.graphics.Bitmap shot = ui.takeScreenshot();
        if (shot == null) return;
        java.io.File dir = getTargetContext().getExternalFilesDir(null);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(dir, "failure.png"))) {
            if (shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)) result.putString("failureFrame", "failure.png");
        } catch (java.io.IOException e) { result.putString("screenshotError", e.toString()); }
        finally { shot.recycle(); }
    }

    private void testFirstInput() {
        stage("First input after IME startup");
        tapField(host.first);
        press("汉字记音");
        boolean loadingAtFirstKey = findNode("正在加载词库，可先输入拼写", false) != null;
        type("ga");
        boolean buffered = findNode("正在加载词库，可先输入拼写", false) != null;
        expectText(host.first, "");
        // No more keyboard events: the model must fill this exact live draft on its own.
        Rect match = pollNode("吃、咬，架", false, 90000);
        if (match == null) throw new AssertionError("First draft never received candidates");
        waitForNode("待选拼写 ga", false);
        expectText(host.first, "");
        awaitKeyboardAtRest();
        match = waitForNode("吃、咬，架", false);
        tap(match.centerX(), match.centerY());
        expectText(host.first, "架");
        stage("First draft committed; loading visible at first key=" + loadingAtFirstKey
                + ", after typing=" + buffered);
        runOnMainSync(() -> host.first.setText(""));
        tapField(host.second);
    }

    private void testEditor() {
        tapField(host.first);
        press("汉字记音");
        assertEditorAboveKeyboard(host.first, "土家语拼写");

        // Three-syllable word plus one more word. Selecting the prefix must leave hxif.
        type("marmardiexhxif");
        stage("Longest prefix and uncommitted suffix");
        expectFirst("女人，麻麻爹");
        pressCandidate("女人，麻麻爹", false);
        expectText(host.first, "麻麻爹");
        waitForNode("待选拼写 hxif", false);
        pressCandidate("走，诶", false);
        expectText(host.first, "麻麻爹诶");

        type("jir");
        expectFirst("脚，及");
        pressCandidate("脚，及", false);
        expectText(host.first, "麻麻爹诶及");

        // A new editor is a new composing session.
        type("jir");
        tapField(host.second);
        waitAbsent("待选拼写 jir");
        press("土家语拼写");
        type("ngafyergaf");
        stage("Whole sentence");
        if (!pageCards("，").get(0).name.endsWith("，ngaf yer gaf"))
            throw new AssertionError("A recognized sentence should be the first candidate");
        pressCandidate("，ngaf yer gaf", true);
        expectText(host.second, "ngaf yer gaf");
        expectText(host.first, "麻麻爹诶及");

        press("退格");
        expectText(host.second, "ngaf yer ga");
        press("隐藏键盘");
        waitAbsent("隐藏键盘");
        tapField(host.second);
        press("汉字记音");
        assertEditorAboveKeyboard(host.second, "土家语拼写");
        type("hxif");
        pressCandidate("走，诶", false);
        expectText(host.second, "ngaf yer ga诶");

        press("手写");
        stage("Automatic handwriting in spelling output");
        setHandwritingOutput(false);
        Rect pad = waitForNode("汉字手写区", false);
        drawThree(pad);
        expectText(host.second, "ngaf yer ga诶sangx");
        if (!pad.equals(waitForNode("汉字手写区", false))) throw new AssertionError("Handwriting pad moved while drawing");

        stage("Handwrite 尬, distinguish the eating sense, commit the recommended Han form");
        setHandwritingOutput(true);
        drawStroke(pad, .13f,.31f, .21f,.31f, .42f,.26f);
        drawStroke(pad, .25f,.08f, .29f,.13f, .25f,.47f, .18f,.64f, .06f,.81f);
        drawStroke(pad, .32f,.38f, .34f,.41f, .33f,.64f, .35f,.76f, .44f,.83f, .63f,.85f, .83f,.79f, .89f,.75f, .90f,.62f);
        drawStroke(pad, .57f,.07f, .61f,.12f, .51f,.33f, .40f,.47f);
        drawStroke(pad, .61f,.21f, .72f,.34f, .81f,.41f, .93f,.43f);
        drawStroke(pad, .50f,.43f, .54f,.47f, .53f,.59f, .49f,.67f, .43f,.72f);
        drawStroke(pad, .65f,.40f, .68f,.44f, .68f,.77f);
        expectText(host.second, "ngaf yer ga诶sangx尬");
        waitForNode("干、渴，夹", false);
        pressCandidate("吃、咬，架", false);
        expectText(host.second, "ngaf yer ga诶sangx架");
        press("退格");
        expectText(host.second, "ngaf yer ga诶sangx");

        press("语音");
        stage("Voice placeholder");
        waitForNode("语音模型尚未上线", false);
        press("切换到土家语拼写");
        waitForNode("退格", false);
        expectText(host.second, "ngaf yer ga诶sangx");

        stage("Four candidate columns, Tujia priority and Mandarin fallback");
        press("汉字记音");
        type("ga");
        List<Card> mixed = fourCards();
        assertMixedPage(mixed);
        expectText(host.second, "ngaf yer ga诶sangx");
        press("下一页候选");
        waitForNode("汉语，伽", false);
        List<Card> following = pageCards("，");
        if (following.isEmpty() || !"汉语，伽".equals(following.get(0).name))
            throw new AssertionError("Mandarin alternatives out of frequency order on the next page");
        press("上一页候选");
        expectFirst(mixed.get(0).name);
        press("退格");
        press("退格");

        type("zhongguo");
        expectFirst("汉语，中国");
        Card china = card("汉语，中国");
        if (!china.lines.equals(Collections.singletonList("中国")))
            throw new AssertionError("Unexpected Chinese candidate labels: " + china.lines);
        pressCandidate("汉语，中", false);
        expectText(host.second, "ngaf yer ga诶sangx中");
        waitForNode("待选拼写 guo", false);
        pressCandidate("汉语，国", false);
        expectText(host.second, "ngaf yer ga诶sangx中国");
        waitForNode("待选拼写 ", false);

        stage("Brief sense and full reader");
        type("boxlirhxif");
        Card child = card("小孩、儿童，波俐");
        if (!child.lines.equals(Arrays.asList("小孩", "波俐")))
            throw new AssertionError("Unexpected brief sense or word: " + child.lines);
        openReader(child);
        if (!visibleControls().contains("小孩、儿童"))
            throw new AssertionError("Full sense missing from reader");
        expectText(host.second, "ngaf yer ga诶sangx中国");
        assertEditorAboveKeyboard(host.second, "返回候选");
        press("返回候选");
        waitForNode("待选拼写 boxlirhxif", false);
        pressCandidate("小孩、儿童，波俐", false);
        expectText(host.second, "ngaf yer ga诶sangx中国波俐");
        waitForNode("待选拼写 hxif", false);
    }

    private void testSpellingStyle() {
        stage("Toneless input, Ye spelling and persistent tone-mark spelling");
        tapField(host.first);
        press("土家语拼写");
        type("ga");
        pressCandidate("吃、咬，gaf", false);
        String prefix = "麻麻爹诶及gaf。";
        press("句号");
        expectText(host.first, prefix);

        setStyleFromLauncher("带调号拼音");
        if (!"带调号拼音".equals(setStyleFromLauncher(null)))
            throw new AssertionError("Output setting did not survive closing and reopening the launcher");
        tapField(host.first);
        press("土家语拼写");
        type("ga");
        pressCandidate("吃、咬，gá", false);
        expectText(host.first, prefix + "gá");
        type("zhongguo");
        pressCandidate("汉语，中国", false);
        expectText(host.first, prefix + "gá中国");

        stage("Actual long-press tone entry");
        holdTone("a", "ā");
        waitForNode("待选拼写 ā", false);
        press("i");
        waitForNode("待选拼写 āi", false);
        press("z");
        waitForNode("待选拼写 āiz", false);
        holdTone("u", "ū");
        waitForNode("待选拼写 āizū", false);
        pressCandidate("从前，āizū", false);
        expectText(host.first, prefix + "gá中国āizū");

        stage("A Chinese loan shows its original Han form and keeps a gloss for romanized output");
        press("汉字记音");
        type("sanggo");
        Card hanLoan = card("山歌");
        if (!hanLoan.lines.equals(Collections.singletonList("山歌")))
            throw new AssertionError("Unexpected Han loan display: " + hanLoan.lines);
        pressCandidate("山歌", false);
        expectText(host.first, prefix + "gá中国āizū山歌");
        press("土家语拼写");
        type("sanggo");
        Card romanLoan = card("山歌，sānggō");
        if (!romanLoan.lines.equals(Arrays.asList("山歌", "sānggō")))
            throw new AssertionError("Unexpected romanized loan display: " + romanLoan.lines);
        pressCandidate("山歌，sānggō", false);
        expectText(host.first, prefix + "gá中国āizū山歌sānggō");
    }

    private void holdTone(String base, String marked) {
        Rect key = waitForNode(base, false);
        long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, key.centerX(), key.centerY());
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180);
        inject(down, MotionEvent.ACTION_UP, key.centerX(), key.centerY());
        press(marked);
    }

    private String setStyleFromLauncher(String requested) {
        Intent settings = new Intent().setClassName(IME_PACKAGE, IME_PACKAGE + ".SetupActivity");
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        getTargetContext().startActivity(settings);
        String label = launcherControl("拼写输出 · ", true, false);
        String previous = label.substring("拼写输出 · ".length());
        if (requested != null && !requested.equals(previous)) {
            launcherControl(label, false, true);
            launcherControl(requested, false, true);
            launcherControl("拼写输出 · " + requested, false, false);
        }
        if (!ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            throw new AssertionError("Could not return to editor from keyboard settings");
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            boolean[] active = new boolean[1];
            runOnMainSync(() -> active[0] = host.hasWindowFocus());
            if (active[0]) return previous;
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Original editor did not return to the foreground");
    }

    private String launcherControl(String query, boolean prefix, boolean click) {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) {
                try {
                    if (IME_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(query)) {
                            try {
                                if (!node.refresh() || node.getText() == null) continue;
                                String value = node.getText().toString();
                                if (!(prefix ? value.startsWith(query) : value.equals(query))) continue;
                                Rect bounds = new Rect();
                                node.getBoundsInScreen(bounds);
                                if (!node.isVisibleToUser() || bounds.isEmpty()) {
                                    node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                                    continue;
                                }
                                if (click) tap(bounds.centerX(), bounds.centerY());
                                return value;
                            } finally { node.recycle(); }
                        }
                    }
                } finally { root.recycle(); }
            }
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Missing launcher setting: " + query);
    }

    private void testJoinedForms() {
        stage("Single-syllable Han pairs keep clean text and rich-editor spans");
        runOnMainSync(() -> host.second.setText(""));
        tapField(host.second);
        press("汉字记音");
        type("engei");
        pressCandidate("那里，阿蒽格锿", false);
        expectText(host.second, "阿蒽格锿");
        assertJoinedRanges(host.second, 0, 2, 2, 4);

        // Each character of this normal two-syllable word represents a separate syllable.
        type("boxlir");
        pressCandidate("小孩、儿童，波俐", false);
        expectText(host.second, "阿蒽格锿波俐");
        assertJoinedRanges(host.second, 0, 2, 2, 4);

        type("kir");
        pressCandidate("克移", false);
        expectText(host.second, "阿蒽格锿波俐克移");
        assertJoinedRanges(host.second, 0, 2, 2, 4, 6, 8);
    }

    private void testApicalVowels() {
        stage("Noon and standalone apical syllables use full-width, unjoined Han characters");
        runOnMainSync(() -> host.second.setText(""));
        tapField(host.second);
        press("汉字记音");
        String[][] examples = {
                {"bangricai", "中午，半日猜"}, {"bangfrixcaix", "中午，半日猜"},
                {"rix", "做，日"}, {"rif", "日"}, {"rir", "多，日"},
                {"riv", "将做，日"}, {"zix", "两，兹"}, {"cix", "雌"}, {"six", "斯"}
        };
        StringBuilder committed = new StringBuilder();
        for (String[] example : examples) {
            type(example[0]);
            if (example[0].equals("bangricai")) {
                stage("Real meaning above noon; unglossed Han spelling in candidate and reader");
                Card noon = card("中午，半日猜");
                Card sound = card("帮");
                if (!noon.lines.equals(Arrays.asList("中午", "半日猜"))
                        || !sound.lines.equals(Collections.singletonList("帮"))
                        || visibleControls().contains("音节"))
                    throw new AssertionError("Unexpected displayed candidate gloss: " + visibleControls());
                openReader(sound);
                String detail = visibleControls();
                if (!detail.contains("待输入") || !detail.contains("帮") || detail.contains("音节") || detail.contains("汉义"))
                    throw new AssertionError("Unexpected unglossed reader content: " + detail);
                press("返回候选");
            }
            pressCandidate(example[1], false);
            committed.append(example[1].substring(example[1].lastIndexOf('，') + 1));
            expectText(host.second, committed.toString());
            assertJoinedRanges(host.second);
        }
    }

    private void testContinuousHandwriting() {
        stage("Two handwritten characters reach the editor with no confirmation taps");
        runOnMainSync(() -> { host.first.setText("首"); host.second.setText("原"); });
        tapField(host.first);
        press("手写");
        setHandwritingOutput(true);
        Rect pad = waitForNode("汉字手写区", false);
        drawBo(pad);
        expectText(host.first, "首波");
        waitForNode("续写 小孩、儿童，波俐", false);
        waitForNode("波  →  俐", false);
        drawLi(pad);
        expectText(host.first, "首波俐");
        if (!pad.equals(waitForNode("汉字手写区", false)))
            throw new AssertionError("Writing the next character moved the pad");
        pressCandidate("小孩、儿童，波俐", false);
        expectText(host.first, "首波俐");
        pressCandidate("联想 没有，太", false);
        expectText(host.first, "首波俐太");

        stage("Correct a handwritten character, then accept a word completion and its next word");
        tapField(host.second);
        drawBo(pad);
        expectText(host.second, "原波");
        String alternate = chooseAlternateGlyph("波");
        String corrected = "原" + alternate;
        stage("Corrected 波 to the visible alternative " + alternate);
        expectText(host.second, corrected);
        tapField(host.first);
        tapField(host.second);
        drawBo(pad);
        expectText(host.second, corrected + "波");
        pressCandidate("续写 小孩儿，波俐", false);
        expectText(host.second, corrected + "波俐");
        pressCandidate("联想 大人，再", false);
        expectText(host.second, corrected + "波俐再");

        stage("Pending strokes and correction cannot cross editor or cursor changes");
        drawThree(pad);
        tapField(host.first);
        SystemClock.sleep(1600);
        expectText(host.second, corrected + "波俐再");
        expectText(host.first, "首波俐太");
        drawBo(pad);
        expectText(host.first, "首波俐太波");
        runOnMainSync(() -> host.first.setSelection(1, 3));
        waitAbsent("手写字形 波");
        drawThree(pad);
        expectText(host.first, "首三太波");
        runOnMainSync(() -> host.first.setSelection(1));
        waitAbsent("手写字形 三");
        drawThree(pad);
        expectText(host.first, "首三三太波");
        expectText(host.second, corrected + "波俐再");
    }

    private String chooseAlternateGlyph(String recognized) {
        String prefix = "手写字形 ";
        waitForNode(prefix + recognized, false);
        Rect first = null;
        String choice = null;
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(prefix)) {
            try {
                if (!node.refresh() || !node.isVisibleToUser() || !node.isClickable() || node.getContentDescription() == null) continue;
                String name = node.getContentDescription().toString();
                if (!name.startsWith(prefix) || name.equals(prefix + recognized)) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                if (first == null || bounds.left < first.left) { first = bounds; choice = name.substring(prefix.length()); }
            } finally { node.recycle(); }
        }
        if (first == null) throw new AssertionError("No correction choice for " + recognized);
        tap(first.centerX(), first.centerY());
        return choice;
    }

    void setHandwritingOutput(boolean han) {
        String wanted = han ? "记音 ▾" : "拼写 ▾";
        if (pollNode(wanted, false, 150) == null) press("切换手写输出");
        waitForNode(wanted, false);
    }

    private void testNextSounds() {
        stage("Predicted syllables open lexical choices before committing any host text");
        String oldStyle = setStyleFromLauncher("叶氏拼音");
        try {
            runOnMainSync(() -> host.nativeScenario(android.text.InputType.TYPE_CLASS_TEXT,
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE, false));
            tapField(host.first);
            press("汉字记音");
            pressCandidate("下一音节 gof", false);
            waitForNode("待选读音 gof", false);
            expectText(host.first, "");
            press("返回联想");
            expectText(host.first, "");
            pressCandidate("下一音节 gof", false);
            pressCandidate("他，过", false);
            expectText(host.first, "过");

            tapField(host.second);
            type("ye");
            pressCandidate("饭，叶", false);
            expectText(host.second, "叶");
            waitForNode("联想 吃、咬，架", false);
            pressCandidate("下一音节 gaf", false);
            waitForNode("待选读音 gaf", false);
            expectText(host.second, "叶");
            pressCandidate("吃、咬，架", false);
            expectText(host.second, "叶架");

            stage("A focused host cleared externally returns to sentence-start predictions");
            runOnMainSync(() -> host.second.setSelection(1));
            setStyleFromLauncher("叶氏拼音");
            runOnMainSync(() -> host.second.setText(""));
            tapField(host.second);
            press("汉字记音");
            pressCandidate("下一音节 gof", false);
            waitForNode("待选读音 gof", false);
            expectText(host.second, "");
            press("返回联想");
            expectText(host.second, "");
        } finally {
            setStyleFromLauncher(oldStyle);
        }
    }

    private void assertJoinedRanges(EditText field, int... limits) {
        AtomicReference<Spanned> snapshot = new AtomicReference<>();
        runOnMainSync(() -> snapshot.set(new SpannedString(field.getText())));
        Spanned text = snapshot.get();
        UnderlineSpan[] underlines = text.getSpans(0, text.length(), UnderlineSpan.class);
        ScaleXSpan[] widths = text.getSpans(0, text.length(), ScaleXSpan.class);
        if (underlines.length * 2 != limits.length || widths.length * 2 != limits.length) {
            throw new AssertionError("Unexpected styling count for " + text + ": underlines="
                    + underlines.length + ", widths=" + widths.length);
        }
        for (int i = 0; i < limits.length; i += 2) {
            boolean underline = false;
            boolean compact = false;
            for (UnderlineSpan span : underlines) {
                if (text.getSpanStart(span) == limits[i] && text.getSpanEnd(span) == limits[i + 1]) {
                    underline = true;
                }
            }
            for (ScaleXSpan span : widths) {
                if (text.getSpanStart(span) == limits[i] && text.getSpanEnd(span) == limits[i + 1]
                        && Math.abs(span.getScaleX() - .84f) < .001f) {
                    compact = true;
                }
            }
            if (!underline || !compact) {
                throw new AssertionError("Missing joined styling at " + limits[i] + ".." + limits[i + 1]);
            }
        }
    }

    private static final class Card {
        final String name;
        final Rect bounds;
        final List<String> lines = new ArrayList<>();
        Card(String name, Rect bounds) {
            this.name = name;
            this.bounds = bounds;
        }
    }

    private void openReader(Card card) {
        long start = SystemClock.uptimeMillis();
        inject(start, MotionEvent.ACTION_DOWN, card.bounds.centerX(), card.bounds.centerY());
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250);
        inject(start, MotionEvent.ACTION_UP, card.bounds.centerX(), card.bounds.centerY());
        waitForNode("输入这个候选", false);
    }

    private List<Card> pageCards(String search) {
        // Refresh the window after its candidate children change in place.
        waitForNode("汉字记音", false);
        List<Card> page = new ArrayList<>();
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(search)) {
            try {
                if (!node.refresh() || !node.isVisibleToUser() || !node.isClickable()
                        || !"android.widget.LinearLayout".contentEquals(node.getClassName())
                        || node.getContentDescription() == null) continue;
                Rect bounds = new Rect();
                node.getBoundsInScreen(bounds);
                Card found = new Card(node.getContentDescription().toString(), bounds);
                for (int i = 0; i < node.getChildCount(); i++) {
                    AccessibilityNodeInfo child = node.getChild(i);
                    if (child == null) continue;
                    try {
                        if (child.isVisibleToUser() && child.getText() != null && child.getText().length() != 0)
                            found.lines.add(child.getText().toString());
                    } finally { child.recycle(); }
                }
                page.add(found);
            } finally { node.recycle(); }
        }
        Collections.sort(page, new Comparator<Card>() {
            @Override public int compare(Card a, Card b) { return Integer.compare(a.bounds.left, b.bounds.left); }
        });
        return page;
    }

    private Card card(String name) {
        waitForNode(name, false);
        for (Card item : pageCards(name)) if (name.equals(item.name)) return item;
        throw new AssertionError("Missing card on current page: " + name);
    }

    private List<Card> fourCards() {
        List<Card> page = pageCards("，");
        if (page.size() != 4) throw new AssertionError("Expected four candidates, saw " + page.size());
        for (int i = 1; i < page.size(); i++) {
            Rect left = page.get(i - 1).bounds, right = page.get(i).bounds;
            if (left.top != right.top || left.bottom != right.bottom || left.right > right.left)
                throw new AssertionError("Candidates must occupy four separate columns on one line");
        }
        return page;
    }

    private void expectFirst(String name) {
        waitForNode(name, false);
        List<Card> page = pageCards("，");
        if (page.isEmpty() || !name.equals(page.get(0).name))
            throw new AssertionError("First candidate should be " + name + ", saw " + visibleControls());
    }

    private void assertMixedPage(List<Card> page) {
        // These three equally long matches have 39, 33 and 2 attested examples.
        List<String> expected = Arrays.asList("吃、咬，架", "上，夹", "干、渴，夹", "汉语，嘎");
        for (int i = 0; i < expected.size(); i++) {
            if (!expected.get(i).equals(page.get(i).name))
                throw new AssertionError("Candidates out of language/frequency order: " + visibleControls());
        }
        if (!page.get(0).lines.equals(Arrays.asList("吃", "架"))
                || !page.get(3).lines.equals(Collections.singletonList("嘎")))
            throw new AssertionError("Expected a short Tujia sense and a word-only Chinese fallback");
    }

    void stage(String name) {
        Bundle status = new Bundle();
        status.putString("step", name);
        sendStatus(1, status);
    }

    // Accessibility can publish final bounds before SurfaceFlinger finishes a keyboard move.
    // Wait at editing transitions for both the visible key bounds and their rendered pixels.
    @android.annotation.TargetApi(30)
    void awaitKeyboardAtRest() {
        if (android.os.Build.VERSION.SDK_INT < 30) return;
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        long lastChange = SystemClock.uptimeMillis(), previous = Long.MIN_VALUE;
        Rect header = null;
        while (SystemClock.uptimeMillis() < deadline) {
            header = findNode("隐藏键盘", false);
            if (header == null || host.imeAnimating) {
                lastChange = SystemClock.uptimeMillis();
                SystemClock.sleep(35);
                continue;
            }
            android.graphics.Bitmap shot = ui.takeScreenshot();
            if (shot == null) throw new AssertionError("Cannot sample the test emulator display");
            long signature = header.hashCode();
            try {
                int top = Math.max(0, Math.min(header.top, shot.getHeight() - 1));
                for (int row = 0; row < 9; row++) {
                    int y = top + row * (shot.getHeight() - top - 1) / 9;
                    for (int col = 1; col < 16; col++)
                        signature = 31 * signature + shot.getPixel(col * (shot.getWidth() - 1) / 16, y);
                }
            } finally { shot.recycle(); }
            long now = SystemClock.uptimeMillis();
            if (signature != previous) { previous = signature; lastChange = now; }
            else if (!host.imeAnimating && now - lastChange >= 240) return;
            SystemClock.sleep(35);
        }
        throw new AssertionError("IME did not settle: header=" + header + ", animating=" + host.imeAnimating);
    }

    void type(String letters) {
        awaitKeyboardAtRest();
        for (int i = 0; i < letters.length(); i++) {
            press(letters.substring(i, i + 1));
            waitForNode("待选拼写 " + letters.substring(0, i + 1), false);
        }
    }

    void press(String label) {
        boolean mode = "土家语拼写".equals(label) || "汉字记音".equals(label)
                || "手写".equals(label) || "语音".equals(label);
        if (mode) awaitKeyboardAtRest();
        Rect rect = waitForNode(label, false);
        tap(rect.centerX(), rect.centerY());
        if (!mode) return;
        long until = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            if (findNode(label, false) != null) {
                for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(label)) {
                    try {
                        if (node.refresh() && node.isSelected() && android.text.TextUtils.equals(label, node.getContentDescription())) {
                            awaitKeyboardAtRest();
                            return;
                        }
                    } finally { node.recycle(); }
                }
            }
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Mode selection did not take effect: " + label);
    }

    @android.annotation.TargetApi(30)
    void assertBottomRowAboveNavigation(String action) {
        if (android.os.Build.VERSION.SDK_INT < 35) return;
        android.view.WindowMetrics window = host.getWindowManager().getCurrentWindowMetrics();
        android.graphics.Insets bars = window.getWindowInsets().getInsets(
                android.view.WindowInsets.Type.navigationBars() | android.view.WindowInsets.Type.displayCutout());
        Rect usable = new Rect(window.getBounds());
        usable.left += bars.left;
        usable.right -= bars.right;
        usable.bottom -= bars.bottom;
        StringBuilder measured = new StringBuilder();
        for (String key : new String[]{"字母或符号", "切换输入法", "逗号", "空格", "句号", action}) {
            Rect area = waitForNode(key, false);
            if (!usable.contains(area)) throw new AssertionError(key + " " + area + " overlaps system navigation " + usable);
            measured.append(' ').append(key).append('=').append(area.toShortString());
        }
        stage("Bottom-row touch bounds inside " + usable.toShortString() + measured);
    }

    void pressAction(String caption) {
        waitForNode(caption, false);
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(caption)) {
            try {
                if (!node.refresh() || !node.isVisibleToUser() || !node.isClickable()
                        || !caption.contentEquals(node.getText() == null ? "" : node.getText())
                        || !caption.contentEquals(node.getContentDescription() == null ? "" : node.getContentDescription())) continue;
                Rect rect = new Rect();
                node.getBoundsInScreen(rect);
                stage("Tap editor action " + caption + " at " + rect.toShortString());
                tap(rect.centerX(), rect.centerY());
                return;
            } finally { node.recycle(); }
        }
        throw new AssertionError("No action key with matching caption and accessibility label: " + caption);
    }

    void pressCandidate(String label, boolean suffix) {
        // Candidate controls can be repopulated after every key. Allow the UI time
        // to expose the current page before deciding the intended item is absent.
        for (int page = 0; page < 8; page++) {
            Rect match = pollNode(label, suffix, page == 0 ? 1500 : 500);
            if (label.indexOf('，') < 0 && match != null) {
                // The same character can also occur in lexical cards with a real meaning.
                match = null;
                for (Card item : pageCards(label)) if (label.equals(item.name)) { match = item.bounds; break; }
            }
            if (match != null) {
                tap(match.centerX(), match.centerY());
                return;
            }
            stage("Candidate " + label + " missing on page " + page + ": " + visibleControls());
            Rect next = pollNode("下一页候选", false, 250);
            if (next == null) break;
            tap(next.centerX(), next.centerY());
        }
        throw new AssertionError("Missing candidate: " + label + "; screen=" + visibleControls());
    }

    void tapField(EditText field) {
        AtomicReference<Rect> bounds = new AtomicReference<>();
        long readyBy = SystemClock.uptimeMillis() + WAIT_MS;
        while (bounds.get() == null && SystemClock.uptimeMillis() < readyBy) {
            runOnMainSync(() -> {
                Rect visible = new Rect();
                if (!host.hasWindowFocus() || !field.isShown() || !field.getLocalVisibleRect(visible)) return;
                int[] pos = new int[2];
                field.getLocationOnScreen(pos);
                visible.offset(pos[0], pos[1]);
                if (!visible.isEmpty()) bounds.set(visible);
            });
            if (bounds.get() == null) SystemClock.sleep(40);
        }
        Rect r = bounds.get();
        if (r == null) throw new AssertionError("Host did not expose a focused window and visible editor");
        // Leave the cursor after short test text without hitting the navigation gesture region.
        int rightInset = Math.round(32 * field.getResources().getDisplayMetrics().density);
        tap(Math.max(r.centerX(), r.right - rightInset), r.centerY());
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            final boolean[] focused = new boolean[1];
            runOnMainSync(() -> {
                android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                        host.getSystemService(android.content.Context.INPUT_METHOD_SERVICE);
                focused[0] = field.hasFocus() && imm.isActive(field);
            });
            if (focused[0]) return;
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Editor did not become the served input field");
    }

    void assertEditorAboveKeyboard(EditText field, String control) {
        Rect key = waitForNode(control, false);
        int[] bottom = new int[1];
        runOnMainSync(() -> {
            Rect r = new Rect();
            if (!field.getGlobalVisibleRect(r) || r.height() < field.getHeight() / 2)
                throw new AssertionError("Editor obscured");
            int[] p = new int[2];
            field.getLocationOnScreen(p);
            bottom[0] = p[1] + field.getHeight();
        });
        if (bottom[0] > key.top) throw new AssertionError("Keyboard overlaps editor");
    }

    void expectText(EditText field, String expected) {
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        AtomicReference<String> actual = new AtomicReference<>();
        do {
            runOnMainSync(() -> actual.set(field.getText().toString()));
            if (expected.equals(actual.get())) return;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Expected [" + expected + "], got [" + actual.get() + "]");
    }

    void drawThree(Rect pad) {
        drawStroke(pad, .23f, .20f, .77f, .20f);
        drawStroke(pad, .29f, .48f, .71f, .48f);
        drawStroke(pad, .10f, .80f, .90f, .80f);
    }

    private void drawBo(Rect pad) {
        drawStroke(pad, .20f,.18f, .30f,.27f);
        drawStroke(pad, .13f,.37f, .22f,.44f);
        drawStroke(pad, .17f,.84f, .16f,.76f, .31f,.50f);
        drawStroke(pad, .45f,.33f, .75f,.27f, .78f,.30f, .72f,.38f);
        drawStroke(pad, .38f,.31f, .42f,.38f, .40f,.59f, .27f,.86f);
        drawStroke(pad, .55f,.08f, .60f,.13f, .57f,.50f);
        drawStroke(pad, .48f,.53f, .66f,.50f, .70f,.53f, .60f,.76f, .39f,.84f);
        drawStroke(pad, .47f,.59f, .79f,.86f, .95f,.87f);
    }

    private void drawLi(Rect pad) {
        drawStroke(pad, .27f,.12f, .31f,.17f, .27f,.24f, .07f,.50f);
        drawStroke(pad, .22f,.37f, .22f,.80f);
        drawStroke(pad, .60f,.16f, .54f,.15f, .34f,.28f);
        drawStroke(pad, .31f,.42f, .58f,.37f);
        drawStroke(pad, .44f,.25f, .48f,.27f, .46f,.82f);
        drawStroke(pad, .45f,.42f, .41f,.52f, .29f,.68f);
        drawStroke(pad, .49f,.50f, .57f,.57f);
        drawStroke(pad, .64f,.30f, .66f,.34f, .67f,.59f);
        drawStroke(pad, .75f,.10f, .81f,.15f, .80f,.76f, .78f,.81f, .66f,.77f);
    }

    private void drawStroke(Rect pad, float... points) {
        float side = Math.min(pad.width(), pad.height()) * .88f;
        float left = pad.exactCenterX() - side / 2f;
        float top = pad.exactCenterY() - side / 2f;
        long start = SystemClock.uptimeMillis();
        inject(start, MotionEvent.ACTION_DOWN, left + points[0] * side, top + points[1] * side);
        for (int i = 2; i < points.length; i += 2) {
            int steps = points.length == 4 ? 8 : 2;
            for (int j = 1; j <= steps; j++) {
                float t = j / (float) steps;
                float x = points[i - 2] * (1 - t) + points[i] * t;
                float y = points[i - 1] * (1 - t) + points[i + 1] * t;
                int action = i + 2 == points.length && j == steps ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE;
                inject(start, action, left + x * side, top + y * side);
            }
        }
    }

    void tap(float x, float y) {
        long start = SystemClock.uptimeMillis();
        inject(start, MotionEvent.ACTION_DOWN, x, y);
        SystemClock.sleep(30);
        inject(start, MotionEvent.ACTION_UP, x, y);
        waitForIdleSync();
    }

    private void inject(long start, int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(start, SystemClock.uptimeMillis(), action, x, y, 0);
        event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        boolean sent;
        try {
            sent = ui.injectInputEvent(event, true);
        } finally {
            event.recycle();
        }
        if (!sent) throw new AssertionError("Touch injection rejected");
    }

    Rect waitForNode(String label, boolean suffix) {
        Rect r = pollNode(label, suffix, WAIT_MS);
        if (r == null) throw new AssertionError("Missing keyboard control: " + label + "; screen=" + visibleControls());
        return r;
    }

    Rect pollNode(String label, boolean suffix, long timeout) {
        long end = SystemClock.uptimeMillis() + timeout;
        do {
            Rect r = findNode(label, suffix);
            if (r != null) return r;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < end);
        return null;
    }

    void waitAbsent(String label) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            if (findNode(label, false) == null) return;
            SystemClock.sleep(80);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Keyboard still exposes " + label + ": " + visibleControls());
    }

    String visibleControls() {
        StringBuilder labels = new StringBuilder();
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            AccessibilityNodeInfo root = window.getRoot();
            if (root != null) {
                try {
                    if (IME_PACKAGE.equals(root.getPackageName())) collectLabels(root, labels);
                } finally { root.recycle(); }
            }
        }
        return labels.toString();
    }

    private void collectLabels(AccessibilityNodeInfo node, StringBuilder labels) {
        if (node.getContentDescription() != null) labels.append(" [").append(node.getContentDescription()).append("]");
        if (node.getText() != null) labels.append(" [").append(node.getText()).append("]");
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child != null) {
                try { collectLabels(child, labels); } finally { child.recycle(); }
            }
        }
    }

    private Rect findNode(String label, boolean suffix) {
        // Reuse the IME window between keystrokes. Re-enumerate windows only
        // when Android has hidden or replaced the old window.
        if (imeRoot != null && !imeRoot.refresh()) {
            imeRoot.recycle();
            imeRoot = null;
        }
        if (imeRoot == null) {
            for (AccessibilityWindowInfo window : ui.getWindows()) {
                if (window.getType() != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
                AccessibilityNodeInfo root = window.getRoot();
                if (root == null) continue;
                if (IME_PACKAGE.equals(root.getPackageName())) { imeRoot = root; break; }
                root.recycle();
            }
        }
        if (imeRoot == null) return null;
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(label)) {
            try {
                if (!node.refresh()) continue;
                Rect rect = matchingBounds(node, label, suffix);
                if (rect != null) return rect;
            } finally { node.recycle(); }
        }
        return null;
    }

    private Rect matchingBounds(AccessibilityNodeInfo node, String label, boolean suffix) {
        String desc = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
        if ((suffix ? desc.endsWith(label) : desc.equals(label) || label.contentEquals(node.getText() == null ? "" : node.getText()))
                && node.isVisibleToUser() && node.isEnabled()) {
            Rect bounds = new Rect();
            node.getBoundsInScreen(bounds);
            if (!bounds.isEmpty()) return bounds;
        }
        return null;
    }
}
