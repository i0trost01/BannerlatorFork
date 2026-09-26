# Drawer Back/B Input — Verified Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the physical Back button open/close the in-game drawer and keep B a plain game button, with JVM tests that actually execute the toggle logic (not just compile it) so a regression cannot ship again.

**Architecture:** Two shipped failures (`3.1.3-fork.18`/`.19`) compiled cleanly but did nothing. There are **two independent causes**, and the plan addresses both:
1. `setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED)` silently turns `openDrawer()` into a no-op (fixed in `d3f418ff`, guarded by a test here).
2. `dispatchKeyEvent`'s fallthrough (`XServerDisplayActivity.java:12925`) gates `super.dispatchKeyEvent(event)` behind `!ExternalController.isGameController(...)`, so the pad's `KEYCODE_BACK` **never reaches** `onBackPressed()` / `OnBackPressedDispatcher`. WinNative has one unconditional exit (`:10104`). This is the cause `fork.19` still shipped with.

Additionally, the toggle logic lived inside an `Activity` and could not run on the JVM, so nothing about it was ever executed before release. This plan fixes the code and closes that gap: the Back/B decision rules move into a pure, Android-free `DrawerController` that is exhaustively unit-tested; Robolectric is added so a real `DrawerLayout` and real key events are exercised in JVM tests; and the CI gate runs the whole `testStandardDebugUnitTest` suite (not one filtered class) before any release.

**Tech Stack:** Java, AndroidX `DrawerLayout`, `OnBackPressedDispatcher`, JUnit 4, Robolectric 4.14.1, AGP 8.8.0, Gradle 8.10.2, JDK 17.

**Spec:** No separate spec — this is a bug-fix follow-up to `docs/superpowers/plans/2026-09-26-drawer-back-button-parity.md` (whose Tasks 1-3 are merged in commits `85b17b7`, `519fa84f`/`cda753ab`, `5ca7405c`/`df0d2449`). Reference behavior: WinNative's `XServerDisplayActivity.java` (`handleControllerMenuKey` :10107, `OnBackPressedCallback` :1915, `handleNavigationBackPressed` :5255).

## Global Constraints

- **Back = drawer toggle. B = game button when closed; closes the drawer when open; B must NEVER open the drawer.**
- **The drawer must never be locked.** `LOCK_MODE_LOCKED_CLOSED` makes `openDrawer()` a silent no-op. The assertion `assertNotEquals(LOCK_MODE_LOCKED_CLOSED, ...)` in Task 3 is the regression guard for cause #1.
- **`dispatchKeyEvent` must always reach `super.dispatchKeyEvent(event)`.** Gating it behind `!isGameController(device)` swallows the pad's `KEYCODE_BACK` before `OnBackPressedDispatcher` can see it. WinNative's single unconditional `return super.dispatchKeyEvent(event);` (`:10104`) is the reference. Task 3's Step 5 source check is the regression guard for cause #2.
- **Device facts (AYN Odin 2 Portal):** physical Back = `KEYCODE_BACK` (kc=4, scan=157); B = `KEYCODE_BUTTON_B` (kc=97, scan=305). B never arrives as `KEYCODE_BACK` in this fork's games — do NOT reintroduce scan-code special-casing.
- **Tests must EXECUTE, not just compile.** `testStandardDebugUnitTest` is the gate. A release is only cut after it is green.
- **No Android SDK/NDK locally.** Robolectric downloads its own android-all jars at test time; the first CI run is slow. Do not attempt a local build.
- **CI gotcha:** every `gh workflow run` / `gh run` call MUST pass `--repo i0trost01/BannerlatorFork`.
- **Robolectric version:** pin `org.robolectric:robolectric:4.14.1` with `@Config(sdk = [34])` — it supports compileSdk 34 / AGP 8.8. Do not use `latest.release` (non-reproducible).
- **No new comments** beyond what each step shows.

---

### Task 1: Add Robolectric and make the CI gate run the whole test suite

Adds the Robolectric dependency and `testOptions`, and changes `fork-ci.yml` from one filtered test class to the full unit-test suite so behavioral tests execute in CI. This is a prerequisite for every later task's tests.

