package biz.tujia.ime.e2e;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;

import java.io.File;
import java.lang.reflect.Method;
import java.util.List;

/** Exercises the installed keyboard's actual Android private storage, on a fresh test installation. */
public final class PrivateStorageTest extends Instrumentation {
    private Boolean expectedRecording;

    @Override public void onStart() {
        Bundle result = new Bundle();
        int status = Activity.RESULT_CANCELED;
        try {
            Context app = getTargetContext();
            Class<?> settings = app.getClassLoader().loadClass("biz.tujia.ime.KeyboardSettings");
            boolean actual = (boolean) method(settings, "crashRecording", Context.class).invoke(null, app);
            boolean personal = (boolean) method(settings, "personalLearning", Context.class).invoke(null, app);
            if (!personal) throw new AssertionError("Existing and fresh installations default to personal learning");
            if (expectedRecording != null) {
                if (actual != expectedRecording)
                    throw new AssertionError("Upgraded keyboard did not honor the stored recording preference");
            } else {
                if (new File(app.getFilesDir(), "last-crash.txt").exists() ||
                        !app.getSharedPreferences("user_dictionary", 0).getAll().isEmpty())
                    throw new AssertionError("Use a fresh test installation");
                if (!actual) throw new AssertionError("Fresh diagnostic operation recording must be enabled");
                checkDiagnostics(app);
                checkDictionary(app);
            }
            result.putString("outcome", "PASS");
            status = Activity.RESULT_OK;
        } catch (Throwable failure) {
            result.putString("outcome", "FAIL");
            result.putString("failure", Log.getStackTraceString(failure));
        } finally {
            finish(status, result);
        }
    }

    @Override public void onCreate(Bundle arguments) {
        super.onCreate(arguments);
        if (arguments != null && arguments.containsKey("expectedRecording")) {
            expectedRecording = Boolean.valueOf(arguments.getString("expectedRecording"));
        }
        start();
    }

    // The fixture is built separately. Load the methods from the installed production APK.
    private static Method method(Class<?> owner, String name, Class<?>... arguments) throws Exception {
        Method m = owner.getDeclaredMethod(name, arguments);
        m.setAccessible(true);
        return m;
    }

    private static void checkDiagnostics(Context app) throws Exception {
        Class<?> diagnostics = app.getClassLoader().loadClass("biz.tujia.ime.CrashReports");
        Method event = method(diagnostics, "event", EditorInfo.class, String.class, String.class);
        Method save = method(diagnostics, "save", Context.class, Thread.class, Throwable.class);
        Method read = method(diagnostics, "read", Context.class);
        Method recording = method(diagnostics, "setRecording", boolean.class);
        // Instrumentation uses its own application lifecycle. Enable the operation trail explicitly.
        recording.invoke(null, true);
        EditorInfo editor = new EditorInfo();
        editor.inputType = InputType.TYPE_CLASS_TEXT;
        event.invoke(null, editor, "synthetic", "ordinary-example");
        int[] excluded = {InputType.TYPE_CLASS_NUMBER, InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI};
        for (int type : excluded) {
            editor.inputType = type;
            event.invoke(null, editor, "filtered", "private-example");
        }
        editor.inputType = InputType.TYPE_CLASS_TEXT;
        editor.imeOptions = EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING;
        event.invoke(null, editor, "filtered", "private-example");
        Throwable fixture = new IllegalStateException("private-exception-message");
        save.invoke(null, app, Thread.currentThread(), fixture);
        String report = (String) read.invoke(null, app);
        if (!report.contains("ordinary-example") || report.contains("private-") ||
                !report.contains(IllegalStateException.class.getName()))
            throw new AssertionError("Unexpected diagnostic filtering in the persisted report");
        recording.invoke(null, false);
        editor.imeOptions = 0;
        event.invoke(null, editor, "disabled", "private-example");
        save.invoke(null, app, Thread.currentThread(), fixture);
        report = (String) read.invoke(null, app);
        if (report.contains("ordinary-example") || report.contains("private-"))
            throw new AssertionError("Disabling diagnostics must clear and suppress the operation trail");
        method(diagnostics, "clear", Context.class).invoke(null, app);
    }

    private void checkDictionary(Context app) throws Exception {
        Class<?> dictionary = app.getClassLoader().loadClass("biz.tujia.ime.UserDictionary");
        Class<?> form = app.getClassLoader().loadClass("biz.tujia.ime.UserDictionary$Form");
        java.lang.reflect.Constructor<?> create = dictionary.getDeclaredConstructor(Context.class);
        create.setAccessible(true);
        Method register = method(dictionary, "register", form, String.class);
        Method complete = method(dictionary, "completions", form, String.class);
        Object first = create.newInstance(app);
        Object second = create.newInstance(app);
        for (Object output : form.getEnumConstants()) {
            register.invoke(first, output, "qvqrestore");
            waitForIdleSync();
            if (!((List<?>) complete.invoke(second, output, "qvq")).isEmpty())
                throw new AssertionError("A single use unlocked a private completion");
            // The second instance must observe the first use before it can count the second.
            register.invoke(second, output, "qvqrestore");
            waitForIdleSync();
            for (Object reader : new Object[]{first, second, create.newInstance(app)}) {
                if (!((List<?>) complete.invoke(reader, output, "qvq")).contains("qvqrestore"))
                    throw new AssertionError("A live or reopened instance lost the completion for " + output);
            }
        }
        android.content.SharedPreferences stored = app.getSharedPreferences("user_dictionary", 0);
        android.content.SharedPreferences.Editor edit = stored.edit();
        for (String key : stored.getAll().keySet()) edit.remove(key);
        edit.apply();
        waitForIdleSync();
        for (Object reader : new Object[]{first, second, create.newInstance(app)}) {
            for (Object output : form.getEnumConstants()) {
                if (!((List<?>) complete.invoke(reader, output, "qvq")).isEmpty())
                    throw new AssertionError("A cleared word remained in memory for " + output);
            }
        }
    }
}
