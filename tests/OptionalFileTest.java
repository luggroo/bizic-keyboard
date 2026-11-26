package biz.tujia.ime;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Run on a host JVM. The live read is optional and depends on a published PDF. */
public final class OptionalFileTest {
    public static void main(String[] args) throws Exception {
        File dir = Files.createTempDirectory("bizic-resource-test").toFile();
        File cached = new File(dir, "publication");
        byte[] pdf = "%PDF-1.7\nOffline resource test".getBytes(StandardCharsets.US_ASCII);
        try {
            OptionalFile.store(cached, new ByteArrayInputStream(pdf), "application/pdf", pdf.length, "");
            for (int scenario = 0; scenario < 5; scenario++) {
                try {
                    byte[] body = scenario == 0 ? "<!doctype html>Unavailable".getBytes(StandardCharsets.US_ASCII) : pdf;
                    long expected = scenario == 1 ? pdf.length + 10 : (scenario == 2 ? pdf.length - 2 : 0);
                    if (scenario == 4) Thread.currentThread().interrupt();
                    OptionalFile.store(cached, new ByteArrayInputStream(body), "application/pdf", expected, scenario == 3 ? "00" : "");
                    throw new AssertionError("Invalid or interrupted content accepted " + scenario);
                } catch (IOException expected) {
                    if (!Arrays.equals(Files.readAllBytes(cached.toPath()), pdf) || dir.list().length != 1) {
                        throw new AssertionError("Cache damaged after failed download");
                    }
                } finally { Thread.interrupted(); }
            }
            for (String bad : new String[]{"http://tujia.biz/p.pdf", "https://tujia.biz.example/p.pdf",
                    "https://other@tujia.biz/p.pdf", "https://tujia.biz:8080/p.pdf"}) {
                if (OptionalFile.allowed(new URL(bad))) throw new AssertionError(bad);
            }
            if (!OptionalFile.allowed(new URL("https://tujia.biz/translation-pdfs/all-examples.pdf"))) throw new AssertionError();
            if (args.length == 3) {
                OptionalFile.download(cached, new URL(args[0]), "application/pdf", Long.parseLong(args[1]), args[2]);
                System.out.println("Verified published PDF and cached " + cached.length() + " bytes");
            }
            System.out.println("Passed optional resource format, length, origin, digest, cancellation and atomic cache checks.");
        } finally {
            Files.deleteIfExists(cached.toPath());
            Files.deleteIfExists(dir.toPath());
        }
    }
}
