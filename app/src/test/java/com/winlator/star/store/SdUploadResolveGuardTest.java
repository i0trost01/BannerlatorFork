package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the SD-card cloud-upload fix.
 *
 * Upload (Collect) resolves the container via SteamCloudSaveManager.resolveShortcut, which used to
 * contain only the imagefs string-match fallback. An exec addressed through a drive letter
 * (F:\steam_games\...) never matches an absolute SD install path that way, so uploads failed with
 * "This game isn't set up in a container yet" while downloads (which use the full
 * SteamCloudSavePaths.resolveContainer) worked. This guard asserts the drive-map branch is present.
 */
public class SdUploadResolveGuardTest {

    private static String read(String... segments) throws IOException {
        Path root = Paths.get("").toAbsolutePath();
        // Walk up until we find the app/src directory (tests run from the module or repo root).
        Path dir = root;
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve(Paths.get("app", "src", "main", "java"));
            if (Files.isDirectory(candidate)) {
                Path p = candidate;
                for (String s : segments) p = p.resolve(s);
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IOException("Could not locate app/src/main/java under " + root);
    }

    @Test
    public void resolveShortcutUsesDriveMapBranch() throws IOException {
        String src = read("com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        assertTrue(
                "resolveShortcut must resolve an exec through the container drive map "
                        + "(WinePath.resolveAndroidPath) so off-imagefs / SD-card games resolve on upload",
                src.contains("WinePath.resolveAndroidPath"));
    }

    @Test
    public void resolveShortcutStillHasImagefsFallback() throws IOException {
        String src = read("com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        assertTrue(
                "resolveShortcut must keep the imagefs string-match fallback for internal (Z:) games",
                src.contains("keys.any"));
    }
}
