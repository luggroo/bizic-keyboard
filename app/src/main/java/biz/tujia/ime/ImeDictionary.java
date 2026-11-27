package biz.tujia.ime;

import android.content.res.AssetManager;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;
import java.util.zip.CRC32;
import java.util.zip.GZIPInputStream;

/** Reads one entry from the reader's packaged dictionary. Only the worker thread calls this class. */
final class ImeDictionary {
    private static final int MAX_ENTRY = 256 * 1024;
    static final int SOURCE_GRAY = 0xff67615e;
    private final AssetManager assets;
    private JSONObject sourceTitles;

    ImeDictionary(AssetManager assets) {
        this.assets = assets;
    }

    static final class Article {
        final String definition, sources, selectedWitness;
        final CharSequence readings;

        Article(String definition, CharSequence readings, String sources, String selectedWitness) {
            this.definition = definition;
            this.readings = readings;
            this.sources = sources;
            this.selectedWitness = selectedWitness;
        }
    }

    static final class Link {
        final String keyboardId, canonicalId, meaning, reference, requiredIds, witnessIds;
        final int batch, offset, size, witness;

        Link(String keyboardId, String canonicalId, String meaning, String reference,
             int batch, int offset, int size, int witness, String requiredIds, String witnessIds) {
            this.keyboardId = keyboardId;
            this.canonicalId = canonicalId;
            this.meaning = meaning;
            this.reference = reference;
            this.batch = batch;
            this.offset = offset;
            this.size = size;
            this.witness = witness;
            this.requiredIds = requiredIds;
            this.witnessIds = witnessIds;
        }

        Link wholeEntry() {
            return new Link(keyboardId, canonicalId, meaning, "", batch, offset, size, 0, "", "");
        }
    }

    List<Link> links(Map<String, String> meanings) throws IOException, JSONException {
        List<Link> explicit = new ArrayList<>();
        Set<String> present = new LinkedHashSet<>();
        for (Map.Entry<String, String> item : meanings.entrySet()) {
            if (Thread.currentThread().isInterrupted()) break;
            String key = item.getKey();
            if (key.isEmpty() || key.length() > 160 || key.indexOf(' ') >= 0) continue;
            Link link = find(key, item.getValue());
            if (link != null) {
                explicit.add(link);
                present.add(link.keyboardId);
            }
        }
        Map<String, Link> links = new LinkedHashMap<>();
        for (Link link : explicit) {
            if (!HanChoice.identitiesPresent(link.requiredIds, present)) continue;
            Link known = HanChoice.identitiesPresent(link.witnessIds, present) ? link : link.wholeEntry();
            links.putIfAbsent(known.canonicalId + "\t" + known.witness, known);
        }
        return new ArrayList<>(links.values());
    }

    private Link find(String key, String meaning) throws IOException, JSONException {
        CRC32 crc = new CRC32();
        crc.update(key.getBytes(StandardCharsets.UTF_8));
        String index = "ime-dictionary/ids-" + (crc.getValue() % 64) + ".tsv";
        String prefix = key + "\t";
        try (BufferedReader input = new BufferedReader(new InputStreamReader(assets.open(index), StandardCharsets.UTF_8))) {
            for (String line; (line = input.readLine()) != null;) {
                if (!line.startsWith(prefix)) continue;
                String[] row = line.split("\t", -1);
                if (row.length != 10) return null;
                int batch = Integer.parseInt(row[2]), offset = Integer.parseInt(row[3]), size = Integer.parseInt(row[4]);
                int witness = Integer.parseInt(row[5]);
                if (batch < 0 || offset < 0 || size < 1 || size > MAX_ENTRY || witness < 0) return null;
                String reference = witness == 0 ? "" : joined(" · ", titles().getJSONObject("sources").optString(row[6]), row[7]);
                return new Link(key, row[1], meaning, reference, batch, offset, size, witness, row[8], row[9]);
            }
        }
        return null;
    }

