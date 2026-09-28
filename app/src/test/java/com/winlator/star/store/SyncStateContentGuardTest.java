package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard: a successful upload must clear the stuck "local is ahead" state.
 *
 * The stuck state came from comparing raw mtimes (newestLocalMtime > lastSync) against a wall-clock
 * lastUploadAt. The fix keeps two SEPARATE stamps: recordAfterUpload writes localBaselineMtime with
 * the NEWEST LOCAL MTIME (so after upload newestLocalMtime == localSyncedThrough) while lastUploadAt
 * keeps the REAL wall-clock upload time (the Save Manager displays it as "Uploaded N"). Overloading
 * lastUploadAt with the mtime made no-op re-uploads show a misleading/future time. There is no
 * content-hash tie-breaker: the Library and cloud hashes are computed over different schemes and can
 * never match.
 */
public class SyncStateContentGuardTest {

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
    public void recordAfterUploadStampsNewestLocalMtime() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SaveSyncStore.kt");
        int idx = src.indexOf("fun recordAfterUpload(");
        assertTrue("recordAfterUpload must exist", idx >= 0);
        String b = src.substring(idx, Math.min(src.length(), idx + 700));
        assertTrue("must stamp localBaselineMtime with the newest local mtime",
                b.contains("localBaselineMtime") && (b.contains("newestLocalMtime") || b.contains("libraryNewestMtime")));
        assertTrue("must restore lastUploadAt to the real wall-clock upload time",
                b.contains("lastUploadAt") && b.contains("System.currentTimeMillis()"));
    }
}
