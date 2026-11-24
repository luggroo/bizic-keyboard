package biz.tujia.ime;

import android.content.Context;

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
}
