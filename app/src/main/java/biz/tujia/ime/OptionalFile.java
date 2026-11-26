package biz.tujia.ime;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Locale;

import javax.net.ssl.HttpsURLConnection;

/** Reads only a URL from the installed resource catalogue. A partial file is never published. */
final class OptionalFile {
    private static final long LIMIT = 512L * 1024 * 1024;
    private static final long RESERVE = 8L * 1024 * 1024;

    static boolean allowed(URL url) {
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null
                || (url.getPort() != -1 && url.getPort() != 443)) return false;
        String host = url.getHost().toLowerCase(Locale.ROOT);
        return host.equals("tujia.biz") || host.equals("drive.usercontent.google.com")
                || host.equals("theswissbay.ch");
    }

    static void download(File file, URL url, String mime, long size, String sha) throws IOException {
        if (!allowed(url)) throw new IOException("Unknown content origin");
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setUseCaches(false);
        connection.setConnectTimeout(12000);
        connection.setReadTimeout(20000);
        connection.setRequestProperty("Accept-Encoding", "identity");
        // No WebView cookies, referring URLs, reader query strings, or IME state.
        try {
            if (connection.getResponseCode() != 200) throw new IOException("Source unavailable");
            long advertised = connection.getContentLengthLong();
            if (advertised > LIMIT || size > LIMIT || (size > 0 && advertised >= 0 && size != advertised)) {
                throw new IOException("Unexpected file length");
            }
            try (InputStream in = connection.getInputStream()) {
                store(file, in, mime, size > 0 ? size : Math.max(0, advertised), sha);
            }
        } finally {
            connection.disconnect();
        }
    }

    static void store(File destination, InputStream input, String type, long size, String sha) throws IOException {
        File directory = destination.getParentFile();
        long free = Math.min(LIMIT, directory.getUsableSpace() - RESERVE);
        if (size > free || free < 16) throw new IOException("Insufficient storage");
        File temporary = File.createTempFile("fetch-", ".part", directory);
        try {
            MessageDigest digest;
            try {
                digest = MessageDigest.getInstance("SHA-256");
            } catch (NoSuchAlgorithmException e) {
                throw new AssertionError(e);
            }
            byte[] prefix = new byte[16];
            byte[] buffer = new byte[65536];
            long length = 0;
            try (FileOutputStream out = new FileOutputStream(temporary)) {
                for (int read; (read = input.read(buffer)) != -1;) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Download cancelled");
                    if (length + read > free || (size > 0 && length + read > size)) throw new IOException("File too large");
                    if (length < prefix.length) {
                        System.arraycopy(buffer, 0, prefix, (int) length, Math.min(read, prefix.length - (int) length));
                    }
                    length += read;
                    digest.update(buffer, 0, read);
                    out.write(buffer, 0, read);
                }
                if (length < prefix.length || (size > 0 && length != size) || !hasFormat(prefix, type)) {
                    throw new IOException("Incomplete or unexpected document");
                }
                if (!sha.isEmpty() && !hex(digest.digest()).equals(sha)) throw new IOException("Different edition");
                out.getFD().sync();
            }
            if (Thread.currentThread().isInterrupted() || !temporary.renameTo(destination)) {
                throw new IOException("File could not be saved");
            }
        } finally {
            if (temporary.exists()) temporary.delete();
        }
    }

    private static boolean hasFormat(byte[] prefix, String type) {
        String text = new String(prefix, java.nio.charset.StandardCharsets.ISO_8859_1);
        switch (type) {
            case "application/pdf": return text.startsWith("%PDF-");
            case "audio/flac": return text.startsWith("fLaC");
            case "audio/wav": return text.startsWith("RIFF") && text.startsWith("WAVE", 8);
            case "audio/mpeg": return text.startsWith("ID3") || ((prefix[0] & 255) == 255 && (prefix[1] & 224) == 224);
            case "video/mp4": return text.startsWith("ftyp", 4);
            case "video/x-flv": return text.startsWith("FLV");
            case "image/jpeg": return (prefix[0] & 255) == 255 && (prefix[1] & 255) == 216 && (prefix[2] & 255) == 255;
            case "image/png": return Arrays.equals(Arrays.copyOf(prefix, 8), new byte[]{(byte)137,80,78,71,13,10,26,10});
            default: return false;
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder();
        for (byte b : bytes) {
            result.append(Character.forDigit((b & 255) >>> 4, 16));
            result.append(Character.forDigit(b & 15, 16));
        }
        return result.toString();
    }
}
