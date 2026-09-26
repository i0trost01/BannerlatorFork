package com.winlator.star.inputcontrols;

/**
 * Pure decision logic for the in-game drawer's Back/B input. No Android imports: every rule the
 * drawer follows is a static function of plain booleans and key codes, so it runs (and is tested)
 * on the JVM. XServerDisplayActivity is a thin adapter that performs the side effects these
 * decisions describe. Back opens/closes the drawer; B is an ordinary game button while the drawer
 * is closed and only ever closes it while open; B never opens it. While the drawer is open at panel
 * level, {@code menuActionAtLevel} instead has B step the highlight from the panel back up to the rail.
 */
public final class DrawerController {
    public static final int KEYCODE_BUTTON_B = 97;

    public enum DrawerBackAction { OPEN, CLOSE, NONE }
    public enum DrawerMenuAction { CLOSE_DRAWER, ROUTE_TO_DRAWER, PASS_THROUGH, PANEL_TO_RAIL }

    private DrawerController() {}

    public static DrawerBackAction backAction(boolean drawerOpen, boolean editorActive) {
        if (editorActive) return DrawerBackAction.NONE;
        return drawerOpen ? DrawerBackAction.CLOSE : DrawerBackAction.OPEN;
    }

    public static boolean isDrawerButton(int keyCode) {
        return keyCode == KEYCODE_BUTTON_B;
    }

    public static DrawerMenuAction menuAction(int keyCode, boolean down, boolean drawerOpen, boolean editorActive) {
        return menuActionAtLevel(keyCode, down, drawerOpen, editorActive, DrawerNavModel.LEVEL_RAIL);
    }

    public static DrawerMenuAction menuActionAtLevel(int keyCode, boolean down, boolean drawerOpen,
                                                     boolean editorActive, int level) {
        if (editorActive || !drawerOpen) return DrawerMenuAction.PASS_THROUGH;
        if (isDrawerButton(keyCode)) {
            if (down && level == DrawerNavModel.LEVEL_PANEL) return DrawerMenuAction.PANEL_TO_RAIL;
            return down ? DrawerMenuAction.CLOSE_DRAWER : DrawerMenuAction.ROUTE_TO_DRAWER;
        }
        return DrawerMenuAction.ROUTE_TO_DRAWER;
    }
}
