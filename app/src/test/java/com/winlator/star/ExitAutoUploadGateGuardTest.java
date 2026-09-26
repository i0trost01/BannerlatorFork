package com.winlator.star;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the auto-upload-on-exit fix.
 *
 * The Save Manager toggle "Steam games: auto-upload to cloud on exit" persists
 * auto_upload_steam_on_exit in save_manager_prefs. The exit path used to AND it with a separate
 * steam_prefs cloud_saves_disclaimer_accepted flag that only a game-detail-page action could set,
 * so the toggle was silently ignored. The exit condition must now be gated by the toggle alone,
 * while the disclaimer flag must remain in the tree (it still gates the detail-page cloud actions).
 */
public class ExitAutoUploadGateGuardTest {

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

    /** Slice out the `if (isGenuineSteamShortcut()) { ... }` block from exit(). */
    private static String steamExitBlock(String src) {
        int marker = src.indexOf("if (isGenuineSteamShortcut()) {");
        assertTrue("exit() must still branch on isGenuineSteamShortcut()", marker >= 0);
        int end = src.indexOf("} else {", marker);
        assertTrue("could not delimit the genuine-Steam exit block", end > marker);
        return src.substring(marker, end);
    }

    @Test
    public void exitAutoUploadIsGatedOnlyByTheToggle() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "XServerDisplayActivity.java");
        String block = steamExitBlock(src);

        assertFalse("exit auto-upload must NOT be gated by cloud_saves_disclaimer_accepted",
                block.contains("cloud_saves_disclaimer_accepted"));
        assertFalse("the cloudDisclaimerOk boolean must be gone from the exit block",
                block.contains("cloudDisclaimerOk"));
        assertTrue("exit auto-upload must still read the Save Manager toggle",
                block.contains("auto_upload_steam_on_exit"));
        assertTrue("exit auto-upload must still call autoUploadSteamSavesBlocking()",
                block.contains("autoUploadSteamSavesBlocking()"));
    }

    @Test
    public void disclaimerFlagStillExistsForTheDetailPagePath() throws IOException {
        String activity = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamGameDetailActivity.kt");
        assertTrue("the game-detail-page disclaimer flag must remain (it gates detail-page cloud actions)",
                activity.contains("cloud_saves_disclaimer_accepted"));
    }
}
