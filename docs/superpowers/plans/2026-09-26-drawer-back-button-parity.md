# Drawer Back-Button Parity with WinNative — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the handheld's Back button open the in-game drawer and keep B a pure game button (close-only while the drawer is open), by mirroring WinNative's proven input structure exactly.

**Architecture:** WinNative splits the two behaviors across two layers. Back is handled by an `OnBackPressedCallback` registered on `getOnBackPressedDispatcher()` — it toggles the drawer and never touches the game input path. B and all other controller buttons go through `dispatchKeyEvent`, where a single early helper (`handleControllerMenuKey`) returns false unless the drawer is already open; when open it closes on `KEYCODE_BUTTON_B` and routes D-pad/A to the drawer's ComposeView, and when closed everything falls through to the game. Bannerlator already has the `OnBackPressedCallback` (added in a prior commit) but still carries a bespoke "B-as-Back" scan-code check and a KEYCODE_BUTTON_MODE hold-to-open block that WinNative does not have; those are what produce the wrong behavior and are removed.

**Tech Stack:** Java (Android Activity, `androidx.drawerlayout`, `OnBackPressedDispatcher`), Kotlin (Jetpack Compose drawer). No new dependencies.

**Spec:** No separate spec doc — this is a bounded change; the design was agreed in chat. Reference implementation: `C:\Users\i0tro\Documents\opencode-random-sessions\WinNative\app\src\main\runtime\display\XServerDisplayActivity.java` (`dispatchKeyEvent` :10039, `handleControllerMenuKey` :10107, `OnBackPressedCallback` :1915, `handleNavigationBackPressed` :5255).

## Global Constraints

- **Reference behavior is WinNative's, verbatim in structure.** Do not invent scan-code special cases or extra openers. If this fork differs from WinNative here, this fork is wrong.
- **Device key facts (AYN Odin 2 Portal):** physical Back = `KEYCODE_BACK` (kc=4), `scanCode=157`; B = `KEYCODE_BUTTON_B` (kc=97), `scanCode=305`. On this pad B is a distinct key code and never needs to be treated as Back.
- **Back = drawer toggle (open when closed, close when open). B = game button when the drawer is closed; closes the drawer when it is open. B must never open the drawer.**
- **No Android SDK/NDK on the build machine.** Compilation and JVM tests run only in GitHub Actions. Never claim a build passes without a green CI run.
- **CI gotcha:** `gh workflow run` and `gh run` MUST pass `--repo i0trost01/BannerlatorFork` (otherwise they resolve to upstream and fail).
- **Release versionCode** is minutes-since-epoch computed inside `fork-release.yml`; pass an explicit `-f version=` string (`3.1.3-fork.N`).
- Commit after every task. Only commit when a task says to.

---

### Task 1: Route the drawer's ComposeView Back/Escape to a real close, and delete the key diagnostic

