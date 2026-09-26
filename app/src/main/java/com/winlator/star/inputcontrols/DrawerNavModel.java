package com.winlator.star.inputcontrols;

/**
 * Pure navigation rules for the in-game drawer's gamepad control. No Android imports: every rule is
 * a static function of plain integers and booleans, so it runs (and is tested) on the JVM.
 * XServerDisplayActivity and the drawer Composable are thin adapters.
 *
 * Two levels: the left icon rail (RAIL) and the selected tab's panel (PANEL). D-pad moves the
 * highlight, A activates it (and steps rail -> panel), B steps back one level. Back-to-open and
 * B-never-opens are NOT modelled here: those already work and are handled elsewhere.
 */
public final class DrawerNavModel {

    public static final int LEVEL_RAIL = 0;
    public static final int LEVEL_PANEL = 1;

    public static final int KEYCODE_DPAD_UP = 19;
    public static final int KEYCODE_DPAD_DOWN = 20;
    public static final int KEYCODE_DPAD_LEFT = 21;
    public static final int KEYCODE_DPAD_RIGHT = 22;
    public static final int KEYCODE_DPAD_CENTER = 23;
    public static final int KEYCODE_BUTTON_A = 96;
    public static final int KEYCODE_BUTTON_B = 97;

    private DrawerNavModel() {}

    /** D-pad Up/Down steps the rail highlight; anything else leaves it alone. */
    public static int railIndexDelta(int keyCode) {
        if (keyCode == KEYCODE_DPAD_UP) return -1;
        if (keyCode == KEYCODE_DPAD_DOWN) return 1;
        return 0;
    }

    public static int clampRailIndex(int index, int railSize) {
        int max = railSize <= 0 ? 0 : railSize - 1;
        if (index < 0) return 0;
        if (index > max) return max;
        return index;
    }

    /** Keys the rail consumes while it holds the highlight. B is deliberately excluded. */
    public static boolean isRailNavigationKey(int keyCode) {
        return keyCode == KEYCODE_DPAD_UP || keyCode == KEYCODE_DPAD_DOWN
                || keyCode == KEYCODE_DPAD_LEFT || keyCode == KEYCODE_DPAD_RIGHT
                || keyCode == KEYCODE_BUTTON_A || keyCode == KEYCODE_DPAD_CENTER;
    }

    /** A on the rail selects the tab and descends into the panel. */
    public static int levelAfterActivateAtRail() {
        return LEVEL_PANEL;
    }

    /** B/Back goes up exactly one level; from the rail it stays on the rail (the caller closes). */
    public static int levelAfterBack(int level) {
        return LEVEL_RAIL;
    }
}
