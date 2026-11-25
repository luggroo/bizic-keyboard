package biz.tujia.ime;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.text.StaticLayout;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.Layout;
import android.text.TextUtils;
import android.text.TextPaint;
import android.util.TypedValue;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Offline Tujia keyboard. Preedit belongs to the keyboard, committed text to the host. */
public final class TujiaIme extends InputMethodService implements LexiconStore.Listener {
    private static final int COLUMNS = 4, HISTORY_LIMIT = 32;
    private static final int LETTER_SP = 20, FORM_SP = 20, GLOSS_SP = 14, TAB_SP = 15;
    private static final int SPELL = 0, HAN = 1, HAND = 2, VOICE = 3, IPA = 4;
    private static final int INK = 0xff392b27, MUTED = 0xff745950, RED = 0xffab3929;
    private static final int PAPER = 0xfffffaf5, BOARD = 0xfffae9df, HEADER = 0xfffff1e8;
    private static final int RULE = 0xffddc5b7, UTILITY = 0xffefd7c7, WHITE = Color.WHITE;
    private static final int PRESSED = 0xffb63d2a, FOCUS = 0xffedc1a8;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private enum KeyLook { LETTER, TAB, UTILITY, SPACE, PRIMARY, PLAIN }
    private CandidateEngine engine;
    private boolean inputViewActive, lexiconFailed;
    private Configuration displayConfiguration;
    private InputConnection reflowEditor;
    private EditorInfo reflowInfo;
    private CandidateEngine.Orthography orthography = CandidateEngine.Orthography.YE;
    private int mode, typingMode, page, glyphPage, selStart, selEnd;
    private int historyLimit;
    private boolean shifted, symbols, direct, numeric, handAsHan, compact, handPanelActive;
    private String draft = "";
    private List<CandidateEngine.Candidate> candidates = Collections.emptyList();
    private final Map<CandidateEngine.Candidate, HanChoice> hanChoices = new HashMap<>();
    private List<String> glyphs = Collections.emptyList();
    // Some editors merge selection notifications from successive commitText calls.
    private final ArrayDeque<Integer> expectedSelections = new ArrayDeque<>();
    private LinearLayout surface, toolbar, composition, choices, body, glyphRow, morePanel;
    private FrameLayout keyboardFrame;
    private int panelHeight, baselinePanelHeight, baselineBodyHeight, panelCeiling;
    private LinearLayout readingPanel;
    private ReadingPage readingPage;
    private CandidateEngine.Candidate inspectedCandidate;
    private TextView preview, moreButton, prevChoices, nextChoices, toneCancel;
    private boolean selectingTone;
    private IpaKeyboard ipaLexicon;
    private boolean ipaLoading, ipaLoadFailed, destroyed;
    private String shownIpaPrefix;
    private FrameLayout ipaAlternatives;
    private HandwritingPad pad;
    private HandAnchor strokeAnchor;
    private HandAnchor matchedAt;
    private boolean handNext;
    private TextView wordsTab, nextTab;
    private LinearLayout handDetail;
    private final Map<CandidateEngine.Candidate, List<CandidateEngine.Candidate>> handSenses = new HashMap<>();
    private InputConnection handEditor;
    private int handStart = -1;
    private boolean handLocked;
    private String handRaw = "", handOutput = "", handOwned = "", handPrefix = "", handPrefixOutput = "";
    private String handHistory = "";
    private String recentWords = "", previousSyllable = "";
    private List<CandidateEngine.Candidate> syllableHints = Collections.emptyList();
    private String cachedWordContext, cachedSoundContext;
    private CandidateEngine.Mode cachedHintMode;
    private CandidateEngine.Orthography cachedHintStyle;
    private List<CandidateEngine.Candidate> allWordHints, allSoundHints;
    private Map<String, String> markedWords, yeWords, markedSounds;
    private List<CandidateEngine.Candidate> wordCompletions = Collections.emptyList(), wordHints = Collections.emptyList();

    @Override public void onCreate() {
        super.onCreate();
        displayConfiguration = new Configuration(getResources().getConfiguration());
        LexiconStore.load(this, this);
        handAsHan = getSharedPreferences("keyboard", MODE_PRIVATE).getBoolean("hand_as_han", true);
        mode = getSharedPreferences("keyboard", MODE_PRIVATE).getInt("mode", HAN);
        if (mode < SPELL || mode > IPA || mode == VOICE) mode = HAN;
        int savedTyping = getSharedPreferences("keyboard", MODE_PRIVATE).getInt("typing_mode", mode);
        typingMode = savedTyping == SPELL ? SPELL : HAN;
    }

    @Override public void onLexiconLoaded(CandidateEngine ready) {
        engine = ready;
        lexiconFailed = ready == null;
        if (inputViewActive && !selectingTone && readingPanel == null) updateCandidates();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public void onDestroy() {
        destroyed = true;
        inputViewActive = false;
        closeIpaAlternatives();
        LexiconStore.detach(this);
        engine = null;
        super.onDestroy();
    }

    @Override public View onCreateInputView() {
        closeReading();
        keyboardFrame = new FrameLayout(this);
        surface = column();
        surface.setPadding(dp(3), dp(2), dp(3), dp(2));
        surface.setBackgroundColor(BOARD);
        layoutKeyboard();
        keyboardFrame.addView(surface, new FrameLayout.LayoutParams(-1, panelHeight));
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            final FrameLayout frame = keyboardFrame;
            frame.getViewTreeObserver().addOnGlobalLayoutListener(() -> fitSystemNavigation(frame));
        }
        return keyboardFrame;
    }

    private boolean sameReflowEditor(EditorInfo info) {
        return reflowEditor != null && reflowEditor == getCurrentInputConnection() && info != null
                && info.fieldId == reflowInfo.fieldId && info.inputType == reflowInfo.inputType
                && TextUtils.equals(info.packageName, reflowInfo.packageName);
    }

    private long cursorAt(InputConnection connection) {
        ExtractedTextRequest request = new ExtractedTextRequest();
        request.hintMaxChars = 1;
        ExtractedText text = connection.getExtractedText(request, 0);
        int start = selStart, end = selEnd;
        if (text != null && text.selectionStart >= 0 && text.selectionEnd >= 0) {
            start = text.startOffset + text.selectionStart;
            end = text.startOffset + text.selectionEnd;
        }
        return ((long) start << 32) | (end & 0xffffffffL);
    }

    @Override public void onConfigurationChanged(Configuration next) {
        int differences = displayConfiguration == null ? 0 : displayConfiguration.diff(next);
        displayConfiguration = new Configuration(next);
        int sizing = ActivityInfo.CONFIG_FONT_SCALE | ActivityInfo.CONFIG_DENSITY
                | ActivityInfo.CONFIG_SCREEN_SIZE | ActivityInfo.CONFIG_SMALLEST_SCREEN_SIZE;
        InputConnection editor = getCurrentInputConnection();
        EditorInfo info = getCurrentInputEditorInfo();
        if ((differences & sizing) == 0 || !inputViewActive || editor == null || info == null) {
            super.onConfigurationChanged(next);
            return;
        }
        reflowEditor = editor;
        reflowInfo = info;
        long cursor = direct ? 0 : cursorAt(editor);
        HandAnchor pen = strokeAnchor;
        HandwritingPad.InkSnapshot ink = pad == null || pen == null ? null : pad.takeInk();
        try {
            // Android 9 synchronously restarts the current editor and recreates the view
            // here; newer Android versions can let the IME handle size changes in place.
            super.onConfigurationChanged(next);
            if (!sameReflowEditor(getCurrentInputEditorInfo()) || (!direct && cursorAt(editor) != cursor)) {
                resetDraft();
                if (inputViewActive) updateCandidates();
                return;
            }
            if (!inputViewActive) return;
            layoutKeyboard();
            if (ink != null && pad != null && pen.matches(handAnchor())) {
                strokeAnchor = pen;
                pad.restoreInk(ink);
            }
        } finally {
            reflowEditor = null;
            reflowInfo = null;
        }
    }

