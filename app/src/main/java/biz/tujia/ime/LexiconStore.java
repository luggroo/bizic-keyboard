package biz.tujia.ime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.Log;

import java.io.IOException;
import java.io.InputStream;

/** A process owns one immutable native dictionary; a service only observes its availability. */
final class LexiconStore {
    interface Listener {
        void onLexiconLoaded(CandidateEngine engine);
    }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    // Only the main looper reads and writes these fields.
    private static CandidateEngine cached;
    private static boolean loading;
    private static Listener observer;

    static void load(Context context, Listener listener) {
        observer = listener;
        if (cached != null) {
            listener.onLexiconLoaded(cached);
            return;
        }
        if (loading) return;
        loading = true;
        Context app = context.getApplicationContext();
        new Thread(() -> {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
            CandidateEngine result;
            try (InputStream words = app.getAssets().open("words.tsv");
                 InputStream northern = app.getAssets().open("northern-words.tsv");
                 InputStream ngrams = app.getAssets().open("ngrams.tsv");
                 InputStream syllables = app.getAssets().open("syllable_bigrams.tsv");
                 InputStream senses = app.getAssets().open("syllable_senses.tsv")) {
                result = new CandidateEngine(new java.io.SequenceInputStream(words, northern), ngrams, syllables, senses);
            } catch (IOException | RuntimeException | LinkageError failure) {
                Log.e("TujiaIME", "Could not load bundled dictionary", failure);
                result = null;
            }
            CandidateEngine completed = result;
            MAIN.post(() -> {
                cached = completed;
                loading = false;
                if (observer != null) observer.onLexiconLoaded(cached);
            });
        }, "Tujia-lexicon").start();
    }

    static void detach(Listener listener) {
        if (observer == listener) observer = null;
    }

    private LexiconStore() { }
}
