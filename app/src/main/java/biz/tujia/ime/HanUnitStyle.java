package biz.tujia.ime;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.LineBreakConfigSpan;
import android.text.style.ReplacementSpan;
import android.text.style.ScaleXSpan;
import android.text.style.UnderlineSpan;

/** Styles the word engine's explicit one-syllable Han ranges without changing their text. */
final class HanUnitStyle {
    private static final float WIDTH = .84f;

    static CharSequence forKeyboard(String text, int[] ranges) {
        return style(text, ranges, 0, false);
    }

    static CharSequence forEditor(String text, int[] ranges, int offset) {
        return style(text, ranges, offset, true);
    }

    private static CharSequence style(String text, int[] ranges, int offset, boolean editor) {
        if (ranges == null || ranges.length == 0) return text;
        SpannableString styled = new SpannableString(text);
        for (int i = 0; i + 1 < ranges.length; i += 2) {
            int first = ranges[i] + offset;
            int last = ranges[i + 1] + offset;
            if (first < 0 || last <= first || last > text.length()) continue;
            if (editor) {
                // Only platform spans can be marshalled to an editor in another process.
                styled.setSpan(new ScaleXSpan(WIDTH), first, last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                styled.setSpan(new UnderlineSpan(), first, last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (Build.VERSION.SDK_INT >= 35) {
                    styled.setSpan(LineBreakConfigSpan.createNoBreakSpan(), first, last,
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
            } else {
                styled.setSpan(new UnitSpan(), first, last, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
        }
        return new SpannedString(styled);
    }

    /** Keeps the pair together across layout, ellipsis and pagination on Android 6 and up. */
    private static final class UnitSpan extends ReplacementSpan {
        @Override public int getSize(Paint paint, CharSequence text, int start, int end,
                                     Paint.FontMetricsInt metrics) {
            if (metrics != null) paint.getFontMetricsInt(metrics);
            return (int)Math.ceil(paint.measureText(text, start, end) * WIDTH);
        }

        @Override public void draw(Canvas canvas, CharSequence text, int start, int end,
                                   float x, int top, int baseline, int bottom, Paint paint) {
            Paint compact = new Paint(paint);
            compact.setTextScaleX(paint.getTextScaleX() * WIDTH);
            compact.setUnderlineText(false);
            canvas.drawText(text, start, end, x, baseline, compact);
            float lineTop = baseline + Math.max(1f, paint.getTextSize() * .07f);
            float lineHeight = Math.max(1f, paint.getTextSize() * .055f);
            float width = compact.measureText(text, start, end);
            canvas.drawRect(x, lineTop, x + width, lineTop + lineHeight, compact);
        }
    }

    private HanUnitStyle() { }
}
