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
import android.view.inputmethod.InputMethodManager;
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
    private boolean freshInstall;

    @Override public void onCreate(Bundle args) {
        super.onCreate(args);
        suite = args == null ? "all" : args.getString("suite", "all");
        captureFailure = args != null && "1".equals(args.getString("captureFailure"));
        freshInstall = args != null && "true".equals(args.getString("freshInstall"));
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
                    testHandLookupActions();
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
            if (suite.equals("all") || suite.equals("trial")) {
                testDelayedSelection();
                new SystemCompatibility(this).trialEditor();
            }
            if (suite.equals("all") || suite.equals("ipa")) testIpaInput();
            if (suite.equals("candidate-layout") || suite.equals("typing-only")) testCandidateLayout();
            if (suite.equals("learning-ime")) {
                testFirstInput();
                testSpellingOnSpace();
                testPrivateKeyboard();
                if (freshInstall) testCrashRecordingDefault();
                testUserDictionary();
                testLearningPredictions();
                testPrivateFieldHistory();
                testPersonalLearningControls();
            }
            if (suite.equals("private-history")) testPrivateFieldHistory();
            if (suite.equals("ipa-detail")) testIpaDetail();
            if (suite.equals("dictionary-detail") || suite.equals("dictionary-large")) testDictionaryDetail();
            if (suite.equals("dictionary-hand")) testHandDictionaryDetail();
            if (suite.equals("dictionary-open")) testOpenDictionary();
            if (suite.equals("dictionary-witness")) testDictionaryWitness();
            if (suite.equals("dictionary-fallback")) testMissingDictionaryDetail();
            if (suite.equals("ipa")) {
                testIpaSettings();
                testHandwritingSidebar();
                testVerbatimSpelling();
                testSpellingOnSpace();
                testSeparatorAfterChoice();
                testUserDictionary();
                testDirectionalTones();
                new SystemTypography(this, host).ipa();
                if (android.os.Build.VERSION.SDK_INT >= 35) new HostCompatibility(this, host).ipaWeb();
                if (freshInstall) testCrashRecordingDefault();
            }
            if (suite.equals("ui-layout")) new SystemTypography(this, host).ipa();
            if (suite.equals("ipa-150")) new SystemTypography(this, host).ipa150();
            if (suite.equals("all") || suite.equals("window")) new HostCompatibility(this, host).window();
            if (suite.equals("outputs")) testOutputButtons();
            if (suite.equals("tone")) testDirectionalTones();
            if (suite.equals("compact")) testCompactControls();
            if (suite.equals("joined")) testJoinedForms();
            if (suite.equals("apical")) testApicalVowels();
            if (suite.equals("long-word")) testLongWordCard();
            if (suite.equals("han-senses")) testHanSenses();
            if (suite.equals("regional")) testModernRegisterCharacters();
            if (suite.equals("hand-layout")) testHandwritingSidebar();
            if (suite.equals("hand-landscape")) new HostCompatibility(this, host).handLandscape();
            if (suite.equals("hand-words")) {
                testHandLookupActions();
                testHandwrittenSuffixes();
                testContinuousHandwriting();
            }
            if (suite.equals("hand-ux")) {
                testHandwritingSidebar();
                testHandLookupActions();
                testHandToneReadings();
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
        waitSelectedControl("拼音");
        press("调号拼音");
        if (pollNode("联想 那，āi", false, 45_000) == null)
            throw new AssertionError("Typing starter missing after cold dictionary initialization: " + visibleControls());
        waitForNode("联想 我，ngá", false);
        waitForNode("下一音节 gó", false);
        waitForNode("下一音节 ní", false);
        press("手写");
        press("设置");
        waitSelectedControl("汉字");
        press("返回拼音");
        waitSelectedControl("拼音");
        runOnMainSync(() -> { host.first.setText("前 / 后"); host.first.setSelection(3); });
        press("音标");
        assertEditorAboveKeyboard(host.first, "音标");
        captureFrame("ipa-layout.png");
        waitForNode("补全 那，ai⁵⁵", false);
        waitForNode("补全 我，ŋa²⁴", false);
        waitForNode("补全 kuɔ²⁴", false);
        waitForNode("补全 ȵi²⁴", false);
        if (card("补全 kuɔ²⁴").bounds.width() <= card("补全 那，ai⁵⁵").bounds.width())
            throw new AssertionError("The longer IPA form should be wider despite the shorter gloss");
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
        expectOriginalAfterMatches("ma");
        pressCandidate("原文，ma", false, "ma");
        expectText(host.first, "前 /ma 后");
        openReader(card("补全 奶，mã²¹"));
        expectText(host.first, "前 /ma 后");
        press("输入这个候选");
        expectText(host.first, "前 /mã²¹ 后");
        expectFirst("奶，mã²¹");

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
        waitAbsent("原文，ma");
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
            waitSelectedControl("拼音");
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
        waitAbsent("原文，ma");
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
        boolean english = englishSettings();
        String prefix = english ? "IPA completion · " : "音标补全 · ";
        String setting = launcherControl(prefix, true, false);
        launcherControl(setting, false, true);
        for (String label : toggles) launcherControl(english ? englishIpaChoice(label) : label, false, true);
        launcherControl(english ? "OK" : "确定", false, true);
        launcherControl(prefix + (english ? englishIpaChoice(expected) : expected), false, false);
        returnFromKeyboardSettings();
        tapField(host.second);
        waitSelectedControl("拼音");
    }

    private static String englishIpaChoice(String value) {
        switch (value) {
            case "北部": return "Northern";
            case "南部": return "Southern";
            case "北部、南部": return "Northern and Southern";
            case "关闭": return "Off";
            default: throw new IllegalArgumentException("Unexpected IPA choice: " + value);
        }
    }

    private boolean englishSettings() {
        return "en".equals(getTargetContext().getResources().getConfiguration().locale.getLanguage());
    }

    void assertBackspaceAlignment(boolean capture) {
        press("拼音");
        Rect pinyinBackspace = waitForNode("退格", false);
        if (capture) captureFrame("backspace-pinyin.png");
        press("音标");
        Rect ipaBackspace = waitForNode("退格", false);
        if (capture) captureFrame("backspace-ipa.png");
        press("手写");
        Rect backspace = waitForNode("退格", false);
        stage("Backspace bounds: pinyin " + pinyinBackspace + ", IPA " + ipaBackspace
                + ", handwriting " + backspace);
        int minimumTargetDp = host.getResources().getConfiguration().orientation
                == android.content.res.Configuration.ORIENTATION_LANDSCAPE ? 40 : 47;
        int minimumTarget = Math.round(minimumTargetDp * host.getResources().getDisplayMetrics().density);
        if (backspace.width() < minimumTarget || backspace.height() < minimumTarget)
            throw new AssertionError("Handwriting backspace touch target is too small: " + backspace);
        int alignment = Math.round(10 * host.getResources().getDisplayMetrics().density);
        for (Rect previous : new Rect[]{pinyinBackspace, ipaBackspace}) {
            if (Math.abs(previous.centerX() - backspace.centerX()) > alignment
                    || Math.abs(previous.centerY() - backspace.centerY()) > alignment)
                throw new AssertionError("Backspace moved between modes: " + previous + " / " + backspace);
        }
    }

    private void testHandwritingSidebar() {
        stage("The handwriting pad leaves backspace in the same corner as pinyin and IPA");
        newPredictionEditor();
        assertBackspaceAlignment(true);
        setHandwritingOutput(true);
        Rect pad = waitForNode("汉字手写区", false);
        if (pad.height() / host.getResources().getDisplayMetrics().density < 150)
            throw new AssertionError("Handwriting pad did not expand vertically: " + pad);
        drawThree(pad);
        expectText(host.first, "三");
        if (findNode("改字", false) != null || findNode("换组", false) != null)
            throw new AssertionError("Handwriting grid still shows a separate header");
        int inset = Math.round(5 * host.getResources().getDisplayMetrics().density);
        for (String label : new String[]{"手写字形 三", "更多手写字形", "重写", "退格"}) {
            Rect control = waitForNode(label, false);
            if (control.left < pad.right || control.top < pad.top - inset || control.bottom > pad.bottom + inset)
                throw new AssertionError(label + " is outside the right side of the handwriting pad: " + control);
        }
        Rect globe = waitForNode("切换输入法", false), comma = waitForNode("逗号", false);
        Rect space = waitForNode("空格", false), period = waitForNode("句号", false), menu = waitForNode("更多标点", false);
        Rect eraser = waitForNode("重写", false), back = waitForNode("退格", false);
        if (Math.abs(period.centerX() - eraser.centerX()) > 1 || Math.abs(menu.centerX() - back.centerX()) > 1
                || Math.abs(2 * space.centerX() - globe.left - menu.right) > 2)
            throw new AssertionError("Footer and handwriting grid do not share their column centers");
        for (Rect key : new Rect[]{globe, comma, period, menu}) {
            if (Math.abs(key.width() - globe.width()) > 2 || key.top != space.top || key.bottom != space.bottom)
                throw new AssertionError("Misaligned handwriting footer key: " + key);
        }
        for (Rect key : new Rect[]{globe, comma, period, menu, eraser, back, waitForNode("更多手写字形", false)})
            assertInk(key, false);
        AccessibilityNodeInfo row = candidateRow(visibleForm("～月"));
        try {
            for (Card option : rowCards(row)) {
                if (option.lines.size() != 2 || option.lines.get(0).trim().isEmpty())
                    throw new AssertionError("Unglossed handwriting candidate: " + option.name);
            }
        } finally { row.recycle(); }
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
        expectText(host.first, "pɔ⁵⁵");
        drawLi(pad);
        expectText(host.first, "pɔ⁵⁵li²¹");
        setHandwritingOutput(true);
        press("拼音");
        press("设置");
        waitForNode("拼音输出 · 选择候选上屏后的写法", false);
        press("设置");
        captureFrame("backspace-pinyin.png");
        press("音标");
        captureFrame("backspace-ipa.png");
        press("拼音");
    }

    private void testCandidateLayout() {
        newPredictionEditor();
        waitForNode("拼音", false);
        if (suite.equals("typing-only")) waitAbsent("手写");
        press("调号拼音");
        type("ga");
        expectFirst("乌鸦，ga");
        checkCandidateLayout("ga", true);
        captureFrame("candidate-pinyin.png");
        tapForm("ga");
        expectText(host.first, "ga");
        press("空格");
        press("音标");
        waitForNode("补全 那，ai⁵⁵", false);
        press("m");
        press("a");
        expectOriginalAfterMatches("ma");
        pressCandidate("补全 奶，mã²¹", false);
        expectText(host.first, "ga mã²¹");
        expectFirst("奶，mã²¹");
        checkCandidateLayout("mã²¹", true);
        captureFrame("candidate-ipa.png");
        String word = "mã²¹pu³⁵li⁵⁵";
        pressCandidate("补全 乳头，" + word, false);
        expectText(host.first, "ga " + word);
        expectFirst("乳头，" + word);
        press("空格");
        press("n");
        expectText(host.first, "ga " + word + " n");
    }

    private void checkCandidateLayout(String witness, boolean green) {
        Card selected = visibleForm(witness);
        AccessibilityNodeInfo row = candidateRow(selected);
        try {
            int previousRight = -1;
            for (Card card : rowCards(row)) {
                if (card.bounds.left <= previousRight) throw new AssertionError("Missing gap at " + card.name);
                for (Rect line : card.lineBounds) {
                    if (!card.bounds.contains(line) || Math.abs(line.centerX() - card.bounds.centerX()) > 1)
                        throw new AssertionError("Gloss and form must share the same cell center: " + card.name);
                }
                Rect form = card.lineBounds.get(card.lineBounds.size() - 1);
                android.util.DisplayMetrics metrics = host.getResources().getDisplayMetrics();
                android.graphics.Paint font = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
                font.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                font.setTextSize(android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 16, metrics));
                if (font.measureText(outputLine(card)) > form.width() - 12 * metrics.density + 1
                        && form.height() < font.getFontSpacing() * 1.65f)
                    throw new AssertionError("A candidate needs another line: " + card.name);
                previousRight = card.bounds.right;
            }
        } finally { row.recycle(); }
        if (green) assertInk(selected.lineBounds.get(selected.lineBounds.size() - 1), true);
    }

    private void assertInk(Rect key, boolean green) {
        android.graphics.Bitmap shot = ui.takeScreenshot();
        if (shot == null) throw new AssertionError("Missing keyboard screenshot");
        try {
            int count = 0;
            for (int y = key.top; y < key.bottom; y++) {
                for (int x = key.left; x < key.right; x++) {
                    int pixel = shot.getPixel(x, y);
                    int r = android.graphics.Color.red(pixel), g = android.graphics.Color.green(pixel);
                    if (green ? g > r + 30 && g < 150 : r < 140 && g < 140) count++;
                }
            }
            if (count < 5) throw new AssertionError("Missing " + (green ? "green" : "icon") + " ink in " + key);
        } finally { shot.recycle(); }
    }

    private void testHandToneReadings() {
        stage("Four distinct handwriting tones come before predictions");
        newPredictionEditor();
        press("手写");
        setHandwritingOutput("叶氏拼音");
        Rect pad = waitForNode("汉字手写区", false);
        drawStroke(pad, .26f,.28f, .31f,.33f, .36f,.52f);
        drawStroke(pad, .30f,.28f, .35f,.29f, .68f,.25f, .73f,.28f, .66f,.42f);
        drawStroke(pad, .37f,.47f, .43f,.48f, .69f,.45f);
        drawStroke(pad, .46f,.08f, .51f,.12f, .50f,.53f, .48f,.67f, .38f,.79f, .15f,.88f);
        drawStroke(pad, .22f,.56f, .34f,.59f, .65f,.84f, .74f,.89f, .94f,.88f);
        expectText(host.first, "siv", 90000);
        assertHandTonePage("siv", "six", "sir", "sif");
        checkCandidateLayout("siv", true);
        captureFrame("hand-tones-ye.png");
        tapForm("sir");
        expectText(host.first, "sir");

        tapField(host.second);
        setHandwritingOutput("国际音标");
        pad = waitForNode("汉字手写区", false);
        drawStroke(pad, .41f,.36f, .46f,.37f, .81f,.30f, .87f,.31f);
        drawStroke(pad, .65f,.11f, .69f,.16f, .70f,.64f, .68f,.71f, .54f,.66f);
        drawStroke(pad, .45f,.47f, .51f,.51f, .54f,.55f);
        drawStroke(pad, .22f,.14f, .30f,.19f, .33f,.23f);
        drawStroke(pad, .13f,.44f, .18f,.44f, .28f,.41f, .31f,.44f, .27f,.54f, .32f,.68f, .30f,.71f);
        drawStroke(pad, .12f,.76f, .16f,.78f, .30f,.74f, .67f,.85f, .75f,.87f, .92f,.83f);
        expectText(host.second, "kuɔ²⁴", 90000);
        assertHandTonePage("kuɔ²⁴", "kuɔ⁵⁵", "kuɔ⁵¹", "kuɔ²¹");
        captureFrame("hand-tones-ipa.png");
        tapForm("kuɔ²¹");
        expectText(host.second, "kuɔ²¹");
        setHandwritingOutput(true);
    }

    private void assertHandTonePage(String... expected) {
        AccessibilityNodeInfo row = candidateRow(visibleForm(expected[0]));
        try {
            List<String> forms = new ArrayList<>();
            for (Card item : rowCards(row)) forms.add(outputLine(item));
            if (!forms.equals(Arrays.asList(expected))) throw new AssertionError("Unexpected tone page: " + forms);
        } finally { row.recycle(); }
    }

    void holdKey(String label) {
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
        assertEditorAboveKeyboard(host.first, "拼音");

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
        expectText(host.second, "ngafyergaf");
        expectOriginalAfterMatches("ngafyergaf");
        pressCandidate("，ngaf yer gaf", true);
        expectText(host.second, "ngaf yer gaf");
        expectText(host.first, "麻麻爹诶及");

        press("退格");
        expectText(host.second, "ngaf yer ga");
        press("隐藏键盘");
        waitAbsent("隐藏键盘");
        tapField(host.second);
        press("谐音字");
        assertEditorAboveKeyboard(host.second, "拼音");
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
        waitForNode("干、渴、夹，夹", false);
        pressCandidate("吃、咬，架", false);
        expectText(host.second, "ngaf yer ga诶sangx架");
        press("退格");
        expectText(host.second, "ngaf yer ga诶sangx");

        press("设置");
        press("返回拼音");
        waitSelectedControl("拼音");
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
        type("gafhxif");
        Card eating = card("吃、咬，架");
        if (!"架".equals(outputLine(eating)) || !eating.name.contains("吃"))
            throw new AssertionError("Unexpected brief sense or word: " + eating.lines);
        openReader(eating);
        if (!visibleControls().contains("吃、咬"))
            throw new AssertionError("Full sense missing from reader");
        expectText(host.second, "ngaf yer ga诶sangx中国");
        assertEditorAboveKeyboard(host.second, "返回候选");
        press("返回候选");
        waitForNode("待选拼写 gafhxif", false);
        pressCandidate("吃、咬，架", false);
        expectText(host.second, "ngaf yer ga诶sangx中国架");
        waitForNode("待选拼写 hxif", false);
    }

    private void testVerbatimSpelling() {
        stage("Verbatim spelling stays first, widens with the draft, and uses exact glosses");
        for (String output : new String[]{"叶氏拼音", "调号拼音"}) {
            runOnMainSync(() -> host.first.setText(""));
            tapField(host.first);
            press(output);
            type("p");
            expectText(host.first, "p");
            expectOriginalAfterMatches("p");
            press("退格");
            expectText(host.first, "");
            type("pifpav");
            expectText(host.first, "pifpav");
            expectOriginalAfterMatches("pifpav");
            Card original = findPagedCard("原文，pifpav", true);
            if (!original.lines.equals(Collections.singletonList("pifpav")))
                throw new AssertionError("Unexpected gloss on unmatched spelling: " + original.lines);
            if (!original.bounds.contains(waitForNode("待选拼写 pifpav", false)))
                throw new AssertionError("Original spelling still appears in the toolbar");
            tap(original.bounds.centerX(), original.bounds.centerY());
            expectText(host.first, "pifpav");

            type("ga");
            expectText(host.first, "pifpav ga");
            boolean marked = "调号拼音".equals(output);
            if (marked) expectFirst("乌鸦，ga");
            else expectOriginalAfterMatches("ga");
            String recommendation = marked ? "gá" : "gaf";
            pressCandidate("，" + recommendation, true);
            expectText(host.first, "pifpav " + recommendation);
            if (!marked) {
                type("bizika");
                if (visibleForm("bifzixkar").bounds.width() <= visibleForm("bizika").bounds.width())
                    throw new AssertionError("The longer Ye recommendation should have the wider card");
                pressCandidate("，bifzixkar", true);
            }
        }

        runOnMainSync(() -> host.first.setText(""));
        tapField(host.first);
        press("叶氏拼音");
        type("marmardiexhxif");
        expectText(host.first, "marmardiexhxif");
        pressCandidate("，marmardiex", true);
        expectText(host.first, "marmardiex hxif");
        waitForNode("待选拼写 hxif", false);
        press("空格");
        expectText(host.first, "marmardiex hxif ");

        runOnMainSync(() -> host.first.setText(""));
        tapField(host.first);
        press("谐音字");
        type("ga");
        int singleHan = visibleForm("架").bounds.width();
        if (singleHan != visibleForm("夹").bounds.width())
            throw new AssertionError("One-character Han choices should have the same minimum width despite their glosses");
        press("退格");
        press("退格");
        type("bizika");
        if (visibleForm("秘兹佧").bounds.width() <= singleHan)
            throw new AssertionError("The longer Han choice should be wider");
        pressCandidate("，秘兹佧", true);
        expectText(host.first, "秘兹佧");
    }

    private void testUserDictionary() {
        stage("Two confirmed originals unlock a personal completion");
        String prefix = "qvq", word = "qvqvqv", completion = "用户词，" + word;
        for (String form : new String[]{"叶氏拼音", "音标"}) {
            newPredictionEditor();
            press(form);
            for (char letter : word.toCharArray()) press(String.valueOf(letter));
            press("空格");
            for (char letter : prefix.toCharArray()) press(String.valueOf(letter));
            waitAbsent(completion);
            for (char letter : word.substring(prefix.length()).toCharArray()) press(String.valueOf(letter));
            expectOriginalAfterMatches(word);
            press("空格");
            for (char letter : prefix.toCharArray()) press(String.valueOf(letter));
            expectText(host.first, word + " " + word + " " + prefix);
            waitForNode(completion, false);
            expectFirst(completion);
            pressCandidate(completion, false);
            expectText(host.first, word + " " + word + " " + word);
            if ("音标".equals(form)) {
                int end = 3 * word.length() + 2;
                runOnMainSync(() -> host.first.setSelection(end - 1));
                waitForNode("输入音标查看候选", false);
                runOnMainSync(() -> host.first.setSelection(end));
                waitForNode("无匹配候选", false);
            }
        }
        runOnMainSync(() -> host.nativeScenario(InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false));
        tapField(host.first);
        press("叶氏拼音");
        type(prefix);
        waitAbsent(completion);
        runOnMainSync(() -> host.nativeScenario(InputType.TYPE_CLASS_TEXT,
                EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false));
        tapField(host.first);
        press("音标");
        for (char letter : prefix.toCharArray()) press(String.valueOf(letter));
        expectText(host.first, prefix);
        waitForNode("无匹配候选", false);
        waitAbsent(completion);
    }

    private void testSpellingOnSpace() {
        stage("Space selects and separates a complete spelling, with a verbatim fallback");
        newPredictionEditor();
        press("叶氏拼音");
        type("ngafyer");
        expectText(host.first, "ngafyer");
        press("空格");
        expectText(host.first, "ngaf yer ");
        type("pifpav");
        press("空格");
        expectText(host.first, "ngaf yer pifpav ");
        press("调号拼音");
        type("bizika");
        press("空格");
        expectText(host.first, "ngaf yer pifpav bízīkǎ ");
    }

    private void checkPersonalIpa(String prefix, String word, boolean available) {
        newPredictionEditor();
        press("音标");
        for (char letter : prefix.toCharArray()) press(String.valueOf(letter));
        expectText(host.first, prefix);
        String learned = "用户词，" + word;
        if (available) {
            waitForNode(learned, false);
        } else {
            waitForNode("无匹配候选", false);
            waitAbsent(learned);
        }
    }

    private void testPersonalLearningControls() {
        stage("Disabling personal words preserves them, re-enabling restores them, clearing removes them");
        checkPersonalIpa("qvq", "qvqvqv", true);
        openKeyboardSettings();
        launcherControl("学习并使用个人词语", false, true);
        returnFromKeyboardSettings();
        checkPersonalIpa("qvq", "qvqvqv", false);
        newPredictionEditor();
        press("音标");
        for (int use = 0; use < 2; use++) {
            for (char letter : "qzxqzx".toCharArray()) press(String.valueOf(letter));
            press("空格");
        }
        expectText(host.first, "qzxqzx qzxqzx ");
        openKeyboardSettings();
        launcherControl("学习并使用个人词语", false, true);
        returnFromKeyboardSettings();
        checkPersonalIpa("qzx", "qzxqzx", false);
        checkPersonalIpa("qvq", "qvqvqv", true);
        openKeyboardSettings();
        launcherControl("清除个人词库", false, true);
        launcherControl("取消", false, true);
        launcherControl("清除个人词库", false, false);
        returnFromKeyboardSettings();
        checkPersonalIpa("qvq", "qvqvqv", true);
        openKeyboardSettings();
        launcherControl("清除个人词库", false, true);
        launcherControl("清除", false, true);
        launcherControl("清除个人词库", false, false);
        returnFromKeyboardSettings();
        checkPersonalIpa("qvq", "qvqvqv", false);
    }

    private void testLearningPredictions() {
        stage("Regional fuzzy spelling and fixed next words commit to a separate editor");
        newPredictionEditor();
        press("调号拼音");
        type("fa");
        pressCandidate("，huā", true);
        expectText(host.first, "huā");
        newPredictionEditor();
        press("谐音字");
        type("zaolai");
        pressCandidate("早饭，早来", false);
        expectFirst("联想 吃、咬，架");
        pressCandidate("联想 吃、咬，架", false);
        expectText(host.first, "早来架");
    }

    private void testPrivateKeyboard() {
        stage("Password editors use direct input and hide learned and official candidates");
        runOnMainSync(() -> host.nativeScenario(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                EditorInfo.IME_ACTION_DONE, false));
        tapField(host.first);
        waitForNode("英文直输", false);
        awaitKeyboardAtRest();
        press("a");
        expectText(host.first, "a");
        waitAbsent("音标");
        if (!pageCards("用户词，").isEmpty() || !pageCards("联想 ").isEmpty())
            throw new AssertionError("Password editor displayed candidates");
    }

    private void testSeparatorAfterChoice() {
        stage("A new letter inserts the separator after choosing a word");
        newPredictionEditor();
        press("音标");
        press("m");
        press("a");
        pressCandidate("补全 奶，mã²¹", false);
        expectText(host.first, "mã²¹");
        press("n");
        expectText(host.first, "mã²¹ n");

        newPredictionEditor();
        press("调号拼音");
        type("pifpav");
        pressCandidate("原文，pifpav", false);
        press("空格");
        type("n");
        expectText(host.first, "pifpav n");
    }

    private void testDirectionalTones() {
        stage("Ye spelling keeps a long-held vowel as ordinary input");
        newPredictionEditor();
        press("叶氏拼音");
        holdKey("a");
        waitForNode("待选拼写 a", false);
        expectText(host.first, "a");
        waitAbsent("取消选声调");
        stage("Marked spelling and Han output share a four-direction vowel picker");
        for (String output : new String[]{"调号拼音", "谐音字"}) {
            newPredictionEditor();
            press(output);
            waitForNode("a", false);
            SystemClock.sleep(350);
            captureFrame(output.equals("调号拼音") ? "tone-marked-idle.png" : "tone-han-idle.png");
            holdKey("a");
            Rect level = waitForNode("ā", false), rising = waitForNode("á", false);
            Rect fallingRising = waitForNode("ǎ", false), falling = waitForNode("à", false);
            captureFrame(output.equals("调号拼音") ? "tone-marked-popup.png" : "tone-han-popup.png");
            if (!(level.centerY() < rising.centerY() && fallingRising.centerY() > rising.centerY()
                    && falling.centerX() > level.centerX() && falling.centerY() == level.centerY()
                    && rising.centerX() > level.centerX())) {
                throw new AssertionError("Tone choices did not surround the vowel in four directions");
            }
            press("ā");
            waitForNode("待选拼写 ā", false);
            expectText(host.first, output.equals("调号拼音") ? "ā" : "");
        }
        stage("Tone can be selected by holding and sliding without lifting");
        newPredictionEditor();
        press("调号拼音");
        Rect vowel = waitForNode("a", false);
        long toneDown = SystemClock.uptimeMillis();
        inject(toneDown, MotionEvent.ACTION_DOWN, vowel.centerX(), vowel.centerY());
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180);
        Rect upwardTone = waitForNode("ā", false);
        inject(toneDown, MotionEvent.ACTION_MOVE, upwardTone.centerX(), upwardTone.centerY());
        SystemClock.sleep(160);
        captureFrame("tone-marked-slide.png");
        inject(toneDown, MotionEvent.ACTION_UP, upwardTone.centerX(), upwardTone.centerY());
        expectText(host.first, "ā");
        stage("IPA alternatives can be selected with a single hold and slide");
        newPredictionEditor();
        press("音标");
        Rect anchor = waitForNode("t", false);
        long down = SystemClock.uptimeMillis();
        inject(down, MotionEvent.ACTION_DOWN, anchor.centerX(), anchor.centerY());
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 180);
        Rect target = waitForNode("tsʰ", false);
        inject(down, MotionEvent.ACTION_MOVE, target.centerX(), target.centerY());
        inject(down, MotionEvent.ACTION_UP, target.centerX(), target.centerY());
        expectText(host.first, "tsʰ");
    }

    void testToneHints150() {
        stage("Vowels show a tone example and long-press indicator at 150%");
        newPredictionEditor();
        press("拼音");
        tapControl("设置");
        String previous = null;
        for (String form : new String[]{"叶氏拼音", "调号拼音", "谐音字"})
            if (isSelectedControl(form)) previous = form;
        if (previous == null) throw new AssertionError("Could not read the active typing output");
        tapControl("设置");
        try {
            press("调号拼音");
            for (String hint : new String[]{"ā ▾", "ē ▾", "ī ▾", "ō ▾", "ū ▾"}) {
                if (pollNode(hint, false, 5000) == null)
                    throw new AssertionError("Missing vowel tone hint: " + hint);
            }
            captureFrame("typing-tone-hints-150.png");
            holdKey("a");
            Rect level = waitForClickableNode("ā");
            tap(level.centerX(), level.centerY());
            expectText(host.first, "ā");
        } finally { press(previous); }
    }

    private void testOutputButtons() {
        String previous = setStyleFromLauncher("谐音字");
        try {
            testVerbatimSpelling();
            stage("Three direct output choices preserve an unfinished spelling");
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
                press("拼音");
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
                if ("谐音字".equals(form[0])) tapForm("秘兹佧");
                else {
                    longPressHandForm("秘兹佧");
                    expectText(host.first, "前秘兹佧后");
                    tapControl("用" + form[0]);
                }
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
        expectOriginalAfterMatches("bifzika");
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
        expectFirst("乌鸦，ga");
        pressCandidate("吃、咬，gá", false);
        expectText(host.first, prefix + "gá");
        type("zhongguo");
        pressCandidate("汉语，中国", false);
        expectText(host.first, prefix + "gá 中国");

        type("surufa");
        pressCandidate("汉语，输入法", false);
        prefix += "gá 中国输入法";
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
        expectFirst("从前，āizū");
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
        if (romanLoan.lines.size() != 2 || !romanLoan.lines.get(0).contains("山歌") || !"sānggō".equals(romanLoan.lines.get(1)))
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

    private void openKeyboardSettings() {
        Intent settings = new Intent().setClassName(IME_PACKAGE, IME_PACKAGE + ".SetupActivity");
        settings.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        getTargetContext().startActivity(settings);
    }

    private void testCrashRecordingDefault() {
        stage("Local diagnostic operation recording starts disabled and a saved opt-in persists");
        openKeyboardSettings();
        String caption = englishSettings() ? "Attach keyboard actions" : "附带键盘操作";
        assertSettingChecked(caption, false);
        launcherControl(caption, false, true);
        assertSettingChecked(caption, true);
        launcherControl(englishSettings() ? "Android field flags" : "输入框的系统标记", true, false);
        returnFromKeyboardSettings();
        openKeyboardSettings();
        assertSettingChecked(caption, true);
        launcherControl(caption, false, true);
        returnFromKeyboardSettings();
    }

    private void assertSettingChecked(String caption, boolean expected) {
        launcherControl(caption, false, false);
        AccessibilityNodeInfo root = ui.getRootInActiveWindow();
        Boolean checked = null;
        try {
            for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(caption)) {
                try {
                    if (node.isCheckable()) checked = node.isChecked();
                } finally { node.recycle(); }
            }
        } finally { root.recycle(); }
        if (!Boolean.valueOf(expected).equals(checked))
            throw new AssertionError(caption + " should have checked=" + expected + "; saw " + checked);
    }

    private String setStyleFromLauncher(String requested) {
        openKeyboardSettings();
        String label = launcherControl("拼音输出 · ", true, false);
        String previous = label.substring("拼音输出 · ".length());
        if (requested != null && !requested.equals(previous)) {
            launcherControl(label, false, true);
            String example = "调号拼音".equals(requested) ? "bízīkǎ"
                    : "叶氏拼音".equals(requested) ? "bifzixkar" : "秘兹佧";
            launcherControl(requested + "，" + example, false, true);
            launcherControl("拼音输出 · " + requested, false, false);
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
            waitForUnrelatedSystemApp();
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) {
                try {
                    if (IME_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                        for (AccessibilityNodeInfo node : root.findAccessibilityNodeInfosByText(query)) {
                            try {
                                if (!node.refresh()) continue;
                                String value = node.getText() == null ? "" : node.getText().toString();
                                String description = node.getContentDescription() == null ? "" : node.getContentDescription().toString();
                                boolean matchesText = prefix ? value.startsWith(query) : value.equals(query);
                                boolean matchesDescription = prefix ? description.startsWith(query) : description.equals(query);
                                if (!matchesText && !matchesDescription) continue;
                                if (!matchesText) value = description;
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
        pressCandidate("小孩，波俐", false);
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
                {"riv", "将做，日"}, {"zix", "两，兹"}, {"cix", "此"}, {"six", "使"}
        };
        StringBuilder committed = new StringBuilder();
        for (String[] example : examples) {
            type(example[0]);
            if (example[0].equals("bangricai")) {
                stage("Real meaning above noon");
                Card noon = card("中午，半日猜");
                if (!noon.lines.equals(Arrays.asList("中午", "半日猜")))
                    throw new AssertionError("Unexpected displayed noon gloss: " + noon.lines);
            }
            if (example[0].startsWith("ri")) {
                Card day = visibleForm("日");
                if (example[1].contains("，") && !day.name.contains(example[1].split("，")[0]))
                    throw new AssertionError("Missing lexical meaning for " + example[0] + ": " + day.name);
                if (example[0].equals("rif")) {
                    if (!day.lines.equals(Collections.singletonList("日")))
                        throw new AssertionError("Bare syllable has a displayed gloss: " + day.lines);
                    openReader(day);
                    String detail = visibleControls();
                    if (!detail.contains("待输入\n日") || detail.contains("音节"))
                        throw new AssertionError("Bare syllable reader is mislabeled: " + detail);
                    press("返回候选");
                    day = visibleForm("日");
                }
                tap(day.bounds.centerX(), day.bounds.centerY());
            } else if (!example[1].contains("，")) {
                Card written = findPagedCard(example[1], false);
                tap(written.bounds.centerX(), written.bounds.centerY());
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
        drawLi(pad);
        expectText(host.first, "首波俐");
        if (!pad.equals(waitForNode("汉字手写区", false)))
            throw new AssertionError("Writing the next character moved the pad");
        tapForm("波俐");
        expectText(host.first, "首波俐");
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
        tapForm("～俐");
        expectText(host.second, corrected + "波俐");
        Card prediction = nextSoundCard();
        tap(prediction.bounds.centerX(), prediction.bounds.centerY());
        String expectedSecond = corrected + "波俐" + appendedText(prediction);
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

    void longPressHandForm(String value) {
        Card item = visibleForm(value);
        long start = SystemClock.uptimeMillis();
        inject(start, MotionEvent.ACTION_DOWN, item.bounds.centerX(), item.bounds.centerY());
        SystemClock.sleep(ViewConfiguration.getLongPressTimeout() + 250);
        inject(start, MotionEvent.ACTION_UP, item.bounds.centerX(), item.bounds.centerY());
        waitForNode("返回手写", false);
    }

    private void testHandLookupActions() {
        stage("Handwriting tap replaces the matching suffix; long press shows the meaning");
        newPredictionEditor();
        press("手写");
        if (Math.abs(host.getResources().getConfiguration().fontScale - 1.5f) < .001f)
            new SystemTypography(this, host).assertToolbarUnderline("手写");
        setHandwritingOutput(true);
        runOnMainSync(() -> { host.first.setText("前波粒后"); host.first.setSelection(3); });
        Card first = visibleForm("波俐"), second = visibleForm("波立");
        int minimum = Math.round(47 * host.getResources().getDisplayMetrics().density);
        if (first.bounds.height() < minimum || second.bounds.height() < minimum)
            throw new AssertionError("Handwriting candidates are cramped");
        if (findNode("改词", false) != null || findNode("续写", false) != null)
            throw new AssertionError("Handwriting still requires a tab switch");
        AccessibilityNodeInfo combinedRow = candidateRow(first);
        try {
            if (rowCards(combinedRow).stream().noneMatch(item -> item.name.startsWith("续写 ")
                    || item.name.startsWith("联想 ") || item.name.startsWith("下一音节 ")))
                throw new AssertionError("A replacement and append choice should share the first page");
        } finally { combinedRow.recycle(); }
        if (first.bounds.right > second.bounds.left)
            throw new AssertionError("Handwriting candidates overlap: " + first.bounds + " / " + second.bounds);
        for (Rect line : first.lineBounds) if (!first.bounds.contains(line))
            throw new AssertionError("Candidate text extends beyond its card: " + line + " / " + first.bounds);
        if (suite.equals("hand-ux")) captureFrame("hand-ux-main.png");
        longPressHandForm("波俐");
        expectText(host.first, "前波粒后");
        waitForNode("已写「波粒」", false);
        if (suite.equals("hand-ux")) captureFrame("hand-ux-detail.png");
        press("返回手写");
        expectText(host.first, "前波粒后");
        tapForm("波俐");
        expectText(host.first, "前波俐后");

        stage("Handwriting eraser clears pending ink and backspace edits ink before text");
        Rect pad = waitForNode("汉字手写区", false);
        Rect eraser = waitForNode("重写", false), backspace = waitForNode("退格", false);
        if (eraser.right > backspace.left || eraser.centerY() != backspace.centerY()
                || eraser.left < pad.right || eraser.height() < minimum || backspace.height() < minimum)
            throw new AssertionError("Handwriting editing keys are misplaced: " + eraser + " / " + backspace);
        drawStroke(pad, .20f, .28f, .78f, .28f);
        drawStroke(pad, .20f, .60f, .78f, .60f);
        tap(eraser.centerX(), eraser.centerY());
        drawStroke(pad, .20f, .45f, .78f, .45f);
        tap(backspace.centerX(), backspace.centerY());
        SystemClock.sleep(1600);
        expectText(host.first, "前波俐后");
        tap(backspace.centerX(), backspace.centerY());
        expectText(host.first, "前波后");

        stage("Handwriting appends only the text after the wave mark, including through the reader");
        Card continuation = visibleForm("～俐");
        if (!continuation.lines.equals(Arrays.asList("小孩", "～俐")))
            throw new AssertionError("Expected the completed word's meaning above its next character: " + continuation.lines);
        if (suite.equals("hand-ux")) {
            stage("Append glyph and gloss bounds: " + continuation.lineBounds);
            captureFrame("hand-ux-append.png");
        }
        openReader(continuation);
        expectText(host.first, "前波后");
        press("输入这个候选");
        expectText(host.first, "前波俐后");
        press("退格");
        expectText(host.first, "前波后");

        stage("A handwritten character, punctuation and a space keep their order at the caret");
        Rect comma = waitForNode("逗号", false);
        waitForNode("切换输入法", false);
        drawThree(pad);
        tap(comma.centerX(), comma.centerY());
        expectText(host.first, "前波三，后", 90000);
        press("空格");
        press("句号");
        press("更多标点");
        press("？");
        expectText(host.first, "前波三， 。？后");
        if (!pad.equals(waitForNode("汉字手写区", false)))
            throw new AssertionError("Handwriting pad moved after punctuation");

        stage("Choose a complete visible match and preserve the text around the cursor");
        runOnMainSync(() -> { host.first.setText("前树稀步李以后"); host.first.setSelection(5); });
        tapForm("希布里");
        expectText(host.first, "前树希布里以后");

        stage("Apply a dictionary extension at the caret in one tap");
        runOnMainSync(() -> { host.first.setText("前波粒后"); host.first.setSelection(3); });
        visibleForm("波俐");
        Card longer = findPagedCard("波立垄", false);
        if (!longer.lines.equals(Arrays.asList("生孩子", "波立垄"))) throw new AssertionError("Wrong full-word gloss: " + longer.lines);
        if (suite.equals("hand-ux")) {
            stage("Mixed extension bounds: " + longer.lineBounds);
            captureFrame("hand-ux-extension.png");
        }
        tap(longer.bounds.centerX(), longer.bounds.centerY());
        expectText(host.first, "前波立垄后");
        runOnMainSync(() -> { host.first.setText("前波立后"); host.first.setSelection(3); });
        visibleForm("波立");
        Card unchangedPrefix = findPagedCard("～庆爹", false);
        if (!unchangedPrefix.lines.equals(Arrays.asList("子女", "～庆爹"))) throw new AssertionError("Wrong two-character ending: " + unchangedPrefix.lines);
        tap(unchangedPrefix.bounds.centerX(), unchangedPrefix.bounds.centerY());
        expectText(host.first, "前波立庆爹后");

        stage("A grouped candidate applies its longest visible match and joined spelling");
        runOnMainSync(() -> { host.first.setText("前阿安后"); host.first.setSelection(3); });
        tapForm("˻阿安˼");
        expectText(host.first, "前˻阿安˼后");
        assertJoinedRanges(host.first, 2, 4);
    }

    private void testIpaDetail() {
        stage("IPA candidate tap completes; long press opens meaning without changing the editor");
        newPredictionEditor();
        press("音标");
        waitForNode("补全 那，ai⁵⁵", false);
        if (suite.equals("ipa-detail")) captureFrame("ipa-ux-main.png");
        press("m");
        captureFrame("ipa-ux-m.png");
        press("a");
        expectText(host.first, "ma");
        captureFrame("ipa-ux-ma.png");
        openReader(card("补全 奶，mã²¹"));
        expectText(host.first, "ma");
        if (!visibleControls().contains("奶")) throw new AssertionError("IPA detail omitted the meaning");
        if (suite.equals("ipa-detail")) captureFrame("ipa-ux-detail.png");
        press("输入这个候选");
        expectText(host.first, "mã²¹");
        captureFrame("ipa-ux-committed.png");
    }

    private void testHandwrittenSuffixes() {
        stage("Dialect homophones and every matching suffix of the last four written characters");
        runOnMainSync(() -> host.first.setText("前树稀步李以后"));
        tapField(host.first);
        press("手写");
        setHandwritingOutput(true);
        runOnMainSync(() -> host.first.setSelection(5));
        Rect pad = waitForNode("汉字手写区", false);
        Card longest = visibleForm("树希布里");
        List<String> forms = new ArrayList<>();
        AccessibilityNodeInfo row = candidateRow(longest);
        try {
            do {
                for (Card item : rowCards(row)) {
                    String form = outputLine(item);
                    if (!item.name.startsWith("续写 ") && !item.name.startsWith("更正并续写 ")
                            && !item.name.startsWith("联想 ") && !item.name.startsWith("下一音节 ")) forms.add(form);
                }
            } while (forms.size() < 4 && advanceCandidatePage(row));
        } finally { row.recycle(); }
        if (forms.size() < 4 || !forms.subList(0, 4).equals(Arrays.asList("树希布里", "希布里", "昔布里", "布里")))
            throw new AssertionError("Expected overlapping dialect matches in order: " + forms);
        if (findNode("希布里", false) == null) press("上一页候选");
        longPressHandForm("希布里");
        expectText(host.first, "前树稀步李以后");
        press("用调号拼音");
        expectText(host.first, "前树xìbúlī以后");
        if (!pad.equals(waitForNode("汉字手写区", false))) throw new AssertionError("Handwriting panel resized");
        runOnMainSync(() -> { host.first.setText("前波粒后"); host.first.setSelection(3); });
        visibleForm("波立");
        tapForm("波俐");
        expectText(host.first, "前波俐后");
        runOnMainSync(() -> { host.first.setText("借米提卡铺"); host.first.setSelection(5); });
        visibleForm("卡铺");
        waitAbsent("指甲花，借米提卡铺");
        runOnMainSync(() -> { host.first.setText("树稀。步李"); host.first.setSelection(5); });
        visibleForm("布里");
        waitAbsent("板栗，树希布里");
        runOnMainSync(() -> { host.first.setText("前阿安后"); host.first.setSelection(3); });
        Card pronoun = visibleForm("˻阿安˼");
        if (pageCards("阿安").size() != 1) throw new AssertionError("The two suffix boundaries must share a card");
        longPressHandForm("˻阿安˼");
        waitForNode("已写「阿安」", false);
        for (int i = 0; i < 8 && findNode("已写「安」", false) == null; i++) press("切换匹配或义项");
        waitForNode("已写「安」", false);
        press("用推荐字");
        expectText(host.first, "前阿˻阿安˼后");
        longPressHandForm("˻阿安˼");
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
        String[] labels = {"拼音", "手写", "音标", "设置", "隐藏键盘"};
        if (findNode("拼音", false) == null) return null;
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
                                if (k == labels.length - 1 && !android.text.TextUtils.isEmpty(child.getText())) continue;
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
                stage("One IME toolbar, 拼音/手写/音标/设置/收起 bounds: " + Arrays.toString(controls));
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
        if (!pronoun.lines.equals(Arrays.asList("讨", "过")))
            throw new AssertionError("Expected the current 讨／过 card: " + pronoun.lines);
        captureFrame("sense-go.png");
        openReader(pronoun);
        String detail = visibleControls();
        if (!detail.contains("汉义") || !detail.contains("讨") || !detail.contains("过错") || !detail.contains("待输入"))
            throw new AssertionError("Both meanings must remain available in the reader: " + detail);
        expectText(host.first, "");
        press("返回候选");
        int goPages = verifyUniqueFormsAcrossPages("过");
        stage("Across all " + goPages + " go pages, one 过 card shows 讨, with the loan 过错 kept in the reader");
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
        Card horse = visibleForm("马");
        if (!horse.name.contains("马年") || !horse.name.contains("马")) throw new AssertionError("Missing lexical meanings for 马: " + horse.name);
        openReader(horse);
        if (!visibleControls().contains("马年") || !visibleControls().contains("待输入")) throw new AssertionError("Full lexical meaning of 马 gone: " + visibleControls());
        press("输入这个候选");
        expectText(host.first, "过郭马");
        stage("g + held o / ǒ gives 他／郭; 马 shows once on the card and retains its lexical meaning in the reader");
        type("ga");
        assertMixedPage(fourCards());
        int gaPages = verifyUniqueFormsAcrossPages("夹");
        stage("Across all " + gaPages + " ga pages each form occurs once; 夹 shows ¹上/²干");
        newPredictionEditor();
        for (int i = 0; i < 2; i++) {
            type("zaolai");
            pressCandidate("早饭，早来", false);
            type("go");
            Card ambiguous = visibleForm("过");
            tap(ambiguous.bounds.centerX(), ambiguous.bounds.centerY());
            press("句号");
        }
        type("zaolai");
        pressCandidate("早饭，早来", false);
        expectFirst("联想 吃、咬，架");
        if (!pageCards("联想 他").isEmpty())
            throw new AssertionError("Unresolved 过 incorrectly trained the first sense 他");
        expectText(host.first, "早来过。早来过。早来");
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

    private void testDictionaryDetail() {
        newPredictionEditor();
        boolean large = suite.equals("dictionary-large");
        if (large && host.getResources().getConfiguration().fontScale < 1.99f)
            throw new AssertionError("Run dictionary-large with the system font scale set to 2");
        press("谐音字");
        type("ga");
        openReader(visibleForm("架"));
        waitForNode("查看学堂词条", false);
        expectText(host.first, "");
        if (!visibleControls().contains("吃、咬"))
            throw new AssertionError("Pending candidate meaning disappeared");
        press("查看学堂词条");
        captureDictionaryFrame("dictionary-entry.png", "查看读音");
        String definition = requireArticleText("原记音　ga2", "to eat, bite");
        if (definition.contains("w684")) throw new AssertionError("Internal dictionary ID visible");
        press("查看读音");
        captureDictionaryFrame("dictionary-readings.png", "查看出处");
        String readings = requireArticleText("叶氏拼音", "gaf");
        if (readings.contains("brassett-to-ye")) throw new AssertionError("Internal conversion ID visible");
        press("查看出处");
        captureDictionaryFrame("dictionary-sources.png", "查看候选全文");
        requireArticleText("出处与原记音", "土家语汉语词表", "ga2");
        expectText(host.first, "");
        if (large) {
            press("查看候选全文");
            requireArticleText("吃、咬");
            press("返回候选");
            openReader(visibleForm("架"));
            press("查看学堂词条");
            expectText(host.first, "");
        }
        press("输入这个候选");
        expectText(host.first, "架");

        type("jia");
        openReader(findPlainHan("架"));
        if (pollNode("查看学堂词条", false, 800) != null)
            throw new AssertionError("A plain Mandarin character acquired a Tujia article");
        press("输入这个候选");
        expectText(host.first, "架架");

        type("ga");
        openReader(visibleForm("架"));
        tapField(host.second);
        waitAbsent("输入这个候选");
        if (pollNode("查看学堂词条", false, 800) != null)
            throw new AssertionError("Previous editor's article remained after a field change");
        expectText(host.first, "架架");
        expectText(host.second, "");
        tapField(host.first);
        runOnMainSync(() -> host.first.setSelection(1));
        type("ga");
        openReader(visibleForm("架"));
        runOnMainSync(() -> host.first.setSelection(0));
        waitAbsent("输入这个候选");
        if (pollNode("查看学堂词条", false, 800) != null)
            throw new AssertionError("Article remained after moving the cursor");
        expectText(host.first, "架架");
        stage("Offline definition, readings, attested sources, input, Mandarin fallback, field and caret changes");
        testDictionaryChoice();
    }

    private void testDictionaryChoice() {
        newPredictionEditor();
        press("谐音字");
        type("ga");
        openReader(visibleForm("夹"));
        press("查看学堂词条");
        captureDictionaryFrame("dictionary-choices.png", "仅显示可确认的词条");
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText("乌鸦")) {
            try {
                if (node.isVisibleToUser() && node.isClickable())
                    throw new AssertionError("A merged crow reading claimed an individual source on the 夹 card");
            } finally { node.recycle(); }
        }
        pressDictionaryChoice("干、渴");
        requireArticleText("所选文献见证", "ga3", "dry");
        press("返回词条上一级");
        pressDictionaryChoice("上面");
        requireArticleText("上面", "ga3");
        expectText(host.first, "");
        press("输入这个候选");
        expectText(host.first, "夹");
        stage("Distinct native readings of the same 夹 card open the dry and above articles independently");
    }

    private void captureDictionaryFrame(String name, String visibleLabel) {
        waitForNode(visibleLabel, false);
        if (!captureFailure) return;
        // The IME tree changes before SurfaceFlinger presents the new panel, especially on Android 7.
        try { ui.waitForIdle(450, 4000); }
        catch (java.util.concurrent.TimeoutException busyEditor) { SystemClock.sleep(450); }
        captureFrame(name);
    }

    private void pressDictionaryChoice(String gloss) { pressDictionaryChoice(gloss, false); }

    private void pressDictionaryChoice(String gloss, boolean wholeEntry) {
        waitForNode("选择学堂词条", false);
        for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(gloss)) {
            try {
                if (!node.isClickable() || node.getContentDescription() == null) continue;
                String label = node.getContentDescription().toString();
                if (wholeEntry ? !label.equals(gloss) : !label.startsWith(gloss + "，")) continue;
                node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SHOW_ON_SCREEN.getId());
                SystemClock.sleep(250);
                if (node.refresh() && node.isVisibleToUser() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    waitForNode("在词典中打开", false);
                    return;
                }
            } finally { node.recycle(); }
        }
        throw new AssertionError("Missing dictionary option " + gloss + ": " + visibleControls());
    }

    private void testDictionaryWitness() throws Exception {
        newPredictionEditor();
        press("谐音字");
        type("go");
        openReader(visibleForm("过"));
        press("查看学堂词条");
        captureDictionaryFrame("dictionary-witness-choice.png", "仅显示可确认的词条");
        pressDictionaryChoice("讨", true);
        String entry = requireArticleText("原记音　guo2", "to beg");
        if (entry.contains("所选文献见证")) throw new AssertionError("A merged reading claimed a particular source: " + entry);
        captureDictionaryFrame("dictionary-witness-entry.png", "查看读音");
        press("查看读音");
        press("查看出处");
        requireArticleText("词条 802", "STEDT rn 427219");
        expectText(host.first, "");
        press("输入这个候选");
        expectText(host.first, "过");

        // Supply the two independently visible IDs to the same installed lookup to check the other branch.
        android.content.Context app = getTargetContext().createPackageContext(IME_PACKAGE,
                android.content.Context.CONTEXT_INCLUDE_CODE | android.content.Context.CONTEXT_IGNORE_SECURITY);
        Class<?> type = app.getClassLoader().loadClass("biz.tujia.ime.ImeDictionary");
        java.lang.reflect.Constructor<?> constructor = type.getDeclaredConstructor(android.content.res.AssetManager.class);
        constructor.setAccessible(true);
        Object dictionary = constructor.newInstance(app.getAssets());
        java.lang.reflect.Method lookup = type.getDeclaredMethod("links", java.util.Map.class);
        lookup.setAccessible(true);
        java.util.Map<String, String> ids = new java.util.LinkedHashMap<>();
        ids.put("w802", "讨");
        ids.put("xu213-4-5", "过错");
        if (!wordWitnesses((List<?>)lookup.invoke(dictionary, ids)).equals(java.util.Collections.singletonMap(0, "")))
            throw new AssertionError("Unrelated word unlocked the withheld witness");
        ids.put("stedt-rn-427219", "讨");
        java.util.Map<Integer, String> actual = wordWitnesses((List<?>)lookup.invoke(dictionary, ids));
        if (actual.size() != 2 || !actual.getOrDefault(1, "").contains("词条 802")
                || !actual.getOrDefault(6, "").contains("STEDT rn 427219"))
            throw new AssertionError("Explicit witnesses did not retain both independent sources: " + actual);
        stage("go / 过 / 讨 opens the full article, hides unproven source identity, and retains both original witnesses in Sources; with both explicit IDs, the installed lookup exposes witness 1 and 6");
    }

    private java.util.Map<Integer, String> wordWitnesses(List<?> links) throws Exception {
        java.util.Map<Integer, String> result = new java.util.LinkedHashMap<>();
        for (Object link : links) {
            Class<?> type = link.getClass();
            java.lang.reflect.Field entry = type.getDeclaredField("canonicalId"),
                    witness = type.getDeclaredField("witness"), reference = type.getDeclaredField("reference");
            entry.setAccessible(true);
            witness.setAccessible(true);
            reference.setAccessible(true);
            if ("w802".equals(entry.get(link))) result.put(witness.getInt(link), (String)reference.get(link));
        }
        return result;
    }

    private String requireArticleText(String... terms) {
        StringBuilder shown = new StringBuilder();
        for (int i = 0; i < 5; i++) {
            shown.append(visibleControls());
            boolean complete = true;
            for (String term : terms) complete &= shown.indexOf(term) >= 0;
            if (complete) return shown.toString();
            if (i < 4) press("下一页释义");
        }
        throw new AssertionError("Missing dictionary text " + Arrays.toString(terms) + " in " + shown);
    }

    private void testHandDictionaryDetail() {
        newPredictionEditor();
        press("手写");
        setHandwritingOutput(true);
        String original = "前树稀步李以后";
        runOnMainSync(() -> { host.first.setText(original); host.first.setSelection(5); });
        longPressHandForm("树希布里");
        press("查看学堂词条");
        requireArticleText("板栗", "chestnut");
        expectText(host.first, original);
        captureDictionaryFrame("dictionary-hand.png", "查看读音");
        press("查看读音");
        requireArticleText("sufxixbuflix");
        press("查看出处");
        requireArticleText("出处与原记音", "su2xi1bu2li1");
        captureDictionaryFrame("dictionary-hand-sources.png", "查看候选全文");
        press("返回词条上一级");
        press("用推荐字");
        expectText(host.first, "前树希布里以后");
        stage("Handwriting opens the exact dictionary article and preserves the original until explicit replacement");
    }

    private void testOpenDictionary() {
        newPredictionEditor();
        runOnMainSync(() -> { host.first.setText("保留正文"); host.first.setSelection(4); });
        press("谐音字");
        type("ga");
        openReader(visibleForm("架"));
        press("查看学堂词条");
        press("在词典中打开");
        long end = SystemClock.uptimeMillis() + 150000;
        boolean accepted = false, found = false;
        String lastWindow = "";
        do {
            AccessibilityNodeInfo root = ui.getRootInActiveWindow();
            if (root != null) try {
                if (IME_PACKAGE.contentEquals(root.getPackageName() == null ? "" : root.getPackageName())) {
                    if (!accepted) for (AccessibilityNodeInfo button : root.findAccessibilityNodeInfosByViewId(
                            IME_PACKAGE + ":id/privacy_accept")) {
                        try { accepted |= button.performAction(AccessibilityNodeInfo.ACTION_CLICK); }
                        finally { button.recycle(); }
                    }
                    // Stock WebView on Android 7 exposes web nodes as descendants but does not
                    // implement IME-side findAccessibilityNodeInfosByText for their content.
                    StringBuilder content = new StringBuilder();
                    collectLabels(root, content);
                    lastWindow = content.toString();
                    found = lastWindow.contains("to eat, bite");
                }
            } finally { root.recycle(); }
            if (!found) SystemClock.sleep(300);
        } while (!found && SystemClock.uptimeMillis() < end);
        if (!found) throw new AssertionError("Learning window did not display eat/bite: "
                + lastWindow.substring(0, Math.min(lastWindow.length(), 1500)));
        captureFrame("dictionary-full.png");
        if (!ui.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)) throw new AssertionError("System Back failed");
        AtomicReference<Boolean> returned = new AtomicReference<>(false);
        long returnDeadline = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            runOnMainSync(() -> returned.set(host.hasWindowFocus()));
            if (!returned.get()) SystemClock.sleep(100);
        } while (!returned.get() && SystemClock.uptimeMillis() < returnDeadline);
        if (!returned.get()) throw new AssertionError("One system Back did not restore the external editor");
        expectText(host.first, "保留正文");
        stage("Trusted article opens the offline learning reader and one system Back returns to untouched external text");
    }

    // Run on a disposable fixture APK with the dictionary index for w684 removed.
    private void testMissingDictionaryDetail() {
        newPredictionEditor();
        press("谐音字");
        type("ga");
        openReader(visibleForm("架"));
        if (pollNode("查看学堂词条", false, 1500) != null || !visibleControls().contains("吃、咬"))
            throw new AssertionError("Missing dictionary resources did not fall back to the native definition");
        press("输入这个候选");
        expectText(host.first, "架");
        stage("Unreadable dictionary data preserves the candidate meaning and commit");
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
                if (word[0].equals("嫁") && i == 2 && found.lines.stream().noneMatch(line -> line.contains("先")))
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
        waitForNode("隐藏键盘", false);
        for (AccessibilityNodeInfo row : imeRoot.findAccessibilityNodeInfosByViewId(IME_PACKAGE + ":id/candidate_row")) {
            if (row.refresh() && row.isVisibleToUser()) return row;
            row.recycle();
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
        if (spoken.startsWith("～")) spoken = spoken.substring(1);
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
        if (!"麻麻爹".equals(outputLine(word)) || !word.name.contains("女人"))
            throw new AssertionError("Three-Han candidate lines: " + word.lines);
        stage("Three-Han candidate: " + word.name + " " + word.bounds.toShortString() + ", lines=" + word.lines);
        assertEditorAboveKeyboard(host.first, "拼音");
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
        for (String extra : new String[]{"叶氏拼音", "谐音字", "返回拼音", "确定拼写"}) waitAbsent(extra);
        assertEditorAboveKeyboard(host.first, "拼音");
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
                            && letter.contentEquals(node.getText() == null ? "" : node.getText())) caption = true;
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
        assertEditorAboveKeyboard(host.first, "拼音");
        captureFrame("compact-typing.png");
        tapControl("设置");
        waitSelectedControl("设置");
        waitSelectedControl("谐音字");
        waitForNode("叶氏拼音", false);
        waitForNode("返回拼音", false);
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
        press("拼音");
        tapControl("设置");
        waitSelectedControl("谐音字");
        tapControl("设置");
        waitAbsent("叶氏拼音");
        assertEditorAboveKeyboard(host.first, "拼音");
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
            type("zaolai");
            pressCandidate("早饭，早来", false);
            pressCandidate("联想 吃、咬，架", false, "吃", "架");
            expectText(host.second, "早来架");
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
            // 完整词条现在优先显示为词语补全，保留汉义并一次写入外部编辑器。
            tapField(host.second);
            type("j");
            holdTone("i", "ì");
            Card oneSense = findPagedCard("补全 紧，jìn", true);
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
            Card hand = nextSoundCard();
            String afterChild = appendedText(hand);
            if (!Character.isIdeographic(afterChild.codePointAt(0)))
                throw new AssertionError("Handwriting produced a non-Han prediction: " + hand.name);
            tap(hand.bounds.centerX(), hand.bounds.centerY());
            expectText(host.first, "波俐" + afterChild);

            tapField(host.second);
            setHandwritingOutput("调号拼音");
            Card handSpelling = nextSoundCard();
            String startingForm = appendedText(handSpelling);
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
            type("zaolai");
            pressCandidate("早饭，早来", false);
            sentence.append("早来");
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
        type("zaolai");
        pressCandidate("早饭，早来", false);
        expectFirst("联想 吃、咬，架");
        if (!pageCards("联想 中午，半日猜").isEmpty())
            throw new AssertionError("Word associations leaked into a new editor");
        expectText(host.second, "早来");

        testPrivateFieldHistory();
    }

    private void testPrivateFieldHistory() {
        stage("An editor disabling personalized learning keeps fixed dictionary predictions");
        runOnMainSync(() -> host.nativeScenario(android.text.InputType.TYPE_CLASS_TEXT,
                android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                        | android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING, false));
        tapField(host.first);
        press("谐音字");
        type("zaolai");
        pressCandidate("早饭，早来", false);
        type("bangricai");
        pressCandidate("中午，半日猜", false);
        press("句号");
        type("zaolai");
        pressCandidate("早饭，早来", false);
        waitForNode("联想 吃、咬，架", false);
        if (!pageCards("联想 中午，半日猜").isEmpty())
            throw new AssertionError("Private editor personalized a repeated continuation");
        expectText(host.first, "早来半日猜。早来");
    }

    private void testDelayedSelection() {
        stage("Repeated old selections preserve backspace and completion; real caret moves are honored");
        newPredictionEditor();
        press("调号拼音");
        type("jie");
        expectText(host.first, "jie");
        repeatOldSelection();
        press("退格");
        expectText(host.first, "ji");

        for (char key : "exie".toCharArray()) press(String.valueOf(key));
        expectText(host.first, "jiexie");
        repeatOldSelection();
        pickFarmerCompletion();
        expectText(host.first, "jiěxiěrǐmǎ");
        runOnMainSync(() -> host.first.setSelection(1));
        press("退格");
        expectText(host.first, "iěxiěrǐmǎ");
    }

    private void repeatOldSelection() {
        // The reported composing range changes twice while the real caret stays at the end.
        runOnMainSync(() -> {
            InputMethodManager imm = host.getSystemService(InputMethodManager.class);
            imm.updateSelection(host.first, 0, 0, 0, 2);
            imm.updateSelection(host.first, 0, 0, -1, -1);
        });
    }

    void pickFarmerCompletion() {
        // The keyboard can accept a draft before the offline dictionary has loaded.
        Rect item = pollNode("补全 农民，jiěxiěrǐmǎ", false, 120000);
        if (item == null) throw new AssertionError("Missing completion for jiexie");
        tap(item.centerX(), item.centerY());
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

    private String appendedText(Card card) {
        String label = outputLine(card);
        if (label.isEmpty() || label.startsWith("～"))
            throw new AssertionError("An independent prediction should show only its text: " + label);
        return label;
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

    // Full northern entries can add meanings to a card without changing its
    // action or written form. Require every expected meaning and exact output.
    private boolean candidateNameMatches(String expected, String actual) {
        if (expected.equals(actual)) return true;
        int cut = expected.lastIndexOf('，'), actualCut = actual.lastIndexOf('，');
        if (cut <= 0 || actualCut <= 0 || !expected.substring(cut).equals(actual.substring(actualCut))) return false;
        String wanted = expected.substring(0, cut), seen = actual.substring(0, actualCut);
        for (String prefix : new String[]{"补全 ", "联想 ", "下一音节 "}) {
            if (wanted.startsWith(prefix) != seen.startsWith(prefix)) return false;
            if (wanted.startsWith(prefix)) { wanted = wanted.substring(prefix.length()); seen = seen.substring(prefix.length()); }
        }
        if (wanted.equals("原文") || wanted.equals("汉语")) return false;
        List<String> seenSenses = Arrays.asList(seen.split("[、；]"));
        for (String sense : wanted.split("[、；]")) if (sense.isEmpty() || !seenSenses.contains(sense)) return false;
        return true;
    }

    private Card card(String name) {
        int comma = name.lastIndexOf('，');
        String search = comma > 0 ? name.substring(comma + 1) : name;
        long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            for (Card item : pageCards(search))
                if (candidateNameMatches(name, item.name)) return item;
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < deadline);
        throw new AssertionError("Missing card on current page: " + name + "; screen=" + visibleControls());
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

    private void expectOriginalAfterMatches(String value) {
        awaitKeyboardAtRest();
        List<Card> page = pageCards("，");
        if (!page.isEmpty() && page.get(0).name.equals("原文，" + value))
            throw new AssertionError("An unglossed echo took the first slot: " + value);
    }

    private void expectFirst(String name) {
        card(name);
        List<Card> page = pageCards("，");
        if (page.isEmpty() || !candidateNameMatches(name, page.get(0).name))
            throw new AssertionError("First candidate should be " + name + ", saw " + visibleControls());
    }

    private void assertMixedPage(List<Card> page) {
        // The first form is the more frequent lexical entry. The two senses of 夹 share its card.
        if (!page.get(0).lines.equals(Arrays.asList("吃", "架")))
            throw new AssertionError("Unexpected top ga candidate: " + page.get(0).lines);
        Card ambiguous = page.get(1);
        if (ambiguous.lines.size() != 2 || !ambiguous.lines.get(0).replace(" ", "").startsWith("¹上²干")
                || !"夹".equals(outputLine(ambiguous)))
            throw new AssertionError("The top 夹 senses changed: " + ambiguous.lines);
        openReader(ambiguous);
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
            if (!isSelectedControl("拼音")) {
                tapControl("拼音");
                waitSelectedControl("拼音");
            }
            tapControl("设置");
            tapControl(label);
            waitAbsent(label);
            waitSelectedControl("拼音");
            awaitKeyboardAtRest();
            return;
        }
        if ("手写".equals(label) || "拼音".equals(label) || "音标".equals(label)) {
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
            if (match == null && !suffix && label.lastIndexOf('，') > 0) {
                String written = label.substring(label.lastIndexOf('，') + 1);
                for (Card item : pageCards(written))
                    if (candidateNameMatches(label, item.name)) { match = item.bounds; break; }
            }
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

    void scrollKeyboardTo(String label, int minimumHeight) {
        waitForNode("设置", false);
        for (int attempt = 0; attempt < 12; attempt++) {
            Rect target = findNode(label, false);
            if (target != null && target.height() >= minimumHeight) return;
            Rect viewport = scrollableBounds(imeRoot);
            if (viewport == null) break;
            float x = viewport.exactCenterX();
            float from = viewport.top + viewport.height() * .75f;
            float to = viewport.top + viewport.height() * .45f;
            long start = SystemClock.uptimeMillis();
            inject(start, MotionEvent.ACTION_DOWN, x, from);
            for (int step = 1; step <= 8; step++) {
                SystemClock.sleep(20);
                inject(start, step == 8 ? MotionEvent.ACTION_UP : MotionEvent.ACTION_MOVE,
                        x, from + (to - from) * step / 8f);
            }
            waitForIdleSync();
        }
        throw new AssertionError("Cannot reach a complete " + label + " control: " + visibleControls());
    }

    private Rect scrollableBounds(AccessibilityNodeInfo node) {
        if (node == null || !node.refresh() || !node.isVisibleToUser()) return null;
        if ("android.widget.ScrollView".contentEquals(node.getClassName()) && node.isScrollable()) {
            Rect result = new Rect();
            node.getBoundsInScreen(result);
            return result;
        }
        for (int i = 0; i < node.getChildCount(); i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                Rect result = scrollableBounds(child);
                if (result != null) return result;
            } finally { child.recycle(); }
        }
        return null;
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

    Rect waitForClickableNode(String label) {
        long end = SystemClock.uptimeMillis() + WAIT_MS;
        do {
            findNode(label, false);
            if (imeRoot != null) for (AccessibilityNodeInfo node : imeRoot.findAccessibilityNodeInfosByText(label)) {
                try {
                    if (!node.refresh() || !node.isVisibleToUser() || !node.isEnabled() || !node.isClickable()
                            || !android.text.TextUtils.equals(label, node.getContentDescription())) continue;
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (!bounds.isEmpty()) return bounds;
                } finally { node.recycle(); }
            }
            SystemClock.sleep(60);
        } while (SystemClock.uptimeMillis() < end);
        throw new AssertionError("Missing clickable keyboard control: " + label + "; screen=" + visibleControls());
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
