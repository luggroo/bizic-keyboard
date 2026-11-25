package biz.tujia.ime.e2e;

import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.TypedValue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Changes actual system settings on a dedicated test device; samples the cross-process UI. */
final class SystemTypography {
    private final KeyboardTest test;
    private final EditorActivity host;
    private final List<String> geometryErrors = new ArrayList<>();

    SystemTypography(KeyboardTest test, EditorActivity host) {
        this.test = test;
        this.host = host;
    }

    void ipa150() throws Exception {
        String startingScale = shell("settings get system font_scale");
        try {
            checkIpaInk(1.5f, host.getResources().getDisplayMetrics().densityDpi, "ipa-layout-150");
            for (String tone : new String[]{"21", "35", "33", "13", "55", "53"})
                if (test.pollNode(tone, false, 5000) == null)
                    throw new AssertionError("Missing full-size IPA tone hint: " + tone);
            test.stage("IPA alternatives remain visible and selectable at 150% font size");
            test.holdKey("t");
            Rect previous = null;
            Rect chosen = null;
            int minimum = Math.round(47 * host.getResources().getDisplayMetrics().density);
            for (String option : new String[]{"ts", "tsʰ", "tɕ", "tɕʰ", "tʰ", "ʈ", "ʈʰ"}) {
                Rect current = test.waitForClickableNode(option);
                if (current.width() < minimum || current.height() < minimum
                        || previous != null && previous.right > current.left)
                    throw new AssertionError("Cramped IPA alternative " + option + ": " + current);
                previous = current;
                if (option.equals("tsʰ")) chosen = current;
            }
            test.captureFrame("ipa-alternatives-150.png");
            test.tap(chosen.centerX(), chosen.centerY());
            test.expectText(host.first, "mã²¹ tsʰ");
            test.holdKey("r");
            Rect tone21 = test.waitForClickableNode("声调 21");
            test.tap(tone21.centerX(), tone21.centerY());
            test.expectText(host.first, "mã²¹ tsʰ²¹");
            test.testToneHints150();
        } finally {
            if ("null".equals(startingScale)) shell("settings delete system font_scale");
            else shell("settings put system font_scale " + Float.parseFloat(startingScale));
        }
    }

    void ipa() throws Exception {
        String startingScale = shell("settings get system font_scale");
        String densities = shell("wm density");
        int physical = intAfter(densities, "Physical density"), override = intAfter(densities, "Override density");
        int baseline = override == 0 ? physical : override;
        int narrow = Math.round(host.getResources().getDisplayMetrics().widthPixels * 160f / 320);
        try {
            checkIpaInk(1f, baseline, "ipa-layout");
            checkIpaInk(1.6f, narrow, "ipa-layout-large");
            checkIpaInk(2f, narrow, "ipa-layout-largest");
            checkLargeHandAndSettings(narrow);
        } finally {
            shell("wm density " + (override == 0 ? "reset" : Integer.toString(override)));
            if ("null".equals(startingScale)) shell("settings delete system font_scale");
            else shell("settings put system font_scale " + Float.parseFloat(startingScale));
        }
    }

