package com.winlator.star;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;

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

    private DrawerLayout inflateDrawer() {
        return (DrawerLayout) LayoutInflater.from(ApplicationProvider.getApplicationContext())
                .inflate(R.layout.xserver_display_activity, null);
    }

    @Test public void drawerIsNeverLockedClosed() {
        DrawerLayout drawer = inflateDrawer();
        assertNotEquals("LOCK_MODE_LOCKED_CLOSED makes openDrawer() a silent no-op",
                DrawerLayout.LOCK_MODE_LOCKED_CLOSED, drawer.getDrawerLockMode(Gravity.START));
    }

    @Test public void unlockedDrawerOpensOnBackAction() {
        DrawerLayout drawer = inflateDrawer();
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED);
        assertFalse(drawer.isDrawerOpen(Gravity.START));

        assertEquals(DrawerController.DrawerBackAction.OPEN,
                DrawerController.backAction(drawer.isDrawerOpen(Gravity.START), false));
        drawer.openDrawer(Gravity.START);
        assertTrue("Back must actually open the drawer", drawer.isDrawerOpen(Gravity.START));
    }

    @Test public void lockedClosedDocumentsTheOldDefect() {
        DrawerLayout drawer = inflateDrawer();
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
