package biz.tujia.ime;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import java.util.function.IntSupplier;

final class ToneHints extends Drawable {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float textSize;
    private final IntSupplier edge;

    ToneHints(float textSize, IntSupplier edge) {
        this.textSize = textSize;
        this.edge = edge;
        paint.setColor(0xff78645d);
        paint.setTextAlign(Paint.Align.CENTER);
    }

    @Override public void draw(Canvas canvas) {
        Rect bounds = getBounds();
        int location = edge.getAsInt();
        paint.setTextSize(Math.min(textSize, Math.min(bounds.width(), bounds.height()) * .26f));
        float baseline = -(paint.ascent() + paint.descent()) / 2;
        float x = bounds.exactCenterX(), y = bounds.exactCenterY() + baseline;
        float dx = bounds.width() * .36f, dy = bounds.height() * .34f;
        canvas.drawText("ˉ", x, y - dy, paint);
        canvas.drawText("ˊ", location > 0 ? x - dx : x + dx, location > 0 ? y - dy : y, paint);
        canvas.drawText("ˇ", x, y + dy, paint);
        canvas.drawText("ˋ", location < 0 ? x + dx : x - dx, location < 0 ? y - dy : y, paint);
    }

    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
