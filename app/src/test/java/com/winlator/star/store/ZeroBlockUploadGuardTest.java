package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard: a zero-block cloud upload is a dedup SUCCESS, not a failure.
 *
 * Steam returns no blocks ("blocks=0") when it already holds the file's content; the commit then
 * returns file_committed=false because there is nothing new to store. Reporting that as FAILED made
 * already-present saves look like failed uploads (device log: Cuphead app 268910, 5 of 6 files).
 */
public class ZeroBlockUploadGuardTest {

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
    public void zeroBlockUploadReturnsSuccess() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "blsteam", "BlSteamSession.kt");
        int zero = src.indexOf("cloud upload short-circuit");
        assertTrue("the zero-block short-circuit must exist", zero >= 0);
        // The short-circuit block must end in a success return (true), not the commit result.
        int ret = src.indexOf("return true", zero);
        assertTrue("zero-block path must return true (dedup success)", ret > zero && ret < zero + 1200);
    }
}
