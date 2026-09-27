package com.winlator.star.store;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the upload "verify what landed" fix.
 *
 * Previously uploadSaves reported success from isCloudManifestEmpty - "is the whole manifest
 * non-empty?" - so a game that already had a cloud file reported "Uploaded N changed" even when the
 * commit did not persist the new bytes. The post-upload decision must now verify each uploaded path's
 * SHA against a fresh manifest, and never claim success for an unverified path.
 */
public class UploadVerifyGuardTest {

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
        assertTrue("no opening brace after " + signature, open > start);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return src.substring(open, i + 1); }
        }
        throw new AssertionError("unterminated body for " + signature);
    }

    @Test
    public void postUploadDecisionVerifiesContentNotMereExistence() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("must verify per-path SHA after upload", b.contains("contentEquals"));
        assertTrue("must reuse the manifest sha map for verification", b.contains("sanitizeRelative("));
        assertTrue("must count unverified paths so success is not claimed blindly", b.contains("unverified"));
    }

    @Test
    public void uploadCapturesEachUploadedFilesSha() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("upload must record the sha of each successfully uploaded file",
                b.contains("SteamCloudBackend.sha1("));
    }

    @Test
    public void emptyManifestStillMarksNoRetention() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("must keep the no-retention message for a completely empty manifest",
                b.contains("NO_RETENTION_MESSAGE"));
        assertTrue("must keep marking no-retention", b.contains("markNoSteamCloud"));
    }
}
