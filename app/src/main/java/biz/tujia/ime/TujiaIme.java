package biz.tujia.ime;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.content.res.ColorStateList;
import android.inputmethodservice.InputMethodService;
import android.text.InputType;
import android.text.StaticLayout;
import android.text.SpannableStringBuilder;
import android.text.Layout;
import android.text.TextUtils;
import android.util.TypedValue;
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

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Locale;

/** Offline Tujia keyboard. Preedit belongs to the keyboard, committed text to the host. */
public final class TujiaIme extends InputMethodService implements LexiconStore.Listener {
    private static final int COLUMNS = 4;
    private static final int SPELL = 0, HAN = 1, HAND = 2, VOICE = 3;
    private static final int INK = 0xff392b27, MUTED = 0xff745950, RED = 0xffab3929;
    private static final int PAPER = 0xfffffaf5, BOARD = 0xfffae9df, HEADER = 0xfffff1e8;
    private static final int RULE = 0xffddc5b7, UTILITY = 0xffefd7c7, WHITE = Color.WHITE;
    private static final int PRESSED = 0xffb63d2a, FOCUS = 0xffedc1a8;
    private static final Typeface MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    private enum KeyLook { LETTER, TAB, UTILITY, SPACE, PRIMARY, PLAIN }
    private static final int[] MODES = {SPELL, HAN, HAND, VOICE};
    private static final String[] MODE_LABELS = {"土家语拼写", "汉字记音", "手写", "语音"};
    private static final String[] MODE_TABS = {"拼写", "记音", "手写", "语音"};

    private CandidateEngine engine;
    private boolean inputViewActive, lexiconFailed;
    private CandidateEngine.Orthography orthography = CandidateEngine.Orthography.YE;
    private int mode, page, glyphPage, selStart, selEnd;
    private boolean shifted, symbols, direct, numeric, handAsHan, compact, handPanelActive;
    private String draft = "";
    private List<CandidateEngine.Candidate> candidates = Collections.emptyList();
    private List<String> glyphs = Collections.emptyList();
    // Some editors merge selection notifications from successive commitText calls.
    private final ArrayDeque<Integer> expectedSelections = new ArrayDeque<>();
    private LinearLayout surface, toolbar, composition, choices, body, glyphRow;
    private FrameLayout keyboardFrame;
    private int panelHeight, baselinePanelHeight, baselineBodyHeight;
    private LinearLayout readingPanel;
    private ReadingPage readingPage;
    private CandidateEngine.Candidate inspectedCandidate;
    private TextView preview, readingHint, prevChoices, nextChoices, toneCancel;
    private boolean selectingTone;
    private HandwritingPad pad;
    private HandAnchor strokeAnchor;
    private InputConnection handEditor;
    private int handStart = -1;
    private boolean handLocked;
    private String handRaw = "", handOutput = "", handOwned = "", handPrefix = "", handPrefixOutput = "";
    private String handIds = "", handHistory = "";
    private String recentWords = "", previousSyllable = "", selectedSound = "", selectedSoundLabel = "";
    private List<CandidateEngine.Candidate> syllableHints = Collections.emptyList();
    private String cachedWordContext, cachedSoundContext;
    private CandidateEngine.Mode cachedHintMode;
    private CandidateEngine.Orthography cachedHintStyle;
    private List<CandidateEngine.Candidate> allWordHints, allSoundHints, markedWords, yeWords, markedSounds;
    private List<CandidateEngine.Candidate> handCompletes = Collections.emptyList(), wordHints = Collections.emptyList();

    @Override public void onCreate() {
        super.onCreate();
        LexiconStore.load(this, this);
        handAsHan = getSharedPreferences("keyboard", MODE_PRIVATE).getBoolean("hand_as_han", true);
        mode = getSharedPreferences("keyboard", MODE_PRIVATE).getInt("mode", HAN);
        if (mode < SPELL || mode > HAND) mode = HAN;
    }

    @Override public void onLexiconLoaded(CandidateEngine ready) {
        engine = ready;
        lexiconFailed = ready == null;
        if (inputViewActive && !selectingTone && readingPanel == null) updateCandidates();
    }

    @Override public boolean onEvaluateFullscreenMode() { return false; }

