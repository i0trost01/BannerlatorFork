package com.winlator.star;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for front-end (Daijisho/Beacon/ES-DE) game launching.
 *
 * A front end starts the app with `-a android.intent.action.VIEW -d {file.uri}`. For the intent to
 * land on MainActivity (which forwards it to the session activity), MainActivity must declare a VIEW
 * intent-filter with file/content data schemes, and its forward must carry the shortcut identity
 * extras. Both launch routes (URI and shortcut_path extra) must keep working.
 */
public class FrontendLaunchGuardTest {

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

    /** Slice the MainActivity <activity ...> ... </activity> block out of the manifest. */
    private static String mainActivityBlock(String manifest) {
        int start = manifest.indexOf("android:name=\"com.winlator.star.MainActivity\"");
        assertTrue("manifest must declare MainActivity", start >= 0);
        int open = manifest.lastIndexOf('<', start);
        int close = manifest.indexOf("</activity>", start);
        assertTrue("could not delimit the MainActivity block", open >= 0 && close > start);
        return manifest.substring(open, close);
    }

    @Test
    public void mainActivityDeclaresViewFilterForDesktopUris() throws IOException {
        String manifest = readRepoFile("src", "main", "AndroidManifest.xml");
        String block = mainActivityBlock(manifest);

        assertTrue("MainActivity must declare the VIEW action",
                block.contains("android:name=\"android.intent.action.VIEW\""));
        assertTrue("MainActivity's VIEW filter must accept file scheme",
                block.contains("android:scheme=\"file\""));
        assertTrue("MainActivity's VIEW filter must accept content scheme",
                block.contains("android:scheme=\"content\""));
        assertTrue("MainActivity must match exported .desktop files by pathPattern",
                block.contains("android:pathPattern=\".*\\\\.desktop\""));
        assertTrue("MainActivity must keep its MAIN/LAUNCHER filter",
                block.contains("android.intent.category.LAUNCHER"));
    }

    @Test
    public void forwardCarriesShortcutIdentityExtras() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "MainActivity.kt");

        assertTrue("forward must still set shortcut_path", src.contains("putExtra(\"shortcut_path\""));
        assertTrue("forward must still set container_id", src.contains("putExtra(\"container_id\""));
        assertTrue("forward must now set shortcut_name", src.contains("putExtra(\"shortcut_name\""));
        assertTrue("forward must now set shortcut_uuid", src.contains("putExtra(\"shortcut_uuid\""));
    }

    @Test
    public void bothLaunchRoutesRemain() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "MainActivity.kt");

        assertTrue("URI route (resolveIncomingDesktopPath) must remain", src.contains("resolveIncomingDesktopPath("));
        assertTrue("path-extra route must remain", src.contains("getStringExtra(\"shortcut_path\")"));
    }
}
