package com.winlator.star;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

import org.junit.Test;

/**
 * JVM-only regression guards for the two causes that made the drawer's Back button do nothing.
 * Robolectric cannot boot on this module's classpath (the app native-loads a .so during framework
 * init), so these assert against the activity's source text instead: each assertion targets the
 * exact anti-pattern that WAS the bug, so reintroducing either bug fails the test.
 */
public class DrawerRegressionGuardTest {

    private String activitySource() throws Exception {
        File f = new File("src/main/java/com/winlator/star/XServerDisplayActivity.java");
        if (!f.isFile()) f = new File("app/src/main/java/com/winlator/star/XServerDisplayActivity.java");
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void drawerMustNeverBeLockedClosed() throws Exception {
        String src = activitySource();
        assertTrue("the drawer lock line must be present", src.contains("setDrawerLockMode("));
        assertFalse("LOCK_MODE_LOCKED_CLOSED makes openDrawer() a silent no-op - cause #1 of the dead Back button",
                src.contains("LOCK_MODE_LOCKED_CLOSED"));
    }

    @Test public void controllerKeysMustReachSuperDispatchKeyEvent() throws Exception {
        String src = activitySource();
        assertFalse("gating super.dispatchKeyEvent behind !isGameController swallowed the pad's KEYCODE_BACK - cause #2",
                src.contains("isGameController(event.getDevice()) && super.dispatchKeyEvent(event)"));
        assertTrue("the fallthrough must return handledByGuest || super.dispatchKeyEvent(event)",
                src.contains("handledByGuest || super.dispatchKeyEvent(event)"));
    }

    @Test public void backHandlerMustNotInlineItsOwnDrawerToggle() throws Exception {
        String src = activitySource();
        assertTrue("handleNavigationBackPressed must delegate to DrawerController",
                src.contains("DrawerController.backAction("));
        assertTrue("handleControllerMenuKey must delegate to DrawerController",
                src.contains("DrawerController.menuAction("));
    }
}