    @Override public void onDestroy() {
        inputViewActive = false;
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

    @Override public void onStartInput(EditorInfo editor, boolean restarting) {
        super.onStartInput(editor, restarting);
        inputViewActive = false;
        resetDraft();
        selStart = editor.initialSelStart;
        selEnd = editor.initialSelEnd;
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
        symbols = numeric;
        shifted = false;
    }

    @Override public void onStartInputView(EditorInfo editor, boolean restarting) {
        super.onStartInputView(editor, restarting);
        inputViewActive = true;
        orthography = KeyboardSettings.spelling(this);
        readSentenceBoundary();
        layoutKeyboard();
    }

    @Override public void onFinishInputView(boolean finishingInput) {
        inputViewActive = false;
        resetDraft();
        super.onFinishInputView(finishingInput);
    }

    @Override public void onFinishInput() {
        inputViewActive = false;
        resetDraft();
        super.onFinishInput();
    }

    private void resetDraft() {
        closeReading();
        selectingTone = false;
        draft = "";
        page = 0;
        candidates = Collections.emptyList();
        glyphs = Collections.emptyList();
        glyphPage = 0;
        expectedSelections.clear();
        recentWords = previousSyllable = selectedSound = "";
        syllableHints = Collections.emptyList();
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
    }

    private void layoutKeyboard() {
        if (surface == null) return;
        closeReading();
        handPanelActive = false;
        pad = null;
        glyphRow = null;
        surface.removeAllViews();
        int screen = Math.round(getResources().getDisplayMetrics().heightPixels /
                getResources().getDisplayMetrics().density);
        int height = Math.min(338, Math.round(screen * (screen < 500 ? .56f : .47f)));
        compact = height < 247;
        toolbar = row(); composition = row(); choices = row(); body = column();
        toolbar.setBackground(edge(HEADER, RULE, Gravity.TOP));
        composition.setBackgroundColor(HEADER);
        choices.setBackground(edge(PAPER, RULE, Gravity.BOTTOM));
        body.setBackgroundColor(BOARD);
        int toolbarHeight = dp(compact ? 30 : 40);
        int compositionHeight = dp(compact ? 27 : 34);
        int choicesHeight = Math.max(dp(compact ? 45 : 64),
                Math.round(sp(compact ? 14 + 18 : 16 + 22) * 1.28f) + dp(4));
        int bodyHeight = Math.max(dp(64), dp(height) - toolbarHeight - compositionHeight - choicesHeight);
        surface.addView(toolbar, new LinearLayout.LayoutParams(-1, toolbarHeight));
        surface.addView(composition, new LinearLayout.LayoutParams(-1, compositionHeight));
        surface.addView(choices, new LinearLayout.LayoutParams(-1, choicesHeight));
        surface.addView(body, new LinearLayout.LayoutParams(-1, bodyHeight));
        baselineBodyHeight = bodyHeight;
        baselinePanelHeight = toolbarHeight + compositionHeight + choicesHeight + bodyHeight
                + surface.getPaddingTop() + surface.getPaddingBottom();
        panelHeight = baselinePanelHeight;
        if (surface.getParent() == keyboardFrame) {
            surface.setLayoutParams(new FrameLayout.LayoutParams(-1, panelHeight));
        }
        buildToolbar();
        buildComposition();
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
        int fittedBody = Math.max(dp(64), baselineBodyHeight - bottom);
        int fittedPanel = baselinePanelHeight - baselineBodyHeight + fittedBody;
        if (frame.getPaddingLeft() != left || frame.getPaddingRight() != right || frame.getPaddingBottom() != bottom)
            frame.setPadding(left, 0, right, bottom);
        // Keep the same total keyboard height whenever there is room for the keys.
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
            toolbar.addView(label(numeric ? "数字" : "英文直输", 17, MUTED),
                    weighted(1));
            key(toolbar, "换键盘", "切换输入法", 1, false, this::pickIme);
        } else {
            for (int index : MODES) {
                key(toolbar, MODE_TABS[index], MODE_LABELS[index], 1, mode == index,
                        () -> switchMode(index));
            }
        }
        key(toolbar, "收起", "隐藏键盘", .95f, false, () -> requestHideSelf(0));
    }