    Article read(Link link) throws IOException, JSONException {
        try (DataInputStream shard = new DataInputStream(new GZIPInputStream(
                assets.open("learning/prepared/words-" + link.batch + ".json.gz"), 8192))) {
            int left = link.offset;
            while (left > 0) {
                if (Thread.currentThread().isInterrupted()) return null;
                int skipped = shard.skipBytes(Math.min(left, 8192));
                if (skipped == 0) { shard.readByte(); skipped = 1; }
                left -= skipped;
            }
            byte[] bytes = new byte[link.size];
            shard.readFully(bytes);
            JSONObject word = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!link.canonicalId.equals(word.getString("id"))) return null;
            if (link.witness > 0 && !link.keyboardId.equals(word.getJSONArray("attestations")
                    .getJSONObject(link.witness - 1).getString("id"))) return null;
            return describe(word, titles(), link.witness);
        }
    }

    private JSONObject titles() throws IOException, JSONException {
        if (sourceTitles == null) {
            try (InputStream input = assets.open("ime-dictionary/sources.json")) {
                ByteArrayOutputStream data = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                for (int n; (n = input.read(chunk)) != -1;) {
                    if (data.size() + n > MAX_ENTRY) throw new IOException("Invalid source catalogue");
                    data.write(chunk, 0, n);
                }
                sourceTitles = new JSONObject(data.toString("UTF-8"));
            }
        }
        return sourceTitles;
    }

    private static String joined(String separator, String... values) {
        StringJoiner list = new StringJoiner(separator);
        for (String value : values) if (!value.isEmpty()) list.add(value);
        return list.toString();
    }

    private static Set<Integer> witnessNumbers(JSONArray ids, Map<String, Integer> numbered) {
        Set<Integer> numbers = new LinkedHashSet<>();
        if (ids == null) return numbers;
        for (int i = 0; i < ids.length(); i++) {
            Integer index = numbered.get(ids.optString(i));
            if (index != null && index > 0) numbers.add(index);
        }
        return numbers;
    }

    private static String references(Set<Integer> numbers) {
        StringJoiner references = new StringJoiner("、");
        for (int number : numbers) references.add(Integer.toString(number));
        return references.length() == 0 ? "" : "〔" + references + "〕";
    }

    static CharSequence sourceNote(String value) {
        SpannableString note = new SpannableString(value);
        note.setSpan(new ForegroundColorSpan(SOURCE_GRAY), 0, note.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        note.setSpan(new RelativeSizeSpan(.80f), 0, note.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return note;
    }

    private static String methodLabel(String method) {
        switch (method) {
            case "source": return "文献记音";
            case "source-normalized": return "原记音规范写法";
            case "derived": return "规则转换";
            case "user-reviewed": return "人工复核";
            case "editorial-correction": return "编校订正";
            default: return "其他记音方式";
        }
    }

    private static Set<String> metadata(JSONObject reading, String single, String multiple) {
        Set<String> values = new LinkedHashSet<>();
        String first = reading.optString(single);
        if (!first.isEmpty()) values.add(first);
        JSONArray rest = reading.optJSONArray(multiple);
        if (rest != null) for (int i = 0; i < rest.length(); i++) values.add(rest.optString(i));
        values.remove("");
        return values;
    }

    private static Article describe(JSONObject word, JSONObject catalogue, int selected) throws JSONException {
        JSONObject titles = catalogue.getJSONObject("sources"), profiles = catalogue.getJSONObject("profiles");
        JSONArray records = word.getJSONArray("attestations");
        Map<String, Integer> numbered = new LinkedHashMap<>();
        for (int i = 0; i < records.length(); i++) {
            String id = records.getJSONObject(i).getString("id");
            numbered.put(id, numbered.containsKey(id) ? -1 : i + 1);
        }

        StringBuilder definition = new StringBuilder("原记音　").append(word.optString("term"));
        definition.append('\n').append(joined("　", word.optString("dialect"), word.optString("pos")));
        definition.append("\n\n释义\n");
        JSONArray senses = word.optJSONArray("senses");
        if (senses == null || senses.length() == 0) {
            definition.append(joined("\n", word.optString("zh"), word.optString("en")));
        } else {
            Map<List<String>, Set<Integer>> definitions = new LinkedHashMap<>();
            for (int i = 0; i < senses.length(); i++) {
                JSONObject sense = senses.getJSONObject(i);
                List<String> gloss = Arrays.asList(sense.optString("zh"), sense.optString("en"));
                definitions.computeIfAbsent(gloss, k -> new LinkedHashSet<>())
                        .addAll(witnessNumbers(sense.optJSONArray("attestationIds"), numbered));
            }
            StringJoiner lines = new StringJoiner("\n");
            for (Map.Entry<List<String>, Set<Integer>> sense : definitions.entrySet())
                lines.add(joined("　", joined(" / ", sense.getKey().get(0), sense.getKey().get(1)),
                        references(sense.getValue())));
            definition.append(lines);
        }

        SpannableStringBuilder forms = new SpannableStringBuilder();
        JSONObject notations = word.optJSONObject("notations");
        if (notations != null) {
            Map<String, String> schemes = new LinkedHashMap<>();
            schemes.put("ye", "叶氏拼音");
            schemes.put("accent", "调号拼音");
            schemes.put("ipa", "国际音标");
            schemes.put("brassett", "Brassett 记音");
            for (Map.Entry<String, String> scheme : schemes.entrySet()) {
                JSONArray readings = notations.optJSONArray(scheme.getKey());
                if (readings == null || readings.length() == 0) continue;
                if (forms.length() > 0) forms.append("\n\n");
                forms.append(scheme.getValue());
                for (int i = 0; i < readings.length(); i++) {
                    JSONObject reading = readings.getJSONObject(i);
                    StringJoiner methods = new StringJoiner("、"), labels = new StringJoiner("、");
                    for (String method : metadata(reading, "method", "sourceMethods")) methods.add(methodLabel(method));
                    for (String profile : metadata(reading, "profile", "sourceProfiles")) {
                        String label = profiles.optString(profile, titles.optString(profile));
                        if (!label.isEmpty()) labels.add(label);
                    }
                    forms.append('\n').append(joined("　", reading.getString("text"),
                            references(witnessNumbers(reading.optJSONArray("attestationIds"), numbered))));
                    String origin = joined(" · ", methods.toString(), labels.toString());
                    if (!origin.isEmpty()) forms.append('\n').append(sourceNote(origin));
                }
            }
        }

        StringBuilder sources = new StringBuilder();
        String selectedWitness = "";
        if (records.length() > 0) sources.append("出处与原记音");
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.getJSONObject(i);
            String source = record.optString("source");
            sources.append("\n〔").append(i + 1).append("〕").append(joined(" · ", titles.optString(source, "书目未标明"),
                    record.optString("locator"), record.optString("dialect")));
            sources.append('\n').append(joined("　", record.optString("term"), record.optString("scheme")));
            String gloss = joined(" / ", record.optString("zh"), record.optString("en"));
            if (!gloss.isEmpty()) sources.append('\n').append(gloss);
            if (i + 1 == selected) selectedWitness = "所选文献见证〔" + selected + "〕　" + record.optString("term");
        }
        return new Article(definition.toString(), forms, sources.toString(), selectedWitness);
    }
}