    private void checkIpaInk(float scale, int density, String frame) throws Exception {
        setConfiguration(scale, density);
        test.newPredictionEditor();
        test.press("打字");
        test.awaitKeyboardAtRest();
        if (Math.abs(scale - 1.5f) < .001f) assertToolbarUnderline("打字");
        Rect typingQ = test.waitForNode("q", false);
        Bitmap typing = test.getUiAutomation().takeScreenshot();
        if (typing == null) throw new AssertionError("Missing typing layout screenshot");
        int typingInk;
        try { typingInk = inkHeight(typing, typingQ); } finally { typing.recycle(); }
        if (Math.abs(scale - 1.5f) < .001f) test.captureFrame("typing-layout-150.png");
        test.press("音标");
        test.assertEditorAboveKeyboard(host.first, "音标");
        test.awaitKeyboardAtRest();
        if (Math.abs(scale - 1.5f) < .001f) {
            assertToolbarUnderline("音标");
            for (String hints : new String[]{"ə ɛ", "ts tsʰ", "ɑ ʌ", "ʂ ʃ", "ŋ ȵ"})
                if (test.pollNode(hints, false, 5000) == null)
                    throw new AssertionError("Missing 150% IPA key hints: " + hints);
        }
        Rect[] keys = new Rect[8];
        String letters = "weqpbnga";
        for (int i = 0; i < letters.length(); i++) keys[i] = test.waitForNode(letters.substring(i, i + 1), false);
        Bitmap shot = test.getUiAutomation().takeScreenshot();
        if (shot == null) throw new AssertionError("Missing IPA layout screenshot");
        int[] bottoms = new int[keys.length];
        int ipaQ;
        try {
            for (int i = 0; i < keys.length; i++) bottoms[i] = ipaInkBottom(shot, keys[i], letters.charAt(i));
            ipaQ = inkHeight(shot, keys[2]);
        } finally { shot.recycle(); }
        test.stage("IPA baseline at font " + scale + " and density " + density + ": "
                + letters + " " + java.util.Arrays.toString(bottoms) + "; Q ink typing/IPA=" + typingInk + "/" + ipaQ);
        if (Math.abs(typingInk - ipaQ) > 2)
            throw new AssertionError("Typing and IPA Q differ in size: " + typingInk + " vs " + ipaQ);
        if (Math.abs(bottoms[0] - bottoms[1]) > 2 || Math.abs(bottoms[2] - bottoms[3]) > 2
                || Math.abs(bottoms[4] - bottoms[5]) > 2 || bottoms[6] <= bottoms[7])
            throw new AssertionError("Misaligned IPA letters or clipped g descender: " + frame);
        if (scale >= 2f) {
            DisplayMetrics metrics = host.getResources().getDisplayMetrics();
            Paint form = new Paint();
            form.setTypeface(Typeface.DEFAULT_BOLD);
            form.setTextSize(sp(16, metrics));
            for (String word : new String[]{"ai⁵⁵", "ŋa²⁴"}) {
                KeyboardTest.Card card = test.visibleForm(word);
                Rect line = card.lineBounds.get(card.lineBounds.size() - 1);
                if (form.measureText(word) > line.width() - 12 * metrics.density)
                    throw new AssertionError("IPA candidate has no room for its full tone: " + word + " " + line);
            }
            test.press("下一页候选");
            test.waitForNode("补全 kuɔ²⁴", false);
            test.press("上一页候选");
            Rect symbols = test.waitForNode("字母或符号", false), done = test.waitForNode("完成", false);
            test.awaitKeyboardAtRest();
            Bitmap controls = test.getUiAutomation().takeScreenshot();
            if (controls == null) throw new AssertionError("Missing large-font action keys");
            try {
                assertKeyInkFits(controls, symbols, false, metrics.density, "符号");
                assertKeyInkFits(controls, done, true, metrics.density, "完成");
            } finally { controls.recycle(); }
        }
        test.captureFrame(frame + ".png");
        test.press("m");
        test.press("a");
        test.press("补全 奶，mã²¹");
        test.expectText(host.first, "mã²¹");
    }

    private static void assertKeyInkFits(Bitmap shot, Rect area, boolean light, float density, String label) {
        int first = area.right, last = area.left;
        for (int y = Math.max(0, area.top); y < Math.min(area.bottom, shot.getHeight()); y++) {
            for (int x = Math.max(0, area.left); x < Math.min(area.right, shot.getWidth()); x++) {
                int color = shot.getPixel(x, y);
                boolean ink = light ? Color.red(color) > 240 && Color.green(color) > 240 && Color.blue(color) > 240
                        : Color.green(color) < 126 && Color.blue(color) < 126;
                if (ink) { first = Math.min(first, x); last = Math.max(last, x); }
            }
        }
        int inset = Math.max(1, Math.round(2 * density));
        if (last < first || first < area.left + inset || last >= area.right - inset)
            throw new AssertionError("Action label is clipped at the largest font: " + label + " " + area
                    + "; ink=" + first + ".." + last);
    }