**Files:**
- Modify: `app/build.gradle` (dependencies block ~:279-287; add `testOptions` inside `android { }`)
- Modify: `.github/workflows/fork-ci.yml` (last step)

**Interfaces:**
- Consumes: nothing.
- Produces: a working Robolectric test environment; `testStandardDebugUnitTest` as the CI command all later tasks rely on.

- [ ] **Step 1: Add `testOptions` to `app/build.gradle`**

Inside the `android { ... }` block, immediately after the `lint { ... }` block (currently ending at line 148), insert:

```gradle
    testOptions {
        unitTests {
            includeAndroidResources = true
            returnDefaultValues = true
        }
    }
```

`includeAndroidResources = true` is required so Robolectric can load the real `xserver_display_activity.xml` layout in Task 3. `returnDefaultValues = true` keeps unrelated Android stubs from throwing.

- [ ] **Step 2: Add the Robolectric dependency**

In the dependencies block, immediately after `testImplementation 'org.json:json:20231013'` (line 282), insert:

```gradle
    // Robolectric runs real Android framework classes (DrawerLayout, OnBackPressedDispatcher)
    // on the JVM, so the drawer input behavior is EXECUTED in CI, not merely compiled.
    testImplementation 'org.robolectric:robolectric:4.14.1'
    testImplementation 'androidx.test:core:1.5.0'
    testImplementation 'androidx.test.ext:junit:1.1.5'
```

- [ ] **Step 3: Point the CI gate at the whole suite**

In `.github/workflows/fork-ci.yml`, replace the final step's run line:

```yaml
    - name: Run fork unit tests
      run: ./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.ui.screens.SteamFrontendExportTest"
```

with:

```yaml
    - name: Run fork unit tests
      run: ./gradlew :app:testStandardDebugUnitTest
```

- [ ] **Step 4: Verify the build script is valid-ish (no local Gradle run possible)**

Run (grep only — a full build is not possible on this machine):

```powershell
Select-String -Path app\build.gradle -Pattern "robolectric:4.14.1","includeAndroidResources"
```
Expected: both patterns match.

- [ ] **Step 5: Commit**

```bash
git add app/build.gradle .github/workflows/fork-ci.yml
git commit -m "test: add Robolectric and gate CI on the full unit-test suite"
```

---

### Task 2: Extract the Back/B decision rules into a pure, Android-free `DrawerController`

The bug-prone logic — *should Back toggle? should this key open, close, or pass through?* — currently lives inside an `Activity` and directly calls `DrawerLayout`, making it untestable without a device. This task moves those rules into a pure class with no Android imports, and rewrites the Activity methods to be thin adapters over it. Behavior of the app must not change in this task except that it is now testable.

**Files:**
- Create: `app/src/main/java/com/winlator/star/inputcontrols/DrawerController.java`
- Create: `app/src/test/java/com/winlator/star/inputcontrols/DrawerControllerTest.java`
- Modify: `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` (`handleNavigationBackPressed` ~:7193; `handleControllerMenuKey` ~:12920-12957)

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `enum DrawerBackAction { OPEN, CLOSE, NONE }`
  - `enum DrawerMenuAction { CLOSE_DRAWER, ROUTE_TO_DRAWER, PASS_THROUGH }`
  - `static DrawerBackAction backAction(boolean drawerOpen, boolean editorActive)`
  - `static DrawerMenuAction menuAction(int keyCode, boolean down, boolean drawerOpen, boolean editorActive)`
  - `static boolean isDrawerButton(int keyCode)` (true for `KEYCODE_BUTTON_B`)
  - `static final int KEYCODE_BUTTON_B = 97;` and constant `KEYCODE_B` re-exported so the test does not import Android

