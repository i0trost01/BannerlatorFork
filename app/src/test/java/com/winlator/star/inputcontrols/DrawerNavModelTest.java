package com.winlator.star.inputcontrols;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class DrawerNavModelTest {

    private static final int DPAD_UP = 19;
    private static final int DPAD_DOWN = 20;
    private static final int DPAD_LEFT = 21;
    private static final int DPAD_RIGHT = 22;
    private static final int BUTTON_A = 96;
    private static final int BUTTON_B = 97;

    @Test public void dpadUpIsMinusOneOnTheRail() {
        assertEquals(-1, DrawerNavModel.railIndexDelta(DPAD_UP));
    }

    @Test public void dpadDownIsPlusOneOnTheRail() {
        assertEquals(1, DrawerNavModel.railIndexDelta(DPAD_DOWN));
    }

    @Test public void horizontalDpadDoesNotMoveTheRail() {
        assertEquals(0, DrawerNavModel.railIndexDelta(DPAD_LEFT));
        assertEquals(0, DrawerNavModel.railIndexDelta(DPAD_RIGHT));
    }

    @Test public void railIndexIsClampedIntoRange() {
        assertEquals(0, DrawerNavModel.clampRailIndex(-3, 5));
        assertEquals(4, DrawerNavModel.clampRailIndex(9, 5));
        assertEquals(2, DrawerNavModel.clampRailIndex(2, 5));
    }

    @Test public void emptyRailClampsToZero() {
        assertEquals(0, DrawerNavModel.clampRailIndex(3, 0));
    }

    @Test public void railNavigationKeysAreRecognised() {
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_UP));
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_DOWN));
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_LEFT));
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_RIGHT));
        assertTrue(DrawerNavModel.isRailNavigationKey(BUTTON_A));
        assertFalse(DrawerNavModel.isRailNavigationKey(BUTTON_B));
    }

    @Test public void activatingOnTheRailEntersThePanel() {
        assertEquals(DrawerNavModel.LEVEL_PANEL, DrawerNavModel.levelAfterActivateAtRail());
    }

    @Test public void backFromThePanelReturnsToTheRail() {
        assertEquals(DrawerNavModel.LEVEL_RAIL, DrawerNavModel.levelAfterBack(DrawerNavModel.LEVEL_PANEL));
    }

    @Test public void backFromTheRailStaysOnTheRail() {
        assertEquals(DrawerNavModel.LEVEL_RAIL, DrawerNavModel.levelAfterBack(DrawerNavModel.LEVEL_RAIL));
    }
}
