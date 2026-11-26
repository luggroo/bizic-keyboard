package biz.tujia.ime;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

final class OptionalResources {
    private static final ExecutorService downloads = Executors.newSingleThreadExecutor();
    private static JSONObject catalogue;

    static synchronized JSONObject entry(Context context, String id) throws IOException {
        if (id == null || !id.matches("[0-9a-f]{32}")) throw new IOException("Invalid resource id");
        if (catalogue == null) {
            try (InputStream input = context.getAssets().open("learning/optional-resources.json")) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                for (int n; (n = input.read(buffer)) != -1;) out.write(buffer, 0, n);
                JSONObject document = new JSONObject(out.toString("UTF-8"));
                if (document.getInt("schemaVersion") != 1) throw new IOException("Unknown catalogue");
                catalogue = document.getJSONObject("resources");
            } catch (JSONException e) {
                throw new IOException(e);
            }
        }
        JSONObject value = catalogue.optJSONObject(id);
        if (value == null) throw new IOException("Unknown resource");
        return value;
    }

    private static boolean cacheInitialized;
    static synchronized File file(Context context, String id) throws IOException {
        // Call entry before constructing the path, even when the caller is a document viewer.
        entry(context, id);
        File directory = new File(context.getNoBackupFilesDir(), "learning-resources");
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Storage unavailable");
        if (!cacheInitialized) {
            File[] partials = directory.listFiles((d, name) -> name.startsWith("fetch-") && name.endsWith(".part"));
            if (partials != null) for (File partial : partials) partial.delete();
            cacheInitialized = true;
        }
        return new File(directory, id);
    }

    // Accessed only by the reader activity on the main thread.
    private static final Map<Activity, ProgressDialog> pending = new IdentityHashMap<>();

    static void finish(Activity activity) {
        ProgressDialog dialog = pending.remove(activity);
        if (dialog != null) dialog.cancel();
    }

    static void open(Activity activity, String id) {
        if (pending.containsKey(activity) || activity.isFinishing()) return;
        ProgressDialog loading = new ProgressDialog(activity);
        loading.setMessage(activity.getText(R.string.optional_opening));
        loading.setIndeterminate(true);
        loading.setCancelable(true);
        pending.put(activity, loading);
        loading.setOnDismissListener(dialog -> pending.remove(activity, loading));
        loading.show();
        Future<?> task = downloads.submit(() -> {
            try {
                JSONObject resource = entry(activity, id);
                File cached = file(activity, id);
                String type = resource.getString("type");
                if (!cached.isFile()) {
                    OptionalFile.download(cached, new URL(resource.getString("url")), type,
                            resource.getLong("size"), resource.getString("sha256"));
                }
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !loading.isShowing()) return;
                    loading.dismiss();
                    Uri uri = new Uri.Builder().scheme("content").authority(activity.getPackageName() + ".learning.resources")
                            .appendPath(id).build();
                    Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, type);
                    view.setClipData(ClipData.newRawUri("resource", uri));
                    view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    try {
                        activity.startActivity(view);
                    } catch (ActivityNotFoundException | SecurityException e) {
                        Toast.makeText(activity, R.string.optional_viewer_missing, Toast.LENGTH_LONG).show();
                    }
                });
            } catch (IOException | JSONException e) {
                activity.runOnUiThread(() -> {
                    if (activity.isFinishing() || activity.isDestroyed() || !loading.isShowing()) return;
                    loading.dismiss();
                    Toast.makeText(activity, R.string.optional_unavailable, Toast.LENGTH_LONG).show();
                });
            }
        });
        loading.setOnCancelListener(dialog -> task.cancel(true));
    }
}
