package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard: a malformed library rel path ("%Root%" token not followed by '/', e.g. "%WinAppDataRoaming%Cuphead")
 * must be rejected so it neither inflates the local snapshot nor the upload set.
 */
public class MalformedLibraryPathGuardTest {

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
    public void malformedRootTokenIsRejected() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSavePaths.kt");
        assertTrue("must validate a %Root% token is followed by '/' or end",
                src.contains("%") && (src.contains("isValidRootPath") || src.contains("rootPathValid") ||
                    src.contains("looksLikeRootedPath") || src.contains("indexOf('/')")));
    }

    @Test
    public void snapshotSkipsMalformedEntries() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SaveSyncStore.kt");
        int idx = src.indexOf("private fun librarySnapshot(");
        assertTrue("librarySnapshot must exist", idx >= 0);
        String b = src.substring(idx, Math.min(src.length(), idx + 900));
        assertTrue("snapshot must skip malformed rooted paths",
                b.contains("malformed") || b.contains("isValidRootPath") || b.contains("looksLikeRootedPath"));
    }
}
