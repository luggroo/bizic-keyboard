package biz.tujia.ime.e2e;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Locale;

/** Receives a test PDF as an external viewer and reports the bytes that the grant actually exposes. */
public final class PdfProbeActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Uri document = getIntent().getData();
        if (document == null || !"content".equals(document.getScheme()) ||
                !"biz.tujia.ime.learning.resources".equals(document.getAuthority())) {
            finish();
            return;
        }
        TextView result = new TextView(this);
        setContentView(result);
        new Thread(() -> {
            String summary;
            try (InputStream source = getContentResolver().openInputStream(document)) {
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                byte[] block = new byte[8192];
                long size = 0;
                for (int n; (n = source.read(block)) != -1;) {
                    digest.update(block, 0, n);
                    size += n;
                }
                StringBuilder hex = new StringBuilder();
                for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
                summary = size + " " + hex;
            } catch (Exception failure) {
                summary = "Read failed: " + failure.getClass().getSimpleName();
            }
            String text = summary;
            Log.i("BizicResourceQA", text);
            runOnUiThread(() -> result.setText(text));
        }, "Read test document").start();
    }
}
