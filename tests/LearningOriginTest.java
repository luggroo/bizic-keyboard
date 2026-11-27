package biz.tujia.ime;

import java.util.Arrays;
import java.util.Objects;

public final class LearningOriginTest {
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }

    public static void main(String[] args) {
        equal(true, LearningOrigin.isLocal("https", "appassets.androidplatform.net"));
        for (String authority : Arrays.asList("", "appassets.androidplatform.net:443", "appassets.androidplatform.net.",
                "appassets.androidplatform.net.example", "appassets.androidplatform.net@other.example",
                "other.example@appassets.androidplatform.net")) {
            equal(false, LearningOrigin.isLocal("https", authority));
        }
        equal(false, LearningOrigin.isLocal("http", LearningOrigin.HOST));
        equal(false, LearningOrigin.isLocal("file", null));
        equal("index.html", LearningOrigin.assetName("/"));
        equal("en/index.html", LearningOrigin.assetName("/en/"));
        equal("prepared/words-0.json.gz", LearningOrigin.assetName("/prepared/words-0.json.gz"));
        for (String path : Arrays.asList("/../files/last-crash.txt", "/.hidden", "//private", "/en//",
                "/_app/keyboard", "/_app/keyboard/enable", "/_app/keyboard/switch", "/_app/privacy",
                "/_app/ime-status.json", "/_app/resource/id", "/%2e%2e/file", "/a%2Fb", "/a%252Fb",
                "/a/%00", "/a\\b", "index.html")) equal(null, LearningOrigin.assetName(path));
        equal("application/json", LearningOrigin.mime("prepared/words-0.json.gz"));
        equal("application/octet-stream", LearningOrigin.mime("other/file.json.gz"));
        equal(true, LearningOrigin.isPreparedJson("prepared/words-0.json.gz"));
        equal(false, LearningOrigin.isPreparedJson("prepared/words.gz"));
        equal("application/javascript", LearningOrigin.mime("page-loader.js"));
        equal("font/woff2", LearningOrigin.mime("fonts/GentiumPlus-Regular.woff2"));
        System.out.println("Local origin, reserved native paths and asset MIME types passed.");
    }
}
