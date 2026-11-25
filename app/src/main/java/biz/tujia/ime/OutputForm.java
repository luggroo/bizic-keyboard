package biz.tujia.ime;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Layout;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;

/** The three forms of dictionary entry xu213-1-3, available before the keyboard loads its dictionary. */
enum OutputForm {
    YE("叶氏拼音", "bifzixkar", CandidateEngine.Orthography.YE),
    MARKED("调号拼音", "bízīkǎ", CandidateEngine.Orthography.DIACRITIC),
    HAN("谐音字", "秘兹佧", null);

    final String name, example;
    final CandidateEngine.Orthography spelling;

    OutputForm(String name, String example, CandidateEngine.Orthography spelling) {
        this.name = name;
        this.example = example;
        this.spelling = spelling;
    }

    static OutputForm current(boolean han, CandidateEngine.Orthography spelling) {
        return han ? HAN : spelling == CandidateEngine.Orthography.DIACRITIC ? MARKED : YE;
    }

    void addButton(LinearLayout row, boolean selected, boolean compact, Runnable choose) {
        Context context = row.getContext();
        float density = context.getResources().getDisplayMetrics().density;
        String action = name;
        SpannableString caption = new SpannableString(example + "\n" + action);
        caption.setSpan(new ForegroundColorSpan(0xff625e5b), 0, example.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        caption.setSpan(new RelativeSizeSpan(.86f), 0, example.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        Button button = new Button(context);
        button.setAllCaps(false);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setPadding(0, 0, 0, Math.round(3 * density));
        button.setGravity(Gravity.CENTER);
        button.setIncludeFontPadding(false);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, compact ? 14 : 16);
        button.setTextColor(selected ? 0xffab3929 : 0xff392b27);
        button.setText(caption);
        button.setContentDescription(action + "，" + example);
        button.setSelected(selected);
        GradientDrawable line = new GradientDrawable();
        line.setColor(selected ? 0xffab3929 : Color.TRANSPARENT);
        line.setCornerRadius(2 * density);
        LayerDrawable background = new LayerDrawable(new Drawable[]{new ColorDrawable(Color.TRANSPARENT), line});
        background.setLayerGravity(1, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        background.setLayerSize(1, Math.round(30 * density), Math.round(3 * density));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x33bc5439), background, new ColorDrawable(Color.WHITE)));
        button.setStateListAnimator(null);
        button.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r <= l) return;
            TextPaint natural = new TextPaint(button.getPaint());
            natural.setTextScaleX(1f);
            float width = Layout.getDesiredWidth(caption, natural);
            float scale = Math.min(1f, Math.max(1f, r - l - 4 * density) / Math.max(1f, width));
            if (Math.abs(button.getTextScaleX() - scale) > .001f) button.setTextScaleX(scale);
        });
        KeyboardTap.bind(button, choose);
        row.addView(button, new LinearLayout.LayoutParams(0, -1, 1));
    }
}
