package com.winlator.star.widget;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.GravityCompat;
import androidx.drawerlayout.widget.DrawerLayout;

/**
 * A DrawerLayout that never OPENS on a touch edge drag.
 *
 * The in-game drawer must only open programmatically (controller Back via
 * OpenXServerDrawerState / handleNavigationBackPressed, and in-app buttons that call
 * openDrawer(GravityCompat.START)). A left-to-right swipe used to open it, which the user does not
 * want. Blocking the lock mode is NOT an option: LOCK_MODE_LOCKED_CLOSED makes openDrawer() a no-op
 * (the fork.18 regression that killed Back), so the drawer must stay UNLOCKED.
 *
 * Instead we veto only the OPEN-on-swipe path: while the drawer is CLOSED we report the touch
 * stream as "not handled", so no edge drag is ever recognized. While the drawer is OPEN we defer to
 * super, so the normal touch-to-close / scrim-tap-to-close behaviour keeps working. Programmatic
 * control (openDrawer()/closeDrawers()/isDrawerOpen()) is completely unaffected.
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
     * Block the edge-drag only while the drawer is CLOSED (removing the open-on-swipe gesture the
     * user asked to drop). While it is open, defer to super so the normal touch-to-close /
     * scrim-tap behaviour still works.
     */
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (!isDrawerOpen(GravityCompat.START)) return false;
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        if (!isDrawerOpen(GravityCompat.START)) return false;
        return super.onTouchEvent(ev);
    }
}