    private void checkLargeHandAndSettings(int density) throws Exception {
        setConfiguration(2f, density);
        test.newPredictionEditor();
        test.press("打字");
        test.press("设置");
        int twoLineHeight = Math.round(sp(15, host.getResources().getDisplayMetrics()) * 2.1f);
        for (String option : new String[]{"叶氏拼音", "调号拼音", "谐音字"})
            test.scrollKeyboardTo(option, twoLineHeight);
        test.scrollKeyboardTo("更多设置", Math.round(46 * host.getResources().getDisplayMetrics().density));
        test.captureFrame("settings-largest-scrolled.png");
        test.press("设置");

        test.press("手写");
        test.setHandwritingOutput(true);
        int minimum = Math.round(47 * host.getResources().getDisplayMetrics().density);
        test.runOnMainSync(() -> { host.first.setText("前波粒后"); host.first.setSelection(3); });
        test.longPressHandForm("波俐");
        test.waitForNode("已写「波粒」", false);
        Rect recommended = test.waitForNode("用推荐字", false);
        Rect marked = test.waitForNode("用调号拼音", false);
        if (recommended.height() < minimum || marked.height() < minimum || recommended.right > marked.left)
            throw new AssertionError("Handwriting choices overlap or are clipped: " + recommended + " / " + marked);
        test.captureFrame("hand-detail-largest.png");
        test.press("用推荐字");
        test.expectText(host.first, "前波俐后");
    }

    private static int ipaInkBottom(Bitmap shot, Rect key, char letter) {
        // Only measure the main glyph, below the smaller alternatives.
        for (int y = key.bottom - 1; y >= key.top + Math.round(key.height() * .43f); y--) {
            int dark = 0;
            for (int x = key.left + 3; x < key.right - 3; x++) {
                int color = shot.getPixel(x, y);
                if (Color.green(color) < 126 && Color.blue(color) < 126) dark++;
            }
            if (dark < 2) continue;
            if (key.bottom - y < 3) throw new AssertionError("IPA letter touches the bottom edge: " + letter);
            return y;
        }
        throw new AssertionError("No main IPA glyph for " + letter);
    }

    void run() throws Exception {
        String startingScale = shell("settings get system font_scale");
        String densities = shell("wm density");
        int physical = intAfter(densities, "Physical density");
        int override = intAfter(densities, "Override density");
        int baseline = override != 0 ? override : physical;
        if (baseline <= 0) throw new AssertionError("Unrecognized wm density: " + densities);
        int narrow = Math.round(host.getResources().getDisplayMetrics().widthPixels * 160f / 320);
        try {
            int small = measure("small", .85f, baseline);
            int standard = measure("default", 1f, baseline);
            measure("115", 1.15f, baseline);
            measure("large", 1.3f, baseline);
            int larger = measure("160", 1.6f, baseline);
            int largest = measure("largest", 2f, baseline);
            if (!(small < standard && standard < larger && larger <= largest + 1))
                geometryErrors.add("The visible Q did not follow system font sizes: " + small + "/" + standard + "/" + larger + "/" + largest);
            measure("default-dense", 1f, narrow);
            measure("160-dense", 1.6f, narrow);
            measure("largest-dense", 2f, narrow);
            checkNarrowNumberedWord();
            activeInputChanges(baseline, narrow);
            if (!geometryErrors.isEmpty()) throw new AssertionError("Typography requirements: " + geometryErrors);
        } finally {
            shell("wm density " + (override == 0 ? "reset" : Integer.toString(override)));
            if ("null".equals(startingScale)) shell("settings delete system font_scale");
            else shell("settings put system font_scale " + Float.parseFloat(startingScale));
            test.stage("Restored original density " + baseline + " and system font_scale " + startingScale);
        }
    }

