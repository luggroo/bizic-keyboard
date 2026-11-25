package biz.tujia.ime;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.regex.Pattern;

final class UserDictionary {
    enum Form { YE, DIACRITIC, IPA }

    private static final int MINIMUM_USES = 2;
    private static final Pattern WORD = Pattern.compile(
            "\\p{L}[\\p{L}\\p{M}¹²³⁴⁵]*(?:[-'’]\\p{L}[\\p{L}\\p{M}¹²³⁴⁵]*)*");
    private final SharedPreferences preferences;
    private final NavigableMap<String, Integer> uses = new TreeMap<>();

    UserDictionary(Context context) {
        preferences = context.getSharedPreferences("user_dictionary", Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getValue() instanceof Integer) uses.put(entry.getKey(), (Integer) entry.getValue());
        }
    }

    void register(Form form, String word) {
        if (!WORD.matcher(word).matches()) return;
        String key = form.name() + '\t' + word;
        int previous = uses.getOrDefault(key, 0);
        if (previous == Integer.MAX_VALUE) return;
        int count = previous + 1;
        uses.put(key, count);
        preferences.edit().putInt(key, count).apply();
    }

    List<String> completions(Form form, String prefix) {
        String namespace = form.name() + '\t';
        String start = namespace + prefix;
        List<Map.Entry<String, Integer>> found = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : uses.tailMap(start, false).entrySet()) {
            if (!entry.getKey().startsWith(start)) break;
            if (entry.getValue() >= MINIMUM_USES) found.add(entry);
        }
        found.sort((left, right) -> Integer.compare(right.getValue(), left.getValue()));
        List<String> words = new ArrayList<>(found.size());
        for (Map.Entry<String, Integer> entry : found) words.add(entry.getKey().substring(namespace.length()));
        return words;
    }
}
