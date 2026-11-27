package biz.tujia.ime;

import android.net.Uri;

import java.util.regex.Pattern;

/** The exported reader uses ASCII asset names and a single, HTTPS-only origin. */
final class LearningOrigin {
    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/index.html";
    static final String KEYBOARD_PATH = "/_app/keyboard";
    static final String ENABLE_PATH = "/_app/keyboard/enable";
    static final String SWITCH_PATH = "/_app/keyboard/switch";
    static final String STATUS_PATH = "/_app/ime-status.json";
    static final String PRIVACY_PATH = "/_app/privacy";
    private static final Pattern ASSET = Pattern.compile("(?:[A-Za-z0-9_][A-Za-z0-9_.-]*/)*[A-Za-z0-9_][A-Za-z0-9_.-]*");
    private static final Pattern DICTIONARY_ENTRY = Pattern.compile("[A-Za-z0-9_-]{1,160}");

    private LearningOrigin() {}

    /** Accept a stable entry ID, never a caller-supplied address or composition text. */
    static String dictionaryEntryUrl(String id) {
        if (id == null || !DICTIONARY_ENTRY.matcher(id).matches()) return START_URL;
        return Uri.parse(START_URL).buildUpon().appendQueryParameter("id", id)
                .fragment("words").build().toString();
    }

    static boolean isLocal(String scheme, String encodedAuthority) {
        return "https".equals(scheme) && HOST.equals(encodedAuthority);
    }

    static String assetName(String encodedPath) {
        if (encodedPath == null || encodedPath.isEmpty()) encodedPath = "/";
        if (!encodedPath.startsWith("/") || encodedPath.startsWith("/_app/")) return null;
        String name = encodedPath.substring(1);
        if (encodedPath.endsWith("/")) name += "index.html";
        return ASSET.matcher(name).matches() ? name : null;
    }

    static boolean isPreparedJson(String name) {
        return name.startsWith("prepared/") && name.endsWith(".json.gz");
    }

    static String mime(String name) {
        if (name.endsWith(".html")) return "text/html";
        if (name.endsWith(".js")) return "application/javascript";
        if (name.endsWith(".css")) return "text/css";
        if (name.endsWith(".json") || isPreparedJson(name)) return "application/json";
        if (name.endsWith(".webmanifest")) return "application/manifest+json";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".woff2")) return "font/woff2";
        if (name.endsWith(".woff")) return "font/woff";
        if (name.endsWith(".ttf")) return "font/ttf";
        if (name.endsWith(".txt") || name.endsWith(".md")) return "text/plain";
        return "application/octet-stream";
    }
}
