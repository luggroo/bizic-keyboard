package biz.tujia.ime;

import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Serves only the packaged learning/ subtree. Every request receives a local response. */
final class LearningWebClient extends WebViewClient {
    private static final byte[] EMPTY = new byte[0];
    private static final Map<String, String> HEADERS;
    static {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Security-Policy", "default-src 'none'; base-uri 'self'; script-src 'self'; "
                + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; "
                + "media-src 'self'; connect-src 'self'; worker-src 'none'; child-src 'none'; "
                + "form-action 'none'; frame-ancestors 'none'");
        headers.put("X-Content-Type-Options", "nosniff");
        headers.put("Referrer-Policy", "no-referrer");
        headers.put("Cache-Control", "no-store");
        HEADERS = Collections.unmodifiableMap(headers);
    }

    private final LearningActivity activity;
    LearningWebClient(LearningActivity activity) { this.activity = activity; }

    private static boolean isLocal(Uri uri) {
        return uri != null && LearningOrigin.isLocal(uri.getScheme(), uri.getEncodedAuthority());
    }

    @Override public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri uri = request.getUrl();
        if (!isLocal(uri)) return empty(403, "Forbidden");
        boolean head = "HEAD".equals(request.getMethod());
        if (!head && !"GET".equals(request.getMethod())) return empty(405, "Method Not Allowed");
        if (LearningOrigin.STATUS_PATH.equals(uri.getEncodedPath()) && uri.getEncodedQuery() == null
                && uri.getEncodedFragment() == null && !request.isForMainFrame()) {
            byte[] bytes = head ? EMPTY : ImeStatus.read(activity).asJson().getBytes(StandardCharsets.UTF_8);
            return new WebResourceResponse("application/json", "UTF-8", 200, "OK", HEADERS,
                    new ByteArrayInputStream(bytes));
        }
        String path = LearningOrigin.assetName(uri.getEncodedPath());
        if (path == null) return empty(404, "Not Found");
        InputStream body;
        try {
            body = activity.getAssets().open("learning/" + path, AssetManager.ACCESS_STREAMING);
        } catch (FileNotFoundException missing) {
            return empty(404, "Not Found");
        } catch (IOException unreadable) {
            return empty(500, "Read Error");
        }
        try {
            if (head) {
                body.close();
                body = new ByteArrayInputStream(EMPTY);
            } else if (LearningOrigin.isPreparedJson(path)) {
                // Keep compressed bytes in the APK and let Android's zlib feed
                // the WebView JSON parser directly, including on Android 7.
                body = new GZIPInputStream(body, 32 * 1024);
            }
            String mime = LearningOrigin.mime(path);
            boolean text = mime.startsWith("text/") || mime.equals("application/javascript")
                    || mime.endsWith("json") || mime.endsWith("+xml");
            return new WebResourceResponse(mime, text ? "UTF-8" : null, 200, "OK", HEADERS, body);
        } catch (IOException unreadable) {
            try { body.close(); } catch (IOException ignored) {}
            return empty(500, "Read Error");
        }
    }

    private static WebResourceResponse empty(int status, String reason) {
        return new WebResourceResponse("text/plain", "UTF-8", status, reason, HEADERS, new ByteArrayInputStream(EMPTY));
    }

    @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        if (!request.isForMainFrame() || !"GET".equals(request.getMethod())) return true;
        Uri uri = request.getUrl();
        String current = view.getUrl();
        boolean explicit = request.hasGesture() && current != null && isLocal(Uri.parse(current));
        if (isLocal(uri)) {
            String path = uri.getEncodedPath();
            if (path != null && path.startsWith("/_app/")) {
                if (explicit && uri.getEncodedQuery() == null && uri.getEncodedFragment() == null) {
                    switch (path) {
                        case LearningOrigin.KEYBOARD_PATH:
                            activity.openKeyboard();
                            break;
                        case LearningOrigin.ENABLE_PATH:
                            activity.enableKeyboard();
                            break;
                        case LearningOrigin.SWITCH_PATH:
                            activity.showKeyboardPicker();
                            break;
                        case LearningOrigin.PRIVACY_PATH:
                            activity.openPrivacy();
                            break;
                        default:
                            break;
                    }
                }
                return true;
            }
            return LearningOrigin.assetName(path) == null;
        }
        if (explicit && "https".equals(uri.getScheme()) && uri.getHost() != null
                && uri.getEncodedUserInfo() == null) {
            activity.confirmExternalLink(uri);
        }
        return true;
    }

    @Override public void onPageFinished(WebView view, String url) {
        if (isLocal(Uri.parse(url))) activity.refreshReaderTheme();
    }

    @Override public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
        if (request.isForMainFrame()) activity.showReaderError(view);
    }

    @Override public void onReceivedHttpError(WebView view, WebResourceRequest request, WebResourceResponse response) {
        if (request.isForMainFrame()) activity.showReaderError(view);
    }

    @Override public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        activity.showReaderError(view);
        return true;
    }
}
