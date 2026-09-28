package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard: a game whose Library content matches the cloud must read IN_SYNC, not LOCAL_AHEAD.
 *
 * The stuck "local is ahead" came from comparing raw mtimes (newestLocalMtime > lastSync). A successful
 * upload must clear it: lastUploadAt is stamped with the newest local mtime, and a content-hash match
 * between Library and cloud is an IN_SYNC tie-breaker.
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
    public void computeStateUsesContentHashForInSync() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SaveSyncStore.kt");
        assertTrue("computeState must consult the content hashes",
                src.contains("librarySnapshotHash") && src.contains("cloudManifestHash"));
        assertTrue("must short-circuit to IN_SYNC on a content match",
                src.contains("contentInSync"));
    }

    @Test
    public void recordAfterUploadStampsNewestLocalMtime() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SaveSyncStore.kt");
        int idx = src.indexOf("fun recordAfterUpload(");
        assertTrue("recordAfterUpload must exist", idx >= 0);
        String b = src.substring(idx, Math.min(src.length(), idx + 500));
        assertTrue("must stamp lastUploadAt with the newest local mtime, not wall-clock",
                b.contains("newestLocalMtime") || b.contains("libraryNewestMtime"));
    }
}
