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
 * Guard for removing the in-game drawer's left-edge swipe.
 *
 * The drawer must no longer open on a touch edge drag, while programmatic opens
 * (drawerLayout.openDrawer(GravityCompat.START)) must keep working. The previous attempt at this
 * used DrawerLayout.LOCK_MODE_LOCKED_CLOSED, which makes openDrawer() a no-op and silently killed
 * the controller Back button — so this guard also asserts the lock mode is NOT locked-closed.
 */
public class NoEdgeSwipeDrawerLayoutTest {

    private static String readRepoFile(String... segments) throws IOException {
        Path root = Paths.get("").toAbsolutePath();
        Path dir = root;
        for (int i = 0; i < 5 && dir != null; i++) {
            Path app = dir.resolve("app");
            if (Files.isDirectory(app)) {
                Path p = app;
                for (String s : segments) p = p.resolve(s);
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IOException("Could not locate app/ under " + root);
    }

    @Test
    public void subclassOverridesTouchInterception() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "widget", "NoEdgeSwipeDrawerLayout.java");
        assertTrue("subclass must override onInterceptTouchEvent to block edge drags",
                src.contains("onInterceptTouchEvent"));
    }

    @Test
    public void inGameLayoutUsesTheSubclass() throws IOException {
        String xml = readRepoFile("src", "main", "res", "layout", "xserver_display_activity.xml");
        assertTrue("in-game layout must use NoEdgeSwipeDrawerLayout",
                xml.contains("com.winlator.star.widget.NoEdgeSwipeDrawerLayout"));
        assertFalse("in-game layout must no longer use the plain framework DrawerLayout tag",
                xml.contains("<androidx.drawerlayout.widget.DrawerLayout"));
    }

    @Test
    public void lockModeIsNotLockedClosed() throws IOException {
        String activity = readRepoFile("src", "main", "java", "com", "winlator", "star", "XServerDisplayActivity.java");
        assertFalse("drawer must not be locked closed — that makes openDrawer() a no-op (fork.18 bug)",
                activity.contains("setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)"));
        assertTrue("drawer must stay unlocked for programmatic opens",
                activity.contains("setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)"));
    }
}
