package biz.tujia.ime;

import java.util.regex.Pattern;

/** The exported reader uses ASCII asset names and a single, HTTPS-only origin. */
final class LearningOrigin {
    static final String HOST = "appassets.androidplatform.net";
    static final String START_URL = "https://" + HOST + "/index.html";
    static final String KEYBOARD_PATH = "/_app/keyboard";
    private static final String RESOURCE_PREFIX = "/_app/resource/";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,96}");
    private static final Pattern ASSET = Pattern.compile("(?:[A-Za-z0-9_][A-Za-z0-9_.-]*/)*[A-Za-z0-9_][A-Za-z0-9_.-]*");

    private LearningOrigin() {}

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

    static String resourceId(String encodedPath) {
        if (encodedPath == null || !encodedPath.startsWith(RESOURCE_PREFIX)) return null;
        String id = encodedPath.substring(RESOURCE_PREFIX.length());
        return ID.matcher(id).matches() ? id : null;
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
