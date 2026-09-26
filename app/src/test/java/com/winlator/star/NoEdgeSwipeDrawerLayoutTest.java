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

    /**
     * Assert the override exists AND its body refuses the touch (return false) while the drawer is
     * closed, then delegates to super while it is open. The signature is matched in its real source
     * form so that deleting the override, or replacing the body with a bare `return false;` or a
     * bare `return super....`, fails the guard.
     */
    private static void assertBlocksOnlyWhileClosed(String src, String method) {
        String signature = "public boolean " + method + "(MotionEvent ev)";
        int sig = src.indexOf(signature);
        assertTrue("subclass must override " + method, sig >= 0);
        int bodyEnd = src.indexOf('}', sig);
        assertTrue("could not delimit the body of " + method, bodyEnd > sig);
        String body = src.substring(sig, bodyEnd);

        assertTrue(method + " must return false only while the drawer is CLOSED, via "
                        + "`if (!isDrawerOpen(GravityCompat.START)) return false;`",
                body.contains("if (!isDrawerOpen(GravityCompat.START)) return false;"));
        assertTrue(method + " must return false while the drawer is closed",
                body.contains("return false"));
        assertTrue(method + " must delegate to super while the drawer is open",
                body.contains("return super." + method + "(ev);"));
    }

    @Test
    public void subclassBlocksEdgeDragOnlyWhileClosed() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "widget", "NoEdgeSwipeDrawerLayout.java");
        assertBlocksOnlyWhileClosed(src, "onInterceptTouchEvent");
        assertBlocksOnlyWhileClosed(src, "onTouchEvent");
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