These are the names Task 3 relies on.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/inputcontrols/DrawerControllerTest.java`:

```java
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

    @Test public void bReleaseIsConsumedWhileOpenButDoesNotCloseTwice() {
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
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerControllerTest"` (in CI; locally this cannot run — note it as CI-verified)

Expected: FAIL to compile — `DrawerController` does not exist.

- [ ] **Step 3: Write the minimal implementation**

Create `app/src/main/java/com/winlator/star/inputcontrols/DrawerController.java`:

```java
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
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerControllerTest"`
Expected: PASS, 10 tests.

- [ ] **Step 5: Rewire `handleNavigationBackPressed` to use `DrawerController`**

In `XServerDisplayActivity.java`, replace the body of `handleNavigationBackPressed` (currently ~7193-7208) with:

```java
    private boolean handleNavigationBackPressed() {
        if (inGameControlsEditor != null) {
            if (inGameControlsEditor.handleBack()) return true;
            closeInGameControlsEditor();
            return true;
        }
        if (environment == null || drawerLayout == null) return false;
        DrawerController.DrawerBackAction action = DrawerController.backAction(
                drawerLayout.isDrawerOpen(GravityCompat.START), false);
        if (action == DrawerController.DrawerBackAction.OPEN) {
            drawerLayout.openDrawer(GravityCompat.START);
        } else if (action == DrawerController.DrawerBackAction.CLOSE) {
            drawerLayout.closeDrawers();
        }
        return true;
    }
```

Add the import `import com.winlator.star.inputcontrols.DrawerController;` if not already present (`com.winlator.star.inputcontrols.*` is a sibling package, so the import is required).

- [ ] **Step 6: Rewire `handleControllerMenuKey` to use `DrawerController`**

In `XServerDisplayActivity.java`, replace the body of `handleControllerMenuKey` (currently ~12926-12957) with:

```java
    private boolean handleControllerMenuKey(int kc, boolean down) {
        if (drawerLayout == null || environment == null) return false;
        DrawerController.DrawerMenuAction action = DrawerController.menuAction(
                kc, down, drawerLayout.isDrawerOpen(GravityCompat.START), inGameControlsEditor != null);
        if (action == DrawerController.DrawerMenuAction.PASS_THROUGH) return false;
        if (action == DrawerController.DrawerMenuAction.CLOSE_DRAWER) {
            drawerLayout.closeDrawers();
            return true;
        }
        if (down) {
            if (kc == KeyEvent.KEYCODE_BUTTON_A) {
                dispatchToDrawer(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
                dispatchToDrawer(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER));
            } else if (kc == KeyEvent.KEYCODE_DPAD_LEFT || kc == KeyEvent.KEYCODE_DPAD_RIGHT
                    || kc == KeyEvent.KEYCODE_DPAD_UP || kc == KeyEvent.KEYCODE_DPAD_DOWN) {
                dispatchToDrawer(new KeyEvent(KeyEvent.ACTION_DOWN, kc));
                dispatchToDrawer(new KeyEvent(KeyEvent.ACTION_UP, kc));
            } else {
                dispatchToDrawer(new KeyEvent(KeyEvent.ACTION_DOWN, kc));
            }
        } else {
            dispatchToDrawer(new KeyEvent(KeyEvent.ACTION_UP, kc));
        }
        return true;
    }
```

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/winlator/star/inputcontrols/DrawerController.java app/src/test/java/com/winlator/star/inputcontrols/DrawerControllerTest.java app/src/main/java/com/winlator/star/XServerDisplayActivity.java
git commit -m "refactor(input): extract drawer Back/B rules into testable DrawerController"
```

---

### Task 3: Make controller BACK actually reach the system, and Robolectric-test that it opens the drawer

**Two independent causes** made Back do nothing. Cause #1 was `setDrawerLockMode(LOCK_MODE_LOCKED_CLOSED)` (already fixed in `d3f418ff`). Cause #2 — which `3.1.3-fork.19` still shipped with — is that `XServerDisplayActivity.dispatchKeyEvent` never calls `super.dispatchKeyEvent(event)` for a game controller, so the pad's `KEYCODE_BACK` never reaches `onBackPressed()` / the `OnBackPressedDispatcher`. WinNative has one unconditional exit (`:10104`). This task fixes cause #2 and adds the Robolectric guard for both.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` (final fallthrough ~:12920-12926)
- Create: `app/src/test/java/com/winlator/star/DrawerInputRobolectricTest.java`

**Interfaces:**
- Consumes: `DrawerController` from Task 2.
- Produces: nothing downstream.

- [ ] **Step 1: Confirm the two defects in the current source**

Run:
```powershell
Select-String -Path app\src\main\java\com\winlator\star\XServerDisplayActivity.java -Pattern "setDrawerLockMode|isGameController\(event.getDevice\(\)\) && super.dispatchKeyEvent"
```
Expected: the lock line shows `LOCK_MODE_UNLOCKED` (cause #1 already fixed), and the fallthrough line — matching `!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event)` — is present. Cause #2 is that line.

- [ ] **Step 2: Write the failing test (cause #2 as an executable fact)**

Create `app/src/test/java/com/winlator/star/DrawerInputRobolectricTest.java`:

```java
package com.winlator.star;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.view.Gravity;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.LayoutInflater;

import androidx.drawerlayout.widget.DrawerLayout;
import androidx.test.core.app.ApplicationProvider;

import com.winlator.star.inputcontrols.DrawerController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowInputDevice;

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
        assertFalse("this is why fork.18 was dead", drawer.isDrawerOpen(Gravity.START));
    }

    @Test public void aGamepadBackKeyMustBeAllowedThroughToSuper() {
        // Cause #2: KEYCODE_BACK arriving on a GAME CONTROLLER device must not be filtered out of
        // the super.dispatchKeyEvent fallthrough, or it can never reach OnBackPressedDispatcher.
        InputDevice pad = ShadowInputDevice.newInstance(
                ShadowInputDevice.makeInputDeviceWithSources(
                        InputDevice.SOURCE_GAMEPAD | InputDevice.SOURCE_JOYSTICK));
        KeyEvent back = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK);
        back.setSource(InputDevice.SOURCE_GAMEPAD);

        assertTrue("the pad's Back is a game controller event", isGameController(pad));
        assertEquals(KeyEvent.KEYCODE_BACK, back.getKeyCode());
    }

    private static boolean isGameController(InputDevice device) {
        int src = device.getSources();
        return (src & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD
                || (src & InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK;
    }
}
```

Note: if `ShadowInputDevice.newInstance` / `makeInputDeviceWithSources` do not exist in the pinned Robolectric version, replace the body of `aGamepadBackKeyMustBeAllowedThroughToSuper` with a plain assertion that `new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK)` is delivered with `ACTION_DOWN` and key code `KEYCODE_BACK` (the source-filtering assertion is a documentation guard; the load-bearing guard for cause #2 is Task 3 Step 4's source check and Task 2's rule tests). Report whichever resolution you used.

- [ ] **Step 3: Run the test to verify it passes except for the fix you are about to apply**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerInputRobolectricTest"`
Expected: PASS. (These assert framework facts and the lock fix; they do not yet cover the Activity's fallthrough, which Step 4 changes.)

- [ ] **Step 4: Fix cause #2 — let controller keys reach super**

In `XServerDisplayActivity.java`, find the final fallthrough of `dispatchKeyEvent` (currently ~:12920-12926):

```java
        // Fallback to existing input handling
        return (!inputControlsView.onKeyEvent(event) && !winHandler.onKeyEvent(event) && xServer.keyboard.onKeyEvent(event)) ||
                (!ExternalController.isGameController(event.getDevice()) && super.dispatchKeyEvent(event));
```

Replace it with:

```java
        // WinNative parity: the fallthrough must reach super for EVERY device, game controllers
        // included. Gating it on !isGameController swallowed the pad's KEYCODE_BACK before it could
        // reach onBackPressed()/OnBackPressedDispatcher — the reason Back never opened the drawer.
        boolean handledByGuest = inputControlsView.onKeyEvent(event)
                || winHandler.onKeyEvent(event)
                || xServer.keyboard.onKeyEvent(event);
        return handledByGuest || super.dispatchKeyEvent(event);
```

This matches WinNative's single unconditional `return super.dispatchKeyEvent(event);` exit while preserving Bannerlator's guest-input chain.

- [ ] **Step 5: Verify no other `!isGameController(...) && super` gating remains**

Run:
```powershell
Select-String -Path app\src\main\java\com\winlator\star\XServerDisplayActivity.java -Pattern "isGameController\(event.getDevice\(\)\) && super"
```
Expected: no output (empty).

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/star/XServerDisplayActivity.java app/src/test/java/com/winlator/star/DrawerInputRobolectricTest.java
git commit -m "fix(input): let controller BACK reach super.dispatchKeyEvent so it opens the drawer"
```

---

### Task 4: Green CI and cut a release that is gated on executed tests

This time the release is only authorized after the executed test suite is green — the whole point of the plan.

**Files:**
- Modify: none (verification + release only)

**Interfaces:**
- Consumes: all tasks above.
- Produces: a published `3.1.3-fork.20` and its APK URL.

- [ ] **Step 1: Push and run CI**

```bash
git push origin main
gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork
sleep 8
gh run list --repo i0trost01/BannerlatorFork --workflow=fork-ci.yml --limit 1
```

- [ ] **Step 2: Wait for CI and confirm the EXECUTED tests**

Run: `gh run view <run-id> --repo i0trost01/BannerlatorFork --json status,conclusion`
Expected: `"conclusion":"success"`. The first run downloads Robolectric's android-all jar and is slow (allow ~15-20 min). If it fails, read `gh run view <run-id> --repo i0trost01/BannerlatorFork --log-failed`, fix, re-run. Do NOT proceed until green — this is the gate that was missing.

- [ ] **Step 3: Cut the release**

```bash
gh workflow run fork-release.yml --repo i0trost01/BannerlatorFork -f version=3.1.3-fork.20
sleep 8
gh run list --repo i0trost01/BannerlatorFork --workflow=fork-release.yml --limit 1
```
Cancel superseded release runs first (`gh run cancel <id> --repo i0trost01/BannerlatorFork`).

- [ ] **Step 4: Confirm the artifact**

Run: `gh release view 3.1.3-fork.20 --repo i0trost01/BannerlatorFork --json tagName,assets`
Expected: a `Bannerlator-Fork-3.1.3-fork.20-Standard-signed.apk` asset. Report the URL.

- [ ] **Step 5: On-device acceptance (user)**

Report verbatim:
1. Game running, press **Back** → drawer opens.
2. Press **Back** again → drawer closes.
3. Drawer closed, press **B** → normal game button, no drawer.
4. Drawer open, press **B** → drawer closes.
5. Drawer open → D-pad/stick moves focus, **A** activates, game gets no input.

If step 1 fails again, the next diagnostic is a one-line log at the top of `handleNavigationBackPressed` reporting whether it was reached and the lock mode — but the Robolectric test should make that unnecessary.

---

## Self-Review

**Spec coverage:**
- "Back opens/closes, B never opens, B closes when open" → Task 2 rule table + 10 unit tests; Task 2 Steps 5-6 wire it into the Activity. ✅
- "Figure out a way to test locally, even just unit tests" → Task 2's `DrawerControllerTest` runs on the JVM; Robolectric (Task 1) makes it executable in CI; no device needed. ✅
- "No more releases that don't do anything" → Task 1 Step 3 changes CI to run the whole suite; Task 4 Step 2 gates the release on it. ✅
- Root cause of both failures → **cause #1** guarded by Task 3's `drawerIsNeverLockedClosed` + the deliberate negative test; **cause #2** fixed by Task 3 Step 4 and guarded by Step 5's source check. ✅
- Correcting the original (wrong) assumption that the lock fix alone was sufficient → the plan's Architecture now names both causes, and Task 3 Step 1 verifies each independently. ✅

**Placeholder scan:** No TBD/TODO; every code step shows literal content. ✅

**Type consistency:** `DrawerController.backAction(boolean, boolean)` → `DrawerBackAction` used identically in Task 2 test, Task 2 Step 5, and Task 3. `menuAction(int, boolean, boolean, boolean)` → `DrawerMenuAction` consistent across Task 2 Steps 1/3/6. `KEYCODE_BUTTON_B` = 97 matches the device fact (kc=97) in Global Constraints. ✅

**Known risk:** Robolectric layout inflation can fail if the layout references a theme attribute unavailable in the test manifest. Task 3 Step 3's note covers the fix (a `@Config` qualifier) without touching production code. If Robolectric proves unable to inflate the layout at all, Task 3 degrades to asserting `LOCK_MODE_UNLOCKED` on a manually-constructed `DrawerLayout` — still executing, still guarding the bug.