    private int measure(String name, float scale, int density) throws Exception {
        setConfiguration(scale, density);
        test.newPredictionEditor();
        test.press("谐音字");
        test.runOnMainSync(() -> { host.first.setText("正文"); host.first.setSelection(2); });
        test.type(name.equals("largest-dense") ? "gaha" : "ga");
        List<KeyboardTest.Card> cards = test.fourCards();
        if (!(name.equals("largest-dense") ? cards.get(0).lines.size() == 2 && cards.get(0).lines.get(0).contains("上面") && cards.get(0).lines.get(1).equals("夹下") : cards.get(0).lines.equals(java.util.Arrays.asList("吃", "架"))))
            throw new AssertionError("Missing meaning and form at " + name);
        test.assertEditorAboveKeyboard(host.first, "打字");
        Rect toolbar = test.waitForNode("打字", false);
        Rect q = test.waitForNode("q", false);
        Rect hostField = new Rect();
        test.runOnMainSync(() -> host.first.getGlobalVisibleRect(hostField));
        // Capture the settled keyboard after the touch highlight has disappeared.
        SystemClock.sleep(200);
        Bitmap shot = test.getUiAutomation().takeScreenshot();
        if (shot == null) throw new AssertionError("No keyboard screenshot at " + name);
        int qInk, glossInk, wordInk;
        try {
            qInk = inkHeight(shot, q);
            glossInk = inkHeight(shot, cards.get(0).lineBounds.get(0));
            wordInk = inkHeight(shot, cards.get(0).lineBounds.get(1));
            if (name.equals("largest-dense")) {
                KeyboardTest.Card twoHan = null;
                for (KeyboardTest.Card word : cards)
                    if (word.lines.size() == 2 && word.lines.get(0).contains("上面") && word.lines.get(1).equals("夹下")) twoHan = word;
                if (twoHan == null) throw new AssertionError("Missing 上面／夹下 in the visible ga candidates");
                Rect form = twoHan.lineBounds.get(1);
                Rect right = new Rect(form);
                right.left += Math.round(form.width() * .72f);
                int whole = inkHeight(shot, form), lastChar = inkHeight(shot, right);
                test.stage("Two-Han pixels for 夹下: form=" + form.toShortString() + " fullInk=" + whole
                        + "px rightInk=" + lastChar + "px sample=" + right.toShortString());
                // 下 reaches high and low in the rightmost quarter. An ellipsis
                // in the same place only paints a narrow strip near the baseline.
                if (lastChar < whole * .55f)
                    geometryErrors.add("Candidate 上面／夹下 ends in baseline dots: " + lastChar + "/" + whole);
            }
        } finally { shot.recycle(); }
        DisplayMetrics dm = host.getResources().getDisplayMetrics();
        Configuration cfg = host.getResources().getConfiguration();
        float keyDp = q.height() / dm.density;
        if (android.os.Build.VERSION.SDK_INT >= 34 && scale == 2f) {
            float ratio14 = sp(14, dm) / (14 * dm.density);
            float ratio20 = sp(20, dm) / (20 * dm.density);
            if (Math.abs(ratio14 - ratio20) < .02f)
                geometryErrors.add("API 34+ did not apply distinct system SP curves at 200%: " + ratio14 + ", " + ratio20);
        }
        if (scale == 1f && cfg.screenHeightDp >= 600 && (keyDp < 43.5f || keyDp > 48.5f))
            geometryErrors.add("Ordinary key height is " + keyDp + "dp at " + name);
        test.stage(String.format(Locale.ROOT, "Typography %s font=%.2f dpi=%d viewport=%ddp keyHeight=%.1fdp platform14sp=%.2fpx platform20sp=%.2fpx Qink=%dpx glossInk=%dpx wordInk=%dpx toolbarY=%dpx editorBottom=%dpx", name, cfg.fontScale, dm.densityDpi, cfg.screenWidthDp, keyDp, sp(14, dm), sp(20, dm), qInk, glossInk, wordInk, toolbar.top, hostField.bottom));
        test.captureFrame("typography-" + name + ".png");
        if (name.equals("largest-dense")) test.tap(cards.get(0).bounds.centerX(), cards.get(0).bounds.centerY());
        else test.pressCandidate("吃、咬，架", false);
        test.expectText(host.first, name.equals("largest-dense") ? "正文夹下" : "正文架");
        test.press("隐藏键盘");
        test.waitAbsent("打字");
        test.tapField(host.first);
        test.assertEditorAboveKeyboard(host.first, "打字");
        test.expectText(host.first, name.equals("largest-dense") ? "正文夹下" : "正文架");
        if (name.equals("default") || name.equals("largest-dense")) {
            test.pressAction("完成");
            long until = SystemClock.uptimeMillis() + 5000;
            while (host.lastEditorAction != android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    && SystemClock.uptimeMillis() < until) SystemClock.sleep(50);
            if (host.lastEditorAction != android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                    || !(name.equals("largest-dense") ? "正文夹下" : "正文架").equals(host.lastSubmitted))
                throw new AssertionError("Bottom-row action did not reach the host at " + name);
            test.stage("Bottom-row 完成 reached the host with " + host.lastSubmitted + " at " + name);
        }
        return qInk;
    }

