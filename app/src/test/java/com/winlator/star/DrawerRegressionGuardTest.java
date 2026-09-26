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
                src.contains("DrawerController.menuActionAtLevel("));
    }

    @Test public void navBridgeExposesTheAgreedSurface() throws Exception {
        File f = new File("src/main/java/com/winlator/star/ui/DrawerNavBridge.kt");
        if (!f.isFile()) f = new File("app/src/main/java/com/winlator/star/ui/DrawerNavBridge.kt");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        for (String member : new String[]{
                "var level by", "var railIndex", "var panelRow by", "var panelCol by",
                "var panelRowCount", "var panelColCount", "var railCount", "var activateSignal",
                "var lastActivatedLevel",
                "fun resetOnOpen", "fun moveRail", "fun railToPanel", "fun panelToRail",
                "fun movePanel", "fun activate"}) {
            assertTrue("DrawerNavBridge must expose " + member, src.contains(member));
        }
    }

    @Test public void activityDrivesTheNavBridge() throws Exception {
        String src = activitySource();
        assertTrue("the Activity must drive the drawer nav bridge",
                src.contains("DrawerNavBridge.INSTANCE"));
        assertTrue("the Activity must use the level-aware menu action",
                src.contains("menuActionAtLevel("));
        assertTrue("the bridge must be reset when the drawer opens", src.contains("resetOnOpen()"));
    }

    @Test public void backStillOpensThroughTheExistingPath() throws Exception {
        String src = activitySource();
        assertTrue("Back-to-open must keep going through backAction (P1)",
                src.contains("DrawerController.backAction("));
        assertFalse("KEYCODE_BACK must NOT be redirected into the controller menu handler",
                src.contains("handleControllerMenuKey(KeyEvent.KEYCODE_BACK"));
    }

    @Test public void drawerReadsTheNavBridge() throws Exception {
        File f = new File("src/main/java/com/winlator/star/ui/XServerDrawer.kt");
        if (!f.isFile()) f = new File("app/src/main/java/com/winlator/star/ui/XServerDrawer.kt");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue("the drawer must read the nav bridge", src.contains("DrawerNavBridge"));
        assertTrue("the drawer must report its rail count", src.contains("railCount"));
        assertTrue("the drawer must react to the activation signal", src.contains("activateSignal"));
    }
}