The drawer's ComposeView currently intercepts `Key.Back`/`Key.Escape` and calls `XServerDrawerState.onClose`, which is also the Windows-close callback — so those keys close the drawer *and* try to close the game window. Remove that interception entirely and let Back propagate to the Activity's `OnBackPressedCallback` (WinNative's path). Then delete the temporary on-screen diagnostic readout and its backing object, which shipped by mistake.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/ui/XServerDrawer.kt` (root `Row` key handler ~:202-208; AdvancedContent readout ~:4429-4438)
- Delete: `app/src/main/java/com/winlator/star/ui/GamepadKeyDiag.kt`

**Interfaces:**
- Consumes: nothing new.
- Produces: nothing. (Removal only; no downstream task depends on new names here.)

- [ ] **Step 1: Remove the root key interception**

In `XServerDrawer.kt`, delete the `.onPreviewKeyEvent { ... }` block from the root `Row` modifier (the block spanning the `Key.Back`/`Key.Escape` → `XServerDrawerState.onClose` logic, currently ~lines 202-208). The modifier chain must read:

```kotlin
    Row(
        modifier = Modifier
            .fillMaxHeight()
            .width(380.dp)
            .focusRequester(rootFocus)
            .focusable()
            .focusGroup()
            .background(surface)
    ) {
```

- [ ] **Step 2: Remove the AdvancedContent diagnostic readout**

In `XServerDrawer.kt`, delete this block (currently ~lines 4429-4438, immediately above `AdvancedActionRow("Picture-in-Picture", ...)`):

```kotlin
    // TEMPORARY diagnostic readout (see GamepadKeyDiag) — the last gamepad buttons' key/scan codes,
    // so the Back-vs-B mapping can be read in-app when a toast is unreadable over the game surface.
    val keyDiag by GamepadKeyDiag.entries
    if (keyDiag.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        SectionHeader("Gamepad keys (debug)")
        keyDiag.forEach { line ->
            Text(line, color = LocalAccentDim.current, fontSize = 12.sp)
        }
    }
```

- [ ] **Step 3: Delete the diagnostic object**

```bash
git rm app/src/main/java/com/winlator/star/ui/GamepadKeyDiag.kt
```

- [ ] **Step 4: Verify no references remain**

Run: `rg -n "GamepadKeyDiag|onPreviewKeyEvent" app/src/main`
Expected: no output (empty). If `onPreviewKeyEvent` still appears in `XServerDrawer.kt`, Step 1 did not fully remove the block.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/ui/XServerDrawer.kt
git commit -m "refactor(drawer): drop ComposeView Back/Escape interception and key diagnostic"
```

---

### Task 2: Replace `dispatchKeyEvent`'s bespoke Back/B logic with WinNative's `handleControllerMenuKey`

Remove the temporary `WinInput`/toast diagnostic, the `backFromGamepadB` scan-code check, and the `KEYCODE_BUTTON_MODE` hold-to-open block. Introduce `handleControllerMenuKey` — WinNative's opener/closer helper, whose first line makes it a no-op while the drawer is closed, so every controller button (B included) falls through to the game. While the drawer is open it closes on `KEYCODE_BUTTON_B` and forwards D-pad/A/X to the drawer's ComposeView.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` (`dispatchKeyEvent` ~:12854-13034; field/runer declarations ~:283-311)

**Interfaces:**
- Consumes: `dispatchToDrawer(KeyEvent)` (existing, :12731) and `environment`, `drawerLayout`, `inGameControlsEditor`, `inputControlsView`, `winHandler`, `xServer` fields (all existing).
- Produces: `private boolean handleControllerMenuKey(int kc, boolean down)` — the single entry point for controller-menu handling from `dispatchKeyEvent`.

- [ ] **Step 1: Delete the dead Back-hold machinery and its constants**

In `XServerDisplayActivity.java`, delete the block currently ~lines 283-311: the comment starting "In-game drawer opener: ...", `DRAWER_BACK_HOLD_MS`, `BACK_BUTTON_SCAN_CODE`, `drawerBackHoldPending`, `drawerBackHoldFired`, `drawerBackHoldHandler`, `drawerBackHoldRunnable`, and `forwardBackTapToGuest()` (verify it is uncalled first: `rg -n "forwardBackTapToGuest" app/src/main` should show only the definition). Keep the `drawerStick*` fields — those drive the drawer pointer/keyboard nav and are used by `translateDrawerControllerMotion`.

Also delete the two lines inside `resetDrawerStickNavigation()` (~:12768-12770) that clear hold state:

```java
        drawerBackHoldHandler.removeCallbacksAndMessages(null);
        drawerBackHoldPending = false;
        drawerBackHoldFired = false;
```

so that method ends after the `for` loop that clears `drawerStickHeld`.

- [ ] **Step 2: Delete the temporary `WinInput` diagnostic block**

In `dispatchKeyEvent`, delete the entire block guarded by `if (event.getDevice() != null && ExternalController.isGameController(event.getDevice())) { ... }` that logs to `WinInput` and shows the high-contrast toast (currently ~lines 12860-12887, up to and including the comment "---- Drawer OPENING is left entirely to onBackPressed() ----"). The `inGameControlsEditor` early-return at the top of `dispatchKeyEvent` must remain:

```java
    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (inGameControlsEditor != null) {
            super.dispatchKeyEvent(event);
            return true;
        }
        if (ExternalController.isGameController(event.getDevice())
                && handleControllerMenuKey(event.getKeyCode(), event.getAction() == KeyEvent.ACTION_DOWN)) {
            return true;
        }
```

- [ ] **Step 3: Delete the `backFromGamepadB` block and the `KEYCODE_BUTTON_MODE` hold block**

Remove the `backFromGamepadB` computation and its `if` block (currently ~:12895-12904) and the entire `if (event.getKeyCode() == KeyEvent.KEYCODE_BUTTON_MODE && ...) { ... }` block with its handler (~:12923-12941). Neither has a WinNative counterpart. Leave the `KEYCODE_HOME`/`KEYCODE_BUTTON_SELECT` swallow (~:12942-12947) and everything below it untouched.

- [ ] **Step 4: Add `handleControllerMenuKey`**

Insert this method immediately after `dispatchKeyEvent` (i.e. after the closing brace at ~:13034), mirroring WinNative's `handleControllerMenuKey` (:10107) with Bannerlator's `DrawerLayout`:

```java
    /**
     * WinNative's handleControllerMenuKey: while the drawer is CLOSED this returns false for every
     * controller button, so B (KEYCODE_BUTTON_B) and the rest fall straight through to the game.
     * While the drawer is OPEN it owns the pad: B closes the drawer, A activates the focused item,
     * and D-pad directions drive Compose focus. Back-to-open is NOT handled here — it arrives via the
     * OnBackPressedDispatcher (see setupUI). Returns true when it consumed the event.
     */
    private boolean handleControllerMenuKey(int kc, boolean down) {
        if (drawerLayout == null || environment == null || inGameControlsEditor != null) return false;
        if (!drawerLayout.isDrawerOpen(GravityCompat.START)) return false;
        if (kc == KeyEvent.KEYCODE_BUTTON_B) {
            if (down) drawerLayout.closeDrawers();
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

- [ ] **Step 5: Verify no stale references remain**

Run: `rg -n "BACK_BUTTON_SCAN_CODE|drawerBackHold|forwardBackTapToGuest|backFromGamepadB|WinInput" app/src/main`
Expected: no output (empty).

Run: `rg -n "handleControllerMenuKey" app/src/main/java/com/winlator/star/XServerDisplayActivity.java`
Expected: exactly two hits — the definition and the one call in `dispatchKeyEvent`.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/star/XServerDisplayActivity.java
git commit -m "refactor(input): mirror WinNative dispatchKeyEvent (handleControllerMenuKey, Back via dispatcher only)"
```

---

### Task 3: Remove every remaining Back/B/gamepad key log and diagnostic

Task 1 deleted the on-screen diagnostic (`GamepadKeyDiag` + the drawer readout) and Task 2 deleted the `WinInput` `Log.i` + high-contrast toast inside `dispatchKeyEvent`. This task is the explicit sweep that guarantees *no* Back/B/gamepad key logging or diagnostic UI survives anywhere in the app — including the dangling `GamepadKeyDiag.INSTANCE.record(...)` call that Task 1 left in `XServerDisplayActivity` (Task 2 should have removed it; this task verifies it and removes it if not) and the temporary `FrontendLaunch` debug logs added while chasing the front-end launch bug.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` (dangling `GamepadKeyDiag` call ~:12873)
- Modify: `app/src/main/java/com/winlator/star/MainActivity.kt` (`FrontendLaunch` debug logs in `maybeForwardFrontendLaunch`)

**Interfaces:**
- Consumes: the committed state of Tasks 1-2.
- Produces: no new names. (Removal only.)

- [ ] **Step 1: Confirm the dangling `GamepadKeyDiag` call is gone**

Run: `rg -n "GamepadKeyDiag" app/src/main`
Expected: no output (empty). If `XServerDisplayActivity.java:12873` still calls `GamepadKeyDiag.INSTANCE.record(...)`, delete that statement (it is inside the temporary `WinInput` diagnostic block that Task 2 removes; if Task 2's block removal already covered it, this passes). Removing it unconditionally is safe — the class is deleted.

- [ ] **Step 2: Remove the `FrontendLaunch` debug logs**

The front-end-launcher investigation added two debug logs that were only for diagnosing the launch bug. Remove them from `MainActivity.kt`:

```kotlin
        android.util.Log.d("FrontendLaunch", "incoming: action=$action data=${src.dataString}"
                + " extras=[${src.extras?.keySet()?.joinToString(", ") { "$it=${src.extras!!.get(it)}" } ?: "none"}]")
```

and

```kotlin
        android.util.Log.d("FrontendLaunch", "forwarding to session: shortcut_path=$shortcutPath container=$containerId")
```

Leave any remaining `Log` calls on other tags (e.g. `BH_REALSTEAM`, `CompositorDriver`) untouched — only these two go.

- [ ] **Step 3: Sweep for any other Back/B/gamepad key logging**

Run: `rg -n -i "log\.[diwev]\(.*(back|gamepad|keydiag|wininput)" app/src/main`
Expected: no output (empty). If any hit appears, it is a leftover key diagnostic — delete it and note it in the report.

- [ ] **Step 4: Confirm no diagnostic UI remains**

Run: `rg -n -i "gamepad keys|keydiag|wininput" app/src/main`
Expected: no output (empty).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/XServerDisplayActivity.java app/src/main/java/com/winlator/star/MainActivity.kt
git commit -m "chore(diag): remove all Back/B gamepad key logs and diagnostics"
```

---

### Task 4: Verify CI is green and cut a signed release

Compilation and the JVM test suite only run in CI. Confirm the branch builds, then publish a signed standard APK for Obtainium and hand it to the user for the on-device check.

**Files:**
- Modify: none (verification + release only)

**Interfaces:**
- Consumes: the committed source from Tasks 1-2.
- Produces: a published release `3.1.3-fork.N` and its APK URL.

- [ ] **Step 1: Push and run CI**

```bash
git push origin main
gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork
sleep 8
gh run list --repo i0trost01/BannerlatorFork --workflow=fork-ci.yml --limit 1
```

- [ ] **Step 2: Wait for CI and check the result**

Run: `gh run view <run-id> --repo i0trost01/BannerlatorFork --json status,conclusion`
Expected: `"status":"completed","conclusion":"success"`. If it failed, read the failing log (`gh run view <run-id> --repo i0trost01/BannerlatorFork --log-failed`), fix the compile error, and re-run. Do not proceed until CI is green.

- [ ] **Step 3: Cut the release**

```bash
gh workflow run fork-release.yml --repo i0trost01/BannerlatorFork -f version=3.1.3-fork.18
sleep 8
gh run list --repo i0trost01/BannerlatorFork --workflow=fork-release.yml --limit 1
```

Cancel any superseded queued/in-progress release runs first (`gh run cancel <id> --repo i0trost01/BannerlatorFork`) so only the newest publishes.

- [ ] **Step 4: Confirm the release artifact**

Run: `gh release view 3.1.3-fork.18 --repo i0trost01/BannerlatorFork --json tagName,assets`
Expected: a `Bannerlator-Fork-3.1.3-fork.18-Standard-signed.apk` asset is listed. Report the release URL to the user.

- [ ] **Step 5: On-device acceptance (user)**

Report this checklist to the user verbatim:

1. With the game running, press **Back** → drawer opens.
2. Press **Back** again → drawer closes.
3. With the drawer closed, press **B** → it acts as a normal game button (no drawer).
4. With the drawer open, press **B** → drawer closes.
5. With the drawer open, D-pad/stick moves focus; **A** activates; the game does not receive input.

Do not mark this task complete until the user confirms 1-5. If any fails, capture `adb logcat -s FrontendLaunch` is not relevant here — instead note which step failed and return to Task 2.

---

## Self-Review

**Spec coverage:**
- "Back opens the drawer" → `OnBackPressedCallback` (already present, preserved) + Task 1 removes the ComposeView interception that could swallow it. ✅
- "B does not open it" → Task 2 Step 3 removes `backFromGamepadB` and hold-open; `handleControllerMenuKey` returns false while closed. ✅
- "B works as a regular button when playing" → same fall-through. ✅
- "B can close the panel" → `handleControllerMenuKey` `KEYCODE_BUTTON_B` close branch. ✅
- "Drop the Mode hold opener entirely; mimic WinNative fully" → Task 2 Step 1 and Step 3. ✅
- "Remove shipped diagnostics" → Task 1 (GamepadKeyDiag + AdvancedContent readout), Task 2 Step 2 (`WinInput`), and **Task 3 (explicit sweep: dangling `GamepadKeyDiag` call, the two `FrontendLaunch` debug logs, and any other Back/B/`gamepad` key log or diagnostic UI).** ✅
- User request "add a task to remove the Back and B button logs" → **Task 3**. ✅

**Placeholder scan:** No TBD/TODO; every code step shows the literal content to write or delete. ✅

**Type consistency:** `handleControllerMenuKey(int kc, boolean down)` is defined in Task 2 Step 4 and called with that exact signature in Step 2. `dispatchToDrawer(KeyEvent)` matches the existing signature at :12731. `GravityCompat.START` is already imported and used at :12901/:1987. ✅

**Known risk:** `KEYCODE_BUTTON_MODE` is still swallowed for Home/Select parity at the existing `:13020-13029` block; on this pad Mode is unused (Steam button), so dropping the hold-opener is safe. If the user ever wants a hold-opener back, it is a separate, explicitly-requested change.
