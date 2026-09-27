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
 * Guard for the live-manifest evidence override.
 *
 * A metadata-derived "no cloud" verdict must not block an upload when the live cloud manifest is
 * non-empty - that is direct proof the game has a cloud store (download already relies on it). This
 * is what unbreaks games like Death's Gambit: Afterlife.
 */
public class CloudUploadEvidenceGuardTest {

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

    @Test
    public void uploadDoesNotHardBlockOnAFalseVerdict() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        int upload = src.indexOf("fun uploadSaves(");
        assertTrue("uploadSaves must exist", upload >= 0);
        int next = src.indexOf("fun uploadFromLibrary(", upload);
        if (next < 0) next = src.length();
        String body = src.substring(upload, next);
        assertTrue("upload must consult the live manifest before refusing",
                body.contains("listFiles("));
        assertFalse("upload must not unconditionally return NO_CLOUD_MESSAGE on support == false",
                body.contains("if (support == false) {\n                    cb.onError(NO_CLOUD_MESSAGE)\n                    return@Thread\n                }"));
    }

    @Test
    public void blockingSyncChecksManifestBeforeLocalOnly() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        int fn = src.indexOf("fun syncToCloudBlocking(");
        assertTrue("syncToCloudBlocking must exist", fn >= 0);
        int end = src.indexOf("fun ", fn + 10);
        String body = src.substring(fn, end < 0 ? src.length() : end);
        assertTrue("must consult the live manifest before the local-only summary",
                body.contains("listFiles("));
        assertTrue("must keep the honest local-only summary for a truly empty manifest",
                body.contains("No Steam Cloud support"));
    }
}
