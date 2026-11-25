package biz.tujia.ime;

import android.content.Context;
import android.content.SharedPreferences;

final class KeyboardSettings {
    private static final String FILE = "keyboard";
    private static final String TONE_MARKS = "spelling_tone_marks";
    private static final String IPA_DIALECTS = "ipa_completion_dialects";

    private static final String TRIAL_RECORDING = "trial_recording";

    static boolean trialRecording(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getBoolean(TRIAL_RECORDING, true);
    }

    static void setTrialRecording(Context context, boolean enabled) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(TRIAL_RECORDING, enabled).apply();
    }

    private KeyboardSettings() { }

    static CandidateEngine.Orthography spelling(Context context) {
        boolean marked = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getBoolean(TONE_MARKS, true);
        return marked ? CandidateEngine.Orthography.DIACRITIC : CandidateEngine.Orthography.YE;
    }

    static int typingMode(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int saved = prefs.getInt("typing_mode", prefs.getInt("mode", TujiaIme.SPELL));
        return saved == TujiaIme.HAN ? TujiaIme.HAN : TujiaIme.SPELL;
    }

    static OutputForm typingOutput(Context context) {
        return OutputForm.current(typingMode(context) == TujiaIme.HAN, spelling(context));
    }

    static void setTypingOutput(Context context, OutputForm form) {
        int typing = form == OutputForm.HAN ? TujiaIme.HAN : TujiaIme.SPELL;
        SharedPreferences.Editor edit = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt("typing_mode", typing);
        if (form.spelling != null) edit.putBoolean(TONE_MARKS, form == OutputForm.MARKED);
        edit.apply();
    }

    static int ipaDialects(Context context) {
        return context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
                .getInt(IPA_DIALECTS, IpaKeyboard.NORTH) & IpaKeyboard.ALL_DIALECTS;
    }

    static void setIpaDialects(Context context, int dialects) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putInt(IPA_DIALECTS, dialects).apply();
    }
}