    private void checkNarrowNumberedWord() {
        test.newPredictionEditor();
        test.press("谐音字");
        test.type("nga");
        KeyboardTest.Card word = test.visibleForm("牙");
        test.assertNumberedCard(word, "¹我²压", "牙");
        test.assertEditorAboveKeyboard(host.first, "打字");
        test.awaitKeyboardAtRest();
        test.captureFrame("typography-numbered-nga.png");
        Bitmap shot = test.getUiAutomation().takeScreenshot();
        if (shot == null) throw new AssertionError("No numbered candidate screenshot");
        try {
            Rect label = word.lineBounds.get(0);
            Rect right = new Rect(label);
            right.left += Math.round(label.width() * .70f);
            int whole = inkHeight(shot, label), last = inkHeight(shot, right);
            test.stage("Two numbered meanings for 牙: upper=" + label.toShortString() + " ink=" + whole
                    + "px, 压 sample=" + right.toShortString() + " rightInk=" + last + "px");
            if (last < whole * .55f) geometryErrors.add("¹我²压 ended in an ellipsis: " + last + "/" + whole);
        } finally { shot.recycle(); }
        test.tap(word.bounds.centerX(), word.bounds.centerY());
        test.expectText(host.first, "牙");
    }

    private void activeInputChanges(int baseline, int narrow) throws Exception {
        setConfiguration(1f, baseline);
        test.newPredictionEditor();
        test.press("谐音字");
        test.runOnMainSync(() -> { host.first.setText("正文"); host.first.setSelection(2); });
        test.type("marmardi");
        setConfiguration(1.6f, baseline);
        test.waitForNode("待选拼写 marmardi", false);
        setConfiguration(1.6f, narrow);
        test.waitForNode("待选拼写 marmardi", false);
        test.expectText(host.first, "正文");
        test.assertEditorAboveKeyboard(host.first, "打字");
        test.captureFrame("typography-draft.png");
        test.pressCandidate("补全 女人，麻麻爹", false);
        test.expectText(host.first, "正文麻麻爹");
        test.press("手写");
        test.setHandwritingOutput(true);
        Rect pad = test.waitForNode("汉字手写区", false);
        test.drawThree(pad);
        // The last pen-up has occurred but the recognizer's idle timer has not elapsed.
        setConfiguration(1.3f, narrow);
        test.expectText(host.first, "正文麻麻爹三");
        test.stage("First handwritten 三 committed while font size changed");
        Rect previous = settledPad(null);
        setConfiguration(1.3f, baseline);
        pad = settledPad(baseline == narrow ? null : previous);
        test.expectText(host.first, "正文麻麻爹三");
        test.runOnMainSync(() -> {
            int end = host.first.length();
            if (host.first.getSelectionStart() != end || host.first.getSelectionEnd() != end)
                throw new AssertionError("After display-size change the caret was not after 三");
        });
        test.assertEditorAboveKeyboard(host.first, "手写");
        test.stage("Writing second 三 in settled pad " + pad.toShortString() + ", previous density pad=" + previous.toShortString());
        test.captureFrame("typography-hand-before-second.png");
        test.drawThree(pad);
        test.expectText(host.first, "正文麻麻爹三三");
        test.captureFrame("typography-hand.png");
        test.stage("Active input preserved marmardi across font and density changes, selected 麻麻爹, committed each handwritten 三 once, and kept the host field visible");
    }

