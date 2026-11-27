package biz.tujia.ime;

import android.content.Context;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Short-lived recording and the Gradio transcription protocol. No audio is logged. */
final class VoiceInput {
    interface Callback { void done(String text, String error); }
    private static final String SERVER = "https://tujianlp-tujia-asr-demo.ms.show";
    private final Context context;
    private MediaRecorder recorder;
    private File audio;
    private volatile int generation;
    private final Handler main = new Handler(Looper.getMainLooper());
    VoiceInput(Context context) { this.context = context.getApplicationContext(); }
    boolean recording() { return recorder != null; }
    @android.annotation.SuppressLint("MissingPermission") // The IME checks the runtime grant before calling.
    void start() throws IOException {
        cancel();
        audio = File.createTempFile("voice-", ".m4a", context.getCacheDir());
        try {
            recorder = new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1); recorder.setAudioSamplingRate(16000); recorder.setAudioEncodingBitRate(32000);
            recorder.setOutputFile(audio.getAbsolutePath()); recorder.prepare(); recorder.start();
        } catch (RuntimeException | IOException e) { cancel(); throw e; }
    }
    void stop(Callback callback) {
        if (recorder == null) return;
        try { recorder.stop(); } catch (RuntimeException e) { cancel(); callback.done(null, "录音太短，请重试"); return; }
        recorder.release(); recorder = null;
        File file = audio; audio = null;
        int request = ++generation;
        new Thread(() -> {
            String result = null, error = null;
            try { result = transcribe(file); }
            catch (Exception failure) { error = "识别失败，请检查网络后重试"; }
            finally { file.delete(); }
            String text = result, message = error;
            main.post(() -> { if (request == generation) callback.done(text, message); });
        }, "northern-voice").start();
    }
    void cancel() {
        generation++;
        if (recorder != null) { try { recorder.stop(); } catch (RuntimeException ignored) {} recorder.release(); recorder = null; }
        if (audio != null) { audio.delete(); audio = null; }
    }
    private static String read(InputStream stream, int maximum) throws IOException {
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096]; int n, total = 0;
            while ((n = in.read(buffer)) != -1) { total += n; if (total > maximum) throw new IOException("Response too large"); out.write(buffer,0,n); }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }
    private static HttpURLConnection connect(String path) throws IOException {
        HttpURLConnection c = (HttpURLConnection)new URL(SERVER + path).openConnection();
        c.setConnectTimeout(20000); c.setReadTimeout(120000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/126.0 Mobile Safari/537.36");
        return c;
    }
    private static String transcribe(File audio) throws Exception {
        byte[] bytes;
        try (InputStream in = new FileInputStream(audio); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buf = new byte[4096]; int n;
            while ((n=in.read(buf))!=-1) { if (out.size()+n>2_000_000) throw new IOException("Recording too large"); out.write(buf,0,n); }
            bytes=out.toByteArray();
        }
        JSONArray params = new JSONArray().put("data:audio/mp4;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)).put(0.0).put(true).put(30);
        byte[] body = new JSONObject().put("data",params).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection post = connect("/gradio_api/call/transcribe");
        String event;
        try {
            post.setRequestMethod("POST"); post.setDoOutput(true); post.setRequestProperty("Content-Type","application/json");
            try (OutputStream output=post.getOutputStream()) { output.write(body); }
            if (post.getResponseCode()!=200) throw new IOException("Request failed");
            event=new JSONObject(read(post.getInputStream(),65536)).getString("event_id");
        } finally { post.disconnect(); }
        if (!event.matches("[A-Za-z0-9_-]{1,128}")) throw new IOException("Invalid event");
        HttpURLConnection get = connect("/gradio_api/call/transcribe/" + event);
        try {
            get.setRequestProperty("Accept","text/event-stream");
            if (get.getResponseCode()!=200) throw new IOException("Result failed");
            String stream=read(get.getInputStream(),2_000_000), kind="";
            for (String line:stream.split("\\r?\\n")) {
                if (line.startsWith("event:")) kind=line.substring(6).trim();
                else if (line.startsWith("data:") && "complete".equals(kind)) {
                    JSONArray data=new JSONArray(line.substring(5).trim());
                    Object first=data.get(0);
                    JSONObject result=first instanceof JSONObject ? (JSONObject)first : new JSONObject(first.toString());
                    if (!result.optBoolean("ok")) throw new IOException("Recognition failed");
                    String text=result.optString("text_sup", "").trim();
                    if (text.isEmpty()) throw new IOException("No speech recognized");
                    return text;
                }
            }
            throw new IOException("Incomplete result");
        } finally { get.disconnect(); }
    }
}
