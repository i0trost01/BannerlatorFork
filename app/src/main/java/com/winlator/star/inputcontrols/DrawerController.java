package com.winlator.star.inputcontrols;

/**
 * Pure decision logic for the in-game drawer's Back/B input. No Android imports: every rule the
 * drawer follows is a static function of plain booleans and key codes, so it runs (and is tested)
 * on the JVM. XServerDisplayActivity is a thin adapter that performs the side effects these
 * decisions describe. Back opens/closes the drawer; B is an ordinary game button while the drawer
 * is closed and only ever closes it while open; B never opens it.
 */
public final class DrawerController {
    public static final int KEYCODE_BUTTON_B = 97;

    public enum DrawerBackAction { OPEN, CLOSE, NONE }
    public enum DrawerMenuAction { CLOSE_DRAWER, ROUTE_TO_DRAWER, PASS_THROUGH }

    private DrawerController() {}

    public static DrawerBackAction backAction(boolean drawerOpen, boolean editorActive) {
        if (editorActive) return DrawerBackAction.NONE;
        return drawerOpen ? DrawerBackAction.CLOSE : DrawerBackAction.OPEN;
    }

    public static boolean isDrawerButton(int keyCode) {
        return keyCode == KEYCODE_BUTTON_B;
    }

    public static DrawerMenuAction menuAction(int keyCode, boolean down, boolean drawerOpen, boolean editorActive) {
        if (editorActive || !drawerOpen) return DrawerMenuAction.PASS_THROUGH;
        if (isDrawerButton(keyCode)) {
            return down ? DrawerMenuAction.CLOSE_DRAWER : DrawerMenuAction.ROUTE_TO_DRAWER;
        }
        return DrawerMenuAction.ROUTE_TO_DRAWER;
    }
}
