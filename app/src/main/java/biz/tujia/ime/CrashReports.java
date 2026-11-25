package biz.tujia.ime;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.text.InputType;
import android.text.TextUtils;
import android.util.AtomicFile;
import android.view.inputmethod.EditorInfo;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Date;

public final class CrashReports extends Application {
    static final int EVENT_LIMIT = 64;
    private static final ArrayDeque<String> events = new ArrayDeque<>();
    private static boolean recording;

    static synchronized void setRecording(boolean enabled) {
        recording = enabled;
        if (!enabled) events.clear();
    }

    static synchronized void event(EditorInfo editor, String action, String value) {
        if (!recording || !canRecord(editor)) return;
        if (events.size() == EVENT_LIMIT) events.removeFirst();
        String bounded = value.length() > 120
                ? value.substring(0, Character.isHighSurrogate(value.charAt(119)) ? 119 : 120) + "…"
                : value;
        events.addLast(bounded.isEmpty() ? action
                : action + " · " + bounded.replace("\n", "↵").replace("\r", "↵"));
    }

    private static boolean canRecord(EditorInfo editor) {
        if (editor == null || (editor.imeOptions & EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING) != 0
                || (editor.inputType & InputType.TYPE_MASK_CLASS) != InputType.TYPE_CLASS_TEXT) return false;
        switch (editor.inputType & InputType.TYPE_MASK_VARIATION) {
            case InputType.TYPE_TEXT_VARIATION_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD:
            case InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS:
            case InputType.TYPE_TEXT_VARIATION_URI:
                return false;
            default:
                return true;
        }
    }

    private static AtomicFile file(Context context) {
        return new AtomicFile(new File(context.getFilesDir(), "last-crash.txt"));
    }

    static synchronized void clear(Context context) { file(context).delete(); }

    static synchronized String read(Context context) {
        try {
            return new String(file(context).readFully(), StandardCharsets.UTF_8);
        } catch (IOException ignored) {
            return "";
        }
    }

    @Override public void onCreate() {
        super.onCreate();
        setRecording(KeyboardSettings.crashRecording(this));
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        if (previous == null) return;
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            try {
                save(this, thread, error);
            } finally {
                previous.uncaughtException(thread, error);
            }
        });
    }

    private static synchronized void save(Context context, Thread thread, Throwable error) {
        AtomicFile destination = file(context);
        FileOutputStream stream = null;
        try {
            StringBuilder report = new StringBuilder("土家语输入法崩溃报告\n");
            report.append(new Date()).append("\n版本 ")
                    .append(context.getPackageManager().getPackageInfo(context.getPackageName(), 0).versionName)
                    .append(" · Android ").append(Build.VERSION.RELEASE)
                    .append("\n线程 ").append(thread.getName()).append('\n');
            if (!events.isEmpty()) report.append("\n最近操作（").append(events.size())
                    .append(" 次）\n").append(TextUtils.join("\n", events)).append('\n');
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
