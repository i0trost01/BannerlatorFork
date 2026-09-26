package com.winlator.star.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * A DrawerLayout that never opens on a touch edge drag.
 *
 * The in-game drawer must only open programmatically (controller Back via
 * OpenXServerDrawerState / handleNavigationBackPressed, and in-app buttons that call
 * openDrawer(GravityCompat.START)). A left-to-right swipe used to open it, which the user does not
 * want. Blocking the lock mode is NOT an option: LOCK_MODE_LOCKED_CLOSED makes openDrawer() a no-op
 * (the fork.18 regression that killed Back), so the drawer must stay UNLOCKED.
 *
 * Instead we veto only the TOUCH path: onInterceptTouchEvent/onTouchEvent always report "not
 * handled", so no edge drag is ever recognized. Because the drawer is laid out and opened/closed
 * through openDrawer()/closeDrawers()/isDrawerOpen() — never through touch — programmatic control
 * is completely unaffected.
 */
public class NoEdgeSwipeDrawerLayout extends DrawerLayout {

    public NoEdgeSwipeDrawerLayout(@NonNull Context context) {
        super(context);
    }

    public NoEdgeSwipeDrawerLayout(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public NoEdgeSwipeDrawerLayout(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    /**
     * Never claim a touch stream: this removes the edge-drag recognition entirely while leaving the
     * programmatic open/close API intact. Returning false lets child views (the Compose drawer
     * content, the game surface) keep receiving their own touches.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        return false;
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        return false;
    }
}
