package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard: an upload that has nothing to do (or uploads partially) must NOT report an error.
 *
 * The only errors uploadSaves may raise are transport/auth/exception: not signed in, a refused batch,
 * or a thrown exception. Every client-side no-op outcome is a plain success summary.
 */
public class NoErrorUploadGuardTest {

    private static String readRepoFile(String... segments) throws IOException {
        Path root = Paths.get("").toAbsolutePath();
        Path dir = root;
        for (int i = 0; i < 5 && dir != null; i++) {
            Path app = dir.resolve("app");
            if (Files.isDirectory(app)) {
                Path p = app;
                for (String s : segments) p = p.resolve(s);
                if (Files.isRegularFile(p)) {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParent();
        }
        throw new IOException("Could not locate app/ under " + root);
    }

    private static String body(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("source must contain: " + signature, start >= 0);
        int open = src.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return src.substring(open, i + 1); }
        }
        throw new AssertionError("unterminated body for " + signature);
    }

    @Test
    public void uploadNoOpOutcomesAreSuccessNotError() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        // No client-side no-op may call onError with a "nothing/partial" style message.
        assertTrue("must not error with 'no file reached Steam Cloud'", !b.contains("no file reached Steam Cloud"));
        assertTrue("must not error with 'some files failed'", !b.contains("some files failed"));
        // The no-retention message must no longer be delivered via onError.
        assertTrue("no-retention must not be an onError", !b.contains("cb.onError(NO_RETENTION_MESSAGE)"));
        assertTrue("GUARD 1 must not error", !b.contains("cb.onError(NO_CLOUD_MESSAGE)"));
    }

    @Test
    public void transportAndExceptionErrorsRemain() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("must keep the not-signed-in error", b.contains("Not signed in"));
        assertTrue("must keep the refused-batch error", b.contains("refused to open a cloud upload batch"));
        assertTrue("must keep the exception error", b.contains("Upload error:"));
    }
}
