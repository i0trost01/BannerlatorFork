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
 * Guard for the Steam-Cloud-support heuristic fix.
 *
 * The upload gate used to treat a PICS metadata shape it did not recognize as "no cloud", blocking
 * uploads for Auto-Cloud games (e.g. Death's Gambit: Afterlife, saves in AppData/Local/deathsgambit397)
 * whose ufs block does not present the legacy savefiles entry shape. The heuristic must now accept
 * Auto-Cloud shapes and must return "unknown" (not false) when it cannot prove absence.
 */
public class CloudSupportHeuristicGuardTest {

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

    /** Slice the body of a private/fun function from its signature to its closing brace. */
    private static String body(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("source must contain: " + signature, start >= 0);
        int open = src.indexOf('{', start);
        assertTrue("no opening brace after " + signature, open > start);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        throw new AssertionError("unterminated body for " + signature);
    }

    @Test
    public void heuristicAcceptsPathAndAddpathNotJustRootOrPattern() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "private fun hasUsableSaveFiles(");
        assertTrue("must still accept a non-blank root", b.contains("root"));
        assertTrue("must also accept a non-blank path (Auto-Cloud)", b.contains("path"));
        assertTrue("must also accept addpath (rootoverride shapes)", b.contains("addpath"));
    }

    @Test
    public void heuristicNormalizesDotAndSlashPath() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "private fun hasUsableSaveFiles(");
        assertFalse("must not test a bare \".\" as content", b.contains("value == \".\""));
        assertTrue("must normalize \".\"/\"/\" to empty before blank-checks",
                b.contains("trim('/')"));
    }

    @Test
    public void hasCloudSupportReturnsUnknownWhenUfsBlockUnrecognized() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun hasCloudSupport(");
        assertTrue("must gate the false verdict on an actual ufs block being present",
                b.contains("get(\"ufs\")"));
        assertTrue("must return unknown (null) for an unrecognized/absent ufs block",
                b.contains("ufs.children.isEmpty()"));
        assertTrue("must treat blank quota/maxnumfiles as unrecognized",
                b.contains("isNullOrBlank()"));
        assertTrue("must only cache a verdict computed from the usable-savefiles check",
                b.contains("cloudSupportCache[appId] = ") && b.contains("hasUsableSaveFiles("));
    }
}
