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
 * Guard for the upload protocol fix (Steam Cloud uploads never persisted).
 *
 * Three differences from the working WinNative upload: JavaSteam sent canEncrypt=true (default) and
 * ignored encrypt_file; the commitFileUpload boolean was discarded; and a batch uploaded files
 * concurrently. These guards pin the corrected call shape so a revert fails.
 */
public class UploadProtocolGuardTest {

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
    public void javaSteamUploadDisablesEncryption() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudBackend.kt");
        assertTrue("beginFileUpload must pass canEncrypt = false (WinNative parity)",
                src.contains("canEncrypt = false"));
    }

    @Test
    public void javaSteamUploadHonorsTheCommitResult() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudBackend.kt");
        assertTrue("commitFileUpload's boolean must be captured",
                src.contains("val committed"));
        assertFalse("must not discard the commit result by returning the block-PUT ok alone",
                src.contains("sc.commitFileUpload(ok, appId, sha, cloudPath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)\n        return ok"));
        assertTrue("uploadOne must fold the commit result into its return",
                src.contains("return ok && committed"));
    }
}
