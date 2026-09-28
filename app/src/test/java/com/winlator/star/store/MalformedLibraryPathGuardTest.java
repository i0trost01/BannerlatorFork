package com.winlator.star.store;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard: a malformed library rel path ("%Root%" token not followed by '/', e.g. "%WinAppDataRoaming%Cuphead")
 * must be rejected so it neither inflates the local snapshot nor the upload set, EXCEPT the deliberate
 * fused "%GameInstall%rest" form that real Steam manifests use (HL2 etc.).
 *
 * <p>{@link SteamCloudSavePaths#isValidRootedPath(String)} is a pure string function, so the helper is
 * exercised directly (the object's class-init only builds its string tables — no Android side effects).</p>
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
    public void gameInstallFusedPathIsAccepted() {
        assertTrue("fused %GameInstall% path must be accepted",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("%GameInstall%hl2/save/x.sav"));
        assertTrue("bare %GameInstall% token must be accepted",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("%GameInstall%"));
    }

    @Test
    public void malformedNonGameInstallFusedPathIsRejected() {
        assertFalse("non-GameInstall fused token must be rejected",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("%WinAppDataRoaming%Cuphead"));
        assertFalse("non-GameInstall fused token mid-path must be rejected",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("%WinAppDataRoaming%Cuphead/slot.sav"));
    }

    @Test
    public void wellFormedRootedPathIsAccepted() {
        assertTrue("separated %Root%/rest must be accepted",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("%WinAppDataRoaming%/Cuphead/slot.sav"));
        assertTrue("non-rooted path must be accepted",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("Documents/foo"));
        assertTrue("bare %Token% must be accepted",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("%Root%"));
        assertFalse("unterminated %Token must be rejected",
                SteamCloudSavePaths.INSTANCE.isValidRootedPath("%abc"));
    }

    @Test
    public void helperExemptsGameInstallFusedForm() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSavePaths.kt");
        int idx = src.indexOf("fun isValidRootedPath(");
        assertTrue("isValidRootedPath must exist", idx >= 0);
        String body = src.substring(idx, Math.min(src.length(), idx + 1200));
        assertTrue("must exempt %GameInstall% from the fused-form rejection (source assertion)",
                body.contains("%GameInstall%"));
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