    private void switchMode(int next) {
        if (next == HAND && mode == HAND && handPanelActive) return;
        if (next != mode) {
            boolean sameAlphabet = mode <= HAN && next <= HAN;
            if (!sameAlphabet) {
                String words = recentWords, syllable = previousSyllable;
                resetDraft();
                recentWords = words;
                previousSyllable = syllable;
            }
            selectedSound = "";
            mode = next;
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
        readingHint = null;
        toneCancel = null;
        boolean choosing = !direct && mode != VOICE;
        if (choosing) prevChoices = pageControl("‹", "上一页候选", -1);
        preview = label("", compact ? 15 : 18, INK);
        preview.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        preview.setPadding(dp(4), 0, dp(2), 0);
        preview.setEllipsize(TextUtils.TruncateAt.START);
        composition.addView(preview, weighted(1));
        if (choosing) {
            toneCancel = textControl("取消", "取消选声调", () -> {
                selectedSound = "";
                updateCandidates();
            });
            toneCancel.setVisibility(View.GONE);
            readingHint = textControl("全文", "长按候选看全文", () -> {
                int first = COLUMNS * page;
                if (first < candidates.size()) openReading(candidates.get(first));
            });
            if (mode == HAND) {
                textControl(handAsHan ? "记音 ▾" : "拼写 ▾", "切换手写输出", () -> {
                    handAsHan = !handAsHan;
                    getSharedPreferences("keyboard", MODE_PRIVATE).edit().putBoolean("hand_as_han", handAsHan).apply();
                    composition.removeAllViews();
                    buildComposition();
                    updateCandidates();
                });
            } else {
                textControl("原拼写", "确定拼写", this::commitRaw);
            }
            nextChoices = pageControl("›", "下一页候选", 1);
        }
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

    private TextView pageControl(String title, String description, int direction) {
        TextView v = label(title, compact ? 22 : 25, RED);
        v.setTextColor(pressColors(RED));
        v.setBackground(pressable(shape(Color.TRANSPARENT, 0), 6));
        v.setContentDescription(description);
        KeyboardTap.bind(v, () -> {
            page += direction;
            showCandidates();
        });
        v.setFocusable(true);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(dp(compact ? 32 : 38), -1);
        composition.addView(v, params);
        return v;
    }

    private void buildBody() {
        if (!direct && mode == VOICE) {
            TextView hint = label("语音模型尚未上线", compact ? 18 : 23, INK);
            body.addView(hint, new LinearLayout.LayoutParams(-1, 0, 1));
            LinearLayout controls = row();
            key(controls, "用拼写输入", "切换到土家语拼写", 1, true, () -> switchMode(SPELL));
            key(controls, "用手写输入", "切换到手写", 1, false, () -> switchMode(HAND));
            body.addView(controls, full(compact ? 30 : 44));
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
        CharSequence left = ic.getTextBeforeCursor(12, 0);
        CharSequence right = ic.getTextAfterCursor(12, 0);
        return new HandAnchor(ic, start, end, left == null ? "" : left.toString(), right == null ? "" : right.toString());
    }

    private void clearHandSession() {
        handRaw = handOwned = handPrefix = handPrefixOutput = handOutput = handIds = handHistory = "";
        handStart = -1;
        handLocked = false;
        handEditor = null;
        strokeAnchor = null;
        handCompletes = wordHints = Collections.emptyList();
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

    private static String historyWith(String past, String next) {
        if (next.isEmpty()) return "";
        String joined = past.isEmpty() ? next : past + " " + next;
        int last = joined.lastIndexOf(' ');
        if (last < 0) return joined;
        int prior = joined.lastIndexOf(' ', last - 1);
        return joined.substring(prior + 1);
    }

    private static boolean sentenceEnd(char c) {
        return "。！？.!?\n\r".indexOf(c) >= 0;
    }

    private void readSentenceBoundary() {
        if (!inputViewActive || direct || mode == VOICE) return;
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
        } else if (selStart != selEnd || !sentenceEnd(left.charAt(left.length() - 1))) {
            return;
        }
        recentWords = "";
        previousSyllable = "<s>";
    }

    private void remember(String history, CandidateEngine.Candidate c, String confirmedSound) {
        recentWords = c == null ? "" : historyWith(history, c.lexemeIds);
        previousSyllable = c == null ? "" : c.lastSyllable;
        if (previousSyllable.isEmpty()) previousSyllable = confirmedSound;
        if (c != null && !c.text.isEmpty() && sentenceEnd(c.text.charAt(c.text.length() - 1))) {
            recentWords = "";
            previousSyllable = "<s>";
        }
    }

    private static String lexicalKey(CandidateEngine.Candidate c) {
        return c.text + "\u0000" + (c.syllableOnly ? "" : c.meaning);
    }

    private void loadHints(String words, String sound, CandidateEngine.Mode output) {
        if (words.equals(cachedWordContext) && sound.equals(cachedSoundContext)
                && cachedHintMode == output && cachedHintStyle == orthography) return;
        cachedWordContext = words;
        cachedSoundContext = sound;
        cachedHintMode = output;
        cachedHintStyle = orthography;
        allWordHints = words.isEmpty() ? Collections.emptyList() : engine.nextWords(words, output, orthography);
        allSoundHints = sound.isEmpty() ? Collections.emptyList() : engine.nextSyllables(sound, orthography);
        markedWords = yeWords = markedSounds = null;
    }

    private static String folded(String source) {
        return Normalizer.normalize(source.toLowerCase(Locale.ROOT).replace(" ", ""), Normalizer.Form.NFC);
    }

    private static boolean startsWithSpelling(String typed, String ye, String marked) {
        if (folded(ye).startsWith(typed) || folded(marked).startsWith(typed)) return true;
        // Genuinely unmarked input can match a marked vowel; an explicit tone stays significant.
        String decomposed = Normalizer.normalize(folded(marked), Normalizer.Form.NFD);
        StringBuilder bare = new StringBuilder(decomposed.length());
        for (int i = 0; i < decomposed.length(); i++) {
            char c = decomposed.charAt(i);
            if (Character.getType(c) != Character.NON_SPACING_MARK) bare.append(c);
        }
        return bare.toString().startsWith(typed);
    }

    /** Equal visible senses stay in their highest-priority source; homonyms retain separate choices. */
    private List<CandidateEngine.Candidate> addSuggestions(List<CandidateEngine.Candidate> exact,
            List<CandidateEngine.Candidate> completions, String wordContext, String soundContext,
            CandidateEngine.Mode output, String typed) {
        ArrayList<CandidateEngine.Candidate> items = new ArrayList<>();
        Set<String> seen = new HashSet<>(), visible = new HashSet<>();
        for (CandidateEngine.Candidate c : exact) {
            if (completions.isEmpty() || !c.syllableOnly) {
                items.add(c); seen.add(lexicalKey(c)); visible.add(c.text);
            }
        }
        for (CandidateEngine.Candidate c : completions) {
            if (seen.add(lexicalKey(c))) { items.add(c); visible.add(c.text); }
        }
        if (!completions.isEmpty()) for (CandidateEngine.Candidate c : exact) {
            if (c.syllableOnly && visible.add(c.text)) items.add(c);
        }
        if (engine == null) {
            wordHints = syllableHints = Collections.emptyList();
            return items;
        }
        loadHints(wordContext, soundContext, output);
        String prefix = folded(typed);
        if (!prefix.isEmpty() && markedWords == null) {
            markedWords = allWordHints.isEmpty() ? Collections.emptyList()
                    : engine.nextWords(wordContext, CandidateEngine.Mode.PINYIN, CandidateEngine.Orthography.DIACRITIC);
            yeWords = allWordHints.isEmpty() ? Collections.emptyList()
                    : engine.nextWords(wordContext, CandidateEngine.Mode.PINYIN, CandidateEngine.Orthography.YE);
            markedSounds = orthography == CandidateEngine.Orthography.DIACRITIC || allSoundHints.isEmpty()
                    ? allSoundHints : engine.nextSyllables(soundContext, CandidateEngine.Orthography.DIACRITIC);
        }
        wordHints = allWordHints;
        for (int i = 0; i < wordHints.size(); i++) {
            CandidateEngine.Candidate c = wordHints.get(i);
            // Ranking by attested counts is identical across output styles.
            if (!prefix.isEmpty() && !startsWithSpelling(prefix, yeWords.get(i).text, markedWords.get(i).text)) continue;
            if (seen.add(lexicalKey(c))) { items.add(c); visible.add(c.text); }
        }
        syllableHints = allSoundHints;
        for (int i = 0; i < syllableHints.size(); i++) {
            CandidateEngine.Candidate c = syllableHints.get(i);
            if (!prefix.isEmpty() && !startsWithSpelling(prefix, c.lastSyllable, markedSounds.get(i).text)) continue;
            if (visible.add(c.text)) items.add(c);
        }
        return items;
    }

    private boolean extendsHandWord(String glyph) {
        return !handLocked && !handRaw.isEmpty() && engine != null && engine.scoreHandwritingPrefix(handRaw + glyph) > 0;
    }

    private void refreshHandCandidates() {
        String pending = strokeAnchor != null && !glyphs.isEmpty() ? glyphs.get(0) : "";
        boolean combined = !pending.isEmpty() && extendsHandWord(pending);
        String raw = pending.isEmpty() ? handRaw : combined ? handRaw + pending : pending;
        boolean closed = pending.isEmpty() && handLocked;
        List<CandidateEngine.Candidate> exact = closed ? Collections.emptyList() : handExact(raw);
        handCompletes = engine == null || raw.isEmpty() || closed ? Collections.emptyList()
                : engine.handwritingSuggestions(raw, handMode(), orthography);
        String wordContext = recentWords, soundContext = previousSyllable;
        if (!pending.isEmpty()) {
            CandidateEngine.Candidate best = exact.isEmpty() ? null : exact.get(0);
            String prior = combined ? handHistory : recentWords;
            wordContext = best == null ? "" : historyWith(prior, best.lexemeIds);
            soundContext = best == null ? "" : best.lastSyllable;
        }
        candidates = addSuggestions(exact, handCompletes, wordContext, soundContext, handMode(), "");
        page = 0;
        if (readingHint != null) readingHint.setVisibility(candidates.isEmpty() ? View.GONE : View.VISIBLE);
        String display = raw.isEmpty() ? "手写" : raw;
        if (!handCompletes.isEmpty()) {
            List<CandidateEngine.Candidate> written = handAsHan ? handCompletes :
                    engine.handwritingSuggestions(raw, CandidateEngine.Mode.HAN, orthography);
            for (CandidateEngine.Candidate word : written) {
                if (word.text.startsWith(raw) && word.text.length() > raw.length()) {
                    int end = word.text.offsetByCodePoints(raw.length(), 1);
                    display = raw + "  →  " + word.text.substring(raw.length(), end);
                    break;
                }
            }
        }
        preview.setText(display);
        preview.setTextColor(raw.isEmpty() ? MUTED : RED);
        preview.setContentDescription("待选拼写 " + raw);
        showCandidates();
    }

    private void buildHandwriting() {
        handPanelActive = true;
        glyphRow = row();
        body.addView(glyphRow, full(compact ? 29 : 39));
        pad = new HandwritingPad(this, new HandwritingPad.Listener() {
            @Override public void onStrokeStarted(boolean first) {
                if (first) {
                    selectedSound = "";
                    if (toneCancel != null) toneCancel.setVisibility(View.GONE);
                    HandAnchor current = handAnchor();
                    if (!handRaw.isEmpty() && (current == null || !ownsHandOutput(current))) {
                        clearHandSession();
                        recentWords = previousSyllable = "";
                        if (current != null && current.start == current.end && (current.start == 0 ||
                                !current.before.isEmpty() && sentenceEnd(current.before.charAt(current.before.length() - 1)))) {
                            previousSyllable = "<s>";
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
                refreshHandCandidates();
            }
            @Override public void onSettled() {
                if (handPanelActive && !glyphs.isEmpty() && strokeAnchor != null) acceptGlyph(glyphs.get(0), false);
            }
        });
        pad.setContentDescription("汉字手写区");
        body.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = row();
        body.addView(controls, full(compact ? 29 : 42));
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
            glyphRow.addView(label("停笔后自动输入，可连续写", compact ? 14 : 17, MUTED), weighted(1));
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
        selectedSound = "";
        if (toneCancel != null) toneCancel.setVisibility(View.GONE);
        HandAnchor cursor = handAnchor();
        if (cursor == null || (!correction && (strokeAnchor == null || !strokeAnchor.matches(cursor)))) {
            discardHandWork();
            return;
        }
        boolean combined = !correction && extendsHandWord(glyph);
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
        handRaw = raw;
        handLocked = false;
        handPrefix = prefix;
        handPrefixOutput = previousOutput;
        handIds = best == null ? "" : best.lexemeIds;
        handHistory = history;
        remember(history, best, "");
        strokeAnchor = null;
        pad.clearInk();
        showGlyphs();
        refreshHandCandidates();
    }

    private void discardHandWork() {
        recentWords = previousSyllable = selectedSound = "";
        clearHandSession();
        glyphs = Collections.emptyList();
        glyphPage = 0;
        showGlyphs();
        refreshHandCandidates();
    }

    private void chooseHand(CandidateEngine.Candidate c) {
        boolean predicting = wordHints.contains(c);
        boolean completing = handCompletes.contains(c);
        if (!candidates.contains(c)) return;
        if (strokeAnchor != null && !glyphs.isEmpty()) acceptGlyph(glyphs.get(0), false);
        if (handRaw.isEmpty()) return;
        HandAnchor cursor = handAnchor();
        if (cursor == null) { discardHandWork(); return; }
        if (predicting && !ownsHandOutput(cursor)) { discardHandWork(); return; }
        String history = predicting ? historyWith(handHistory, handIds) : handHistory;
        if (!writeHand(cursor, c.text, c.joinedHanRanges, !predicting)) { discardHandWork(); return; }
        // An explicit completion chooses a whole word, not another character to correct.
        if (predicting || completing) {
            handRaw = c.text;
            handLocked = true;
            handPrefix = handPrefixOutput = "";
            glyphs = Collections.emptyList();
        }
        handHistory = history;
        handIds = c.lexemeIds;
        remember(history, c, "");
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
        InputConnection ic = cursor.editor;
        int start = replace ? handStart : Math.min(cursor.start, cursor.end);
        int oldLength = replace ? handOwned.length() : 0;
        CharSequence before = ic.getTextBeforeCursor(oldLength + 1, 0);
        CharSequence after = ic.getTextAfterCursor(1, 0);
        char left = before == null || before.length() <= oldLength ? 0 : before.charAt(before.length() - oldLength - 1);
        char right = after == null || after.length() == 0 ? 0 : after.charAt(0);
        String written = CandidateEngine.spaceWord(out, left, right);
        CharSequence styled = HanUnitStyle.forEditor(written, ranges, written.startsWith(out) ? 0 : 1);
        ic.beginBatchEdit();
        try {
            boolean selected = false;
            if (replace && !ic.setComposingRegion(start, start + oldLength)) {
                if (!ic.setSelection(start, start + oldLength)) return false;
                selected = true;
            }
            if (!ic.commitText(styled, 1)) {
                if (selected) ic.setSelection(cursor.end, cursor.end);
                else if (replace) ic.finishComposingText();
                return false;
            }
            if (start >= 0) expect(start + written.length());
            handStart = start;
            handOwned = written;
            handOutput = out;
            handEditor = ic;
            return true;
        } finally {
            ic.endBatchEdit();
        }
    }

    private void buildKeys() {
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
        key(r, "换", "切换输入法", .9f, false, this::pickIme);
        key(r, direct ? "," : "，", "逗号", .8f, KeyLook.PLAIN, false, () -> typeChar(direct ? ',' : '，'));
        key(r, "空格", "空格", 2.35f, KeyLook.SPACE, false, this::space);
        key(r, direct ? "." : "。", "句号", .8f, KeyLook.PLAIN, false, () -> typeChar(direct ? '.' : '。'));
        String action = enterLabel();
        key(r, action, action, 1.3f, true, this::enter);
        fitNarrowKeycaps();
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
        selectedSound = "";
        selectingTone = true;
        toneCancel.setText("取消");
        toneCancel.setContentDescription("取消选声调");
        choices.removeAllViews();
        preview.setText(draft.isEmpty() ? "选择声调" : draft);
        readingHint.setVisibility(View.GONE);
        toneCancel.setVisibility(View.VISIBLE);
        prevChoices.setVisibility(View.GONE);
        nextChoices.setVisibility(View.GONE);
        for (char toned : tones.toCharArray()) {
            String title = String.valueOf(toned);
            key(choices, title, title, 1, false, () -> typeChar(toned));
        }
    }

    private void fitNarrowKeycaps() {
        int width = getResources().getDisplayMetrics().widthPixels;
        if (width > dp(360) || getResources().getConfiguration().fontScale < 1.2f) return;
        width -= surface.getPaddingLeft() + surface.getPaddingRight();
        for (int i = 0; i < body.getChildCount(); i++) {
            LinearLayout keys = (LinearLayout) body.getChildAt(i);
            int available = width;
            float weights = 0;
            for (int j = 0; j < keys.getChildCount(); j++) {
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) keys.getChildAt(j).getLayoutParams();
                weights += p.weight;
                available -= p.leftMargin + p.rightMargin;
            }
            for (int j = 0; j < keys.getChildCount(); j++) {
                TextView key = (TextView) keys.getChildAt(j);
                LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) key.getLayoutParams();
                float keyWidth = (int)(available * p.weight / weights) - dp(2);
                float textWidth = key.getPaint().measureText(key.getText().toString());
                if (textWidth > keyWidth) {
                    key.setTextSize(TypedValue.COMPLEX_UNIT_PX,
                            Math.max(dp(12), key.getTextSize() * keyWidth / textWidth));
                }
            }
        }
    }

    private void typeChar(char c) {
        selectedSound = "";
        if (direct) commit(String.valueOf(c), false);
        else if (Character.isLetter(c) || !draft.isEmpty()) appendDraft(String.valueOf(c));
        else if (commit(String.valueOf(c), false)) {
            recentWords = "";
            previousSyllable = sentenceEnd(c) ? "<s>" : "";
            updateCandidates();
        }
        if (shifted && !symbols) { shifted = false; buildKeys(); }
    }

    private void appendDraft(String text) {
        draft += text;
        updateCandidates();
    }

    private void updateCandidates() {
        if (surface == null || preview == null || choices == null) return;
        closeReading();
        selectingTone = false;
        if (toneCancel != null) toneCancel.setVisibility(View.GONE);
        if (direct || mode == VOICE || !inputViewActive) {
            candidates = wordHints = syllableHints = Collections.emptyList();
        } else if (!selectedSound.isEmpty() && engine != null) {
            showSoundForms();
            return;
        } else if (mode == HAND) {
            refreshHandCandidates();
            return;
        } else {
            CandidateEngine.Mode output = mode == SPELL ? CandidateEngine.Mode.PINYIN : CandidateEngine.Mode.HAN;
            handCompletes = Collections.emptyList();
            List<CandidateEngine.Candidate> dictionary = engine == null || draft.isEmpty()
                    ? Collections.emptyList() : engine.lookup(draft, output, orthography);
            candidates = addSuggestions(dictionary, Collections.emptyList(), recentWords, previousSyllable, output, draft);
        }
        page = 0;
        if (readingHint != null) readingHint.setVisibility(candidates.isEmpty() ? View.GONE : View.VISIBLE);
        preview.setText(draft.isEmpty() ? (direct ? "直接输入" : mode == VOICE ? "语音输入" :
                mode == HAND ? "待选记音字" : mode == SPELL ? "输入土家语拼写" : "输拼写，选汉字记音") : draft);
        preview.setTextColor(draft.isEmpty() ? MUTED : RED);
        preview.setTypeface(draft.isEmpty() ? Typeface.DEFAULT : MEDIUM);
        preview.setContentDescription("待选拼写 " + draft);
        showCandidates();
    }

    private void showSoundForms() {
        CandidateEngine.Mode target = mode == HAN || mode == HAND && handAsHan
                ? CandidateEngine.Mode.HAN : CandidateEngine.Mode.PINYIN;
        ArrayList<CandidateEngine.Candidate> forms = new ArrayList<>();
        for (CandidateEngine.Candidate c : engine.lookup(selectedSound, target, orthography))
            if (c.kind == CandidateEngine.Kind.TUJIA && c.consumed == selectedSound.length() &&
                    (c.lastSyllable.isEmpty() || selectedSound.equals(c.lastSyllable))) forms.add(c);
        candidates = forms;
        handCompletes = wordHints = syllableHints = Collections.emptyList();
        page = 0;
        preview.setText(selectedSoundLabel + "  ·  选择写法");
        preview.setContentDescription("待选读音 " + selectedSound);
        toneCancel.setText("返回");
        toneCancel.setContentDescription("返回联想");
        toneCancel.setVisibility(View.VISIBLE);
        readingHint.setVisibility(forms.isEmpty() ? View.GONE : View.VISIBLE);
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
            else if (!selectedSound.isEmpty()) text = "这个读音暂无可选写法";
            else if (engine == null) text = lexiconFailed ? "词库未载入，点此重试" : "正在加载词库，可先输入拼写";
            else if (draft.isEmpty()) text = mode == HAND ? (handRaw.isEmpty() ? "在下方写一个字" : "可以继续写") : "长按元音可选声调";
            else text = mode == HAND ? "可以继续写" : "未找到词，可修改或选原拼写";
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
            boolean hasGloss = tujia && !c.syllableOnly && !c.plainHanLoan && !c.shortMeaning.isEmpty();
            boolean nextSound = syllableHints.contains(c);
            String hint = nextSound ? "下一音节 " : handCompletes.contains(c) ? "续写 " :
                    wordHints.contains(c) ? "联想 " : "";
            int glossSize = compact ? 14 : 16;
            TextView meaning = cardLine(hasGloss ? c.shortMeaning : "", glossSize, 13, MUTED, false);
            meaning.setDuplicateParentStateEnabled(true);
            meaning.setTextColor(pressColors(MUTED));
            if (!hasGloss) {
                meaning.setVisibility(View.INVISIBLE);
                meaning.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            }
            card.addView(meaning, new LinearLayout.LayoutParams(-1, Math.round(sp(glossSize) * 1.28f)));
            TextView text = cardLine(HanUnitStyle.forKeyboard(c.text, c.joinedHanRanges), compact ? 18 : 22, 16, tujia && i == 0 ? RED : INK, true);
            text.setDuplicateParentStateEnabled(true);
            text.setTextColor(pressColors(tujia && i == 0 ? RED : INK));
            card.addView(text, new LinearLayout.LayoutParams(-1, Math.round(sp(compact ? 18 : 22) * 1.28f)));
            KeyboardTap.bind(card, () -> choose(c));
            card.setHapticFeedbackEnabled(false);
            card.setOnLongClickListener(v -> {
                openReading(c);
                return true;
            });
            card.setContentDescription(hint + (c.syllableOnly || c.plainHanLoan ? c.text : (tujia ? c.meaning : categoryName(c)) + "，" + c.text));
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
        return v;
    }

    private static String categoryName(CandidateEngine.Candidate c) {
        switch (c.kind) {
            case CHINESE: return "汉语";
            case PUNCTUATION: return "标点";
            default: return "原文";
        }
    }

    private void openReading(CandidateEngine.Candidate candidate) {
        closeReading();
        inspectedCandidate = candidate;
        readingPanel = column();
        readingPanel.setBackgroundColor(PAPER);
        readingPanel.setPadding(dp(4), dp(2), dp(4), dp(2));
        TextView heading = label("候选全文", compact ? 16 : 19, RED);
        readingPanel.addView(heading, full(compact ? 29 : 38));
        int controlsHeight = dp(compact ? 36 : 48);
        int contentHeight = panelHeight - readingPanel.getPaddingTop() - readingPanel.getPaddingBottom()
                - heading.getLayoutParams().height - controlsHeight;
        int contentWidth = keyboardFrame.getWidth() - readingPanel.getPaddingLeft() - readingPanel.getPaddingRight();
        String header;
        boolean soundHint = syllableHints.contains(candidate);
        if (soundHint) header = "可接的读音\n";
        else if (candidate.syllableOnly || candidate.plainHanLoan) header = "待输入\n";
        else if (candidate.kind == CandidateEngine.Kind.TUJIA) header = "汉义\n" + candidate.meaning + "\n\n待输入\n";
        else header = categoryName(candidate) + "\n";
        CharSequence detail = new SpannableStringBuilder(header)
                .append(HanUnitStyle.forKeyboard(candidate.text, candidate.joinedHanRanges));
        ReadingPage reading = new ReadingPage(detail, contentWidth, contentHeight);
        readingPage = reading;
        readingPanel.addView(reading, new LinearLayout.LayoutParams(-1, contentHeight));
        LinearLayout controls = row();
        key(controls, "返回", "返回候选", 1, false, this::closeReading);
        TextView previous = key(controls, "上一页", "上一页释义", 1.2f, false, () -> reading.turn(-1));
        TextView next = key(controls, "下一页", "下一页释义", 1.2f, false, () -> reading.turn(1));
        key(controls, soundHint ? "看写法" : "输入", soundHint ? "查看这个读音的写法" : "输入这个候选",
                1, true, () -> choose(candidate));
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
                    compact ? 19 : 22, getResources().getDisplayMetrics());
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
        if (direct || mode == VOICE || !candidates.contains(c)) return;
        closeReading();
        if (syllableHints.contains(c)) {
            if (mode == HAND && strokeAnchor != null && !glyphs.isEmpty()) {
                acceptGlyph(glyphs.get(0), false);
                if (handRaw.isEmpty()) return;
            }
            selectedSound = c.lastSyllable;
            selectedSoundLabel = c.text;
            updateCandidates();
            return;
        }
        boolean soundForm = !selectedSound.isEmpty();
        boolean wordHint = wordHints.contains(c);
        if (mode == HAND && !soundForm && (!wordHint || !handRaw.isEmpty() || strokeAnchor != null)) {
            chooseHand(c);
            return;
        }
        if (!soundForm && !wordHint && (c.consumed <= 0 || c.consumed > draft.length())) return;
        if (!commit(c.text, true, c.joinedHanRanges)) return;
        remember(recentWords, c, soundForm ? selectedSound : "");
        if (mode == HAND) {
            clearHandSession();
            glyphs = Collections.emptyList();
            showGlyphs();
        }
        // Every suggested spelling was matched against the entire current prefix.
        draft = soundForm || wordHint ? "" : CandidateEngine.suffixAfterChoice(draft, c.consumed);
        selectedSound = "";
        updateCandidates();
    }

    private void commitRaw() {
        if (!draft.isEmpty() && commit(draft, false)) {
            recentWords = "";
            previousSyllable = sentenceEnd(draft.charAt(draft.length() - 1)) ? "<s>" : "";
            draft = "";
            updateCandidates();
        }
    }

    private void space() {
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty() && engine == null && !lexiconFailed) return;
        if ((!draft.isEmpty() || !selectedSound.isEmpty()) && !candidates.isEmpty()) choose(candidates.get(0));
        else if (!draft.isEmpty()) commitRaw();
        else if (selectedSound.isEmpty()) commit(" ", false);
    }

    private void enter() {
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty() || !selectedSound.isEmpty()) { space(); return; }
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
            if (!direct && mode != VOICE) { previousSyllable = "<s>"; updateCandidates(); }
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
        if (selStart >= 0 && selEnd >= 0) expect(Math.min(selStart, selEnd) + text.length());
        return true;
    }

    private void expect(int pos) {
        selStart = selEnd = pos;
        expectedSelections.add(pos);
        if (expectedSelections.size() > 32) expectedSelections.removeFirst();
    }

    private void backspace() {
        if (!selectedSound.isEmpty()) { selectedSound = ""; updateCandidates(); return; }
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
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        if (before == null) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return; }
        if (before.length() == 0) return;
        int len = Character.charCount(Character.codePointBefore(before, before.length()));
        if (ic.deleteSurroundingText(len, 0) && selStart >= 0) {
            expect(Math.max(0, selStart - len));
            readSentenceBoundary();
            updateCandidates();
        }
    }

    @Override public boolean onKeyDown(int code, KeyEvent ev) {
        if (readingPanel == null && (selectingTone || !selectedSound.isEmpty()) &&
                (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
            selectedSound = "";
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
        int size = title.length() == 1 ? 22 : 16;
        if (look == KeyLook.TAB) size = 17;
        int ink = look == KeyLook.PRIMARY ? WHITE : look == KeyLook.TAB && selected ? RED : INK;
        TextView v = label(title, compact ? size - 2 : size, ink);
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

    private LinearLayout.LayoutParams full(int height) { return new LinearLayout.LayoutParams(-1, dp(height)); }
    private float sp(int value) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, value, getResources().getDisplayMetrics());
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
