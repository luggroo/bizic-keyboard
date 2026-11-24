package biz.tujia.ime;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import android.view.inputmethod.EditorInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Date;

public final class CrashReports extends Application {
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static boolean recording;

    static synchronized void recordTrial(boolean enabled) {
        events.clear();
        recording = enabled;
    }

    static synchronized void event(EditorInfo editor, String action, String value) {
        if (!recording || editor == null || !"biz.tujia.ime".equals(editor.packageName)
                || editor.fieldId != R.id.trial_editor) return;
        if (events.size() == 64) events.removeFirst();
        String bounded = value.length() > 120 ? value.substring(0, 120) + "…" : value;
        events.addLast(action + " " + bounded.replace("\n", "↵").replace("\r", "↵"));
    }

    private static synchronized String trace() {
        return android.text.TextUtils.join("\n", events);
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "last-crash.txt"));
    }

    static void clear(Context context) { file(context).delete(); }

    static String read(Context context) {
        try {
            return new String(file(context).readFully(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous == null) return;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(error);
            } finally {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private void save(Throwable error) {
        AtomicFile destination = file(this);
        FileOutputStream stream = null;
        try {
            StringBuilder report = new StringBuilder("土家语输入法崩溃报告\n");
            report.append(new Date()).append("\n版本 ")
                    .append(getPackageManager().getPackageInfo(getPackageName(), 0).versionName)
                    .append(" · Android ").append(Build.VERSION.RELEASE).append('\n');
            String recent = trace();
            if (!recent.isEmpty()) report.append("\n试打操作\n").append(recent).append('\n');
            for (int cause = 0; error != null && cause < 6; cause++, error = error.getCause()) {
                report.append('\n').append(error.getClass().getName()).append('\n');
                StackTraceElement[] frames = error.getStackTrace();
                for (int i = 0; i < Math.min(frames.length, 48); i++)
                    report.append("  ").append(frames[i]).append('\n');
            }
            stream = destination.startWrite();
            stream.write(report.toString().getBytes(StandardCharsets.UTF_8));
            destination.finishWrite(stream);
        } catch (Exception ignored) {
            if (stream != null) destination.failWrite(stream);
        }
    }
}
