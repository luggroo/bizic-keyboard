package biz.tujia.ime;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Finger/stylus input; the caller displays character choices and chooses when to commit. */
public final class HandwritingPad extends View {
    public interface Listener {
        void onStrokeStarted(boolean firstStroke);
        void onCandidates(List<String> characters);
        void onSettled();
    }
    private static final long SETTLE_MS = 1050;
    private Runnable pendingAccept;

    private static final int NONE = -1;
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor();
    private static StrokeRecognizer recognizer; // Accessed only on WORKER.
    private final AssetManager assets;
    private final Listener listener;
    private final Paint ink = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint label = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayList<float[]> strokes = new ArrayList<>();
    private final ArrayList<Path> paths = new ArrayList<>();
    private float[] writing = new float[128];
    private int used, activePointer = NONE;
    private volatile int generation;
    private Path currentPath;
    private String message = "";

    public HandwritingPad(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        assets = context.getApplicationContext().getAssets();
        float density = getResources().getDisplayMetrics().density;
        ink.setColor(Color.rgb(63,43,36));
        ink.setStrokeWidth(3.6f*density);
        ink.setStyle(Paint.Style.STROKE);
        ink.setStrokeJoin(Paint.Join.ROUND);
        ink.setStrokeCap(Paint.Cap.ROUND);
        label.setColor(Color.rgb(112,84,65));
        label.setTextAlign(Paint.Align.CENTER);
        label.setTextSize(17*getResources().getDisplayMetrics().scaledDensity);
        guide.setColor(Color.rgb(211,180,160));
        guide.setStrokeWidth(Math.max(1f, density));
        setBackgroundColor(Color.rgb(255,249,241));
        setContentDescription("手写记音字，逐笔书写后选字");
    }

    public boolean hasInk() { return currentPath != null || !strokes.isEmpty(); }

    private void cancelAccept() {
        if (pendingAccept != null) main.removeCallbacks(pendingAccept);
        pendingAccept = null;
    }

    public void clear() {
        clearInk();
        listener.onCandidates(Collections.<String>emptyList());
    }

    public void clearInk() {
        cancelAccept();
        generation++;
        discardActive();
        strokes.clear();
        paths.clear();
        message = "";
        invalidate();
    }

    public void undoStroke() {
        cancelAccept();
        generation++;
        discardActive();
        if (!strokes.isEmpty()) {
            strokes.remove(strokes.size()-1);
            paths.remove(paths.size()-1);
        }
        listener.onCandidates(Collections.<String>emptyList());
        message = "";
        if (!strokes.isEmpty()) recognize();
        invalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float density = getResources().getDisplayMetrics().density;
        float margin = 10 * density;
        float mark = 7 * density;
        for (int corner = 0; corner < 4; corner++) {
            boolean right = (corner & 1) != 0;
            boolean bottom = (corner & 2) != 0;
            float x = right ? getWidth() - margin : margin;
            float y = bottom ? getHeight() - margin : margin;
            canvas.drawLine(x, y, x + (right ? -mark : mark), y, guide);
            canvas.drawLine(x, y, x, y + (bottom ? -mark : mark), guide);
        }
        for (int i = 0; i < paths.size(); i++) {
            canvas.drawPath(paths.get(i),ink);
            float[] points = strokes.get(i);
            if (points.length <= 2) canvas.drawPoint(points[0],points[1],ink);
        }
        if (currentPath != null) {
            canvas.drawPath(currentPath,ink);
            if (used == 2) canvas.drawPoint(writing[0],writing[1],ink);
        }
        if (!message.isEmpty()) canvas.drawText(message,getWidth()/2f,getHeight()-5,label);
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        switch(event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (strokes.size() >= 64) { message="请选字、撤笔或重写"; invalidate(); return true; }
                cancelAccept();
                generation++;
                listener.onStrokeStarted(strokes.isEmpty());
                getParent().requestDisallowInterceptTouchEvent(true);
                activePointer = event.getPointerId(0);
                used = 0;
                currentPath = new Path();
                message = "";
                addPoint(event.getX(),event.getY());
                listener.onCandidates(Collections.<String>emptyList());
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(activePointer);
                if (index < 0 || currentPath == null) return true;
                for (int h = 0; h < event.getHistorySize(); h++)
                    addPoint(event.getHistoricalX(index,h),event.getHistoricalY(index,h));
                addPoint(event.getX(index),event.getY(index));
                invalidate();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
            case MotionEvent.ACTION_UP:
                int end = event.getActionIndex();
                if (event.getPointerId(end) != activePointer || currentPath == null) return true;
                addPoint(event.getX(end),event.getY(end));
                strokes.add(Arrays.copyOf(writing,used));
                paths.add(currentPath);
                discardActive();
                getParent().requestDisallowInterceptTouchEvent(false);
                recognize();
                invalidate();
                performClick();
                return true;
            case MotionEvent.ACTION_CANCEL:
                discardActive();
                getParent().requestDisallowInterceptTouchEvent(false);
                if (!strokes.isEmpty()) recognize();
                invalidate();
                return true;
            default:
                return true;
        }
    }

    @Override public boolean performClick() { super.performClick(); return true; }

    private void addPoint(float x,float y) {
        if (used > 0 && (x == writing[used-2] && y == writing[used-1])) return;
        if (used == 4096) return;
        if (used == writing.length) writing = Arrays.copyOf(writing,used*2);
        if (used == 0) currentPath.moveTo(x,y);
        else currentPath.lineTo(x,y);
        writing[used++] = x;
        writing[used++] = y;
    }

    private void discardActive() {
        currentPath = null;
        activePointer = NONE;
        used = 0;
    }

    private void recognize() {
        final int request = ++generation;
        final long idleAt = SystemClock.uptimeMillis() + SETTLE_MS;
        cancelAccept();
        // Completed arrays are immutable; the next stroke writes to a separate buffer.
        final List<float[]> snapshot = new ArrayList<>(strokes);
        WORKER.execute(() -> {
            if (generation != request) return;
            try {
                if (recognizer == null)
                    recognizer = new StrokeRecognizer(assets.open("handwriting/medians.tsv"));
                if (generation != request) return;
                final List<String> results = recognizer.recognize(snapshot, 12);
                main.post(() -> {
                    if (generation != request) return;
                    message = results.isEmpty() ? "请继续写或重写" : "";
                    listener.onCandidates(results);
                    if (!results.isEmpty() && generation == request) {
                        pendingAccept = () -> {
                            pendingAccept = null;
                            if (generation == request && currentPath == null && isShown()) listener.onSettled();
                        };
                        main.postAtTime(pendingAccept, idleAt);
                    }
                    invalidate();
                });
            } catch (IOException | RuntimeException ex) {
                Log.e("TujiaHandwriting","Unable to load or match handwriting templates",ex);
                main.post(() -> {
                    if (generation != request) return;
                    message = "手写字形未能读取";
                    listener.onCandidates(Collections.<String>emptyList());
                    invalidate();
                });
            }
        });
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!strokes.isEmpty() && currentPath == null) recognize();
    }

    @Override protected void onDetachedFromWindow() {
        cancelAccept();
        generation++;
        discardActive();
        super.onDetachedFromWindow();
    }
}