    private Rect settledPad(Rect oldBounds) {
        // The host receives its new resources before the IME's separate surface moves.
        long until = SystemClock.uptimeMillis() + 12000;
        Rect last = null;
        do {
            Rect current = test.waitForNode("汉字手写区", false);
            if (!current.equals(oldBounds) && current.equals(last)) {
                test.awaitKeyboardAtRest();
                if (current.equals(test.waitForNode("汉字手写区", false))) return current;
            }
            last = current;
            SystemClock.sleep(140);
        } while (SystemClock.uptimeMillis() < until);
        throw new AssertionError("Handwriting pad did not settle after reflow: old=" + oldBounds + " current=" + last);
    }

    private void setConfiguration(float scale, int density) throws Exception {
        Configuration current = host.getResources().getConfiguration();
        if (Math.abs(current.fontScale - scale) >= .001f) shell("settings put system font_scale " + scale);
        if (host.getResources().getDisplayMetrics().densityDpi != density) shell("wm density " + density);
        long until = SystemClock.uptimeMillis() + 12000;
        while (SystemClock.uptimeMillis() < until) {
            current = host.getResources().getConfiguration();
            if (Math.abs(current.fontScale - scale) < .001f && host.getResources().getDisplayMetrics().densityDpi == density) {
                test.waitForIdleSync();
                return;
            }
            SystemClock.sleep(75);
        }
        throw new AssertionError("Host failed to receive font=" + scale + ", dpi=" + density + "; actual=" + current);
    }

    private static float sp(float value, DisplayMetrics metrics) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, metrics);
    }

    private static int inkHeight(Bitmap shot, Rect area) {
        int first = Integer.MAX_VALUE, last = -1;
        for (int y = Math.max(0, area.top); y < Math.min(area.bottom, shot.getHeight()); y++) {
            int dark = 0;
            for (int x = Math.max(0, area.left + 3); x < Math.min(area.right - 3, shot.getWidth()); x++) {
                int color = shot.getPixel(x, y);
                if (Color.green(color) < 126 && Color.blue(color) < 126) dark++;
            }
            if (dark >= 2) { first = Math.min(first, y); last = y; }
        }
        if (last < first) throw new AssertionError("No visible text at physical " + area);
        return last - first + 1;
    }

    void assertToolbarUnderline(String selected) {
        String[] tabs = {"打字", "手写", "音标"};
        Rect[] bounds = new Rect[tabs.length];
        for (int i = 0; i < tabs.length; i++) bounds[i] = test.waitForNode(tabs[i], false);
        Bitmap shot = test.getUiAutomation().takeScreenshot();
        if (shot == null) throw new AssertionError("Missing toolbar screenshot");
        float density = host.getResources().getDisplayMetrics().density;
        try {
            for (int i = 0; i < tabs.length; i++) {
                Rect area = bounds[i];
                int y = area.bottom - Math.max(1, Math.round(1.5f * density));
                for (int x = area.left + 1; x < area.right - 1; x++) {
                    int color = shot.getPixel(x, y);
                    boolean red = Color.red(color) > 120 && Color.green(color) < 110 && Color.blue(color) < 100
                            && Color.red(color) > Color.green(color) * 1.5f;
                    if (red != tabs[i].equals(selected))
                        throw new AssertionError("Unexpected underline at " + x + "," + y + " in "
                                + tabs[i] + " " + area + " with " + selected + " selected");
                }
            }
        } finally { shot.recycle(); }
    }

    private static int intAfter(String text, String label) {
        Matcher found = Pattern.compile(Pattern.quote(label) + ":\\s*([0-9]+)").matcher(text);
        return found.find() ? Integer.parseInt(found.group(1)) : 0;
    }

    private String shell(String command) throws Exception {
        try (ParcelFileDescriptor fd = test.getUiAutomation().executeShellCommand(command);
             BufferedReader in = new BufferedReader(new InputStreamReader(new ParcelFileDescriptor.AutoCloseInputStream(fd)))) {
            StringBuilder result = new StringBuilder();
            for (String line; (line = in.readLine()) != null;) result.append(line).append('\n');
            return result.toString().trim();
        }
    }
}