    @Override public void onStartInput(EditorInfo editor, boolean restarting) {
        super.onStartInput(editor, restarting);
        inputViewActive = false;
        boolean retain = restarting && sameReflowEditor(editor);
        if (!retain) {
            reflowEditor = null;
            resetDraft();
            selStart = editor.initialSelStart;
            selEnd = editor.initialSelEnd;
        }
        // Two tokens supply a fixed trigram context. Longer histories enable local adaptation.
        historyLimit = android.os.Build.VERSION.SDK_INT >= 26 &&
                (editor.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0 ? 2 : HISTORY_LIMIT;
        int type = editor.inputType & InputType.TYPE_MASK_CLASS;
        int variation = editor.inputType & InputType.TYPE_MASK_VARIATION;
        numeric = type == InputType.TYPE_CLASS_NUMBER || type == InputType.TYPE_CLASS_PHONE
                || type == InputType.TYPE_CLASS_DATETIME;
        direct = numeric || (type == InputType.TYPE_CLASS_TEXT && (
                variation == InputType.TYPE_TEXT_VARIATION_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD ||
                variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS ||
                variation == InputType.TYPE_TEXT_VARIATION_URI));
        if (!retain) {
            symbols = numeric;
            shifted = false;
        }
    }

    @Override public void onStartInputView(EditorInfo editor, boolean restarting) {
        super.onStartInputView(editor, restarting);
        inputViewActive = true;
        orthography = KeyboardSettings.spelling(this);
        if (sameReflowEditor(editor)) return;
        readSentenceBoundary();
        layoutKeyboard();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        inputViewActive = false;
        if (!sameReflowEditor(getCurrentInputEditorInfo())) resetDraft();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        inputViewActive = false;
        if (!sameReflowEditor(getCurrentInputEditorInfo())) resetDraft();
        super.onFinishInput();
    }

    private void resetDraft() {
        closeIpaAlternatives();
        shownIpaPrefix = null;
        closeHandDetail();
        handNext = false;
        closeReading();
        closeMore();
        selectingTone = false;
        draft = "";
        page = 0;
        candidates = Collections.emptyList();
        hanChoices.clear();
        glyphs = Collections.emptyList();
        glyphPage = 0;
        expectedSelections.clear();
        recentWords = previousSyllable = "";
        syllableHints = Collections.emptyList();
        cachedWordContext = cachedSoundContext = null;
        allWordHints = allSoundHints = Collections.emptyList();
        markedWords = yeWords = markedSounds = null;
        clearHandSession();
        if (handPanelActive && glyphRow != null) showGlyphs();
    }

    @Override public void onUpdateSelection(int oldStart, int oldEnd, int newStart, int newEnd,
                                             int candidatesStart, int candidatesEnd) {
        super.onUpdateSelection(oldStart, oldEnd, newStart, newEnd, candidatesStart, candidatesEnd);
        if (newStart == newEnd && expectedSelections.contains(newEnd)) {
            while (!expectedSelections.isEmpty() && expectedSelections.removeFirst() != newEnd) { }
            if (!expectedSelections.isEmpty()) return;
        } else if (oldStart != newStart || oldEnd != newEnd) {
            if (newStart != selStart || newEnd != selEnd) {
                resetDraft();
                selStart = newStart;
                selEnd = newEnd;
                readSentenceBoundary();
                updateCandidates();
            }
        }
        selStart = newStart;
        selEnd = newEnd;
        if (inIpaMode() && inputViewActive) showIpaCandidates();
    }

    private void layoutKeyboard() {
        if (surface == null) return;
        closeIpaAlternatives();
        shownIpaPrefix = null;
        closeHandDetail();
        closeReading();
        closeMore();
        handPanelActive = false;
        pad = null;
        glyphRow = null;
        surface.removeAllViews();
        surface.setPadding(dp(3), dp(2), dp(3), dp(2));
        int screen = Math.round(getResources().getDisplayMetrics().heightPixels /
                getResources().getDisplayMetrics().density);
        boolean portrait = getResources().getDisplayMetrics().heightPixels >= getResources().getDisplayMetrics().widthPixels;
        compact = !portrait && screen < 500 || screen < 400;
        toolbar = row(); composition = row(); choices = row(); body = column();
        toolbar.setBackground(edge(HEADER, RULE, Gravity.TOP));
        composition.setBackgroundColor(HEADER);
        LinearLayout strip = row();
        strip.setBackground(edge(PAPER, RULE, Gravity.BOTTOM));
        body.setBackgroundColor(BOARD);
        int toolbarHeight = Math.max(dp(compact ? 33 : 40), textHeight(compact ? TAB_SP - 1 : TAB_SP) + dp(8));
        // Android 14+ scales different SP sizes nonlinearly, so size each line separately.
        int choicesHeight = Math.max(dp(compact ? 40 : 48),
                textHeight(glossSp()) + textHeight(formSp()) + dp(4));
        int targetRow = Math.max(dp(compact ? 36 : 48), textHeight(letterSp()) + dp(6));
        int maximum = dp(Math.max(120, screen - (portrait ? 140 : 80)));
        int total = Math.min(maximum, toolbarHeight + choicesHeight + 4 * targetRow);
        if (direct) total = Math.min(maximum, toolbarHeight + 4 * targetRow);
        if (direct || mode == VOICE) choicesHeight = 0;
        int bodyHeight = Math.max(dp(64), total - toolbarHeight - choicesHeight);
        surface.addView(toolbar, new LinearLayout.LayoutParams(-1, toolbarHeight));
        surface.addView(strip, new LinearLayout.LayoutParams(-1, choicesHeight));
        surface.addView(body, new LinearLayout.LayoutParams(-1, bodyHeight));
        baselineBodyHeight = bodyHeight;
        int verticalPadding = surface.getPaddingTop() + surface.getPaddingBottom();
        baselinePanelHeight = toolbarHeight + choicesHeight + bodyHeight + verticalPadding;
        panelCeiling = maximum + verticalPadding;
        panelHeight = baselinePanelHeight;
        if (surface.getParent() == keyboardFrame) {
            surface.setLayoutParams(new FrameLayout.LayoutParams(-1, panelHeight));
        }
        buildToolbar();
        if (!direct && mode != VOICE && mode != IPA) prevChoices = pageControl(strip, "‹", "上一页候选", -1);
        strip.addView(choices, new LinearLayout.LayoutParams(0, -1, 1));
        if (!direct && mode != VOICE && mode != IPA) nextChoices = pageControl(strip, "›", "下一页候选", 1);
        buildBody();
        updateCandidates();
    }

    @android.annotation.TargetApi(35)
    private void fitSystemNavigation(FrameLayout frame) {
        if (frame != keyboardFrame || frame.getHeight() == 0 || body == null) return;
        View decor = frame.getRootView();
        android.view.WindowInsets insets = decor.getRootWindowInsets();
        if (insets == null) return;
        android.graphics.Insets bars = insets.getInsets(android.view.WindowInsets.Type.navigationBars()
                | android.view.WindowInsets.Type.displayCutout());
        int[] origin = new int[2];
        frame.getLocationInWindow(origin);
        // Some IME windows are already inset by the framework. Only reserve the
        // portion of this keyboard that actually overlaps a system navigation area.
        int left = Math.max(0, bars.left - origin[0]);
        int right = Math.max(0, origin[0] + frame.getWidth() - (decor.getWidth() - bars.right));
        int bottom = Math.min(bars.bottom, Math.max(0,
                origin[1] + frame.getHeight() - (decor.getHeight() - bars.bottom)));
        // The gesture bar needs its own space below the keys. Borrow from the rows
        // only when growing the window would cover the editor on a short display.
        int overflow = Math.max(0, baselinePanelHeight + bottom - panelCeiling);
        int fittedBody = Math.max(dp(64), baselineBodyHeight - overflow);
        int fittedPanel = baselinePanelHeight - baselineBodyHeight + fittedBody;
        if (frame.getPaddingLeft() != left || frame.getPaddingRight() != right || frame.getPaddingBottom() != bottom)
            frame.setPadding(left, 0, right, bottom);
        if (body.getLayoutParams().height != fittedBody) {
            body.setLayoutParams(new LinearLayout.LayoutParams(-1, fittedBody));
        }
        if (panelHeight != fittedPanel) panelHeight = fittedPanel;
        View visiblePanel = readingPanel != null ? readingPanel : surface;
        if (visiblePanel != null && visiblePanel.getParent() == frame
                && visiblePanel.getLayoutParams().height != fittedPanel)
            visiblePanel.setLayoutParams(new FrameLayout.LayoutParams(-1, fittedPanel));
    }

    private void buildToolbar() {
        if (direct) {
            toolbar.addView(label(numeric ? "数字" : "英文直输", 17, MUTED), weighted(1));
            stripKey("🌐", "切换输入法", false, this::pickIme);
        } else {
            stripKey("打字", "打字", mode == SPELL || mode == HAN, () -> switchMode(typingMode));
            stripKey("手写", "手写", mode == HAND, () -> switchMode(HAND));
            stripKey("音标", "音标", mode == IPA, () -> switchMode(IPA));
        }
        toolbar.addView(composition, new LinearLayout.LayoutParams(0, -1, 1));
        buildComposition();
        if (!direct) moreButton = stripKey("更多", "更多", false, this::toggleMore);
        stripKey("收起", "隐藏键盘", false, () -> requestHideSelf(0));
    }

    private TextView stripKey(String label, String desc, boolean selected, Runnable action) {
        TextView key = key(toolbar, label, desc, 1, selected, action);
        int natural = Math.max(dp(42), (int)Math.ceil(key.getPaint().measureText(label)) + dp(8));
        int width = getResources().getDisplayMetrics().widthPixels - surface.getPaddingLeft() - surface.getPaddingRight();
        int pixels = direct ? natural : Math.min(natural, Math.max(dp(32), (width - dp(54)) / 5));
        key.setLayoutParams(new LinearLayout.LayoutParams(pixels, -1));
        if (pixels < natural) key.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                fitTextWidth(key, key.getText(), r - l - dp(2), .72f));
        return key;
    }

