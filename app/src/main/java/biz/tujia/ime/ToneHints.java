package biz.tujia.ime;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;

final class ToneHints extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float textSize;

    ToneHints(float textSize) {
        this.textSize = textSize;
        paint.setColor(0xff847b76);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        paint.setTextSize(Math.min(textSize, Math.min(bounds.width(), bounds.height()) * .22f));
        float baseline = -(paint.ascent() + paint.descent()) / 2;
        float x = bounds.exactCenterX(), y = bounds.exactCenterY() + baseline;
        float dx = bounds.width() * .36f, dy = bounds.height() * .34f;
        canvas.drawText("ˉ", x, y - dy, paint);
        canvas.drawText("ˊ", x + dx, y, paint);
        canvas.drawText("ˇ", x, y + dy, paint);
        canvas.drawText("ˋ", x - dx, y, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
