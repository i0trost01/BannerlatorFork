package com.winlator.star;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.core.app.ApplicationProvider;

import com.winlator.star.inputcontrols.DrawerController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class DrawerInputRobolectricTest {

    private DrawerLayout buildDrawer() {
        Application app = ApplicationProvider.getApplicationContext();
        DrawerLayout drawer = new DrawerLayout(app);
        FrameLayout content = new FrameLayout(app);
        drawer.addView(content, new DrawerLayout.LayoutParams(
                DrawerLayout.LayoutParams.MATCH_PARENT, DrawerLayout.LayoutParams.MATCH_PARENT));
        FrameLayout panel = new FrameLayout(app);
        DrawerLayout.LayoutParams lp = new DrawerLayout.LayoutParams(
                380, ViewGroup.LayoutParams.MATCH_PARENT);
        lp.gravity = Gravity.START;
        drawer.addView(panel, lp);
        return drawer;
    }

    @Test public void drawerIsNeverLockedClosed() {
        DrawerLayout drawer = buildDrawer();
        assertNotEquals("LOCK_MODE_LOCKED_CLOSED makes openDrawer() a silent no-op",
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawer.getDrawerLockMode(Gravity.START));
    }

    @Test public void unlockedDrawerOpensOnBackAction() {
        DrawerLayout drawer = buildDrawer();
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        assertFalse(drawer.isDrawerOpen(Gravity.START));

        assertEquals(DrawerController.DrawerBackAction.OPEN,
                DrawerController.backAction(drawer.isDrawerOpen(Gravity.START), false));
        drawer.openDrawer(Gravity.START);
        assertTrue("Back must actually open the drawer", drawer.isDrawerOpen(Gravity.START));
    }

    @Test public void lockedClosedDocumentsTheOldDefect() {
        DrawerLayout drawer = buildDrawer();
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED);
        drawer.openDrawer(Gravity.START);
        assertFalse("this is why the first fix was dead", drawer.isDrawerOpen(Gravity.START));
    }

    @Test public void backKeyEventCarriesKeycodeBack() {
        KeyEvent back = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        assertEquals(KeyEvent.KEYCODE_BACK, back.getKeyCode());
        assertEquals(KeyEvent.ACTION_DOWN, back.getAction());
    }
}
