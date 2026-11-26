package biz.tujia.ime;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.widget.TextView;

final class ToolbarIcon extends Drawable {
    enum Mark { TYPE, WRITE, IPA, SETTINGS, HIDE, GLOBE, ERASER, BACKSPACE, NEXT, COMMA, CIRCLE_STOP, DOT, MORE }

    private final Mark mark;
    private final TextView button;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private int alpha = 255;

    ToolbarIcon(Mark mark, TextView button) {
        this.mark = mark;
        this.button = button;
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    }

    @Override public void draw(Canvas canvas) {
        canvas.save();
        canvas.translate(getBounds().left, getBounds().top);
        canvas.scale(getBounds().width() / 24f, getBounds().height() / 18f);
        int color = button.getCurrentTextColor();
        paint.setColor(color);
        paint.setAlpha(Color.alpha(color) * alpha / 255);
        paint.setStrokeWidth(1.55f);
        paint.setStyle(Paint.Style.STROKE);
        switch (mark) {
            case TYPE:
            case IPA:
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(15);
                canvas.drawText(mark == Mark.TYPE ? "Aa" : "ɤ³⁵", 12, 13.5f, paint);
                break;
            case WRITE:
                path.reset();
                path.moveTo(5.5f, 15);
                path.lineTo(6.6f, 10.7f);
                path.lineTo(14.7f, 2.6f);
                path.lineTo(18, 5.9f);
                path.lineTo(9.9f, 14);
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(13.1f, 4.3f, 16.4f, 7.6f, paint);
                break;
            case SETTINGS:
                canvas.drawCircle(12, 9, 5.2f, paint);
                canvas.drawCircle(12, 9, 2, paint);
                paint.setStrokeWidth(2.2f);
                for (int i = 0; i < 8; i++) {
                    canvas.drawLine(12, 2.2f, 12, 3.8f, paint);
                    canvas.rotate(45, 12, 9);
                }
                break;
            case HIDE:
                canvas.drawLine(5, 4, 19, 4, paint);
                path.reset();
                path.moveTo(7.5f, 9);
                path.lineTo(12, 13.5f);
                path.lineTo(16.5f, 9);
                canvas.drawPath(path, paint);
                break;
            case GLOBE:
                canvas.drawCircle(12, 9, 7.5f, paint);
                canvas.drawOval(8.5f, 1.5f, 15.5f, 16.5f, paint);
                canvas.drawLine(5.5f, 6, 18.5f, 6, paint);
                canvas.drawLine(5.5f, 12, 18.5f, 12, paint);
                break;
            case NEXT:
                canvas.drawLine(5, 9, 19, 9, paint);
                path.reset();
                path.moveTo(13, 3);
                path.lineTo(19, 9);
                path.lineTo(13, 15);
                canvas.drawPath(path, paint);
                break;
            case COMMA:
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12.5f, 7, 1.9f, paint);
                paint.setStyle(Paint.Style.STROKE);
                path.reset();
                path.moveTo(13.6f, 7);
                path.quadTo(14, 10.7f, 10.4f, 12.7f);
                canvas.drawPath(path, paint);
                break;
            case CIRCLE_STOP:
                canvas.drawCircle(12, 9, 2.6f, paint);
                break;
            case DOT:
            case MORE:
                paint.setStyle(Paint.Style.FILL);
                canvas.drawCircle(12, 9, 1.6f, paint);
                if (mark == Mark.MORE) {
                    canvas.drawCircle(6, 9, 1.6f, paint);
                    canvas.drawCircle(18, 9, 1.6f, paint);
                }
                break;
            case ERASER:
                path.reset();
                path.moveTo(3.5f, 10.5f);
                path.lineTo(13, 1);
                path.lineTo(20, 8);
                path.lineTo(12.5f, 15.5f);
                path.lineTo(8.5f, 15.5f);
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(7.4f, 6.6f, 14.4f, 13.6f, paint);
                canvas.drawLine(12.5f, 15.5f, 20.5f, 15.5f, paint);
                break;
            case BACKSPACE:
                path.reset();
                path.moveTo(1.5f, 9);
                path.lineTo(7.5f, 2.5f);
                path.lineTo(21.5f, 2.5f);
                path.lineTo(21.5f, 15.5f);
                path.lineTo(7.5f, 15.5f);
                path.close();
                canvas.drawPath(path, paint);
                canvas.drawLine(11, 6.5f, 16, 11.5f, paint);
                canvas.drawLine(16, 6.5f, 11, 11.5f, paint);
                break;
        }
        canvas.restore();
    }

    @Override public void setAlpha(int alpha) {
        this.alpha = alpha;
        invalidateSelf();
    }

    @Override public void setColorFilter(ColorFilter filter) {
        paint.setColorFilter(filter);
        invalidateSelf();
    }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
}