    private void toggleMore() {
        closeIpaAlternatives();
        closeHandDetail();
        if (morePanel != null) { closeMore(); return; }
        if (selectingTone) updateCandidates();
        for (int i = 0; i < body.getChildCount(); i++) body.getChildAt(i).setVisibility(View.GONE);
        morePanel = column();
        morePanel.setBackgroundColor(PAPER);
        body.addView(morePanel, new LinearLayout.LayoutParams(-1, -1));
        moreButton.setSelected(true);
        moreButton.setTextColor(pressColors(RED));
        if (mode == HAND) {
            LinearLayout outputs = menuRow();
            outputs.addView(label("手写输出", compact ? 15 : 18, MUTED), weighted(1));
            key(outputs, handAsHan ? "记音 ▾" : "拼写 ▾", "切换手写输出", 1,
                    KeyLook.UTILITY, false, this::toggleHandOutput);
        } else {
            LinearLayout outputs = menuRow();
            outputChoice(outputs, "叶氏拼音", "bifzixkar", CandidateEngine.Orthography.YE);
            outputChoice(outputs, "调号拼音", "bízīkǎ", CandidateEngine.Orthography.DIACRITIC);
            outputChoice(outputs, "谐音字", "币子咔", null);
        }
        LinearLayout tools = menuRow();
        key(tools, "语音", "语音", 1, KeyLook.PLAIN, false, () -> switchMode(VOICE));
        key(tools, "键盘设置", "键盘设置", 1, KeyLook.PLAIN, false, () -> {
            closeMore();
            startActivity(new Intent(this, SetupActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        });
        if (!candidates.isEmpty() || !draft.isEmpty()) {
            LinearLayout actions = menuRow();
            if (!candidates.isEmpty()) key(actions, "看候选全文", "长按候选看全文", 1, KeyLook.PLAIN, false, () -> {
                closeMore();
                int index = COLUMNS * page;
                if (index < candidates.size()) openReading(candidates.get(index));
            });
            if (!draft.isEmpty() && mode != HAND) key(actions, "输入原拼写", "确定拼写", 1, KeyLook.PLAIN, false, () -> {
                closeMore();
                commitRaw();
            });
        }
    }

    private void outputChoice(LinearLayout row, String name, String example, CandidateEngine.Orthography style) {
        boolean selected = style == null ? typingMode == HAN : typingMode == SPELL && orthography == style;
        TextView choice = key(row, name + "\n" + example, name, 1, KeyLook.TAB, selected, () -> {
            if (style != null) {
                orthography = style;
                KeyboardSettings.setSpelling(this, style);
            }
            switchMode(style == null ? HAN : SPELL);
        });
        choice.setSingleLine(false);
        choice.setMaxLines(2);
    }

    private LinearLayout menuRow() {
        LinearLayout r = row();
        morePanel.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
        return r;
    }

    private void toggleHandOutput() {
        handAsHan = !handAsHan;
        getSharedPreferences("keyboard", MODE_PRIVATE).edit().putBoolean("hand_as_han", handAsHan).apply();
        closeMore();
        clearHandSession();
        glyphs = Collections.emptyList();
        handNext = false;
        layoutKeyboard();
    }

    private void closeMore() {
        if (morePanel == null) return;
        body.removeView(morePanel);
        morePanel = null;
        for (int i = 0; i < body.getChildCount(); i++) body.getChildAt(i).setVisibility(View.VISIBLE);
        moreButton.setSelected(false);
        moreButton.setTextColor(pressColors(INK));
    }

    private void switchMode(int next) {
        closeMore();
        if (next == HAND && mode == HAND && handPanelActive) return;
        if (next != mode) {
            boolean sameAlphabet = mode <= HAN && next <= HAN;
            if (!sameAlphabet) {
                String words = recentWords, syllable = previousSyllable;
                resetDraft();
                recentWords = words;
                previousSyllable = syllable;
            }
            mode = next;
            if (mode <= HAN) {
                typingMode = mode;
                getSharedPreferences("keyboard", MODE_PRIVATE).edit().putInt("typing_mode", typingMode).apply();
            }
            if (mode != VOICE) {
                getSharedPreferences("keyboard", MODE_PRIVATE).edit().putInt("mode", mode).apply();
            }
            symbols = false;
            shifted = false;
        }
        if (draft.isEmpty()) readSentenceBoundary();
        layoutKeyboard();
    }

    private void buildComposition() {
        prevChoices = null;
        nextChoices = null;
        toneCancel = null;
        boolean choosing = !direct && mode != VOICE;
        preview = label("", compact ? 14 : 16, INK);
        wordsTab = nextTab = null;
        if (choosing && mode == HAND && handAsHan) {
            wordsTab = handTab("已写成词", false);
            nextTab = handTab("接着写", true);
            return;
        }
        preview.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(2), 0, dp(2), 0);
        preview.setEllipsize(TextUtils.TruncateAt.START);
        composition.addView(preview, new LinearLayout.LayoutParams(0, -1, 1));
        if (choosing && mode != IPA) {
            toneCancel = textControl("取消", "取消选声调", this::updateCandidates);
            toneCancel.setVisibility(View.GONE);
        }
    }

    private TextView handTab(String name, boolean next) {
        TextView tab = key(composition, name, name, next ? 3 : 4, false, () -> {
            closeHandDetail();
            handNext = next;
            refreshHandCandidates();
        });
        tab.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) ->
                fitTextWidth(tab, name, r - l - dp(2), .65f));
        return tab;
    }

    private TextView textControl(String title, String description, Runnable action) {
        TextView v = label(title, compact ? 13 : 15, INK);
        v.setPadding(dp(4), 0, dp(4), 0);
        v.setTypeface(MEDIUM);
        v.setTextColor(pressColors(RED));
        v.setBackground(pressable(shape(Color.TRANSPARENT, 0), 6));
        v.setContentDescription(description);
        KeyboardTap.bind(v, action);
        v.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -1);
        params.setMargins(dp(1), dp(1), dp(1), dp(1));
        composition.addView(v, params);
        return v;
    }

    private TextView pageControl(LinearLayout parent, String title, String description, int direction) {
        TextView v = label(title, compact ? 22 : 25, RED);
        v.setTextColor(pressColors(RED));
        v.setBackground(pressable(shape(Color.TRANSPARENT, 0), 6));
        v.setContentDescription(description);
        KeyboardTap.bind(v, () -> {
            page += direction;
            showCandidates();
        });
        v.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(compact ? 20 : 24), -1);
        parent.addView(v, params);
        return v;
    }

    private void buildBody() {
        if (!direct && mode == VOICE) {
            TextView hint = label("语音模型尚未上线", compact ? 16 : 18, INK);
            body.addView(hint, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout controls = row();
            key(controls, "用拼写输入", "切换到土家语拼写", 1, true, () -> switchMode(SPELL));
            key(controls, "用手写输入", "切换到手写", 1, false, () -> switchMode(HAND));
            body.addView(controls, lineWithMinimum(compact ? 30 : 40, compact ? 14 : 15));
        } else if (!direct && mode == HAND) {
            buildHandwriting();
        } else {
            buildKeys();
        }
    }

    private static final class HandAnchor {
        final InputConnection editor;
        final int start, end;
        final String before, after;

        HandAnchor(InputConnection editor, int start, int end, String before, String after) {
            this.editor = editor; this.start = start; this.end = end; this.before = before; this.after = after;
        }

        boolean matches(HandAnchor other) {
            return other != null && editor == other.editor && start == other.start && end == other.end
                    && before.equals(other.before) && after.equals(other.after);
        }
    }

    private HandAnchor handAnchor() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || !inputViewActive || direct || mode != HAND) return null;
        ExtractedTextRequest req = new ExtractedTextRequest();
        req.hintMaxChars = 64;
        ExtractedText extracted = ic.getExtractedText(req, 0);
        int start = selStart, end = selEnd;
        if (extracted != null && extracted.selectionStart >= 0 && extracted.selectionEnd >= 0) {
            start = extracted.startOffset + extracted.selectionStart;
            end = extracted.startOffset + extracted.selectionEnd;
        }
        int limit = engine == null ? 2 * CandidateEngine.HANDWRITING_WINDOW : Math.max(1, engine.handwritingContextLength);
        CharSequence left = ic.getTextBeforeCursor(limit, 0);
        CharSequence right = ic.getTextAfterCursor(12, 0);
        String before = CandidateEngine.handwritingTail(left == null ? "" : left.toString());
        return new HandAnchor(ic, start, end, before, right == null ? "" : right.toString());
    }

    private void clearHandSession() {
        matchedAt = null;
        handSenses.clear();
        closeHandDetail();
        handRaw = handOwned = handPrefix = handPrefixOutput = handOutput = handHistory = "";
        handStart = -1;
        handLocked = false;
        handEditor = null;
        strokeAnchor = null;
        wordCompletions = wordHints = Collections.emptyList();
        if (pad != null) pad.clearInk();
    }

    private CandidateEngine.Mode handMode() {
        return handAsHan ? CandidateEngine.Mode.HAN : CandidateEngine.Mode.HANDWRITING;
    }

    private List<CandidateEngine.Candidate> handExact(String raw) {
        if (engine == null || raw.isEmpty()) return Collections.emptyList();
        List<CandidateEngine.Candidate> full = new ArrayList<>();
        for (CandidateEngine.Candidate c : engine.lookup(raw, handMode(), orthography))
            if (c.kind == CandidateEngine.Kind.TUJIA && c.consumed == raw.length()) full.add(c);
        return full;
    }

    private String historyWith(String past, String next) {
        if (next.isEmpty()) return "";
        if (next.equals("<s>") && (past.equals(next) || past.endsWith(" <s>"))) return past;
        String joined = past.isEmpty() ? next : past + " " + next;
        int start = joined.length();
        for (int i = 0; i < historyLimit; i++) {
            start = joined.lastIndexOf(' ', start - 1);
            if (start < 0) return joined;
        }
        return joined.substring(start + 1);
    }

    private static boolean sentenceEnd(char c) {
        return "。！？.!?\n\r".indexOf(c) >= 0;
    }

    private void beginSentence() {
        recentWords = historyWith(recentWords, "<s>");
        previousSyllable = "<s>";
    }

    private void readSentenceBoundary() {
        if (!inputViewActive || direct || mode == VOICE || mode == IPA) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence left = ic.getTextBeforeCursor(1, 0);
        if (left == null) return;
        if (left.length() == 0) {
            // A host may clear its field before delivering the new selection callback.
            // Confirm a stale/unknown offset rather than reading history from the last field.
            if (selStart != 0 || selEnd != 0) {
                ExtractedTextRequest req = new ExtractedTextRequest();
                req.hintMaxChars = 1;
                ExtractedText actual = ic.getExtractedText(req, 0);
                if (actual == null || actual.startOffset != 0 || actual.selectionStart != 0
                        || actual.selectionEnd != 0) return;
            }
            recentWords = "";
        } else if (selStart != selEnd || !sentenceEnd(left.charAt(left.length() - 1))) {
            return;
        }
        beginSentence();
    }

    private void remember(String history, CandidateEngine.Candidate c) {
        remember(history, c, c == null ? "" : c.lexemeIds, c == null ? "" : c.lastSyllable);
    }

    private void remember(String history, CandidateEngine.Candidate c, String ids, String syllable) {
        recentWords = historyWith(history, ids);
        previousSyllable = syllable;
        if (c != null && !c.text.isEmpty() && sentenceEnd(c.text.charAt(c.text.length() - 1))) {
            if (c.kind == CandidateEngine.Kind.PUNCTUATION) recentWords = history;
            beginSentence();
        }
    }

    private static String lexicalKey(CandidateEngine.Candidate c) {
        return c.text + "\u0000" + (c.syllableOnly || c.plainHanLoan ? "" : c.meaning);
    }

    private void loadHints(String words, String sound, CandidateEngine.Mode output) {
        if (words.equals(cachedWordContext) && sound.equals(cachedSoundContext)
                && cachedHintMode == output && cachedHintStyle == orthography) return;
        cachedWordContext = words;
        cachedSoundContext = sound;
        cachedHintMode = output;
        cachedHintStyle = orthography;
        allWordHints = words.isEmpty() ? Collections.emptyList() : engine.nextWords(words, output, orthography);
        allSoundHints = sound.isEmpty() ? Collections.emptyList() : engine.nextSyllables(sound, output, orthography);
        markedWords = yeWords = markedSounds = null;
    }

    private static Map<String, String> spellings(List<CandidateEngine.Candidate> hints, boolean sounds) {
        Map<String, String> forms = new HashMap<>();
        for (CandidateEngine.Candidate c : hints) forms.put(sounds ? c.lastSyllable : c.lexemeIds, c.text);
        return forms;
    }

    private static void addMatched(List<CandidateEngine.Candidate> items, Set<String> edits,
            Set<String> forms, Map<String, String> hanSenses, CandidateEngine.Candidate c) {
        String form = c.consumed + "\u0000" + c.text;
        // An identical character can commit either the entire draft or just a prefix.
        if (hanSenses == null && c.syllableOnly && forms.contains(form)) return;
        String identity = lexicalKey(c);
        if (hanSenses != null) identity += "\u0000" + c.meaning + "\u0000" + c.lexemeIds + "\u0000" + c.lastSyllable;
        if (edits.add(c.consumed + "\u0000" + identity) && takeHanSense(hanSenses, c, c.consumed)) {
            items.add(c);
            forms.add(form);
        }
    }

    /** The same word/sense may arrive from the lookup, completion and prediction APIs. */
    private static boolean takeHanSense(Map<String, String> seen, CandidateEngine.Candidate c, int consumed) {
        if (seen == null || c.hanSenseId.isEmpty()) return true;
        String key = consumed + "\u0000" + c.hanSenseId;
        String previous = seen.get(key);
        if (previous != null) return previous.equals(c.text);
        seen.put(key, c.text);
        return true;
    }

    /** Equal visible senses stay in their highest-priority source; homonyms retain separate choices. */
    private List<CandidateEngine.Candidate> addSuggestions(List<CandidateEngine.Candidate> exact,
            List<CandidateEngine.Candidate> completions, String wordContext, String soundContext,
            CandidateEngine.Mode output, String typed) {
        ArrayList<CandidateEngine.Candidate> items = new ArrayList<>();
        Set<String> edits = new HashSet<>(), forms = new HashSet<>();
        Map<String, String> hanSenses = mode == HAN ? new HashMap<>() : null;
        for (CandidateEngine.Candidate c : exact) {
            if (c.kind == CandidateEngine.Kind.TUJIA && !c.syllableOnly
                    && (typed.isEmpty() || c.consumed == typed.length())) addMatched(items, edits, forms, hanSenses, c);
        }
        for (CandidateEngine.Candidate c : exact) {
            if (c.kind == CandidateEngine.Kind.CHINESE && c.consumed == typed.length()
                    && c.text.codePointCount(0, c.text.length()) > 1 && !forms.contains(c.consumed + "\u0000" + c.text)) items.add(c);
        }
        for (CandidateEngine.Candidate c : completions) addMatched(items, edits, forms, hanSenses, c);
        for (CandidateEngine.Candidate c : exact) {
            if (c.syllableOnly && (typed.isEmpty() || c.consumed == typed.length())) addMatched(items, edits, forms, hanSenses, c);
        }
        for (CandidateEngine.Candidate c : exact) {
            if (c.kind == CandidateEngine.Kind.TUJIA) addMatched(items, edits, forms, hanSenses, c);
        }
        for (CandidateEngine.Candidate c : exact) {
            if (hanSenses != null && c.kind == CandidateEngine.Kind.CHINESE
                    && forms.contains(c.consumed + "\u0000" + c.text)) continue;
            if (c.kind != CandidateEngine.Kind.TUJIA && !(c.kind == CandidateEngine.Kind.CHINESE
                    && c.consumed == typed.length() && c.text.codePointCount(0, c.text.length()) > 1)) items.add(c);
        }
        Set<String> seen = new HashSet<>();
        for (CandidateEngine.Candidate c : items) {
            if (typed.isEmpty() || c.consumed == typed.length()) {
                seen.add(lexicalKey(c));
            }
        }
        if (engine == null) {
            wordHints = syllableHints = Collections.emptyList();
            return items;
        }
        loadHints(wordContext, soundContext, output);
        String prefix = typed.trim();
        if (!prefix.isEmpty() && markedWords == null) {
            markedWords = spellings(engine.nextWords(wordContext, CandidateEngine.Mode.PINYIN,
                    CandidateEngine.Orthography.DIACRITIC), false);
            yeWords = spellings(engine.nextWords(wordContext, CandidateEngine.Mode.PINYIN,
                    CandidateEngine.Orthography.YE), false);
            markedSounds = spellings(engine.nextSyllables(soundContext, CandidateEngine.Mode.PINYIN,
                    CandidateEngine.Orthography.DIACRITIC), true);
        }
        boolean allowYe = output != CandidateEngine.Mode.PINYIN || orthography != CandidateEngine.Orthography.DIACRITIC;
        wordHints = allWordHints;
        for (CandidateEngine.Candidate c : wordHints) {
            if (!prefix.isEmpty() && !CandidateEngine.matchesSpellingPrefix(prefix, yeWords.get(c.lexemeIds),
                    markedWords.get(c.lexemeIds), allowYe)) continue;
            if ((hanSenses != null || seen.add(lexicalKey(c))) && takeHanSense(hanSenses, c, typed.length())) {
                items.add(c);
            }
        }
        syllableHints = allSoundHints;
        for (CandidateEngine.Candidate c : syllableHints) {
            if (!prefix.isEmpty() && !CandidateEngine.matchesSpellingPrefix(prefix, c.lastSyllable, markedSounds.get(c.lastSyllable), allowYe)) continue;
            if (takeHanSense(hanSenses, c, typed.length())) items.add(c);
        }
        return items;
    }

    private boolean hasMeaning(CandidateEngine.Candidate c) {
        return c.kind == CandidateEngine.Kind.TUJIA && !c.plainHanLoan && !c.shortMeaning.isEmpty()
                && (!c.syllableOnly || syllableHints.contains(c));
    }

    private List<CandidateEngine.Candidate> groupTypingChoices(List<CandidateEngine.Candidate> ranked) {
        hanChoices.clear();
        Map<String, HanChoice> byForm = new LinkedHashMap<>();
        for (CandidateEngine.Candidate c : ranked) {
            String form = java.text.Normalizer.normalize(c.text, java.text.Normalizer.Form.NFC);
            HanChoice group = byForm.get(form);
            if (group == null) {
                group = new HanChoice(c);
                byForm.put(form, group);
            }
            boolean prediction = wordHints.contains(c) || syllableHints.contains(c);
            group.add(c, prediction ? draft.length() : c.consumed, hasMeaning(c));
        }
        List<CandidateEngine.Candidate> visible = new ArrayList<>(byForm.size());
        for (HanChoice group : byForm.values()) {
            visible.add(group.first);
            hanChoices.put(group.first, group);
        }
        return visible;
    }

    private boolean extendsHandWord(String glyph) {
        String extended = handRaw + glyph;
        return !handLocked && !handRaw.isEmpty() && engine != null
                && extended.codePointCount(0, extended.length()) <= CandidateEngine.HANDWRITING_WINDOW
                && engine.scoreHandwritingPrefix(extended) > 0;
    }

    private void refreshHandCandidates() {
        hanChoices.clear();
        if (handAsHan) {
            refreshCommittedWords();
            return;
        }
        String pending = strokeAnchor != null && !glyphs.isEmpty() ? glyphs.get(0) : "";
        boolean combined = !pending.isEmpty() && extendsHandWord(pending);
        String raw = pending.isEmpty() ? handRaw : combined ? handRaw + pending : pending;
        boolean closed = pending.isEmpty() && handLocked;
        List<CandidateEngine.Candidate> exact = closed ? Collections.emptyList() : handExact(raw);
        wordCompletions = engine == null || raw.isEmpty() || closed ? Collections.emptyList()
                : engine.handwritingSuggestions(raw, handMode(), orthography);
        String wordContext = recentWords, soundContext = previousSyllable;
        if (!pending.isEmpty()) {
            CandidateEngine.Candidate best = exact.isEmpty() ? null : exact.get(0);
            String prior = combined ? handHistory : recentWords;
            wordContext = best == null ? "" : historyWith(prior, best.lexemeIds);
            soundContext = best == null ? "" : best.lastSyllable;
        }
        candidates = addSuggestions(exact, wordCompletions, wordContext, soundContext, handMode(), "");
        page = 0;
        SpannableStringBuilder display = new SpannableStringBuilder(raw);
        if (!wordCompletions.isEmpty()) {
            List<CandidateEngine.Candidate> written = handAsHan ? wordCompletions :
                    engine.handwritingSuggestions(raw, CandidateEngine.Mode.HAN, orthography);
            for (CandidateEngine.Candidate word : written) {
                if (word.text.startsWith(raw) && word.text.length() > raw.length()) {
                    int end = word.text.offsetByCodePoints(raw.length(), 1);
                    display.append(word.text, raw.length(), end);
                    display.setSpan(new ForegroundColorSpan(MUTED), raw.length(), end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                }
            }
        }
        preview.setText(display);
        preview.setTextColor(raw.isEmpty() ? MUTED : RED);
        preview.setContentDescription("待选拼写 " + raw);
        showCandidates();
    }

    private void refreshCommittedWords() {
        handSenses.clear();
        wordHints = syllableHints = wordCompletions = Collections.emptyList();
        matchedAt = handAnchor();
        boolean available = engine != null && matchedAt != null && matchedAt.start >= 0 && matchedAt.start == matchedAt.end;
        String before = available ? matchedAt.before : "";
        List<CandidateEngine.Candidate> matches = available
                ? engine.handwrittenWords(before, CandidateEngine.Mode.HAN, orthography) : Collections.emptyList();
        Map<String, CandidateEngine.Candidate> visible = new LinkedHashMap<>();
        if (handNext && available) {
            wordCompletions = engine.nextHandwrittenGlyphs(before);
            for (CandidateEngine.Candidate c : wordCompletions) visible.put(c.text, c);
            CandidateEngine.Candidate last = matches.isEmpty() ? null : matches.get(0);
            loadHints(recentWords.isEmpty() && last != null ? last.lexemeIds : recentWords,
                    previousSyllable.isEmpty() && last != null ? last.lastSyllable : previousSyllable, CandidateEngine.Mode.HAN);
            wordHints = allWordHints;
            syllableHints = allSoundHints;
            for (CandidateEngine.Candidate c : wordHints) visible.putIfAbsent(c.text, c);
            for (CandidateEngine.Candidate c : syllableHints) visible.putIfAbsent(c.text, c);
        } else {
            for (CandidateEngine.Candidate c : matches) {
                String key = c.consumed + "\t" + c.text;
                CandidateEngine.Candidate first = visible.get(key);
                if (first == null) {
                    first = c;
                    visible.put(key, first);
                    handSenses.put(first, new ArrayList<>());
                    hanChoices.put(first, new HanChoice(first));
                }
                handSenses.get(first).add(c);
                hanChoices.get(first).add(c, c.consumed, hasMeaning(c));
            }
        }
        if (wordsTab != null) {
            wordsTab.setSelected(!handNext);
            wordsTab.setTextColor(pressColors(handNext ? MUTED : RED));
            nextTab.setSelected(handNext);
            nextTab.setTextColor(pressColors(handNext ? RED : MUTED));
        }
        candidates = new ArrayList<>(visible.values());
        page = 0;
        showCandidates();
    }

    private void buildHandwriting() {
        handPanelActive = true;
        glyphRow = row();
        body.addView(glyphRow, lineWithMinimum(compact ? 29 : 34, compact ? 18 : 19));
        pad = new HandwritingPad(this, new HandwritingPad.Listener() {
            @Override public void onStrokeStarted(boolean first) {
                if (first) {
                    if (toneCancel != null) toneCancel.setVisibility(View.GONE);
                    HandAnchor current = handAnchor();
                    if (!handRaw.isEmpty() && (current == null || !ownsHandOutput(current))) {
                        clearHandSession();
                        recentWords = previousSyllable = "";
                        if (current != null && current.start == current.end && (current.start == 0 ||
                                !current.before.isEmpty() && sentenceEnd(current.before.charAt(current.before.length() - 1)))) {
                            recentWords = previousSyllable = "<s>";
                        }
                    }
                    strokeAnchor = current;
                }
            }
            @Override public void onCandidates(List<String> found) {
                if (!handPanelActive || !inputViewActive || mode != HAND) return;
                glyphs = found;
                glyphPage = 0;
                if (!pad.hasInk()) strokeAnchor = null;
                showGlyphs();
                if (!handAsHan || strokeAnchor == null) refreshHandCandidates();
            }
            @Override public void onSettled() {
                if (handPanelActive && !glyphs.isEmpty() && strokeAnchor != null) acceptGlyph(glyphs.get(0), false);
            }
        });
        pad.setContentDescription("汉字手写区");
        body.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = row();
        body.addView(controls, lineWithMinimum(compact ? 29 : 38, compact ? 14 : 15));
        key(controls, "撤笔", "撤销一笔", 1, false, () -> pad.undoStroke());
        key(controls, "清笔迹", "清除笔迹", 1, false, () -> pad.clear());
        key(controls, "退格", "退格", 1, false, this::backspace);
        String action = enterLabel();
        key(controls, action, action, 1, true, () -> {
            clearHandSession();
            glyphs = Collections.emptyList();
            showGlyphs();
            refreshHandCandidates();
            enter();
        });
        showGlyphs();
    }

    private void showGlyphs() {
        if (glyphRow == null) return;
        glyphRow.removeAllViews();
        if (glyphs.isEmpty()) {
            return;
        }
        TextView label = label(strokeAnchor == null ? "改字" : "字形", compact ? 13 : 15, MUTED);
        glyphRow.addView(label, new LinearLayout.LayoutParams(dp(compact ? 33 : 42), -1));
        for (int i = glyphPage * 5; i < Math.min((glyphPage + 1) * 5, glyphs.size()); i++) {
            String glyph = glyphs.get(i);
            key(glyphRow, glyph, "手写字形 " + glyph, 1, false, () -> acceptGlyph(glyph, strokeAnchor == null));
        }
        if (glyphs.size() > 5) {
            key(glyphRow, "换组", "更多手写字形", 1, false, () -> {
                glyphPage = (glyphPage + 1) % ((glyphs.size() + 4) / 5);
                showGlyphs();
            });
        }
    }

    private void acceptGlyph(String glyph, boolean correction) {
        CrashReports.event(getCurrentInputEditorInfo(), "手写字", glyph);
        if (toneCancel != null) toneCancel.setVisibility(View.GONE);
        HandAnchor cursor = handAnchor();
        if (cursor == null || (!correction && (strokeAnchor == null || !strokeAnchor.matches(cursor)))) {
            discardHandWork();
            return;
        }
        String oldHandGlyph = handOwned;
        boolean combined = !handAsHan && !correction && extendsHandWord(glyph);
        String prefix = correction ? handPrefix : combined ? handRaw : "";
        String previousOutput = correction ? handPrefixOutput : combined ? handOutput : "";
        String raw = prefix + glyph;
        List<CandidateEngine.Candidate> lexical = handExact(raw);
        CandidateEngine.Candidate best = lexical.isEmpty() ? null : lexical.get(0);
        String out = raw;
        if (!handAsHan) {
            if (best != null) out = best.text;
            else if (!previousOutput.isEmpty()) {
                List<CandidateEngine.Candidate> single = handExact(glyph);
                String last = single.isEmpty() ? glyph : single.get(0).text;
                out = previousOutput + CandidateEngine.spaceWord(last, previousOutput.charAt(previousOutput.length() - 1), (char)0);
            }
        }
        String history = correction || combined ? handHistory : recentWords;
        int[] ranges = handAsHan && best != null && out.equals(best.text) ? best.joinedHanRanges : null;
        if (!writeHand(cursor, out, ranges, combined || correction)) { discardHandWork(); return; }
        handNext = false;
        handRaw = raw;
        handLocked = false;
        handPrefix = prefix;
        handPrefixOutput = previousOutput;
        handHistory = history;
        if (handAsHan && engine != null) {
            String preceding = cursor.before;
            if (correction && preceding.endsWith(oldHandGlyph))
                preceding = preceding.substring(0, preceding.length() - oldHandGlyph.length());
            List<CandidateEngine.Candidate> ending = engine.handwrittenWords(preceding + glyph, CandidateEngine.Mode.HAN, orthography);
            best = ending.isEmpty() ? null : ending.get(0);
        }
        remember(history, best);
        strokeAnchor = null;
        pad.clearInk();
        showGlyphs();
        refreshHandCandidates();
    }

    private void discardHandWork() {
        recentWords = previousSyllable = "";
        clearHandSession();
        glyphs = Collections.emptyList();
        glyphPage = 0;
        showGlyphs();
        refreshHandCandidates();
    }

    private void chooseHand(CandidateEngine.Candidate c) {
        CrashReports.event(getCurrentInputEditorInfo(), "手写选词", c.text);
        if (handAsHan) {
            if (strokeAnchor != null) {
                if (!glyphs.isEmpty()) acceptGlyph(glyphs.get(0), false);
                return;
            }
            HandAnchor cursor = handAnchor();
            if (matchedAt == null || !matchedAt.matches(cursor)) { discardHandWork(); return; }
            if (!handNext) {
                openHandDetail(c, 0);
            } else if (writeHand(cursor, c.text, c.joinedHanRanges, false)) {
                remember(recentWords, c);
                handNext = false;
                glyphs = Collections.emptyList();
                clearHandSession();
                showGlyphs();
                refreshHandCandidates();
            }
            return;
        }
        boolean predicting = wordHints.contains(c) || syllableHints.contains(c);
        boolean completing = wordCompletions.contains(c);
        if (!candidates.contains(c)) return;
        if (strokeAnchor != null && !glyphs.isEmpty()) {
            acceptGlyph(glyphs.get(0), false);
            if (handRaw.isEmpty()) return;
        }
        if (!predicting && handRaw.isEmpty()) return;
        HandAnchor cursor = handAnchor();
        if (cursor == null) { discardHandWork(); return; }
        if (predicting && !handRaw.isEmpty() && !ownsHandOutput(cursor)) { discardHandWork(); return; }
        String history = predicting ? recentWords : handHistory;
        if (!writeHand(cursor, c.text, c.joinedHanRanges, !predicting)) { discardHandWork(); return; }
        // An explicit completion chooses a whole word, not another character to correct.
        if (predicting || completing) {
            handRaw = c.text;
            handLocked = true;
            handPrefix = handPrefixOutput = "";
            glyphs = Collections.emptyList();
        }
        handHistory = history;
        remember(history, c);
        strokeAnchor = null;
        pad.clearInk();
        showGlyphs();
        refreshHandCandidates();
    }

    private boolean ownsHandOutput(HandAnchor cursor) {
        if (handEditor != cursor.editor || handStart < 0 || cursor.start != cursor.end
                || cursor.end != handStart + handOwned.length() || handOwned.isEmpty()) return false;
        CharSequence before = cursor.editor.getTextBeforeCursor(handOwned.length(), 0);
        return before != null && handOwned.contentEquals(before);
    }

    /** Replace only this session's confirmed previous output, as a single editor operation. */
    private boolean writeHand(HandAnchor cursor, String out, int[] ranges, boolean replace) {
        if (out.isEmpty() || (replace && !ownsHandOutput(cursor))) return false;
        int start = replace ? handStart : Math.min(cursor.start, cursor.end);
        int oldLength = replace ? handOwned.length() : 0;
        return writeHandRange(cursor, out, ranges, start, oldLength);
    }

    private boolean writeHandRange(HandAnchor cursor, String out, int[] ranges, int start, int oldLength) {
        InputConnection ic = cursor.editor;
        CharSequence before = ic.getTextBeforeCursor(oldLength + 1, 0);
        CharSequence after = ic.getTextAfterCursor(1, 0);
        char left = before == null || before.length() <= oldLength ? 0 : before.charAt(before.length() - oldLength - 1);
        char right = after == null || after.length() == 0 ? 0 : after.charAt(0);
        String written = CandidateEngine.spaceWord(out, left, right);
        CharSequence styled = HanUnitStyle.forEditor(written, ranges, written.startsWith(out) ? 0 : 1);
        ic.beginBatchEdit();
        try {
            boolean selected = false;
            if (oldLength > 0 && !ic.setComposingRegion(start, start + oldLength)) {
                if (!ic.setSelection(start, start + oldLength)) return false;
                selected = true;
            }
            if (!ic.commitText(styled, 1)) {
                if (selected) ic.setSelection(cursor.end, cursor.end);
                else if (oldLength > 0) ic.finishComposingText();
                return false;
            }
            if (start >= 0) expect(start + styled.length());
            handStart = start;
            handOwned = styled.toString();
            handOutput = out;
            handEditor = ic;
            return true;
        } finally {
            ic.endBatchEdit();
        }
    }

    private void closeHandDetail() {
        if (handDetail == null) return;
        body.removeView(handDetail);
        handDetail = null;
        for (int i = 0; i < body.getChildCount(); i++) body.getChildAt(i).setVisibility(View.VISIBLE);
    }

    private void openHandDetail(CandidateEngine.Candidate first, int index) {
        List<CandidateEngine.Candidate> senses = handSenses.get(first);
        HandAnchor anchor = matchedAt;
        if (senses == null || anchor == null || !anchor.matches(handAnchor()) || first.consumed > anchor.before.length()) {
            discardHandWork();
            return;
        }
        Map<String, CandidateEngine.Candidate> spellings = new HashMap<>();
        for (CandidateEngine.Candidate roman : engine.handwrittenWords(anchor.before, CandidateEngine.Mode.HANDWRITING,
                CandidateEngine.Orthography.DIACRITIC)) {
            if (roman.consumed == first.consumed) spellings.put(roman.lexemeIds, roman);
        }
        List<CandidateEngine.Candidate> distinct = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (CandidateEngine.Candidate c : senses) {
            CandidateEngine.Candidate spelling = spellings.get(c.lexemeIds);
            if (spelling != null && seen.add(spelling.text + "\t" + c.meaning)) distinct.add(c);
        }
        if (distinct.isEmpty()) return;
        int current = index % distinct.size();
        CandidateEngine.Candidate c = distinct.get(current), roman = spellings.get(c.lexemeIds);
        String raw = anchor.before.substring(anchor.before.length() - c.consumed);
        closeHandDetail();
        for (int i = 0; i < body.getChildCount(); i++) body.getChildAt(i).setVisibility(View.GONE);
        handDetail = column();
        handDetail.setBackgroundColor(PAPER);
        body.addView(handDetail, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout top = row();
        handDetail.addView(top, lineWithMinimum(compact ? 29 : 35, compact ? 14 : 16));
        top.addView(label("已写「" + raw + "」", compact ? 14 : 16, MUTED), weighted(2));
        if (distinct.size() > 1) {
            String count = "义项 " + (current + 1) + "/" + distinct.size();
            key(top, count, "切换" + count, 1.3f, false, () -> openHandDetail(first, current + 1));
        }
        key(top, "返回", "返回手写", 1, false, this::closeHandDetail);
        TextView definition = label("", compact ? 16 : 18, INK);
        definition.setSingleLine(false);
        definition.setText(new SpannableStringBuilder(HanUnitStyle.forKeyboard(c.text, c.joinedHanRanges))
                .append("　").append(roman.text).append(c.plainHanLoan || c.meaning.equals(c.text) ? "" : "\n" + c.meaning));
        handDetail.addView(definition, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout actions = row();
        handDetail.addView(actions, lineWithMinimum(compact ? 33 : 41, compact ? 14 : 16));
        String hanLabel = raw.equals(c.text) ? "保留原写法" : "用推荐字";
        key(actions, hanLabel, hanLabel, 1, false, () -> applyHandWord(anchor, c, c, raw));
        key(actions, "用调号拼音", "用调号拼音", 1, true, () -> applyHandWord(anchor, c, roman, raw));
    }

    private void applyHandWord(HandAnchor shown, CandidateEngine.Candidate lexical, CandidateEngine.Candidate form, String raw) {
        HandAnchor cursor = handAnchor();
        if (!shown.matches(cursor) || cursor.start != cursor.end || cursor.end < raw.length()
                || !cursor.before.endsWith(raw)) { discardHandWork(); return; }
        if (!form.text.equals(raw) && !writeHandRange(cursor, form.text, form.joinedHanRanges,
                cursor.end - raw.length(), raw.length())) { discardHandWork(); return; }
        remember(handHistory, lexical);
        clearHandSession();
        glyphs = Collections.emptyList();
        showGlyphs();
        refreshHandCandidates();
    }

    private boolean inIpaMode() {
        return mode == IPA && !direct;
    }

    private void buildIpaKeys() {
        closeIpaAlternatives();
        body.removeAllViews();
        String[][][] layout = symbols ? IpaKeyboard.SYMBOLS : IpaKeyboard.LETTERS;
        for (int i = 0; i < layout.length; i++) {
            LinearLayout line = row();
            body.addView(line, new LinearLayout.LayoutParams(-1, 0, 1));
            if (i == 2) {
                if (symbols) {
                    ipaKey(line, IpaKeyboard.NASAL, 1.25f, KeyLook.UTILITY, false,
                            () -> typeIpa(IpaKeyboard.NASAL), IpaKeyboard.SYLLABIC, IpaKeyboard.TIE);
                } else {
                    ipaKey(line, "⇧", 1.25f, KeyLook.UTILITY, shifted, () -> {
                        shifted = !shifted;
                        buildIpaKeys();
                    }, IpaKeyboard.NASAL, IpaKeyboard.SYLLABIC, IpaKeyboard.TIE)
                            .setContentDescription("切换大小写");
                }
            }
            for (String[] options : layout[i]) {
                String text = shifted ? options[0].toUpperCase(Locale.ROOT) : options[0];
                ipaKey(line, text, 1, KeyLook.LETTER, false, () -> typeIpa(text),
                        Arrays.copyOfRange(options, 1, options.length));
            }
            if (i == 2) key(line, "⌫", "退格", 1.25f, false, this::backspace);
        }
        LinearLayout line = row();
        body.addView(line, new LinearLayout.LayoutParams(-1, 0, 1));
        key(line, symbols ? "字母" : "符号", "字母或符号", 1.25f, false, () -> {
            symbols = !symbols;
            shifted = false;
            buildIpaKeys();
        });
        key(line, "🌐", "切换输入法", .9f, false, this::pickIme);
        key(line, "/", "/", .8f, KeyLook.PLAIN, false, () -> typeIpa("/"));
        key(line, "空格", "空格", 2.35f, KeyLook.SPACE, false, this::space);
        ipaKey(line, "?", .8f, KeyLook.UTILITY, false, () -> typeIpa("?"), "ʕ", "ʔ", "[", "]");
        String action = enterLabel();
        key(line, action, action, 1.3f, true, this::enter);
    }

    private TextView ipaKey(LinearLayout line, String text, float weight, KeyLook look,
                            boolean selected, Runnable tap, String... alternatives) {
        String primary = IpaKeyboard.display(text);
        TextView key = key(line, primary, IpaKeyboard.description(text), weight, look, selected, tap);
        if (alternatives.length == 0) return key;
        String hint = IpaKeyboard.display(alternatives[0]);
        if (alternatives.length > 1) hint += " " + IpaKeyboard.display(alternatives[1]);
        SpannableStringBuilder caption = new SpannableStringBuilder(hint).append('\n').append(primary);
        caption.setSpan(new RelativeSizeSpan(.57f), 0, hint.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        key.setSingleLine(false);
        key.setMaxLines(2);
        key.setText(caption);
        key.setHapticFeedbackEnabled(false);
        key.setOnLongClickListener(v -> {
            showIpaAlternatives(key, alternatives);
            return true;
        });
        return key;
    }

    private void closeIpaAlternatives() {
        if (ipaAlternatives == null) return;
        keyboardFrame.removeView(ipaAlternatives);
        ipaAlternatives = null;
    }

    private void showIpaAlternatives(View anchor, String[] alternatives) {
        closeIpaAlternatives();
        if (keyboardFrame.getWidth() == 0) return;
        int[] origin = new int[2], position = new int[2], strip = new int[2];
        keyboardFrame.getLocationInWindow(origin);
        origin[0] += keyboardFrame.getPaddingLeft();
        origin[1] += keyboardFrame.getPaddingTop();
        anchor.getLocationInWindow(position);
        choices.getLocationInWindow(strip);
        int room = keyboardFrame.getWidth() - keyboardFrame.getPaddingLeft() - keyboardFrame.getPaddingRight();
        int width = Math.min(room - dp(4), dp(53) * alternatives.length);
        int height = Math.max(dp(44), textHeight(formSp()) + dp(8));
        int left = Math.max(0, Math.min(room - width,
                position[0] - origin[0] + (anchor.getWidth() - width) / 2));
        int top = position[1] - origin[1] - height;
        if (top < strip[1] + choices.getHeight() - origin[1])
            top = position[1] - origin[1] + anchor.getHeight();

        ipaAlternatives = new FrameLayout(this);
        ipaAlternatives.setOnClickListener(v -> closeIpaAlternatives());
        ipaAlternatives.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout menu = row();
        menu.setBackground(shape(RED, 8));
        menu.setPadding(dp(1), dp(1), dp(1), dp(1));
        for (String value : alternatives) {
            key(menu, IpaKeyboard.display(value), IpaKeyboard.description(value), 1, KeyLook.SPACE, false,
                    () -> typeIpa(value));
        }
        FrameLayout.LayoutParams menuBounds = new FrameLayout.LayoutParams(width, height);
        menuBounds.leftMargin = left;
        menuBounds.topMargin = top;
        ipaAlternatives.addView(menu, menuBounds);
        keyboardFrame.addView(ipaAlternatives, new FrameLayout.LayoutParams(-1, -1));
    }

    private void typeIpa(String text) {
        closeIpaAlternatives();
        if (commit(text, false)) showIpaCandidates();
        if (shifted) {
            shifted = false;
            buildIpaKeys();
        }
    }

    private void loadIpaLexicon() {
        ipaLoading = true;
        new Thread(() -> {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
            IpaKeyboard loaded = null;
            try {
                loaded = new IpaKeyboard(getAssets().open("ipa-completions.tsv"));
            } catch (java.io.IOException failure) {
                Log.e("TujiaIME", "Could not load bundled IPA completions", failure);
            }
            IpaKeyboard result = loaded;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (destroyed) return;
                ipaLexicon = result;
                ipaLoading = false;
                ipaLoadFailed = result == null;
                shownIpaPrefix = null;
                if (inputViewActive && inIpaMode()) showIpaCandidates();
            });
        }, "Tujia-IPA-lexicon").start();
    }

    private String ipaPrefix(InputConnection editor) {
        return IpaKeyboard.prefix(editor.getTextBeforeCursor(IpaKeyboard.MAX_PREFIX + 1, 0),
                editor.getTextAfterCursor(1, 0));
    }

    private void showIpaCandidates() {
        if (choices == null) return;
        if (ipaLexicon == null && !ipaLoading && !ipaLoadFailed) loadIpaLexicon();
        InputConnection editor = getCurrentInputConnection();
        String prefix = editor == null || selStart != selEnd ? "" : ipaPrefix(editor);
        if (prefix.equals(shownIpaPrefix)) return;
        shownIpaPrefix = prefix;
        choices.removeAllViews();
        List<IpaKeyboard.Entry> matches = ipaLexicon == null ? Collections.emptyList() : ipaLexicon.complete(prefix);
        if (matches.isEmpty()) {
            String message = prefix.isEmpty() ? "输入音标查看候选"
                    : ipaLoading ? "正在加载音标词库" : ipaLoadFailed ? "音标词库未载入" : "无匹配候选";
            choices.addView(label(message, glossSp(), MUTED), weighted(1));
            return;
        }
        for (IpaKeyboard.Entry entry : matches) {
            LinearLayout card = column();
            card.setGravity(Gravity.CENTER);
            card.setBackground(pressable(shape(Color.TRANSPARENT, 6), 6));
            TextView form = label(entry.ipa, formSp(), RED);
            form.setTextColor(pressColors(RED));
            form.setDuplicateParentStateEnabled(true);
            card.addView(form, new LinearLayout.LayoutParams(-1, textHeight(formSp())));
            TextView gloss = label(entry.gloss, glossSp(), MUTED);
            gloss.setTextColor(pressColors(MUTED));
            gloss.setDuplicateParentStateEnabled(true);
            card.addView(gloss, new LinearLayout.LayoutParams(-1, textHeight(glossSp())));
            card.setContentDescription("补全 " + entry.gloss + "，" + entry.ipa);
            card.setFocusable(true);
            KeyboardTap.bind(card, () -> completeIpa(editor, prefix, entry.ipa));
            choices.addView(card, weighted(1));
        }
        if (matches.size() < IpaKeyboard.COLUMNS) {
            choices.addView(new View(this), weighted(IpaKeyboard.COLUMNS - matches.size()));
        }
    }

    private void completeIpa(InputConnection editor, String prefix, String replacement) {
        if (!inputViewActive || !inIpaMode() || editor != getCurrentInputConnection()) return;
        long cursor = cursorAt(editor);
        int end = (int) cursor, start = end - prefix.length();
        if ((int) (cursor >>> 32) != end || start < 0 || !prefix.equals(ipaPrefix(editor))) return;
        closeMore();
        closeIpaAlternatives();
        editor.beginBatchEdit();
        try {
            boolean selected = !editor.setComposingRegion(start, end);
            if (selected && !editor.setSelection(start, end)) return;
            if (editor.commitText(replacement, 1)) {
                expect(start + replacement.length());
            } else if (selected) {
                editor.setSelection(end, end);
            } else {
                editor.finishComposingText();
            }
        } finally {
            editor.endBatchEdit();
        }
        showIpaCandidates();
    }

    private void buildKeys() {
        if (inIpaMode()) {
            buildIpaKeys();
            return;
        }
        body.removeAllViews();
        String[] rows = symbols ? new String[]{"1234567890", "@#$%&*-+/", direct ? ",.:;?!()" : "，。：；？！（）"}
                : new String[]{"qwertyuiop", "asdfghjkl", "zxcvbnm"};
        for (int i = 0; i < rows.length; i++) {
            LinearLayout r = row();
            body.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
            if (i == 2 && !symbols) key(r, "⇧", "切换大小写", 1.4f, shifted, () -> {
                shifted = !shifted;
                buildKeys();
            });
            for (char raw : rows[i].toCharArray()) {
                char c = shifted && !symbols ? Character.toUpperCase(raw) : raw;
                String shown = String.valueOf(c).toUpperCase(Locale.ROOT);
                TextView letter = key(r, shown, String.valueOf(c), 1, KeyLook.LETTER, false, () -> typeChar(c));
                if (!direct && !symbols) {
                    String tones = toneChoices(c);
                    if (!tones.isEmpty()) {
                        letter.setHapticFeedbackEnabled(false);
                        letter.setOnLongClickListener(v -> {
                            showTones(tones);
                            return true;
                        });
                    }
                }
            }
            if (i == 2) key(r, "⌫", "退格", 1.4f, false, this::backspace);
        }
        LinearLayout r = row();
        body.addView(r, new LinearLayout.LayoutParams(-1, 0, 1));
        key(r, symbols ? "ABC" : "123", "字母或符号", 1.25f, false, () -> {
            if (selectingTone) updateCandidates();
            symbols = !symbols; shifted = false; buildKeys();
        });
        key(r, "🌐", "切换输入法", .9f, false, this::pickIme);
        key(r, direct ? "," : "，", "逗号", .8f, KeyLook.PLAIN, false, () -> typeChar(direct ? ',' : '，'));
        key(r, "空格", "空格", 2.35f, KeyLook.SPACE, false, this::space);
        key(r, direct ? "." : "。", "句号", .8f, KeyLook.PLAIN, false, () -> typeChar(direct ? '.' : '。'));
        String action = enterLabel();
        key(r, action, action, 1.3f, true, this::enter);
    }

    private static String toneChoices(char vowel) {
        String tones;
        switch (Character.toLowerCase(vowel)) {
            case 'a': tones = "āáǎà"; break;
            case 'e': tones = "ēéěè"; break;
            case 'i': tones = "īíǐì"; break;
            case 'o': tones = "ōóǒò"; break;
            case 'u': tones = "ūúǔù"; break;
            default: return "";
        }
        return Character.isUpperCase(vowel) ? tones.toUpperCase(Locale.ROOT) : tones;
    }

    private void showTones(String tones) {
        selectingTone = true;
        toneCancel.setText("取消");
        toneCancel.setContentDescription("取消选声调");
        choices.removeAllViews();
        preview.setText(draft.isEmpty() ? "选择声调" : draft);
        toneCancel.setVisibility(View.VISIBLE);
        prevChoices.setVisibility(View.GONE);
        nextChoices.setVisibility(View.GONE);
        for (char toned : tones.toCharArray()) {
            String title = String.valueOf(toned);
            key(choices, title, title, 1, false, () -> typeChar(toned));
        }
    }

    private void typeChar(char c) {
        CrashReports.event(getCurrentInputEditorInfo(), "按键", String.valueOf(c));
        if (direct) commit(String.valueOf(c), false);
        else if (Character.isLetter(c) || !draft.isEmpty()) appendDraft(String.valueOf(c));
        else if (commit(String.valueOf(c), false)) {
            if (sentenceEnd(c)) beginSentence();
            else recentWords = previousSyllable = "";
            updateCandidates();
        }
        if (shifted && !symbols) { shifted = false; buildKeys(); }
    }

    private void appendDraft(String text) {
        draft += text;
        CrashReports.event(getCurrentInputEditorInfo(), "组字", draft);
        updateCandidates();
    }

    private void updateCandidates() {
        if (surface == null || preview == null || choices == null) return;
        closeReading();
        selectingTone = false;
        if (toneCancel != null) toneCancel.setVisibility(View.GONE);
        if (direct || mode == VOICE || !inputViewActive) {
            hanChoices.clear();
            candidates = wordHints = syllableHints = Collections.emptyList();
        } else if (mode == IPA) {
            preview.setText("");
            showIpaCandidates();
            return;
        } else if (mode == HAND) {
            refreshHandCandidates();
            return;
        } else {
            CandidateEngine.Mode output = mode == SPELL ? CandidateEngine.Mode.PINYIN : CandidateEngine.Mode.HAN;
            wordCompletions = engine == null || draft.isEmpty() ? Collections.emptyList()
                    : engine.spellingCompletions(draft, recentWords, output, orthography);
            List<CandidateEngine.Candidate> dictionary = engine == null || draft.isEmpty()
                    ? Collections.emptyList() : engine.lookup(draft, output, orthography);
            candidates = groupTypingChoices(addSuggestions(dictionary, wordCompletions, recentWords, previousSyllable, output, draft));
        }
        page = 0;
        preview.setText(draft);
        preview.setTextColor(draft.isEmpty() ? MUTED : RED);
        preview.setTypeface(draft.isEmpty() ? Typeface.DEFAULT : MEDIUM);
        preview.setContentDescription("待选拼写 " + draft);
        showCandidates();
    }

    private void showCandidates() {
        choices.removeAllViews();
        boolean multiplePages = candidates.size() > COLUMNS;
        if (prevChoices != null) {
            updatePageControl(prevChoices, multiplePages, page > 0);
            updatePageControl(nextChoices, multiplePages, (page + 1) * COLUMNS < candidates.size());
        }
        if (candidates.isEmpty()) {
            String text;
            if (direct || mode == VOICE) text = "";
            else if (engine == null) text = lexiconFailed ? "词库未载入，点此重试" : "正在加载词库，可先输入拼写";
            else if (draft.isEmpty() || mode == HAND) text = "";
            else text = "未找到词，可在更多里输入原拼写";
            TextView status = label(text, compact ? 15 : 18, MUTED);
            if (!direct && mode != VOICE && lexiconFailed) {
                KeyboardTap.bind(status, () -> {
                    lexiconFailed = false;
                    updateCandidates();
                    LexiconStore.load(this, this);
                });
            }
            choices.addView(status, weighted(1));
            return;
        }
        int first = page * COLUMNS;
        int count = Math.min(COLUMNS, candidates.size() - first);
        for (int i = first; i < first + count; i++) {
            CandidateEngine.Candidate c = candidates.get(i);
            LinearLayout card = column();
            card.setGravity(Gravity.CENTER);
            Drawable rest = shape(Color.TRANSPARENT, 0);
            if (i > first) rest = marker(rest, RULE, 1, compact ? 19 : 26, Gravity.START | Gravity.CENTER_VERTICAL);
            card.setBackground(pressable(rest, 6));
            boolean tujia = c.kind == CandidateEngine.Kind.TUJIA;
            boolean nextSound = syllableHints.contains(c);
            HanChoice group = hanChoices.get(c);
            int glossLimit = mode == HAND ? 2 : Integer.MAX_VALUE;
            String shortText = group != null ? HanChoice.gloss(group.primary.shortMeanings, true, glossLimit) : hasMeaning(c) ? c.shortMeaning : "";
            String fullText = group != null ? (group.primary.meanings.isEmpty() ? "" : group.primary.fullMeanings()) : hasMeaning(c) ? c.meaning : "";
            boolean hasGloss = !shortText.isEmpty() && !shortText.equals(c.text);
            String hint = nextSound ? "下一音节 " : wordCompletions.contains(c) ? (mode == HAND ? "续写 " : "补全 ") :
                    wordHints.contains(c) ? "联想 " : "";
            int glossSize = glossSp();
            TextView meaning;
            if (group != null && group.primary.shortMeanings.size() > 1) {
                meaning = numberedGloss(shortText, HanChoice.gloss(group.primary.shortMeanings, false, glossLimit));
            } else meaning = cardLine(hasGloss ? shortText : "", glossSize, 13, MUTED, false);
            meaning.setDuplicateParentStateEnabled(true);
            meaning.setTextColor(pressColors(MUTED));
            if (!hasGloss) {
                meaning.setVisibility(View.INVISIBLE);
                meaning.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            card.addView(meaning, new LinearLayout.LayoutParams(-1, textHeight(glossSize)));
            TextView text = cardLine(HanUnitStyle.forKeyboard(c.text, c.joinedHanRanges), formSp(), 16, tujia && i == 0 ? RED : INK, true);
            text.setDuplicateParentStateEnabled(true);
            text.setTextColor(pressColors(tujia && i == 0 ? RED : INK));
            card.addView(text, new LinearLayout.LayoutParams(-1, textHeight(formSp())));
            KeyboardTap.bind(card, () -> choose(c));
            card.setHapticFeedbackEnabled(false);
            card.setOnLongClickListener(v -> {
                openReading(c);
                return true;
            });
            String description = !fullText.isEmpty() && !fullText.equals(c.text) ? fullText + "，" + c.text
                    : tujia ? c.text : categoryName(c) + "，" + c.text;
            card.setContentDescription(hint + description);
            card.setFocusable(true);
            choices.addView(card, weighted(1));
        }
        // Keep a single result as easy to locate as the first of a full group.
        if (count < COLUMNS) {
            View end = new View(this);
            end.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            LinearLayout.LayoutParams blank = weighted(COLUMNS - count);
            blank.setMargins(dp(COLUMNS - count), dp(1), dp(COLUMNS - count), dp(1));
            choices.addView(end, blank);
        }
    }

    private void updatePageControl(TextView control, boolean visible, boolean enabled) {
        control.setVisibility(visible ? View.VISIBLE : View.GONE);
        control.setEnabled(enabled);
        control.setAlpha(enabled ? 1f : .35f);
    }

    private TextView numberedGloss(String spaced, String compactText) {
        TextView view = label(spaced, glossSp(), MUTED);
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int room = r - l - view.getCompoundPaddingLeft() - view.getCompoundPaddingRight() - 1;
            if (room <= 0) return;
            TextPaint natural = new TextPaint(view.getPaint());
            natural.setTextScaleX(1f);
            String display = Layout.getDesiredWidth(spaced, natural) <= room ? spaced : compactText;
            if (!display.contentEquals(view.getText())) view.setText(display);
            fitTextWidth(view, display, room, .78f);
        });
        return view;
    }

    private TextView cardLine(CharSequence value, int preferred, int minimum, int color, boolean bold) {
        TextView v = label(value, preferred, color);
        if (bold) v.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        int rowWidth = choices.getWidth();
        if (rowWidth == 0) rowWidth = getResources().getDisplayMetrics().widthPixels
                - surface.getPaddingLeft() - surface.getPaddingRight();
        int available = Math.max(1, rowWidth / COLUMNS - dp(2));
        float width = Layout.getDesiredWidth(value, v.getPaint());
        if (width > available) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                    Math.max(sp(minimum), v.getTextSize() * available / width));
        }
        if (bold && getResources().getConfiguration().fontScale >= 1.3f && shortHanWord(value)) {
            v.addOnLayoutChangeListener((view, left, top, right, bottom, ol, ot, or, ob) -> {
                if (keyboardFrame.getWidth() > dp(360)) {
                    if (v.getTextScaleX() != 1f) v.setTextScaleX(1f);
                    return;
                }
                int room = right - left - v.getCompoundPaddingLeft() - v.getCompoundPaddingRight() - 1;
                if (room <= 0) return;
                fitTextWidth(v, value, room, .75f);
            });
        }
        return v;
    }

    private static void fitTextWidth(TextView view, CharSequence value, int room, float minimumScale) {
        if (room <= 0) return;
        TextPaint natural = new TextPaint(view.getPaint());
        natural.setTextScaleX(1f);
        // Layout also accounts for the existing compact, underlined Han pairs.
        float width = Layout.getDesiredWidth(value, natural);
        float scale = Math.max(minimumScale, Math.min(1f, room / Math.max(1f, width)));
        if (Math.abs(view.getTextScaleX() - scale) > .001f) view.setTextScaleX(scale);
    }

    private static boolean shortHanWord(CharSequence value) {
        int count = Character.codePointCount(value, 0, value.length());
        if (count < 2 || count > 3) return false;
        for (int at = 0; at < value.length();) {
            int c = Character.codePointAt(value, at);
            if (!Character.isIdeographic(c)) return false;
            at += Character.charCount(c);
        }
        return true;
    }

    private static String categoryName(CandidateEngine.Candidate c) {
        switch (c.kind) {
            case CHINESE: return "汉语";
            case PUNCTUATION: return "标点";
            default: return "原文";
        }
    }

    private void openReading(CandidateEngine.Candidate candidate) { openReading(candidate, 0); }

    private void openReading(CandidateEngine.Candidate candidate, int alternateIndex) {
        if (mode == HAND && handAsHan && !handNext) { openHandDetail(candidate, 0); return; }
        closeMore();
        closeReading();
        inspectedCandidate = candidate;
        readingPanel = column();
        readingPanel.setBackgroundColor(PAPER);
        readingPanel.setPadding(dp(4), dp(2), dp(4), dp(2));
        TextView heading = label("候选全文", compact ? 15 : 17, RED);
        readingPanel.addView(heading, lineWithMinimum(compact ? 29 : 34, compact ? 15 : 17));
        int controlsHeight = Math.max(dp(compact ? 34 : 42), textHeight(compact ? 14 : 15) + dp(6));
        HanChoice group = hanChoices.get(candidate);
        List<HanChoice.Action> alternatives = new ArrayList<>();
        if (group != null) for (HanChoice.Action action : group.actions) {
            if (action != group.primary) alternatives.add(action);
        }
        int contentHeight = panelHeight - readingPanel.getPaddingTop() - readingPanel.getPaddingBottom()
                - heading.getLayoutParams().height - controlsHeight * (alternatives.isEmpty() ? 1 : 2);
        int contentWidth = keyboardFrame.getWidth() - readingPanel.getPaddingLeft() - readingPanel.getPaddingRight();
        String header;
        boolean soundHint = syllableHints.contains(candidate);
        if (group != null && !group.primary.meanings.isEmpty())
            header = "汉义\n" + group.primary.fullMeanings() + "\n\n待输入\n";
        else if (group != null && group.primary.candidate.kind == CandidateEngine.Kind.TUJIA)
            header = "待输入\n";
        else if (candidate.plainHanLoan || candidate.syllableOnly && (!soundHint || candidate.meaning.isEmpty()))
            header = "待输入\n";
        else if (candidate.kind == CandidateEngine.Kind.TUJIA) header = "汉义\n" + candidate.meaning + "\n\n待输入\n";
        else header = categoryName(candidate) + "\n";
        SpannableStringBuilder detail = new SpannableStringBuilder(header)
                .append(HanUnitStyle.forKeyboard(candidate.text, candidate.joinedHanRanges));
        for (HanChoice.Action action : alternatives) {
            String suffix = CandidateEngine.suffixAfterChoice(draft, action.consumed);
            detail.append("\n\n输入 ").append(draft, 0, action.consumed).append("，保留 ").append(suffix);
            String meanings = action.fullMeanings();
            if (!meanings.isEmpty()) detail.append("\n汉义\n").append(meanings);
        }
        if (soundHint && (mode == HAN || mode == HAND && handAsHan)) {
            for (CandidateEngine.Candidate spelling : engine.nextSyllables(cachedSoundContext,
                    CandidateEngine.Mode.PINYIN, orthography)) {
                if (candidate.lastSyllable.equals(spelling.lastSyllable)) {
                    detail.append("\n\n读音\n").append(spelling.text);
                    break;
                }
            }
        }
        ReadingPage reading = new ReadingPage(detail, contentWidth, contentHeight);
        readingPage = reading;
        readingPanel.addView(reading, new LinearLayout.LayoutParams(-1, contentHeight));
        if (!alternatives.isEmpty()) {
            LinearLayout boundaries = row();
            HanChoice.Action alternate = alternatives.get(alternateIndex);
            String name = "保留 " + CandidateEngine.suffixAfterChoice(draft, alternate.consumed);
            key(boundaries, name, name, 2, false, () -> choose(candidate, alternate));
            if (alternatives.size() > 1) key(boundaries, "其他分词", "其他分词", 1, false,
                    () -> openReading(candidate, (alternateIndex + 1) % alternatives.size()));
            readingPanel.addView(boundaries, new LinearLayout.LayoutParams(-1, controlsHeight));
        }
        LinearLayout controls = row();
        key(controls, "返回", "返回候选", 1, false, this::closeReading);
        TextView previous = key(controls, "上一页", "上一页释义", 1.2f, false, () -> reading.turn(-1));
        TextView next = key(controls, "下一页", "下一页释义", 1.2f, false, () -> reading.turn(1));
        key(controls, "输入", "输入这个候选", 1, true, () -> choose(candidate));
        readingPanel.addView(controls, new LinearLayout.LayoutParams(-1, controlsHeight));
        reading.updateControls = () -> {
            int total = reading.pages.size();
            heading.setText("候选全文　" + (reading.index + 1) + " / " + total);
            heading.setContentDescription("候选全文 第" + (reading.index + 1) + "页 共" + total + "页");
            previous.setEnabled(reading.index > 0);
            previous.setAlpha(previous.isEnabled() ? 1f : .45f);
            next.setEnabled(reading.index + 1 < total);
            next.setAlpha(next.isEnabled() ? 1f : .45f);
        };
        reading.turn(0);
        keyboardFrame.removeView(surface);
        keyboardFrame.addView(readingPanel, new FrameLayout.LayoutParams(-1, panelHeight));
    }

    private void closeReading() {
        if (readingPanel == null) return;
        keyboardFrame.removeView(readingPanel);
        readingPanel = null;
        readingPage = null;
        inspectedCandidate = null;
        keyboardFrame.addView(surface, new FrameLayout.LayoutParams(-1, panelHeight));
    }

    /** The keyboard owns a fixed-size page. TextView lays out and draws its ordinary text. */
    private final class ReadingPage extends TextView {
        final ArrayList<CharSequence> pages = new ArrayList<>();
        int index;
        Runnable updateControls;

        ReadingPage(CharSequence words, int width, int height) {
            super(TujiaIme.this);
            setPadding(dp(12), dp(4), dp(12), dp(4));
            setGravity(Gravity.START | Gravity.TOP);
            setIncludeFontPadding(false);
            setTextColor(INK);
            setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
            setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE);
            int usableWidth = Math.max(1, width - getPaddingLeft() - getPaddingRight());
            int usableHeight = Math.max(1, height - getPaddingTop() - getPaddingBottom());
            float preferredSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP,
                    compact ? 17 : 19, getResources().getDisplayMetrics());
            setTextSize(TypedValue.COMPLEX_UNIT_PX, Math.min(preferredSize, usableHeight * .75f));
            StaticLayout.Builder builder = StaticLayout.Builder.obtain(words, 0, words.length(), getPaint(), usableWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setBreakStrategy(getBreakStrategy())
                    .setHyphenationFrequency(getHyphenationFrequency());
            if (android.os.Build.VERSION.SDK_INT >= 28) {
                setFallbackLineSpacing(true);
                builder.setUseLineSpacingFromFallbacks(true);
            }
            StaticLayout layout = builder.build();
            int line = 0;
            while (line < layout.getLineCount()) {
                int firstLine = line;
                int top = layout.getLineTop(line++);
                while (line < layout.getLineCount() && layout.getLineBottom(line) - top <= usableHeight) {
                    line++;
                }
                int first = layout.getLineStart(firstLine);
                int last = layout.getLineEnd(line - 1);
                // A terminal line break already marked the end of this page.
                if (last > first && words.charAt(last - 1) == '\n') last--;
                pages.add(words.subSequence(first, last));
            }
        }

        void turn(int offset) {
            index = Math.max(0, Math.min(index + offset, pages.size() - 1));
            setText(pages.get(index));
            updateControls.run();
        }
    }

    private void choose(CandidateEngine.Candidate c) {
        HanChoice group = hanChoices.get(c);
        choose(c, group == null ? null : group.primary);
    }

    private void choose(CandidateEngine.Candidate c, HanChoice.Action action) {
        if (!inputViewActive || direct || mode == VOICE || !candidates.contains(c)) return;
        closeMore();
        closeReading();
        if (mode == HAND) {
            chooseHand(c);
            return;
        }
        boolean prediction = wordHints.contains(c) || syllableHints.contains(c);
        int consumed = action != null ? action.consumed : prediction ? draft.length() : c.consumed;
        if (consumed > draft.length() || consumed < 0 || consumed == 0 && !draft.isEmpty()) return;
        CandidateEngine.Candidate selected = action == null ? c : action.candidate;
        CrashReports.event(getCurrentInputEditorInfo(), "选词", selected.text);
        if (!commit(selected.text, true, selected.joinedHanRanges)) return;
        if (action == null) remember(recentWords, c);
        else remember(recentWords, selected, action.ids(), action.lastSyllable());
        draft = CandidateEngine.suffixAfterChoice(draft, consumed);
        updateCandidates();
    }

    private void commitRaw() {
        if (!draft.isEmpty() && commit(draft, false)) {
            recentWords = previousSyllable = sentenceEnd(draft.charAt(draft.length() - 1)) ? "<s>" : "";
            draft = "";
            updateCandidates();
        }
    }

    private void space() {
        if (inIpaMode()) { typeIpa(" "); return; }
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty() && engine == null && !lexiconFailed) return;
        if (!draft.isEmpty() && !candidates.isEmpty()) choose(candidates.get(0));
        else if (!draft.isEmpty()) commitRaw();
        else commit(" ", false);
    }

    private void enter() {
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty()) { space(); return; }
        String history = recentWords;
        recentWords = previousSyllable = "";
        updateCandidates();
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (info != null && info.actionLabel != null && info.actionId != 0 && ic != null &&
                (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            ic.performEditorAction(info.actionId);
        } else if (!sendDefaultEditorAction(true)) {
            // WebView may deliver the newline's cursor update after the next key.
            // Register it like any other edit so that callback preserves the new draft.
            boolean multiline = info != null && (info.inputType & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_TEXT
                    && (info.inputType & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0;
            if (!multiline || !commit("\n", false)) sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
            if (inIpaMode() && multiline) {
                showIpaCandidates();
            } else if (!direct && mode != VOICE && multiline) {
                recentWords = history;
                beginSentence();
                updateCandidates();
            }
        }
    }

    private String enterLabel() {
        EditorInfo info = getCurrentInputEditorInfo();
        if (info == null || (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0) return "换行";
        if (info.actionLabel != null && info.actionId != 0) return info.actionLabel.toString();
        switch (info.imeOptions & EditorInfo.IME_MASK_ACTION) {
            case EditorInfo.IME_ACTION_SEND: return "发送";
            case EditorInfo.IME_ACTION_SEARCH: return "搜索";
            case EditorInfo.IME_ACTION_DONE: return "完成";
            case EditorInfo.IME_ACTION_GO: return "前往";
            case EditorInfo.IME_ACTION_NEXT: return "下一项";
            case EditorInfo.IME_ACTION_PREVIOUS: return "上一项";
            default: return "换行";
        }
    }

    private boolean commit(String text, boolean wordSpacing) {
        return commit(text, wordSpacing, null);
    }

    private boolean commit(String text, boolean wordSpacing, int[] joinedRanges) {
        String original = text;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null || text.isEmpty()) return false;
        if (wordSpacing) {
            CharSequence before = ic.getTextBeforeCursor(1, 0);
            CharSequence after = ic.getTextAfterCursor(1, 0);
            char left = before == null || before.length() == 0 ? 0 : before.charAt(before.length() - 1);
            char right = after == null || after.length() == 0 ? 0 : after.charAt(0);
            text = CandidateEngine.spaceWord(text, left, right);
        }
        int offset = text.startsWith(original) ? 0 : 1;
        CharSequence output = HanUnitStyle.forEditor(text, joinedRanges, offset);
        if (!ic.commitText(output, 1)) return false;
        CrashReports.event(getCurrentInputEditorInfo(), "上屏", text);
        if (selStart >= 0 && selEnd >= 0) expect(Math.min(selStart, selEnd) + output.length());
        return true;
    }

    private void expect(int pos) {
        selStart = selEnd = pos;
        expectedSelections.add(pos);
        if (expectedSelections.size() > 32) expectedSelections.removeFirst();
    }

    private void backspace() {
        CrashReports.event(getCurrentInputEditorInfo(), "退格", "");
        if (mode == HAND && !direct) {
            if (pad != null && pad.hasInk()) { pad.undoStroke(); return; }
            discardHandWork();
        }
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty()) {
            draft = draft.substring(0, draft.offsetByCodePoints(draft.length(), -1));
            updateCandidates();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        recentWords = previousSyllable = "";
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            if (ic.commitText("", 1)) { expect(Math.min(selStart, selEnd)); readSentenceBoundary(); updateCandidates(); }
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(8, 0);
        if (before == null) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return; }
        if (before.length() == 0) { readSentenceBoundary(); updateCandidates(); return; }
        int len = Character.charCount(Character.codePointBefore(before, before.length()));
        if (before.charAt(before.length() - 1) == '˼') {
            String prefix = before.toString();
            int start = prefix.lastIndexOf('˻');
            if (start >= 0) {
                String inner = prefix.substring(start + 1, prefix.length() - 1);
                if (inner.codePointCount(0, inner.length()) == 2 && inner.codePoints().allMatch(Character::isIdeographic))
                    len = prefix.length() - start;
            }
        }
        if (ic.deleteSurroundingText(len, 0) && selStart >= 0) {
            expect(Math.max(0, selStart - len));
            readSentenceBoundary();
            updateCandidates();
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent ev) {
        if (ipaAlternatives != null && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
            closeIpaAlternatives();
            return true;
        }
        if (handDetail != null && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
            closeHandDetail();
            return true;
        }
        if (morePanel != null) {
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE) { closeMore(); return true; }
            if (ev.getUnicodeChar() > 0 || code == KeyEvent.KEYCODE_DEL) closeMore();
        }
        if (readingPanel == null && selectingTone &&
                (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
            updateCandidates();
            return true;
        }
        if (readingPanel != null) {
            switch (code) {
                case KeyEvent.KEYCODE_ESCAPE:
                case KeyEvent.KEYCODE_BACK:
                    closeReading();
                    return true;
                case KeyEvent.KEYCODE_PAGE_UP:
                case KeyEvent.KEYCODE_DPAD_LEFT:
                    readingPage.turn(-1);
                    return true;
                case KeyEvent.KEYCODE_SPACE:
                case KeyEvent.KEYCODE_PAGE_DOWN:
                case KeyEvent.KEYCODE_DPAD_RIGHT:
                    readingPage.turn(1);
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                    choose(inspectedCandidate);
                    return true;
                default:
                    if (code == KeyEvent.KEYCODE_DEL || ev.getUnicodeChar() > 0) return true;
                    return super.onKeyDown(code, ev);
            }
        }
        if (isInputViewShown() && inIpaMode() &&
                (ev.hasNoModifiers() || ev.hasModifiers(KeyEvent.META_SHIFT_ON))) {
            if (code == KeyEvent.KEYCODE_DEL) { backspace(); return true; }
            int c = ev.getUnicodeChar();
            if (c > 0 && Character.isValidCodePoint(c) && !Character.isISOControl(c)) {
                typeIpa(new String(Character.toChars(c)));
                return true;
            }
        }
        if (!isInputViewShown() || !ev.hasNoModifiers() || direct || mode == VOICE)
            return super.onKeyDown(code, ev);
        if (code == KeyEvent.KEYCODE_DEL) { backspace(); return true; }
        if (code == KeyEvent.KEYCODE_SPACE) { space(); return true; }
        if (code == KeyEvent.KEYCODE_ENTER && !draft.isEmpty()) { enter(); return true; }
        int c = ev.getUnicodeChar();
        if (c > 0 && c < 128 && Character.isLetter((char)c) && mode != HAND) {
            typeChar((char)c);
            return true;
        }
        return super.onKeyDown(code, ev);
    }

    private void pickIme() {
        InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    private LinearLayout row() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        return layout;
    }

    private LinearLayout column() {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        return layout;
    }

    private TextView label(CharSequence text, int sp, int color) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        v.setGravity(Gravity.CENTER);
        v.setIncludeFontPadding(false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        return v;
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private Drawable marker(Drawable base, int color, int width, int height, int gravity) {
        LayerDrawable layers = new LayerDrawable(new Drawable[]{base, shape(color, 1)});
        layers.setLayerSize(1, width < 0 ? -1 : dp(width), dp(height));
        layers.setLayerGravity(1, gravity);
        return layers;
    }

    private Drawable edge(int fill, int line, int gravity) {
        return marker(shape(fill, 0), line, -1, 1, gravity | Gravity.FILL_HORIZONTAL);
    }

    private ColorStateList pressColors(int resting) {
        return new ColorStateList(new int[][]{{android.R.attr.state_pressed}, {android.R.attr.state_focused}, {}},
                new int[]{WHITE, INK, resting});
    }

    private Drawable pressable(Drawable rest, int radius) {
        StateListDrawable states = new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_pressed}, shape(PRESSED, radius));
        states.addState(new int[]{android.R.attr.state_focused}, shape(FOCUS, radius));
        states.addState(new int[]{}, rest);
        return states;
    }

    private TextView key(LinearLayout parent, String title, String desc, float weight,
                         boolean selected, Runnable action) {
        KeyLook look = parent == toolbar ? KeyLook.TAB : selected ? KeyLook.PRIMARY
                : parent == choices || parent == glyphRow ? KeyLook.PLAIN : KeyLook.UTILITY;
        return key(parent, title, desc, weight, look, selected, action);
    }

    private TextView key(LinearLayout parent, String title, String desc, float weight,
                         KeyLook look, boolean selected, Runnable action) {
        int size = look == KeyLook.LETTER ? LETTER_SP : title.length() == 1 ? 19 : 15;
        if (look == KeyLook.TAB) size = TAB_SP;
        int ink = look == KeyLook.PRIMARY ? WHITE : look == KeyLook.TAB && selected ? RED : INK;
        TextView v = label(title, look == KeyLook.LETTER ? letterSp() : compact ? size - 1 : size, ink);
        v.setTypeface(look == KeyLook.LETTER || look == KeyLook.TAB && !selected
                ? Typeface.DEFAULT : MEDIUM);
        Drawable resting;
        if (look == KeyLook.TAB && selected) {
            resting = marker(shape(Color.TRANSPARENT, 0), RED, 27, 3, Gravity.CENTER_HORIZONTAL | Gravity.BOTTOM);
        } else {
            int fill = look == KeyLook.PRIMARY ? RED : look == KeyLook.UTILITY ? UTILITY
                    : look == KeyLook.SPACE ? PAPER : Color.TRANSPARENT;
            resting = shape(fill, 9);
        }
        Drawable background = pressable(resting, 9);
        if (look == KeyLook.UTILITY || look == KeyLook.SPACE || look == KeyLook.PRIMARY) {
            background = new InsetDrawable(background, dp(2), dp(4), dp(2), dp(4));
        }
        v.setBackground(background);
        v.setPadding(0, 0, 0, 0);
        v.setTextColor(pressColors(ink));
        if (look == KeyLook.LETTER || look == KeyLook.UTILITY || look == KeyLook.PRIMARY || look == KeyLook.SPACE) {
            // Keep the user-selected SP height in small touch targets, including a wide W.
            v.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> {
                if (r > l) fitTextWidth(v, v.getText(), r - l - dp(2), .72f);
            });
        }
        v.setContentDescription(desc);
        v.setSelected(selected);
        v.setFocusable(true);
        KeyboardTap.bind(v, action);
        parent.addView(v, weighted(weight));
        return v;
    }

    private LinearLayout.LayoutParams weighted(float weight) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, -1, weight);
        p.setMargins(dp(1), dp(1), dp(1), dp(1));
        return p;
    }

    private LinearLayout.LayoutParams lineWithMinimum(int minimumDp, int fontSp) {
        return new LinearLayout.LayoutParams(-1, Math.max(dp(minimumDp), textHeight(fontSp) + dp(6)));
    }
    private int letterSp() { return compact ? LETTER_SP - 2 : LETTER_SP; }
    private int glossSp() { return compact ? GLOSS_SP - 1 : GLOSS_SP; }
    private int formSp() { return compact ? FORM_SP - 2 : FORM_SP; }
    private int textHeight(int sizeSp) { return (int)Math.ceil(sp(sizeSp) * 1.28f); }

    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
