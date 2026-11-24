package biz.tujia.ime;

import android.content.Context;
import android.content.SharedPreferences;

final class KeyboardSettings {
    private static final String FILE = "keyboard";
    private static final String TONE_MARKS = "spelling_tone_marks";

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
                .getBoolean(TONE_MARKS, false);
        return marked ? CandidateEngine.Orthography.DIACRITIC : CandidateEngine.Orthography.YE;
    }

    static void setSpelling(Context context, CandidateEngine.Orthography spelling) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
                .putBoolean(TONE_MARKS, spelling == CandidateEngine.Orthography.DIACRITIC).apply();
    }

    static OutputForm typingOutput(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int mode = prefs.getInt("typing_mode", prefs.getInt("mode", TujiaIme.HAN));
        return OutputForm.current(mode != TujiaIme.SPELL, spelling(context));
    }

    static void setDefaultOutput(Context context, OutputForm form) {
        SharedPreferences prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
        int typing = form == OutputForm.HAN ? TujiaIme.HAN : TujiaIme.SPELL;
        SharedPreferences.Editor edit = prefs.edit().putInt("typing_mode", typing)
                .putBoolean("hand_as_han", form == OutputForm.HAN);
        if (prefs.getInt("mode", TujiaIme.HAN) != TujiaIme.HAND) edit.putInt("mode", typing);
        if (form.spelling != null) edit.putBoolean(TONE_MARKS, form == OutputForm.MARKED);
        edit.apply();
    }
}
