package com.winlator.star.inputcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DrawerControllerTest {

    private static final int BUTTON_B = DrawerController.KEYCODE_BUTTON_B;
    private static final int DPAD_LEFT = 21;
    private static final int DPAD_CENTER = 23;
    private static final int BUTTON_A = 96;
    private static final int BUTTON_MODE = 110;

    @Test public void backOpensWhenDrawerClosed() {
        assertEquals(DrawerController.DrawerBackAction.OPEN,
                DrawerController.backAction(false, false));
    }

    @Test public void backClosesWhenDrawerOpen() {
        assertEquals(DrawerController.DrawerBackAction.CLOSE,
                DrawerController.backAction(true, false));
    }

    @Test public void backIsNONEWhenControlsEditorActive() {
        assertEquals(DrawerController.DrawerBackAction.NONE,
                DrawerController.backAction(false, true));
        assertEquals(DrawerController.DrawerBackAction.NONE,
                DrawerController.backAction(true, true));
    }

    @Test public void bNeverOpensTheDrawer() {
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuAction(BUTTON_B, true, false, false));
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuAction(BUTTON_B, false, false, false));
    }

    @Test public void bClosesTheDrawerWhenOpen() {
        assertEquals(DrawerController.DrawerMenuAction.CLOSE_DRAWER,
                DrawerController.menuAction(BUTTON_B, true, true, false));
    }

    @Test public void bReleaseIsRoutedWhileOpen() {
        assertEquals(DrawerController.DrawerMenuAction.ROUTE_TO_DRAWER,
                DrawerController.menuAction(BUTTON_B, false, true, false));
    }

    @Test public void everythingPassesThroughWhileDrawerClosed() {
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuAction(BUTTON_A, true, false, false));
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuAction(DPAD_LEFT, true, false, false));
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuAction(BUTTON_MODE, true, false, false));
    }

    @Test public void dpadAndAAndModeRouteToDrawerWhileOpen() {
        assertEquals(DrawerController.DrawerMenuAction.ROUTE_TO_DRAWER,
                DrawerController.menuAction(DPAD_LEFT, true, true, false));
        assertEquals(DrawerController.DrawerMenuAction.ROUTE_TO_DRAWER,
                DrawerController.menuAction(BUTTON_A, true, true, false));
        assertEquals(DrawerController.DrawerMenuAction.ROUTE_TO_DRAWER,
                DrawerController.menuAction(DPAD_CENTER, true, true, false));
    }

    @Test public void editorActiveMakesMenuActionPassThrough() {
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuAction(BUTTON_B, true, true, true));
    }

    @Test public void isDrawerButtonOnlyForB() {
        assertTrue(DrawerController.isDrawerButton(BUTTON_B));
        assertFalse(DrawerController.isDrawerButton(BUTTON_A));
        assertFalse(DrawerController.isDrawerButton(DPAD_LEFT));
    }
}
