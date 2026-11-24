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
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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
    private int panelHeight;
    private LinearLayout readingPanel;
    private ReadingPage readingPage;
    private CandidateEngine.Candidate inspectedCandidate;
    private TextView preview, readingHint, prevChoices, nextChoices, toneCancel;
    private boolean selectingTone;
    private HandwritingPad pad;

    @Override public void onCreate() {
        super.onCreate();
        LexiconStore.load(this, this);
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
        if (pad != null) pad.clear();
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
        panelHeight = toolbarHeight + compositionHeight + choicesHeight + bodyHeight
                + surface.getPaddingTop() + surface.getPaddingBottom();
        if (surface.getParent() == keyboardFrame) {
            surface.setLayoutParams(new FrameLayout.LayoutParams(-1, panelHeight));
        }
        buildToolbar();
        buildComposition();
        buildBody();
        updateCandidates();
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
        if (next != mode) {
            boolean sameAlphabet = mode <= HAN && next <= HAN;
            if (!sameAlphabet) resetDraft();
            mode = next;
            if (mode != VOICE) {
                getSharedPreferences("keyboard", MODE_PRIVATE).edit().putInt("mode", mode).apply();
            }
            symbols = false;
            shifted = false;
        }
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
            toneCancel = textControl("取消", "取消选声调", this::updateCandidates);
            toneCancel.setVisibility(View.GONE);
            readingHint = textControl("全文", "长按候选看全文", () -> {
                int first = COLUMNS * page;
                if (first < candidates.size()) openReading(candidates.get(first));
            });
            if (mode == HAND) {
                textControl(handAsHan ? "记音 ▾" : "拼写 ▾", "切换手写输出", () -> {
                    handAsHan = !handAsHan;
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
        v.setOnClickListener(b -> action.run());
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
        v.setOnClickListener(b -> {
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

    private void buildHandwriting() {
        handPanelActive = true;
        glyphRow = row();
        body.addView(glyphRow, full(compact ? 29 : 39));
        pad = new HandwritingPad(this, found -> {
            if (!handPanelActive) return;
            glyphs = found;
            glyphPage = 0;
            showGlyphs();
        });
        pad.setContentDescription("汉字手写区");
        body.addView(pad, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout controls = row();
        body.addView(controls, full(compact ? 29 : 42));
        key(controls, "撤笔", "撤销一笔", 1, false, () -> pad.undoStroke());
        key(controls, "清笔迹", "清除笔迹", 1, false, () -> pad.clear());
        key(controls, "原字", "输入手写原字", 1, false, this::commitRaw);
        key(controls, "退格", "退格", 1, false, this::backspace);
        key(controls, "换键盘", "切换输入法", 1.15f, false, this::pickIme);
        showGlyphs();
    }

    private void showGlyphs() {
        if (glyphRow == null) return;
        glyphRow.removeAllViews();
        if (glyphs.isEmpty()) {
            glyphRow.addView(label("在下方写一个字，再点选字形", compact ? 14 : 17, MUTED),
                    weighted(1));
            return;
        }
        for (int i = glyphPage * 5; i < Math.min((glyphPage + 1) * 5, glyphs.size()); i++) {
            String glyph = glyphs.get(i);
            key(glyphRow, glyph, "手写字形 " + glyph, 1, false, () -> {
                appendDraft(glyph);
                pad.clear();
            });
        }
        if (glyphs.size() > 5) {
            key(glyphRow, "换组", "更多手写字形", 1, false, () -> {
                glyphPage = (glyphPage + 1) % ((glyphs.size() + 4) / 5);
                showGlyphs();
            });
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
        key(r, enterLabel(), "回车", 1.3f, true, this::enter);
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
        selectingTone = true;
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
        if (direct) commit(String.valueOf(c), false);
        else if (Character.isLetter(c) || !draft.isEmpty()) appendDraft(String.valueOf(c));
        else commit(String.valueOf(c), false);
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
        CandidateEngine.Mode target = mode == SPELL ? CandidateEngine.Mode.PINYIN :
                mode == HAND && !handAsHan ? CandidateEngine.Mode.HANDWRITING : CandidateEngine.Mode.HAN;
        candidates = engine == null || direct || mode == VOICE || draft.isEmpty()
                ? Collections.emptyList() : engine.lookup(draft, target, orthography);
        page = 0;
        if (readingHint != null) readingHint.setVisibility(candidates.isEmpty() ? View.GONE : View.VISIBLE);
        preview.setText(draft.isEmpty() ? (direct ? "直接输入" : mode == VOICE ? "语音输入" :
                mode == HAND ? "待选记音字" : mode == SPELL ? "输入土家语拼写" : "输拼写，选汉字记音") : draft);
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
            else if (draft.isEmpty()) text = mode == HAND ? "长按候选看汉义" : "长按元音可选声调";
            else text = mode == HAND ? "未找到词，可修改或选原字" : "未找到词，可修改或选原拼写";
            TextView status = label(text, compact ? 15 : 18, MUTED);
            if (!direct && mode != VOICE && lexiconFailed) {
                status.setOnClickListener(v -> {
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
            boolean hasGloss = tujia && !c.syllableOnly && !c.shortMeaning.isEmpty();
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
            card.setOnClickListener(v -> choose(c));
            card.setHapticFeedbackEnabled(false);
            card.setOnLongClickListener(v -> {
                openReading(c);
                return true;
            });
            card.setContentDescription(c.syllableOnly ? c.text : (tujia ? c.meaning : categoryName(c)) + "，" + c.text);
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
        if (candidate.syllableOnly) header = "待输入\n";
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
            setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE);
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
        if (c.consumed <= 0 || c.consumed > draft.length()) return;
        if (!commit(c.text, true, c.joinedHanRanges)) return;
        draft = CandidateEngine.suffixAfterChoice(draft, c.consumed);
        updateCandidates();
    }

    private void commitRaw() {
        if (!draft.isEmpty() && commit(draft, false)) {
            draft = "";
            updateCandidates();
        }
    }

    private void space() {
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty() && engine == null && !lexiconFailed) return;
        if (!candidates.isEmpty()) choose(candidates.get(0));
        else if (!draft.isEmpty()) commitRaw();
        else commit(" ", false);
    }

    private void enter() {
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty()) { space(); return; }
        EditorInfo info = getCurrentInputEditorInfo();
        InputConnection ic = getCurrentInputConnection();
        if (info != null && info.actionLabel != null && info.actionId != 0 && ic != null &&
                (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) == 0) {
            ic.performEditorAction(info.actionId);
        } else if (!sendDefaultEditorAction(true)) {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
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
        if (selectingTone) { updateCandidates(); return; }
        if (!draft.isEmpty()) {
            draft = draft.substring(0, draft.offsetByCodePoints(draft.length(), -1));
            updateCandidates();
            return;
        }
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (selStart >= 0 && selEnd >= 0 && selStart != selEnd) {
            if (ic.commitText("", 1)) expect(Math.min(selStart, selEnd));
            return;
        }
        CharSequence before = ic.getTextBeforeCursor(2, 0);
        if (before == null) { sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL); return; }
        if (before.length() == 0) return;
        int len = Character.charCount(Character.codePointBefore(before, before.length()));
        if (ic.deleteSurroundingText(len, 0) && selStart >= 0) expect(Math.max(0, selStart - len));
    }

    @Override public boolean onKeyDown(int code, KeyEvent ev) {
        if (selectingTone && (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE)) {
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
        v.setOnClickListener(b -> action.run());
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
