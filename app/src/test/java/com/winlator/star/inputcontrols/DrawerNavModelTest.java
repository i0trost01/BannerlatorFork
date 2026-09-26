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
    private static final int DPAD_CENTER = 23;
    private static final int BUTTON_A = 96;
    private static final int BUTTON_B = 97;

    @Test public void dpadUpIsMinusOneOnTheRail() {
        assertEquals(-1, DrawerNavModel.railIndexDelta(DPAD_UP));
    }

    @Test public void dpadDownIsPlusOneOnTheRail() {
        assertEquals(1, DrawerNavModel.railIndexDelta(DPAD_DOWN));
    }

    @Test public void nonVerticalKeysDoNotMoveTheRail() {
        assertEquals(0, DrawerNavModel.railIndexDelta(DPAD_LEFT));
        assertEquals(0, DrawerNavModel.railIndexDelta(DPAD_RIGHT));
        assertEquals(0, DrawerNavModel.railIndexDelta(BUTTON_B));
    }

    @Test public void railIndexIsClampedIntoRange() {
        assertEquals(0, DrawerNavModel.clampRailIndex(-3, 5));
        assertEquals(4, DrawerNavModel.clampRailIndex(9, 5));
        assertEquals(2, DrawerNavModel.clampRailIndex(2, 5));
    }

    @Test public void emptyOrNegativeRailSizeClampsToZero() {
        assertEquals(0, DrawerNavModel.clampRailIndex(3, 0));
        assertEquals(0, DrawerNavModel.clampRailIndex(3, -5));
    }

    @Test public void railNavigationKeysAreRecognised() {
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_UP));
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_DOWN));
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_LEFT));
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_RIGHT));
        assertTrue(DrawerNavModel.isRailNavigationKey(DPAD_CENTER));
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

    @Test public void upOnTheFirstRowLeavesThePanel() {
        assertEquals(DrawerNavModel.PanelMove.UP_FROM_FIRST_ROW,
                DrawerNavModel.panelMove(DPAD_UP, 0, 3, 0, 2));
    }

    @Test public void upInsideTheGridMovesUp() {
        assertEquals(DrawerNavModel.PanelMove.UP,
                DrawerNavModel.panelMove(DPAD_UP, 2, 3, 1, 2));
    }

    @Test public void downAtTheLastRowIsNone() {
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_DOWN, 2, 3, 0, 2));
    }

    @Test public void downInsideTheGridMovesDown() {
        assertEquals(DrawerNavModel.PanelMove.DOWN,
                DrawerNavModel.panelMove(DPAD_DOWN, 0, 3, 0, 2));
    }

    @Test public void leftAtTheFirstColumnIsNone() {
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_LEFT, 0, 3, 0, 2));
    }

    @Test public void leftInsideARowMovesLeft() {
        assertEquals(DrawerNavModel.PanelMove.LEFT,
                DrawerNavModel.panelMove(DPAD_LEFT, 0, 3, 1, 2));
    }

    @Test public void rightAtTheLastColumnIsNone() {
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_RIGHT, 0, 3, 1, 2));
    }

    @Test public void rightInsideARowMovesRight() {
        assertEquals(DrawerNavModel.PanelMove.RIGHT,
                DrawerNavModel.panelMove(DPAD_RIGHT, 0, 3, 0, 2));
    }

    @Test public void emptyPanelSwallowsDirectionalKeys() {
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_DOWN, 0, 0, 0, 0));
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_UP, 0, 0, 0, 0));
    }

    @Test public void singleColumnSwallowsHorizontalKeysEvenOutOfRange() {
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_LEFT, 0, 1, 2, 1));
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_RIGHT, 0, 1, 2, 1));
    }

    @Test public void nonDirectionalKeyDoesNotMoveThePanel() {
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(BUTTON_A, 0, 3, 1, 2));
    }

    @Test public void negativeRowCountSwallowsDirectionalKeys() {
        assertEquals(DrawerNavModel.PanelMove.NONE,
                DrawerNavModel.panelMove(DPAD_DOWN, 0, -1, 0, 2));
    }
}
