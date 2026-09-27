package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the cloud-support cache epoch.
 *
 * Wrong "no cloud" verdicts were persisted to steam_prefs["cloud_support_<appId>"]. A cache epoch
 * makes previously-stored verdicts be ignored/refreshed, so an already-mis-marked game is fixed
 * without the user clearing app data.
 */
public class CloudSupportEpochGuardTest {

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
    public void epochKeyAndConstantExist() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamPrefs.kt");
        assertTrue("must define a cloud-support epoch key", src.contains("cloud_support_epoch"));
        assertTrue("must define a current epoch constant", src.contains("CLOUD_SUPPORT_EPOCH"));
    }

    @Test
    public void getCloudSupportCachedConsultsTheEpoch() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamPrefs.kt");
        int get = src.indexOf("fun getCloudSupportCached(");
        assertTrue("getCloudSupportCached must exist", get >= 0);
        int end = src.indexOf("fun setCloudSupportCached(", get);
        assertTrue("setCloudSupportCached must follow", end > get);
        String getBody = src.substring(get, end);
        assertTrue("getCloudSupportCached must read the stored epoch and return null when stale",
                getBody.contains("CLOUD_SUPPORT_EPOCH") && getBody.contains("return null"));
    }

    @Test
    public void setCloudSupportCachedStampsTheEpoch() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamPrefs.kt");
        int set = src.indexOf("fun setCloudSupportCached(");
        assertTrue("setCloudSupportCached must exist", set >= 0);
        String setBody = src.substring(set, Math.min(src.length(), set + 400));
        assertTrue("setCloudSupportCached must write the current epoch",
                setBody.contains("CLOUD_SUPPORT_EPOCH"));
    }
}
