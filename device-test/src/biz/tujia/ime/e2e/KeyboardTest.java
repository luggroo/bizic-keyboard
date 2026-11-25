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
import android.text.InputType;
import android.text.style.ScaleXSpan;
import android.text.style.UnderlineSpan;
import android.util.Log;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.EditorInfo;
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
    static final String WALK_HAN = "走、去、去；离开，诶";
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
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
                    | AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
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
                    testHanSenses();
                    testContinuousHandwriting();
                    testHandwrittenSuffixes();
                    testCompactControls();
                } catch (Throwable failure) {
                    saveFailureFrame(result);
                    throw failure;
                } finally {
                    setStyleFromLauncher(originalStyle);
                }
            }
            if (suite.equals("all") || suite.equals("host")) new HostCompatibility(this, host).run();
            if (suite.equals("all") || suite.equals("ipa")) testIpaInput();
            if (suite.equals("ipa")) {
                testIpaSettings();
                testHandwritingSidebar();
                new SystemTypography(this, host).ipa();
                if (android.os.Build.VERSION.SDK_INT >= 35) new HostCompatibility(this, host).ipaWeb();
            }
            if (suite.equals("all") || suite.equals("window")) new HostCompatibility(this, host).window();
            if (suite.equals("outputs")) testOutputButtons();
            if (suite.equals("compact")) testCompactControls();
            if (suite.equals("long-word")) testLongWordCard();
            if (suite.equals("han-senses")) testHanSenses();
            if (suite.equals("regional")) testModernRegisterCharacters();
            if (suite.equals("hand-words")) {
                testHandwrittenSuffixes();
                testContinuousHandwriting();
            }
            if (suite.equals("typography")) new SystemTypography(this, host).run();
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
        if (captureFrame("failure.png")) result.putString("failureFrame", "failure.png");
    }

    boolean captureFrame(String name) {
        if (!captureFailure || ui == null) return false;
        android.graphics.Bitmap shot = ui.takeScreenshot();
        if (shot == null) return false;
        java.io.File dir = getTargetContext().getExternalFilesDir(null);
        try (java.io.FileOutputStream output = new java.io.FileOutputStream(new java.io.File(dir, name))) {
            return shot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output);
        } catch (java.io.IOException e) {
            Log.w("TujiaImeE2E", "Could not save " + name, e);
            return false;
        } finally { shot.recycle(); }
    }

    private void testIpaInput() {
        stage("IPA literal input, tone alternatives and offline completions");
        runOnMainSync(() -> host.nativeScenario(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE,
                EditorInfo.IME_ACTION_NONE | EditorInfo.IME_FLAG_NO_ENTER_ACTION, true));
        tapField(host.first);
        waitSelectedControl("打字");
        if (pollNode("联想 那，āi", false, 45_000) == null)
            throw new AssertionError("Typing starter missing after cold dictionary initialization: " + visibleControls());
        waitForNode("联想 我，ngá", false);
        waitForNode("下一音节 gó", false);
        waitForNode("下一音节 ní", false);
        press("手写");
        press("设置");
        waitSelectedControl("汉字");
        press("返回打字");
        waitSelectedControl("打字");
        runOnMainSync(() -> { host.first.setText("前 / 后"); host.first.setSelection(3); });
        press("音标");
        assertEditorAboveKeyboard(host.first, "音标");
        captureFrame("ipa-layout.png");
        waitForNode("补全 那，ai⁵⁵", false);
        waitForNode("补全 我，ŋa²⁴", false);
        waitForNode("补全 kuɔ²⁴", false);
        waitForNode("补全 ȵi²⁴", false);
        captureFrame("ipa-candidates.png");
        press("设置");
        waitSelectedControl("北部");
        if (isSelectedControl("南部")) throw new AssertionError("Southern IPA enabled by default");
        press("设置");
        press("下一页候选");
        waitAbsent("补全 那，ai⁵⁵");
        press("上一页候选");
        waitForNode("补全 那，ai⁵⁵", false);
        press("m");
        expectText(host.first, "前 /m 后");
        press("下一页候选");
        press("a");
        expectText(host.first, "前 /ma 后");
        runOnMainSync(() -> host.first.setSelection(4));
        waitForNode("输入音标查看候选", false);
        runOnMainSync(() -> host.first.setSelection(5));
        waitForNode("补全 奶，mã²¹", false);
        pressCandidate("补全 奶，mã²¹", false, "奶", "mã²¹");
        expectText(host.first, "前 /mã²¹ 后");

        press("空格");
        for (String letter : new String[]{"v", "x", "r", "f", "j", "l"}) press(letter);
        expectText(host.first, "前 /mã²¹ vxrfjl 后");
        String[][] shortcuts = {{"v", "53"}, {"x", "55"}, {"r", "21"}, {"f", "35"}, {"j", "33"}, {"l", "13"}};
        for (String[] shortcut : shortcuts) {
            holdKey(shortcut[0]);
            press("声调 " + shortcut[1]);
        }
        expectText(host.first, "前 /mã²¹ vxrfjl⁵³⁵⁵²¹³⁵³³¹³ 后");
        holdKey("t");
        for (String option : new String[]{"ts", "tsʰ", "tɕ", "tɕʰ", "tʰ", "ʈ", "ʈʰ"}) waitForNode(option, false);
        captureFrame("ipa-popup.png");
        press("tsʰ");
        holdKey("t");
        press("ʈʰ");
        holdKey("d");
        press("ɖ");
        holdKey("?");
        waitForNode("ʔ", false);
        press("ʕ");
        press("字母或符号");
        press("清化");
        press("浊化");
        expectText(host.first, "前 /mã²¹ vxrfjl⁵³⁵⁵²¹³⁵³³¹³tsʰʈʰɖʕ\u0325\u032c 后");
        press("退格");
        expectText(host.first, "前 /mã²¹ vxrfjl⁵³⁵⁵²¹³⁵³³¹³tsʰʈʰɖʕ\u0325 后");
        press("字母或符号");
        tapField(host.second);
        awaitKeyboardAtRest();
        press("切换大小写");
        press("G");
        press("g");
        expectText(host.second, "Gg");
        runOnMainSync(() -> host.second.setSelection(0, 2));
        holdKey("i");
        waitForNode("ɿ", false);
        press("ʅ");
        expectText(host.second, "ʅ");
        holdKey("v");
        waitForNode("声调 53", false);
        sendKeyDownUpSync(KeyEvent.KEYCODE_ESCAPE);
        waitAbsent("声调 53");
        expectText(host.second, "ʅ");

        stage("A password field uses literal direct input while IPA is selected");
        runOnMainSync(() -> host.nativeScenario(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE, false));
        tapField(host.first);
        waitForNode("英文直输", false);
        waitAbsent("音标");
        awaitKeyboardAtRest();
        press("m");
        press("a");
        waitAbsent("补全 奶，mã²¹");
        expectText(host.first, "ma");
        tapField(host.second);

        stage("Reopening the IME starts in typing and keeps the saved typing output");
        press("调号拼音");
        for (String previous : new String[]{"音标", "手写"}) {
            press(previous);
            press("隐藏键盘");
            waitAbsent("隐藏键盘");
            tapField(host.second);
            waitSelectedControl("打字");
            awaitKeyboardAtRest();
            press("设置");
            waitSelectedControl("调号拼音");
            press("设置");
        }
        press("谐音字");
    }

    private void testIpaSettings() {
        stage("IPA dictionary settings select Southern, Northern, both or no completions");
        runOnMainSync(() -> { host.second.setText(""); });
        tapField(host.second);
        changeIpaDictionaries("北部、南部", "南部");
        changeIpaDictionaries("南部", "北部");
        press("音标");
        press("设置");
        waitForNode("音标补全词库 · 可多选，两项都不选即关闭", false);
        waitSelectedControl("南部");
        captureFrame("ipa-options.png");
        press("北部");
        waitSelectedControl("北部");
        press("北部");
        press("设置");
        String southernI = "ŋo³³";
        press("补全 我，" + southernI);
        expectText(host.second, southernI);
        press("空格");
        waitForNode("补全 我，" + southernI, false);
        waitAbsent("补全 奶，mã²¹");
        press("b");
        press("a");
        holdKey("j");
        press("声调 33");
        press("n");
        press("补全 乌鸦，ba³³nõ²¹");
        expectText(host.second, southernI + " ba³³nõ²¹");

        changeIpaDictionaries("关闭", "南部");
        press("音标");
        press("空格");
        waitAbsent("补全 我，" + southernI);
        press("m");
        press("a");
        waitAbsent("补全 奶，mã²¹");
        waitAbsent("无匹配候选");
        expectText(host.second, southernI + " ba³³nõ²¹ ma");

        changeIpaDictionaries("北部", "北部");
        press("音标");
        press("空格");
        waitForNode("补全 那，ai⁵⁵", false);
        waitAbsent("补全 我，" + southernI);
        changeIpaDictionaries("北部、南部", "南部");
        changeIpaDictionaries("北部", "南部");
    }

    private void changeIpaDictionaries(String expected, String... toggles) {
        awaitKeyboardAtRest();
        press("设置");
        press("更多设置");
        String setting = launcherControl("音标补全 · ", true, false);
        launcherControl(setting, false, true);
        for (String label : toggles) launcherControl(label, false, true);
        launcherControl("确定", false, true);
        launcherControl("音标补全 · " + expected, false, false);
        returnFromKeyboardSettings();
        tapField(host.second);
        waitSelectedControl("打字");
    }

    private void testHandwritingSidebar() {
        stage("The tall handwriting pad keeps correction and editing controls to the left");
        newPredictionEditor();
        press("手写");
        setHandwritingOutput(true);
        Rect pad = waitForNode("汉字手写区", false);
        if (pad.height() / host.getResources().getDisplayMetrics().density < 150)
            throw new AssertionError("Handwriting pad did not expand vertically: " + pad);
        drawThree(pad);
        expectText(host.first, "三");
        for (String label : new String[]{"改字", "手写字形 三", "更多手写字形", "撤销一笔", "清除笔迹", "退格", "完成"}) {
            Rect control = waitForNode(label, false);
            if (control.right > pad.left || control.top < pad.top || control.bottom > pad.bottom)
                throw new AssertionError(label + " is outside the left side of the handwriting pad: " + control);
        }
        captureFrame("handwriting-sidebar.png");
        press("更多手写字形");
        waitAbsent("手写字形 三");
        if (!pad.equals(waitForNode("汉字手写区", false))) throw new AssertionError("Handwriting pad moved");
        press("退格");
        expectText(host.first, "");
        press("设置");
        waitForNode("手写输出 · 识别汉字后，选择上屏写法", false);
        waitSelectedControl("汉字");
        for (String format : new String[]{"叶氏拼音", "调号拼音", "国际音标"}) waitForNode(format, false);
        captureFrame("handwriting-options.png");
        tapControl("国际音标");
        awaitKeyboardAtRest();
        pad = waitForNode("汉字手写区", false);
        drawBo(pad);
        waitForNode("改字", false);
        drawLi(pad);
        expectText(host.first, "pɔ⁵⁵li²¹");
        setHandwritingOutput(true);
        press("打字");
        press("设置");
        waitForNode("打字输出 · 选择候选上屏后的写法", false);
        press("设置");
    }

    private void holdKey(String label) {
        Rect rect = waitForNode(label, false);
        long start = SystemClock.uptimeMillis();
        inject(start, MotionEvent.ACTION_DOWN, rect.centerX(), rect.centerY());
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 120L);
        inject(start, MotionEvent.ACTION_UP, rect.centerX(), rect.centerY());
        waitForIdleSync();
    }

    private void testFirstInput() {
        stage("First input after IME startup");
        tapField(host.first);
        press("谐音字");
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
        press("谐音字");
        assertEditorAboveKeyboard(host.first, "打字");

        // Three-syllable word plus one more word. Selecting the prefix must leave hxif.
        type("marmardiexhxif");
        stage("Longest prefix and uncommitted suffix");
        Card prefix = pageCards("，").get(0);
        if (!prefix.name.startsWith("女人") || !outputLine(prefix).equals("麻麻爹"))
            throw new AssertionError("Expected the longest Tujia prefix first: " + prefix.name);
        tap(prefix.bounds.centerX(), prefix.bounds.centerY());
        expectText(host.first, "麻麻爹");
        waitForNode("待选拼写 hxif", false);
        pressCandidate(WALK_HAN, false);
        expectText(host.first, "麻麻爹诶");

        type("jir");
        expectFirst("脚，及");
        pressCandidate("脚，及", false);
        expectText(host.first, "麻麻爹诶及");

        // A new editor is a new composing session.
        type("jir");
        tapField(host.second);
        waitAbsent("待选拼写 jir");
        press("叶氏拼音");
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
        press("谐音字");
        assertEditorAboveKeyboard(host.second, "打字");
        type("hxif");
        pressCandidate(WALK_HAN, false);
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

        press("设置");
        press("返回打字");
        waitSelectedControl("打字");
        waitForNode("退格", false);
        expectText(host.second, "ngaf yer ga诶sangx");

        stage("Four candidate columns, Tujia priority and Mandarin fallback");
        press("谐音字");
        type("ga");
        List<Card> mixed = fourCards();
        assertMixedPage(mixed);
        expectText(host.second, "ngaf yer ga诶sangx");
        // Completions can shift the Mandarin list onto a later page. Verify the
        // leading Mandarin characters and first Tujia result irrespective of the page break.
        List<String> mandarin = new ArrayList<>();
        int pages = 0;
        while (pages < 8) {
            for (Card item : pageCards("汉语，")) mandarin.add(item.name);
            if (mandarin.size() >= 2) break;
            press("下一页候选");
            pages++;
        }
        // The dialect reading ga also retrieves 家 (564) and 加 (522), ahead of 嘎 (375).
        if (mandarin.size() < 2 || !mandarin.subList(0, 2).equals(Arrays.asList("汉语，家", "汉语，加")))
            throw new AssertionError("Mandarin alternatives out of frequency order: " + mandarin);
        for (int i = 0; i < pages; i++) press("上一页候选");
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

    private void testOutputButtons() {
        stage("Three direct output choices preserve an unfinished spelling");
        String previous = setStyleFromLauncher("谐音字");
        try {
            runOnMainSync(() -> host.first.setText(""));
            tapField(host.first);
            press("谐音字");
            String expected = "";
            String[][] forms = {{"叶氏拼音", "bifzixkar"}, {"调号拼音", "bízīkǎ"}, {"谐音字", "秘兹佧"}};
            for (String[] form : forms) {
                type("bizika");
                tapControl("设置");
                for (String[] key : forms) waitForNode(key[0], false);
                captureFrame("three-output-menu.png");
                tapControl(form[0]);
                waitForNode("待选拼写 bizika", false);
                Card word = visibleForm(form[1]);
                if (!word.name.contains("土家族")) throw new AssertionError("Missing meaning: " + word.name);
                tap(word.bounds.centerX(), word.bounds.centerY());
                press("句号");
                expected += form[1] + "。";
                expectText(host.first, expected);
            }
            captureFrame("three-output-commits.png");
            stage("App settings and the keyboard agree on each output choice");
            for (String[] form : forms) {
                setStyleFromLauncher(form[0]);
                tapField(host.second);
                press("打字");
                tapControl("设置");
                waitSelectedControl(form[0]);
                tapControl("设置");
                waitAbsent(form[0]);
            }
            tapField(host.first);
            press("手写");
            setHandwritingOutput(true);
            for (String[] form : forms) {
                runOnMainSync(() -> { host.first.setText("前秘兹佧后"); host.first.setSelection(4); });
                press("已写成词");
                tapForm("秘兹佧");
                tapControl("谐音字".equals(form[0]) ? "保留原写法" : "用" + form[0]);
                expectText(host.first, "前" + form[1] + "后");
                waitSelectedControl("手写");
            }
        } finally { setStyleFromLauncher(previous); }
    }

    private void testSpellingStyle() {
        stage("Toneless input, Ye spelling and persistent tone-mark spelling");
        tapField(host.first);
        press("叶氏拼音");
        type("bifzika");
        pressCandidate("土家族，bifzixkar", false);
        press("句号");
        type("ga");
        pressCandidate("吃、咬，gaf", false);
        String prefix = "麻麻爹诶及bifzixkar。gaf。";
        press("句号");
        expectText(host.first, prefix);

        press("调号拼音");
        if (!"调号拼音".equals(setStyleFromLauncher(null)))
            throw new AssertionError("Output setting did not survive closing and reopening the launcher");
        tapField(host.first);
        press("调号拼音");
        type("ga");
        pressCandidate("吃、咬，gá", false);
        expectText(host.first, prefix + "gá");
        type("zhongguo");
        pressCandidate("汉语，中国", false);
        expectText(host.first, prefix + "gá中国");

        type("surufa");
        pressCandidate("汉语，输入法", false);
        prefix += "gá中国输入法";
        expectText(host.first, prefix);
        type("siribuli");
        pressCandidate("星星，sīrǐbǔlǐ", false);
        prefix += "sīrǐbǔlǐ";
        expectText(host.first, prefix);

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
        expectText(host.first, prefix + " āizū");

        stage("A Chinese loan shows its original Han form and keeps a gloss for romanized output");
        press("谐音字");
        type("sanggo");
        Card hanLoan = card("山歌");
        if (!hanLoan.lines.equals(Collections.singletonList("山歌")))
            throw new AssertionError("Unexpected Han loan display: " + hanLoan.lines);
        pressCandidate("山歌", false);
        expectText(host.first, prefix + " āizū山歌");
        press("调号拼音");
        type("sanggo");
        Card romanLoan = card("山歌，sānggō");
        if (!romanLoan.lines.equals(Arrays.asList("山歌", "sānggō")))
            throw new AssertionError("Unexpected romanized loan display: " + romanLoan.lines);
        pressCandidate("山歌，sānggō", false);
        expectText(host.first, prefix + " āizū山歌sānggō");
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
        String label = launcherControl("打字输出 · ", true, false);
        String previous = label.substring("打字输出 · ".length());
        if (requested != null && !requested.equals(previous)) {
            launcherControl(label, false, true);
            String example = "调号拼音".equals(requested) ? "bízīkǎ"
                    : "叶氏拼音".equals(requested) ? "bifzixkar" : "秘兹佧";
            launcherControl(requested + "，" + example, false, true);
            launcherControl("打字输出 · " + requested, false, false);
        }
        returnFromKeyboardSettings();
        return previous;
    }

    private void returnFromKeyboardSettings() {
        if (!ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
            throw new AssertionError("Could not return to editor from keyboard settings");
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            boolean[] active = new boolean[1];
            runOnMainSync(() -> active[0] = host.hasWindowFocus());
            if (active[0]) return;
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
        stage("Single-syllable Han pairs retain brackets in editor text");
        runOnMainSync(() -> host.second.setText(""));
        tapField(host.second);
        press("谐音字");
        type("kir");
        pressCandidate("克移", false);
        expectText(host.second, "˻克移˼");
        assertJoinedRanges(host.second, 1, 3);

        // Each character of this normal two-syllable word represents a separate syllable.
        type("boxlir");
        pressCandidate("小孩、儿童，波俐", false);
        expectText(host.second, "˻克移˼波俐");
        assertJoinedRanges(host.second, 1, 3);

        type("kir");
        pressCandidate("克移", false);
        expectText(host.second, "˻克移˼波俐˻克移˼");
        assertJoinedRanges(host.second, 1, 3, 7, 9);
        press("退格");
        expectText(host.second, "˻克移˼波俐");
    }

    private void testApicalVowels() {
        stage("Noon and standalone apical syllables use full-width, unjoined Han characters");
        runOnMainSync(() -> host.second.setText(""));
        tapField(host.second);
        press("谐音字");
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
            if (example[0].startsWith("ri")) {
                Card day = visibleForm("日");
                if (example[1].contains("，") && !day.name.contains(example[1].split("，")[0]))
                    throw new AssertionError("Missing lexical meaning for " + example[0] + ": " + day.name);
                tap(day.bounds.centerX(), day.bounds.centerY());
            } else pressCandidate(example[1], false);
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
        // The first stroke also loads the offline model on software-emulated devices.
        expectText(host.first, "首波", 90000);
        waitForNode("已写成词", false);
        drawLi(pad);
        expectText(host.first, "首波俐");
        if (!pad.equals(waitForNode("汉字手写区", false)))
            throw new AssertionError("Writing the next character moved the pad");
        tapForm("波俐");
        press("保留原写法");
        expectText(host.first, "首波俐");
        press("接着写");
        pressCandidate("联想 一，哪", false, "一", "哪");
        expectText(host.first, "首波俐哪");

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
        press("接着写");
        tapForm("俐");
        expectText(host.second, corrected + "波俐");
        press("接着写");
        Card prediction = nextSoundCard();
        tap(prediction.bounds.centerX(), prediction.bounds.centerY());
        String expectedSecond = corrected + "波俐" + outputLine(prediction);
        expectText(host.second, expectedSecond);

        stage("Pending strokes and correction cannot cross editor or cursor changes");
        drawThree(pad);
        tapField(host.first);
        SystemClock.sleep(1600);
        expectText(host.second, expectedSecond);
        expectText(host.first, "首波俐哪");
        drawBo(pad);
        expectText(host.first, "首波俐哪波");
        runOnMainSync(() -> host.first.setSelection(1, 3));
        waitAbsent("手写字形 波");
        drawThree(pad);
        expectText(host.first, "首三哪波");
        runOnMainSync(() -> host.first.setSelection(1));
        waitAbsent("手写字形 三");
        drawThree(pad);
        expectText(host.first, "首三三哪波");
        expectText(host.second, expectedSecond);
    }

    private void tapForm(String value) {
        Card item = visibleForm(value);
        tap(item.bounds.centerX(), item.bounds.centerY());
    }

    private void testHandwrittenSuffixes() {
        stage("Dialect homophones and every matching suffix of the last four written characters");
        runOnMainSync(() -> host.first.setText("前树稀步李以后"));
        tapField(host.first);
        press("手写");
        setHandwritingOutput(true);
        runOnMainSync(() -> host.first.setSelection(5));
        press("已写成词");
        Rect pad = waitForNode("汉字手写区", false);
        List<Card> row = pageCards("，");
        visibleForm("树希布里");
        if (row.size() < 4) row = pageCards("，");
        List<String> forms = new ArrayList<>();
        for (Card c : row) forms.add(outputLine(c));
        if (!forms.equals(Arrays.asList("树希布里", "希布里", "昔布里", "布里")))
            throw new AssertionError("Expected overlapping dialect matches in order: " + forms);
        tapForm("希布里");
        expectText(host.first, "前树稀步李以后");
        press("用调号拼音");
        expectText(host.first, "前树xìbúlī以后");
        if (!pad.equals(waitForNode("汉字手写区", false))) throw new AssertionError("Handwriting panel resized");
        runOnMainSync(() -> { host.first.setText("前波粒后"); host.first.setSelection(3); });
        press("已写成词");
        visibleForm("波立");
        tapForm("波俐");
        press("用推荐字");
        expectText(host.first, "前波俐后");
        runOnMainSync(() -> { host.first.setText("借米提卡铺"); host.first.setSelection(5); });
        press("已写成词");
        visibleForm("卡铺");
        waitAbsent("指甲花，借米提卡铺");
        runOnMainSync(() -> { host.first.setText("树稀。步李"); host.first.setSelection(5); });
        press("已写成词");
        visibleForm("布里");
        waitAbsent("板栗，树希布里");
        runOnMainSync(() -> { host.first.setText("前阿安后"); host.first.setSelection(3); });
        press("已写成词");
        Card pronoun = visibleForm("˻阿安˼");
        if (pageCards("阿安").size() != 1) throw new AssertionError("The two suffix boundaries must share a card");
        tap(pronoun.bounds.centerX(), pronoun.bounds.centerY());
        waitForNode("已写「阿安」", false);
        for (int i = 0; i < 8 && findNode("已写「安」", false) == null; i++) press("切换匹配或义项");
        waitForNode("已写「安」", false);
        press("用推荐字");
        expectText(host.first, "前阿˻阿安˼后");
        tapForm("˻阿安˼");
        waitForNode("已写「˻阿安˼」", false);
        press("用调号拼音");
        expectText(host.first, "前阿ān后");
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
        setHandwritingOutput(han ? "汉字" : "叶氏拼音");
    }

    private void setHandwritingOutput(String wanted) {
        tapControl("设置");
        waitForNode("手写输出 · 识别汉字后，选择上屏写法", false);
        tapControl(wanted);
        waitAbsent("手写输出 · 识别汉字后，选择上屏写法");
    }

    private Rect[] compactToolbarSnapshot() {
        String[] labels = {"打字", "手写", "音标", "设置", "隐藏键盘"};
        if (findNode("打字", false) == null) return null;
        for (AccessibilityNodeInfo typing : imeRoot.findAccessibilityNodeInfosByText(labels[0])) {
            try {
                if (!typing.refresh() || !typing.isVisibleToUser() || !typing.isClickable()
                        || !android.text.TextUtils.equals(labels[0], typing.getContentDescription())) continue;
                AccessibilityNodeInfo toolbar = typing.getParent();
                if (toolbar == null) continue;
                try {
                    Rect[] bounds = new Rect[labels.length];
                    for (int i = 0; i < toolbar.getChildCount(); i++) {
                        AccessibilityNodeInfo child = toolbar.getChild(i);
                        if (child == null) continue;
                        try {
                            if (!child.isVisibleToUser() || !child.isClickable()) continue;
                            for (int k = 0; k < labels.length; k++) {
                                if (!android.text.TextUtils.equals(labels[k], child.getContentDescription())) continue;
                                if (k == labels.length - 1 && !android.text.TextUtils.equals("收起", child.getText())) continue;
                                bounds[k] = new Rect();
                                child.getBoundsInScreen(bounds[k]);
                            }
                        } finally { child.recycle(); }
                    }
                    if (!Arrays.asList(bounds).contains(null)) return bounds;
                } finally { toolbar.recycle(); }
            } finally { typing.recycle(); }
        }
        return null;
    }

    private void assertCompactToolbar() {
        awaitKeyboardAtRest();
        Rect[] previous = null;
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            Rect[] controls = compactToolbarSnapshot();
            if (controls != null && Arrays.equals(previous, controls)) {
                stage("One IME toolbar, 打字/手写/音标/设置/收起 bounds: " + Arrays.toString(controls));
                for (int i = 0; i < controls.length; i++) {
                    Rect area = controls[i];
                    if (area.isEmpty() || area.top != controls[0].top || area.bottom != controls[0].bottom
                            || (i > 0 && controls[i - 1].right > area.left))
                        throw new AssertionError("The IME controls are not on one ordered line: " + Arrays.toString(controls));
                }
                return;
            }
            previous = controls;
            SystemClock.sleep(100);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("No stable IME toolbar with four sibling controls: " + Arrays.toString(previous) + visibleControls());
    }

    private void testHanSenses() {
        newPredictionEditor();
        press("谐音字");
        type("go");
        Card pronoun = visibleForm("过");
        assertNumberedCard(pronoun, "¹他²讨", "过");
        captureFrame("sense-go.png");
        openReader(pronoun);
        String detail = visibleControls();
        if (!detail.contains("汉义") || !detail.contains("他") || !detail.contains("讨") || !detail.contains("过错") || !detail.contains("待输入"))
            throw new AssertionError("Both meanings must remain available in the reader: " + detail);
        expectText(host.first, "");
        press("返回候选");
        int goPages = verifyUniqueFormsAcrossPages("过");
        stage("Across all " + goPages + " go pages, one 过 card shows ¹他/²讨, with the loan 过错 kept in the reader");
        press("退格"); press("退格"); type("go");
        pronoun = visibleForm("过");
        tap(pronoun.bounds.centerX(), pronoun.bounds.centerY());
        expectText(host.first, "过");
        type("g"); holdTone("o", "ǒ");
        waitForNode("待选拼写 gǒ", false);
        Card marked = card("他，郭");
        captureFrame("sense-marked-go.png");
        tap(marked.bounds.centerX(), marked.bounds.centerY());
        expectText(host.first, "过郭");
        type("mav");
        Card horse = card("马");
        if (!horse.lines.equals(Collections.singletonList("马"))) throw new AssertionError("Repeated same label for 马: " + horse.lines);
        openReader(horse);
        if (!visibleControls().contains("汉义\n马\n\n待输入\n马")) throw new AssertionError("Full lexical meaning of 马 gone: " + visibleControls());
        press("输入这个候选");
        expectText(host.first, "过郭马");
        stage("g + held o / ǒ gives 他／郭; 马 shows once on the card and retains its lexical meaning in the reader");
        type("ga");
        assertMixedPage(fourCards());
        int gaPages = verifyUniqueFormsAcrossPages("夹");
        stage("Across all " + gaPages + " ga pages each form occurs once; 夹 shows ¹上/²干");
        newPredictionEditor();
        for (int i = 0; i < 2; i++) {
            type("ye");
            pressCandidate("饭，叶", false);
            type("go");
            Card ambiguous = visibleForm("过");
            tap(ambiguous.bounds.centerX(), ambiguous.bounds.centerY());
            press("句号");
        }
        type("ye");
        pressCandidate("饭，叶", false);
        expectFirst("联想 吃、咬，架");
        if (!pageCards("联想 他").isEmpty())
            throw new AssertionError("Unresolved 过 incorrectly trained the first sense 他");
        expectText(host.first, "叶过。叶过。叶");
        stage("An ambiguous written 过 did not teach the host that the user had chosen 他");
        testReadingSpecificHan();
    }

    private void testReadingSpecificHan() {
        newPredictionEditor();
        press("谐音字");
        type("ga");
        Card eating = visibleForm("架");
        if (!eating.lines.equals(Arrays.asList("吃", "架")))
            throw new AssertionError("The native ga reading lost its eating sense: " + eating.lines);
        captureFrame("sense-ga-native.png");
        openReader(eating);
        if (!visibleControls().contains("吃、咬")) throw new AssertionError("Full ga sense missing");
        press("输入这个候选");
        expectText(host.first, "架");

        type("jia");
        waitForNode("待选拼写 jia", false);
        Card mandarin = findPlainHan("架");
        captureFrame("sense-jia-mandarin.png");
        openReader(mandarin);
        String detail = visibleControls();
        if (detail.contains("吃") || detail.contains("咬"))
            throw new AssertionError("jia inherited an unrelated ga meaning: " + detail);
        press("输入这个候选");
        expectText(host.first, "架架");
        stage("Typed ga and jia: only the ga card and its full reader gave 吃／吃、咬; both committed 架");

        type("gogo");
        Card brother = visibleForm("哥哥");
        if (!brother.lines.equals(Collections.singletonList("哥哥")))
            throw new AssertionError("Loan should use its Chinese form alone: " + brother.lines);
        captureFrame("sense-gogo-loan.png");
        openReader(brother);
        detail = visibleControls();
        if (!detail.contains("待输入\n哥哥") || detail.contains("汉义"))
            throw new AssertionError("Loan repeated its form as a meaning: " + detail);
        press("输入这个候选");
        expectText(host.first, "架架哥哥");

        String expected = "架架哥哥";
        for (String spelling : new String[]{"banricai", "bangricai"}) {
            type(spelling);
            pressCandidate("中午，半日猜", false, "中午", "半日猜");
            expected += "半日猜";
            expectText(host.first, expected);
            waitForNode("待选拼写 ", false);
        }
        stage("gogo displayed the loan 哥哥 once; banricai/bangricai both selected the full word 中午／半日猜 with no remainder");

        newPredictionEditor();
        expected = "";
        String[][] regional = {{"ga","稼"}, {"jia","稼"}, {"zhuangga","庄稼"}, {"zhuangjia","庄稼"},
                {"zuanga","庄稼"}, {"zuanjia","庄稼"}, {"gaikai","解开"}, {"jianjie","讲解"}};
        for (String[] example : regional) {
            type(example[0]);
            Card chosen = findPlainHan(example[1]);
            if (example[1].equals("稼")) captureFrame("regional-" + example[0] + ".png");
            tap(chosen.bounds.centerX(), chosen.bounds.centerY());
            expected += example[1];
            expectText(host.first, expected);
            waitForNode("待选拼写 ", false);
            stage("Regional Chinese input " + example[0] + " committed plain " + example[1]);
        }
    }

    private void testModernRegisterCharacters() {
        newPredictionEditor();
        press("谐音字");
        String[][] examples = {{"稼","ga","jia"}, {"窖","gao","jiao"}, {"教","gao","jiao"},
                {"降","gan","jian"}, {"嫁","ga","jia"}, {"甲","ga","jia"}, {"讲","gan","jian"},
                {"眼","ngan","yan"}, {"虾","ha","xia"}};
        StringBuilder expected = new StringBuilder();
        for (String[] word : examples) {
            for (int i = 1; i <= 2; i++) {
                type(word[i]);
                Card found = findPagedCard(word[0], false);
                if (word[0].equals("稼") && !found.lines.equals(Collections.singletonList("稼")))
                    throw new AssertionError("The Chinese character 稼 inherited a Tujia gloss: " + found.lines);
                if (word[0].equals("嫁") && i == 2 && !found.lines.contains("先"))
                    throw new AssertionError("jia lost the attested Tujia sense 先／嫁");
                tap(found.bounds.centerX(), found.bounds.centerY());
                expected.append(word[0]);
                expectText(host.first, expected.toString());
                waitForNode("待选拼写 ", false);
            }
            stage("Both modern Jishou readings committed " + word[0] + ": " + word[1] + " / " + word[2]);
        }
    }

    private Card findPlainHan(String form) {
        Card candidate = findPagedCard(form, false);
        if (!candidate.lines.equals(Collections.singletonList(form)))
            throw new AssertionError(form + " has unexpected annotation " + candidate.lines);
        return candidate;
    }

    private Card findPagedCard(String wanted, boolean byName) {
        for (Card candidate : pageCards(wanted)) {
            if (wanted.equals(byName ? candidate.name : outputLine(candidate))) return candidate;
        }
        // A whole page can consist of bare syllables, with no gloss or comma in their names.
        AccessibilityNodeInfo row = rowBesidePager();
        try {
            do {
                for (Card candidate : rowCards(row)) {
                    if (wanted.equals(byName ? candidate.name : outputLine(candidate))) return candidate;
                }
            } while (advanceCandidatePage(row));
        } finally { row.recycle(); }
        throw new AssertionError("Could not find " + wanted + " across candidate pages");
    }

    private AccessibilityNodeInfo rowBesidePager() {
        waitForNode("下一页候选", false);
        for (AccessibilityNodeInfo arrow : imeRoot.findAccessibilityNodeInfosByText("下一页候选")) {
            try {
                if (!arrow.refresh() || !android.text.TextUtils.equals(arrow.getContentDescription(), "下一页候选")) continue;
                AccessibilityNodeInfo strip = arrow.getParent();
                if (strip == null) continue;
                try {
                    strip.refresh();
                    for (int i = 0; i < strip.getChildCount(); i++) {
                        AccessibilityNodeInfo child = strip.getChild(i);
                        if (child == null) continue;
                        if ("android.widget.LinearLayout".contentEquals(child.getClassName())
                                && !rowCards(child).isEmpty()) return child;
                        child.recycle();
                    }
                } finally { strip.recycle(); }
            } finally { arrow.recycle(); }
        }
        throw new AssertionError("No candidate row beside its paging controls: " + visibleControls());
    }

    void assertNumberedCard(Card card, String compactLabel, String form) {
        if (card.lines.size() != 2 || !compactLabel.equals(card.lines.get(0).replace(" ", ""))
                || !form.equals(outputLine(card))) throw new AssertionError("Unexpected meanings or form: " + card.lines);
    }

    Card visibleForm(String output) {
        waitForNode(output, false);
        // The spoken form omits the two visible syllable delimiters.
        String spoken = output.replace("˻", "").replace("˼", "");
        for (Card item : pageCards(spoken)) if (output.equals(outputLine(item))) return item;
        throw new AssertionError("Missing visible form " + output + ": " + visibleControls());
    }

    private AccessibilityNodeInfo candidateRow(Card witness) {
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(witness.name)) {
            try {
                if (node.refresh() && node.isClickable() && node.isVisibleToUser()
                        && android.text.TextUtils.equals(node.getContentDescription(), witness.name)
                        && "android.widget.LinearLayout".contentEquals(node.getClassName())) {
                    AccessibilityNodeInfo parent = node.getParent();
                    if (parent != null && parent.refresh()) return parent;
                    if (parent != null) parent.recycle();
                }
            } finally { node.recycle(); }
        }
        throw new AssertionError("Could not locate candidate row for " + witness.name);
    }

    private List<Card> rowCards(AccessibilityNodeInfo row) {
        List<Card> cards = new ArrayList<>();
        if (!row.refresh()) throw new AssertionError("Candidate row is no longer attached");
        for (int i = 0; i < row.getChildCount(); i++) {
            AccessibilityNodeInfo child = row.getChild(i);
            if (child != null) {
                try {
                    Card item = readCard(child);
                    if (item != null) cards.add(item);
                } finally { child.recycle(); }
            }
        }
        return cards;
    }

    private List<String> pageForms(AccessibilityNodeInfo row) {
        List<String> forms = new ArrayList<>();
        for (Card item : rowCards(row)) forms.add(outputLine(item));
        return forms;
    }

    private int verifyUniqueFormsAcrossPages(String witness) {
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        AccessibilityNodeInfo row = candidateRow(visibleForm(witness));
        try {
            List<String> forms = pageForms(row);
            for (int page = 1; page <= 100; page++) {
                if (forms.isEmpty()) throw new AssertionError("Empty candidate page for " + witness);
                for (String form : forms) if (!seen.add(form))
                    throw new AssertionError("Repeated form on candidate page " + page + ": " + form);
                if (!advanceCandidatePage(row)) return page;
                forms = pageForms(row);
            }
        } finally { row.recycle(); }
        throw new AssertionError("Unbounded pagination for " + witness);
    }

    private boolean advanceCandidatePage(AccessibilityNodeInfo row) {
        Rect next = pollNode("下一页候选", false, 250);
        if (next == null) return false;
        List<String> previous = pageForms(row);
        tap(next.centerX(), next.centerY());
        long until = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            SystemClock.sleep(70);
            List<String> forms = pageForms(row);
            if (!forms.isEmpty() && !forms.equals(previous)) return true;
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Candidate page did not advance from " + previous);
    }

    private void testLongWordCard() {
        newPredictionEditor();
        press("谐音字");
        type("marmardi");
        Card word = card("补全 女人，麻麻爹");
        if (!word.lines.equals(Arrays.asList("女人", "麻麻爹")))
            throw new AssertionError("Three-Han candidate lines: " + word.lines);
        stage("Three-Han candidate: " + word.name + " " + word.bounds.toShortString() + ", lines=" + word.lines);
        assertEditorAboveKeyboard(host.first, "打字");
        captureFrame("compact-three-han.png");
        tap(word.bounds.centerX(), word.bounds.centerY());
        expectText(host.first, "麻麻爹");
        captureFrame("compact-three-han-after.png");
    }

    private void testCompactControls() {
        stage("Compact toolbar, fixed host field, actual letter-key bounds and text size");
        newPredictionEditor();
        press("谐音字");
        runOnMainSync(() -> { host.first.setText("正文"); host.first.setSelection(2); });
        assertCompactToolbar();
        for (String extra : new String[]{"叶氏拼音", "谐音字", "返回打字", "确定拼写"}) waitAbsent(extra);
        assertEditorAboveKeyboard(host.first, "打字");
        waitForNode("待选拼写 ", false);
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText("待选拼写 ")) {
            try {
                if (node.refresh() && node.isVisibleToUser() && android.text.TextUtils.equals("待选拼写 ", node.getContentDescription())
                        && !android.text.TextUtils.isEmpty(node.getText()))
                    throw new AssertionError("An empty draft showed a visible instruction: " + node.getText());
            } finally { node.recycle(); }
        }
        Rect q = waitForNode("q", false), a = waitForNode("a", false), z = waitForNode("z", false);
        float density = host.getResources().getDisplayMetrics().density;
        android.content.res.Configuration config = host.getResources().getConfiguration();
        int minKeyHeight = Math.round(Math.min(q.height(), Math.min(a.height(), z.height())) / density);
        if (config.orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT
                && config.screenHeightDp >= 600 && config.fontScale <= 1.15f) {
            if (minKeyHeight < 44 || minKeyHeight > 48)
                throw new AssertionError("Expected a standard 44–48dp letter key at ordinary scale, got " + minKeyHeight + "dp");
        }
        for (String letter : new String[]{"q", "w", "a", "z", "m"}) {
            waitForNode(letter, false);
            boolean caption = false;
            for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(letter)) {
                try {
                    if (node.refresh() && node.isVisibleToUser() && android.text.TextUtils.equals(letter, node.getContentDescription())
                            && letter.toUpperCase(java.util.Locale.ROOT).contentEquals(node.getText() == null ? "" : node.getText())) caption = true;
                } finally { node.recycle(); }
            }
            if (!caption) throw new AssertionError("Missing visible letter caption: " + letter);
        }
        stage("Letter keys Q=" + q.toShortString() + " A=" + a.toShortString() + " Z=" + z.toShortString()
                + "; minHeight=" + minKeyHeight + "dp; viewport=" + config.screenWidthDp + "dp; fontScale=" + config.fontScale);
        captureFrame("compact-idle.png");
        type("ga");
        List<Card> cards = fourCards();
        if (!cards.get(0).lines.equals(Arrays.asList("吃", "架"))) throw new AssertionError("Missing first lexical gloss in compact mode");
        assertEditorAboveKeyboard(host.first, "打字");
        captureFrame("compact-typing.png");
        tapControl("设置");
        waitSelectedControl("设置");
        waitSelectedControl("谐音字");
        waitForNode("叶氏拼音", false);
        waitForNode("返回打字", false);
        waitForNode("确定拼写", false);
        expectText(host.first, "正文");
        captureFrame("compact-more.png");
        tapControl("设置");
        waitAbsent("确定拼写");
        waitForNode("待选拼写 ga", false);
        pressCandidate("吃、咬，架", false);
        expectText(host.first, "正文架");
        type("rif");
        Card day = visibleForm("日");
        tap(day.bounds.centerX(), day.bounds.centerY());
        expectText(host.first, "正文架日");
        waitForNode("待选拼写 ", false);
        stage("Default 日 consumes the whole rif");
        type("rif");
        openReader(visibleForm("日"));
        press("保留 f");
        expectText(host.first, "正文架日日");
        waitForNode("待选拼写 f", false);
        press("退格");
        waitForNode("待选拼写 ", false);
        press("手写");
        Rect pad = waitForNode("汉字手写区", false);
        assertEditorAboveKeyboard(host.first, "手写");
        setHandwritingOutput(true);
        if (!pad.equals(waitForNode("汉字手写区", false))) throw new AssertionError("Output settings moved the handwriting pad");
        captureFrame("compact-handwriting.png");
        drawThree(pad);
        expectText(host.first, "正文架日日三");
        press("打字");
        tapControl("设置");
        waitSelectedControl("谐音字");
        tapControl("设置");
        waitAbsent("叶氏拼音");
        assertEditorAboveKeyboard(host.first, "打字");
    }

    private void testNextSounds() {
        stage("Single-tap predictions commit the displayed output across modes");
        String oldStyle = setStyleFromLauncher("叶氏拼音");
        try {
            newPredictionEditor();
            press("谐音字");
            Card han = nextSoundCard();
            String written = outputLine(han);
            if (!"下一音节 过".equals(han.name) || !han.lines.equals(Collections.singletonList("过")))
                throw new AssertionError("The polysemous prediction gof should show just 过: " + han.name + han.lines);
            openReader(han);
            expectText(host.first, "");
            press("返回候选");
            expectText(host.first, "");
            Card visible = card(han.name);
            captureFrame("prediction-han-before.png");
            tap(visible.bounds.centerX(), visible.bounds.centerY());
            expectText(host.first, written);
            waitForNode("待选拼写 ", false);
            captureFrame("prediction-han-after.png");
            press("退格");
            if (written.codePointCount(0, written.length()) == 1) expectText(host.first, "");

            newPredictionEditor();
            press("谐音字");
            type("marmardi");
            pressCandidate("补全 女人，麻麻爹", false);
            expectText(host.first, "麻麻爹");
            waitForNode("待选拼写 ", false);
            tapField(host.second);
            type("ye");
            pressCandidate("饭，叶", false);
            pressCandidate("联想 吃、咬，架", false, "吃", "架");
            expectText(host.second, "叶架");
            runOnMainSync(() -> host.second.setSelection(1));
            setStyleFromLauncher("叶氏拼音");
            runOnMainSync(() -> host.second.setText(""));
            tapField(host.second);
            press("谐音字");
            Card start = nextSoundCard();
            openReader(start);
            expectText(host.second, "");
            press("输入这个候选");
            expectText(host.second, outputLine(start));

            testFieldHistory();

            newPredictionEditor();
            press("叶氏拼音");
            Card ye = nextSoundCard();
            String spelling = outputLine(ye);
            if (!spelling.matches("[a-z]+")) throw new AssertionError("Unexpected Ye prediction: " + ye.name);
            tap(ye.bounds.centerX(), ye.bounds.centerY());
            expectText(host.first, spelling);
            press("空格");
            expectText(host.first, spelling + " ");
            press("退格");
            expectText(host.first, spelling);

            setStyleFromLauncher("调号拼音");
            newPredictionEditor();
            press("调号拼音");
            Card toned = nextSoundCard();
            String marked = outputLine(toned);
            if (marked.matches("[a-z]+") || Character.isIdeographic(marked.codePointAt(0)))
                throw new AssertionError("Expected a tone mark in the first spelling prediction: " + toned.name);
            tap(toned.bounds.centerX(), toned.bounds.centerY());
            expectText(host.first, marked);
            type("ga");
            pressCandidate("吃、咬，gá", false);
            expectText(host.first, marked + " gá");

            // 北部词典仅有“紧”这一独立义项。显式第四调前缀缩小句首联想，
            // 保留上方汉义，并把完整预测一次写入外部编辑器。
            tapField(host.second);
            type("j");
            holdTone("i", "ì");
            Card oneSense = findPagedCard("下一音节 紧，jìn", true);
            if (!oneSense.lines.equals(Arrays.asList("紧", "jìn")))
                throw new AssertionError("Expected the verified sole sense above jìn: " + oneSense.lines);
            captureFrame("prediction-jin-before.png");
            tap(oneSense.bounds.centerX(), oneSense.bounds.centerY());
            expectText(host.second, "jìn");
            waitForNode("待选拼写 ", false);
            captureFrame("prediction-jin-after.png");

            stage("Handwriting predictions append and respect the selected output");
            newPredictionEditor();
            press("手写");
            setHandwritingOutput(true);
            Rect pad = waitForNode("汉字手写区", false);
            drawBo(pad);
            expectText(host.first, "波");
            drawLi(pad);
            expectText(host.first, "波俐");
            tapForm("波俐");
            press("保留原写法");
            press("接着写");
            Card hand = nextSoundCard();
            String afterChild = outputLine(hand);
            if (!Character.isIdeographic(afterChild.codePointAt(0)))
                throw new AssertionError("Handwriting produced a non-Han prediction: " + hand.name);
            tap(hand.bounds.centerX(), hand.bounds.centerY());
            expectText(host.first, "波俐" + afterChild);

            tapField(host.second);
            setHandwritingOutput("调号拼音");
            Card handSpelling = nextSoundCard();
            String startingForm = outputLine(handSpelling);
            if (startingForm.matches("[a-z]+") || Character.isIdeographic(startingForm.codePointAt(0)))
                throw new AssertionError("Handwriting spelling ignored the selected style: " + handSpelling.name);
            tap(handSpelling.bounds.centerX(), handSpelling.bounds.centerY());
            expectText(host.second, startingForm);
            expectText(host.first, "波俐" + afterChild);
            setHandwritingOutput(true);
            press("谐音字");

            runOnMainSync(() -> host.nativeScenario(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD,
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE, false));
            tapField(host.first);
            waitForNode("英文直输", false);
            // The password layout drops the candidate rows and moves every letter key.
            awaitKeyboardAtRest();
            Rect aKey = waitForNode("a", false);
            Rect qKey = waitForNode("q", false);
            if (aKey.centerY() <= qKey.centerY())
                throw new AssertionError("Password layout did not separate the A and Q rows: " + aKey + ", " + qKey);
            stage("Tap A in the settled password layout: A=" + aKey + ", Q=" + qKey);
            tap(aKey.centerX(), aKey.centerY());
            expectText(host.first, "a");
            if (!pageCards("下一音节 ").isEmpty() || !pageCards("联想 ").isEmpty())
                throw new AssertionError("Visible prediction cards appeared in password field");
        } finally {
            setStyleFromLauncher(oldStyle);
        }
    }

    private void testFieldHistory() {
        stage("Recent confirmed word pairs stay in one editor across sentence boundaries");
        newPredictionEditor();
        press("谐音字");
        StringBuilder sentence = new StringBuilder();
        for (int i = 0; i < 3; i++) {
            type("ye");
            pressCandidate("饭，叶", false);
            sentence.append("叶");
            if (i == 2) {
                expectFirst("联想 中午，半日猜");
                break;
            }
            if (i == 0) {
                type("bangricai");
                pressCandidate("中午，半日猜", false);
            } else {
                pressCandidate("联想 中午，半日猜", false);
            }
            press("句号");
            sentence.append("半日猜。");
            expectText(host.first, sentence.toString());
        }
        expectText(host.first, sentence.toString());
        tapField(host.second);
        type("ye");
        pressCandidate("饭，叶", false);
        expectFirst("联想 吃、咬，架");
        if (!pageCards("联想 中午，半日猜").isEmpty())
            throw new AssertionError("Word associations leaked into a new editor");
        expectText(host.second, "叶");

        if (android.os.Build.VERSION.SDK_INT >= 26) {
            stage("An editor disabling personalized learning keeps fixed dictionary predictions");
            runOnMainSync(() -> host.nativeScenario(android.text.InputType.TYPE_CLASS_TEXT,
                    android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                            | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false));
            tapField(host.first);
            type("ye");
            pressCandidate("饭，叶", false);
            type("bangricai");
            pressCandidate("中午，半日猜", false);
            press("句号");
            type("ye");
            pressCandidate("饭，叶", false);
            waitForNode("联想 吃、咬，架", false);
            if (!pageCards("联想 中午，半日猜").isEmpty())
                throw new AssertionError("Private editor personalized a repeated continuation");
            expectText(host.first, "叶半日猜。叶");
        }
    }

    void newPredictionEditor() {
        runOnMainSync(() -> host.nativeScenario(android.text.InputType.TYPE_CLASS_TEXT,
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE, false));
        tapField(host.first);
    }

    private String outputLine(Card item) {
        if (item.lines.isEmpty()) throw new AssertionError("Missing candidate form: " + item.name);
        return item.lines.get(item.lines.size() - 1);
    }

    private Card nextSoundCard() {
        List<Card> sounds = pageCards("下一音节 ");
        if (!sounds.isEmpty()) return sounds.get(0);
        AccessibilityNodeInfo row = rowBesidePager();
        try {
            do {
                for (Card card : rowCards(row)) if (card.name.startsWith("下一音节 ")) return card;
            } while (advanceCandidatePage(row));
        } finally { row.recycle(); }
        throw new AssertionError("No writable syllable prediction: " + visibleControls());
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

    static final class Card {
        final String name;
        final Rect bounds;
        final List<String> lines = new ArrayList<>();
        final List<Rect> lineBounds = new ArrayList<>();
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
        // This control exists in direct, handwriting and spelling layouts alike.
        waitForNode("隐藏键盘", false);
        List<Card> page = new ArrayList<>();
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(search)) {
            try {
                Card item = readCard(node);
                if (item != null) page.add(item);
            } finally { node.recycle(); }
        }
        Collections.sort(page, new Comparator<Card>() {
            @Override public int compare(Card a, Card b) { return Integer.compare(a.bounds.left, b.bounds.left); }
        });
        return page;
    }

    private Card readCard(AccessibilityNodeInfo node) {
        if (!node.refresh() || !node.isVisibleToUser() || !node.isClickable()
                || !"android.widget.LinearLayout".contentEquals(node.getClassName())
                || node.getContentDescription() == null) return null;
        Rect bounds = new Rect();
        node.getBoundsInScreen(bounds);
        Card found = new Card(node.getContentDescription().toString(), bounds);
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (child.refresh() && child.isVisibleToUser() && child.getText() != null && child.getText().length() != 0) {
                    found.lines.add(child.getText().toString());
                    Rect line = new Rect();
                    child.getBoundsInScreen(line);
                    found.lineBounds.add(line);
                }
            } finally { child.recycle(); }
        }
        return found;
    }

    private Card card(String name) {
        waitForNode(name, false);
        for (Card item : pageCards(name)) if (name.equals(item.name)) return item;
        throw new AssertionError("Missing card on current page: " + name);
    }

    List<Card> fourCards() {
        List<Card> page;
        AccessibilityNodeInfo row = candidateRow(visibleForm("架"));
        try { page = rowCards(row); } finally { row.recycle(); }
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
        // The first form is the more frequent lexical entry. The two senses of 夹 share its card.
        if (!page.get(0).lines.equals(Arrays.asList("吃", "架")))
            throw new AssertionError("Unexpected top ga candidate: " + page.get(0).lines);
        assertNumberedCard(page.get(1), "¹上²干", "夹");
        openReader(page.get(1));
        String detail = visibleControls();
        if (!detail.contains("汉义") || !detail.contains("上") || !detail.contains("干、渴"))
            throw new AssertionError("Merged 夹 lost a full sense: " + detail);
        press("返回候选");
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

    private void tapControl(String label) {
        Rect rect = waitForNode(label, false);
        tap(rect.centerX(), rect.centerY());
    }

    private boolean isSelectedControl(String label) {
        if (findNode(label, false) == null) return false;
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(label)) {
            try {
                if (node.refresh() && node.isVisibleToUser() && node.isSelected()
                        && android.text.TextUtils.equals(label, node.getContentDescription())) return true;
            } finally { node.recycle(); }
        }
        return false;
    }

    private void waitSelectedControl(String label) {
        long until = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            if (isSelectedControl(label)) return;
            SystemClock.sleep(40);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Mode selection did not take effect: " + label);
    }

    void press(String label) {
        boolean typingOutput = "调号拼音".equals(label) || "叶氏拼音".equals(label) || "谐音字".equals(label);
        if (typingOutput) {
            awaitKeyboardAtRest();
            if (!isSelectedControl("打字")) {
                tapControl("打字");
                waitSelectedControl("打字");
            }
            tapControl("设置");
            tapControl(label);
            waitAbsent(label);
            waitSelectedControl("打字");
            awaitKeyboardAtRest();
            return;
        }
        if ("手写".equals(label) || "打字".equals(label) || "音标".equals(label)) {
            awaitKeyboardAtRest();
            tapControl(label);
            waitSelectedControl(label);
            awaitKeyboardAtRest();
            return;
        }
        tapControl(label);
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

    void pressCandidate(String label, boolean suffix, String... visibleLines) {
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
                if (visibleLines.length > 0) {
                    List<String> lines = card(label).lines;
                    if (!lines.equals(Arrays.asList(visibleLines)))
                        throw new AssertionError("Unexpected visible candidate lines for " + label + ": " + lines);
                    stage("Visible candidate rows " + lines + " before selection");
                }
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

    private boolean waitForUnrelatedSystemApp() {
        for (AccessibilityWindowInfo window : ui.getWindows()) {
            if (!window.isFocused()) continue;
            AccessibilityNodeInfo root = window.getRoot();
            if (root == null) continue;
            try {
                if (!android.text.TextUtils.equals(root.getPackageName(), "android")) continue;
                StringBuilder labels = new StringBuilder();
                collectLabels(root, labels);
                String text = labels.toString().replace('’', (char) 39);
                if (!text.contains("Pixel Launcher isn't responding") && !text.contains("System UI isn't responding")
                        && !text.contains("系统界面没有响应") && !text.contains("系统界面无响应")) continue;
                for (AccessibilityNodeInfo wait : root.findAccessibilityNodeInfosByViewId("android:id/aerr_wait")) {
                    try {
                        if (!wait.isVisibleToUser() || !wait.performAction(AccessibilityNodeInfo.ACTION_CLICK)) continue;
                        stage("Chose Wait on an unrelated Android launcher/System UI ANR before focusing the test editor");
                        return true;
                    } finally { wait.recycle(); }
                }
            } finally { root.recycle(); }
        }
        return false;
    }

    void tapField(EditText field) {
        AtomicReference<Rect> bounds = new AtomicReference<>();
        long readyBy = SystemClock.uptimeMillis() + WAIT_MS;
        long nextSystemCheck = 0;
        boolean waitedForSystemApp = false;
        while (bounds.get() == null && SystemClock.uptimeMillis() < readyBy) {
            runOnMainSync(() -> {
                Rect visible = new Rect();
                if (!host.hasWindowFocus() || !field.isShown() || !field.getLocalVisibleRect(visible)) return;
                int[] pos = new int[2];
                field.getLocationOnScreen(pos);
                visible.offset(pos[0], pos[1]);
                if (!visible.isEmpty()) bounds.set(visible);
            });
            if (bounds.get() == null) {
                long now = SystemClock.uptimeMillis();
                if (!waitedForSystemApp && now >= nextSystemCheck) {
                    waitedForSystemApp = waitForUnrelatedSystemApp();
                    if (waitedForSystemApp) readyBy = now + WAIT_MS;
                    nextSystemCheck = now + 500;
                }
                SystemClock.sleep(40);
            }
        }
        Rect r = bounds.get();
        if (r == null) throw new AssertionError("Host did not expose a focused window and visible editor; "
                + "unrelated system ANR accepted=" + waitedForSystemApp);
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
        expectText(field, expected, WAIT_MS);
    }

    private void expectText(EditText field, String expected, long timeoutMs) {
        long deadline = SystemClock.uptimeMillis() + timeoutMs;
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
