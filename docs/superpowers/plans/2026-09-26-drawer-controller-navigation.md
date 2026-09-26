# Drawer Controller Navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the in-game drawer fully navigable with a gamepad *while it is already open* — D-pad moves a highlight (starting on the left icon rail), A enters the highlighted item / switches from the rail into the panel, B goes back exactly one level (panel → rail, rail → close) without ever opening the drawer, and Back closes the drawer when it is open.

**Architecture:** Split into three layers. (1) A pure, Android-free decision class `DrawerNavModel` holds ALL navigation rules (levels, rail index math, panel grid movement) and is covered by real JVM unit tests. (2) `XServerDisplayActivity.handleControllerMenuKey` translates controller key codes into calls on a state bridge and owns the side effects (open/close the DrawerLayout, move/activate the highlight). (3) `XServerDrawer.kt` renders the highlight and performs the selection, driven by a small observable bridge object the Activity pushes into. WinNative is the reference: it does exactly this — an Activity-side `menuNavRegion`/`menuNavIndex` state machine that pushes signals into Compose — and does NOT use Compose focus for controller navigation.

**Tech Stack:** Android (Java activity + Jetpack Compose/Kotlin drawer), Gradle, JUnit4 JVM tests, GitHub Actions CI.

**Spec:** This plan was preceded by an on-device investigation and a clarification round with the user. There is no separate spec file; the design record and the confirmed decisions live in "Design Decisions" below. Read that section as the spec.

---

## Global Constraints

- Only the `standard` flavor exists. Do not reintroduce `ludashi`/`pubg`.
- Do NOT add Robolectric tests: this module native-loads a `.so` during framework init and Robolectric cannot boot (proven over 3 CI rounds — see `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java:13-16`). All new tests must be plain JVM JUnit4 running in `:app:testStandardDebugUnitTest`.
- All navigation DECISIONS must live in the pure class `com.winlator.star.inputcontrols.DrawerNavModel` (no `android.*` imports). The Activity and the Composable are thin adapters.
- The CI gate is `./gradlew :app:testStandardDebugUnitTest` and it must be GREEN before any release.
- Release versioning: cut via `gh workflow run fork-release.yml --repo i0trost01/BannerlatorFork -f version=3.1.3-fork.N`. NEVER publish a release on a red or unrun CI.
- Every `gh workflow run` / `gh run` command MUST pass `--repo i0trost01/BannerlatorFork`.
- `rg` is NOT installed on this machine. Use the Grep tool or PowerShell `Select-String`.
- No Android SDK is available locally: local `./gradlew` will fail. All test execution is CI-verified.
- Follow `AGENTS.md`: match surrounding naming/indentation; no comments unless they explain WHY; strings via `res/values/strings.xml` if any are added (this plan adds none).

## Design Decisions (the spec)

Confirmed with the user, with on-device verification against WinNative on the same hardware (AYN Odin 2 Portal).

### Already correct — DO NOT CHANGE

These behaviors are verified working on-device today. The plan must preserve them exactly.

- **P1 — Back opens the drawer when closed.** It arrives via the framework `OnBackPressedDispatcher` → `handleNavigationBackPressed()` → `DrawerController.backAction(false, ...)` → `OPEN`. Verified working. **Do not touch this path.** (Note: `KEYCODE_BACK` is deliberately NOT routed through `handleControllerMenuKey`; see `XServerDisplayActivity.java:12937-12938`.)
- **P2 — B does not open the drawer.** While closed, `DrawerController.menuAction` returns `PASS_THROUGH` (`DrawerController.java:28`), so B reaches the game as an ordinary button. Verified working, and WinNative behaves the same on this device. **Do not change B-while-closed.**
- **P3 — B closes the drawer when open.** `menuAction(B, down=true, drawerOpen=true, ...)` → `CLOSE_DRAWER` (`DrawerController.java:30`). This stays as the final level's behavior (see N4).
- **P4 — `DrawerController`'s existing public API and its 10 tests in `DrawerControllerTest.java` stay valid.** This plan must not break them.

### To be added — navigation while the drawer is OPEN

- **N1 — Two levels.** Level 0 = the left icon rail (vertical). Level 1 = the panel (the selected tab's content). The highlight starts on the rail (level 0, index 0) every time the drawer opens.
- **N2 — Selection resets on open.** Always reset to rail index 0 on open. Do not remember the last position.
- **N3 — A at level 0** activates the highlighted rail item (selects that tab) AND moves the level to 1 (now inside the panel). **A at level 1** activates the highlighted panel control.
- **N4 — B while open goes back exactly one level.** Level 1 → level 0. Level 0 → close the drawer (this is the existing P3 behavior, now scoped to level 0). That is the entire B behavior while open.
- **N5 — Back while open** closes the drawer (level 1 → level 0, then level 0 → close; the user-facing effect is "closes"). This must not disturb P1.
- **N6 — D-pad Up/Down at level 0** moves the highlight up/down the rail, clamped. **D-pad Left/Right at level 0** are consumed no-ops (they must not fall through to the game).
- **N7 — D-pad at level 1** moves the highlight between panel controls: Up/Down between rows, Left/Right within a row. When the highlight is on the FIRST row and the user presses Up, the level goes back to 0 (rail) — WinNative's behavior, and it keeps the rail reachable.
- **N8 — Analog stick and D-pad/HAT must both work.** Stick motion already arrives as `dispatchGenericMotionEvent`; the Activity already translates it to synthetic D-pad taps. Route those through the SAME code path as physical D-pad so behavior is identical.
- **N9 — While the drawer is open and the highlight is active, controller input must not reach the game.** All keys the model handles are consumed (`return true`). D-pad/A/B never leak to the guest while the drawer is open.

### Out of scope (deliberate, stated so it is not mistaken for a gap)

- **S1 — Steam/SDL virtual controllers.** The `SteamControllerBackend.Listener` path (`XServerDisplayActivity.java:13090-13102`) does not reach `handleControllerMenuKey` today. This plan does not wire it. Physical Android HID pads (what the Odin uses) are the target.
- **S2 — Panel controls outside the Controls tab.** Task 6 wires the Controls tab. Other tabs adopt the same helper incrementally. See the scope note in Task 6.

---

## File Structure

| File | Responsibility |
|---|---|
| `app/src/main/java/com/winlator/star/inputcontrols/DrawerNavModel.java` | **Create.** Pure decision logic: levels, rail index math, panel grid movement, key→action mapping. No Android imports. |
| `app/src/test/java/com/winlator/star/inputcontrols/DrawerNavModelTest.java` | **Create.** JVM unit tests for every rule in N1–N9. |
| `app/src/main/java/com/winlator/star/inputcontrols/DrawerController.java` | **Modify (minimally).** Add panel-level awareness so B at level 0 closes (unchanged from P3) and at level 1 steps to the rail. Keep the whole existing public surface and all 10 tests green. |
| `app/src/test/java/com/winlator/star/inputcontrols/DrawerControllerTest.java` | **Modify.** Add tests for the level-aware B behavior; leave the 10 existing tests untouched. |
| `app/src/main/java/com/winlator/star/ui/DrawerNavBridge.kt` | **Create.** Observable bridge (state holder) the Activity writes and the Composable reads: `level`, `railIndex`, `panelRow`, `panelCol`, grid shape, activation counter. |
| `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` | **Modify.** Rewrite the `down` branch of `handleControllerMenuKey` (`:12949-12962`) to drive the model and the bridge instead of forwarding raw keys into Compose. |
| `app/src/main/java/com/winlator/star/ui/XServerDrawer.kt` | **Modify.** Read the bridge; draw the highlight on the highlighted rail item and panel cell; perform selection/activation on signals. |
| `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java` | **Modify.** Add source-level guards so the wiring cannot silently regress. |

---

## Task 1: Pure navigation model — levels and rail movement

**Files:**
- Create: `app/src/main/java/com/winlator/star/inputcontrols/DrawerNavModel.java`
- Test: `app/src/test/java/com/winlator/star/inputcontrols/DrawerNavModelTest.java`

**Interfaces:**
- Consumes: nothing.
- Produces:
  - `public static final int LEVEL_RAIL = 0;`
  - `public static final int LEVEL_PANEL = 1;`
  - `public static int railIndexDelta(int keyCode)` → `-1` for D-pad Up, `+1` for D-pad Down, `0` otherwise.
  - `public static int clampRailIndex(int index, int railSize)` → clamped into `0..max(0, railSize-1)`.
  - `public static boolean isRailNavigationKey(int keyCode)` → true for D-pad Up/Down/Left/Right, A, and D-pad Center.
  - `public static int levelAfterActivateAtRail()` → `LEVEL_PANEL`.
  - `public static int levelAfterBack(int level)` → `LEVEL_RAIL`.

Key codes (verified against `ExternalController` and the Activity): `KEYCODE_DPAD_UP=19`, `KEYCODE_DPAD_DOWN=20`, `KEYCODE_DPAD_LEFT=21`, `KEYCODE_DPAD_RIGHT=22`, `KEYCODE_DPAD_CENTER=23`, `KEYCODE_BUTTON_A=96`, `KEYCODE_BUTTON_B=97`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/inputcontrols/DrawerNavModelTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerNavModelTest"`
Expected: FAIL — compilation error, `DrawerNavModel` does not exist. (CI-verified; local run impossible, no Android SDK.)

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/winlator/star/inputcontrols/DrawerNavModel.java`:

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerNavModelTest"`
Expected: PASS — 9 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/inputcontrols/DrawerNavModel.java app/src/test/java/com/winlator/star/inputcontrols/DrawerNavModelTest.java
git commit -m "feat(input): add pure drawer navigation model (levels, rail movement)"
```

---

## Task 2: Panel grid movement in the model

**Files:**
- Modify: `app/src/main/java/com/winlator/star/inputcontrols/DrawerNavModel.java`
- Test: `app/src/test/java/com/winlator/star/inputcontrols/DrawerNavModelTest.java`

**Interfaces:**
- Consumes: Task 1's constants.
- Produces:
  - `public enum DrawerNavModel.PanelMove { UP_FROM_FIRST_ROW, UP, DOWN, LEFT, RIGHT, NONE }`
  - `public static DrawerNavModel.PanelMove panelMove(int keyCode, int row, int rowCount, int col, int colCount)` — returns `UP_FROM_FIRST_ROW` when Up is pressed on row 0 (the caller steps to the rail); `NONE` for a move that would leave the grid.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/winlator/star/inputcontrols/DrawerNavModelTest.java` (before the final closing brace):

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerNavModelTest"`
Expected: FAIL — `PanelMove` and `panelMove` do not exist.

- [ ] **Step 3: Write minimal implementation**

Add to `DrawerNavModel.java`:

```java
    /** What a D-pad press should do to the panel highlight. */
    public enum PanelMove { UP_FROM_FIRST_ROW, UP, DOWN, LEFT, RIGHT, NONE }

    public static PanelMove panelMove(int keyCode, int row, int rowCount, int col, int colCount) {
        if (rowCount <= 0) return PanelMove.NONE;
        if (keyCode == KEYCODE_DPAD_UP) {
            if (row <= 0) return PanelMove.UP_FROM_FIRST_ROW;
            return PanelMove.UP;
        }
        if (keyCode == KEYCODE_DPAD_DOWN) {
            return row >= rowCount - 1 ? PanelMove.NONE : PanelMove.DOWN;
        }
        if (keyCode == KEYCODE_DPAD_LEFT) {
            if (colCount <= 1) return PanelMove.NONE;
            return col <= 0 ? PanelMove.NONE : PanelMove.LEFT;
        }
        if (keyCode == KEYCODE_DPAD_RIGHT) {
            if (colCount <= 1) return PanelMove.NONE;
            return col >= colCount - 1 ? PanelMove.NONE : PanelMove.RIGHT;
        }
        return PanelMove.NONE;
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerNavModelTest"`
Expected: PASS — 18 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/inputcontrols/DrawerNavModel.java app/src/test/java/com/winlator/star/inputcontrols/DrawerNavModelTest.java
git commit -m "feat(input): model panel grid movement (up-on-first-row leaves to the rail)"
```

---

## Task 3: Level-aware B in `DrawerController`

**Files:**
- Modify: `app/src/main/java/com/winlator/star/inputcontrols/DrawerController.java`
- Modify: `app/src/test/java/com/winlator/star/inputcontrols/DrawerControllerTest.java`

**Interfaces:**
- Consumes: `DrawerNavModel.LEVEL_RAIL` / `LEVEL_PANEL`.
- Produces: a NEW overload `public static DrawerMenuAction menuActionAtLevel(int keyCode, boolean down, boolean drawerOpen, boolean editorActive, int level)`. The existing `menuAction(int, boolean, boolean, boolean)` becomes a call to it with `LEVEL_RAIL`, so all 10 existing tests keep passing (P4). At `LEVEL_RAIL` nothing changes; at `LEVEL_PANEL` B DOWN returns the new `DrawerMenuAction.PANEL_TO_RAIL`.

**Why this shape:** adding an enum value and an overload is additive. If instead B always meant "close", pressing B inside the panel would skip the rail — which is exactly the behavior the user does not want (N4).

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/winlator/star/inputcontrols/DrawerControllerTest.java` (before the final closing brace). The 10 existing tests are NOT modified:

```java
    @Test public void bFromTheRailStillCloses() {
        assertEquals(DrawerController.DrawerMenuAction.CLOSE_DRAWER,
                DrawerController.menuActionAtLevel(BUTTON_B, true, true, false,
                        DrawerNavModel.LEVEL_RAIL));
    }

    @Test public void bFromThePanelStepsBackToTheRail() {
        assertEquals(DrawerController.DrawerMenuAction.PANEL_TO_RAIL,
                DrawerController.menuActionAtLevel(BUTTON_B, true, true, false,
                        DrawerNavModel.LEVEL_PANEL));
    }

    @Test public void bNeverOpensEvenWithALevel() {
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuActionAtLevel(BUTTON_B, true, false, false,
                        DrawerNavModel.LEVEL_RAIL));
        assertEquals(DrawerController.DrawerMenuAction.PASS_THROUGH,
                DrawerController.menuActionAtLevel(BUTTON_B, true, false, false,
                        DrawerNavModel.LEVEL_PANEL));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerControllerTest"`
Expected: FAIL — `menuActionAtLevel` and `PANEL_TO_RAIL` do not exist.

- [ ] **Step 3: Write minimal implementation**

In `DrawerController.java`, add `PANEL_TO_RAIL` to the enum and add the overload (leave every existing method signature in place):

```java
    public enum DrawerMenuAction { CLOSE_DRAWER, ROUTE_TO_DRAWER, PASS_THROUGH, PANEL_TO_RAIL }
```

```java
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
```

Leave `backAction(...)` and `isDrawerButton(...)` exactly as they are (P1 and P4 depend on them).

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.inputcontrols.DrawerControllerTest"`
Expected: PASS — the 10 original tests plus the 3 new ones = 13 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/inputcontrols/DrawerController.java app/src/test/java/com/winlator/star/inputcontrols/DrawerControllerTest.java
git commit -m "feat(input): level-aware B - panel steps to rail, rail closes"
```

---

## Task 4: Observable bridge the Composable reads

**Files:**
- Create: `app/src/main/java/com/winlator/star/ui/DrawerNavBridge.kt`

**Interfaces:**
- Consumes: `DrawerNavModel.LEVEL_RAIL` / `LEVEL_PANEL`, `DrawerNavModel.clampRailIndex`.
- Produces (read by `XServerDrawer.kt`, written by `XServerDisplayActivity`):
  - `object DrawerNavBridge`
  - `var level: Int`
  - `var railIndex: Int`
  - `var panelRow: Int`
  - `var panelCol: Int`
  - `var panelRowCount: Int` / `var panelColCount: Int` — reported by the Composable so the Activity can clamp
  - `var railCount: Int` — reported by the Composable
  - `var activateSignal: Int` — incremented to ask the Composable to activate the highlighted item
  - `fun resetOnOpen()`, `fun moveRail(delta: Int)`, `fun railToPanel()`, `fun panelToRail()`, `fun movePanel(move: DrawerNavModel.PanelMove)`, `fun activate()`

- [ ] **Step 1: Write the failing test**

The bridge is deliberately a dumb state holder; everything worth testing lives in `DrawerNavModel`. The one thing that can silently break is a member-name typo, which would only surface as a CI compile error. Guard the agreed surface. Append to `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java` (before the final closing brace):

```java
    @Test public void navBridgeExposesTheAgreedSurface() throws Exception {
        File f = new File("src/main/java/com/winlator/star/ui/DrawerNavBridge.kt");
        if (!f.isFile()) f = new File("app/src/main/java/com/winlator/star/ui/DrawerNavBridge.kt");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        for (String member : new String[]{
                "var level", "var railIndex", "var panelRow", "var panelCol",
                "var panelRowCount", "var panelColCount", "var railCount", "var activateSignal",
                "fun resetOnOpen", "fun moveRail", "fun railToPanel", "fun panelToRail",
                "fun movePanel", "fun activate"}) {
            assertTrue("DrawerNavBridge must expose " + member, src.contains(member));
        }
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: FAIL — `DrawerNavBridge.kt` does not exist (the test throws `FileNotFoundException`).

- [ ] **Step 3: Write minimal implementation**

Create `app/src/main/java/com/winlator/star/ui/DrawerNavBridge.kt`:

```kotlin
package com.winlator.star.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.winlator.star.inputcontrols.DrawerNavModel

/**
 * Observable state shared between the Activity's controller input (writer) and the drawer
 * Composable (reader). The Activity owns the rules (DrawerNavModel) and only pushes positions and
 * an activation counter here; the Composable renders the highlight and performs the selection.
 * Deliberately dumb: everything worth testing lives in DrawerNavModel.
 */
object DrawerNavBridge {

    var level by mutableIntStateOf(DrawerNavModel.LEVEL_RAIL)
    var railIndex by mutableIntStateOf(0)
    var panelRow by mutableIntStateOf(0)
    var panelCol by mutableIntStateOf(0)

    // Reported by the Composable so the Activity can clamp movement without knowing the layout.
    var railCount by mutableIntStateOf(0)
    var panelRowCount by mutableIntStateOf(0)
    var panelColCount by mutableIntStateOf(0)

    // Bumped to ask the Composable to activate the currently highlighted item.
    var activateSignal by mutableIntStateOf(0)

    fun resetOnOpen() {
        level = DrawerNavModel.LEVEL_RAIL
        railIndex = 0
        panelRow = 0
        panelCol = 0
    }

    fun moveRail(delta: Int) {
        railIndex = DrawerNavModel.clampRailIndex(railIndex + delta, railCount)
    }

    fun railToPanel() {
        level = DrawerNavModel.LEVEL_PANEL
        panelRow = 0
        panelCol = 0
    }

    fun panelToRail() {
        level = DrawerNavModel.LEVEL_RAIL
    }

    fun movePanel(move: DrawerNavModel.PanelMove) {
        when (move) {
            DrawerNavModel.PanelMove.UP_FROM_FIRST_ROW -> panelToRail()
            DrawerNavModel.PanelMove.UP -> panelRow--
            DrawerNavModel.PanelMove.DOWN -> {
                panelRow++
                panelCol = 0
            }
            DrawerNavModel.PanelMove.LEFT -> panelCol--
            DrawerNavModel.PanelMove.RIGHT -> panelCol++
            DrawerNavModel.PanelMove.NONE -> Unit
        }
    }

    fun activate() {
        activateSignal++
    }
}
```

Note: `mutableIntStateOf` requires Compose 1.6+ (BOM 2024.02.00, already in use). If the compiler rejects it, use `mutableStateOf(0)` instead — the Composable usage is identical.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/ui/DrawerNavBridge.kt app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java
git commit -m "feat(input): add observable DrawerNavBridge for controller-driven drawer highlight"
```

---

## Task 5: Activity drives the model from the open drawer

**Files:**
- Modify: `app/src/main/java/com/winlator/star/XServerDisplayActivity.java:12826-12964`
- Modify: `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`

**Interfaces:**
- Consumes: `DrawerNavModel`, `DrawerController.menuActionAtLevel`, `DrawerNavBridge`.
- Produces: `handleControllerMenuKey` rewritten so the `down` branch moves/activates through the bridge instead of forwarding raw keys into the ComposeView.

**Critical detail — Java access to the Kotlin object:** `DrawerNavBridge` is a Kotlin `object`, so from Java it is `DrawerNavBridge.INSTANCE`. Kotlin properties `level`/`railIndex`/... become `getLevel()`/`setLevel(int)` etc. Call `DrawerNavBridge.INSTANCE.getLevel()`, `DrawerNavBridge.INSTANCE.setRailCount(n)`, `DrawerNavBridge.INSTANCE.moveRail(-1)`, `DrawerNavBridge.INSTANCE.movePanel(...)`, `DrawerNavBridge.INSTANCE.activate()`, `DrawerNavBridge.INSTANCE.railToPanel()`, `DrawerNavBridge.INSTANCE.panelToRail()`, `DrawerNavBridge.INSTANCE.getPanelRow()`.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java` (before the final closing brace):

```java
    @Test public void activityDrivesTheNavBridge() throws Exception {
        String src = activitySource();
        assertTrue("the Activity must drive the drawer nav bridge",
                src.contains("DrawerNavBridge.INSTANCE"));
        assertTrue("the Activity must use the level-aware menu action",
                src.contains("menuActionAtLevel("));
        assertTrue("the bridge must be reset when the drawer opens", src.contains("resetOnOpen()"));
    }

    @Test public void backStillOpensThroughTheExistingPath() throws Exception {
        String src = activitySource();
        assertTrue("Back-to-open must keep going through backAction (P1)",
                src.contains("DrawerController.backAction("));
        assertFalse("KEYCODE_BACK must NOT be redirected into the controller menu handler",
                src.contains("handleControllerMenuKey(KeyEvent.KEYCODE_BACK"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: FAIL — none of those strings are in the Activity yet.

- [ ] **Step 3: Write minimal implementation**

Add these imports beside the existing `import com.winlator.star.inputcontrols.DrawerController;`:

```java
import com.winlator.star.inputcontrols.DrawerNavModel;
import com.winlator.star.ui.DrawerNavBridge;
```

Replace the body of `handleControllerMenuKey` (`XServerDisplayActivity.java:12940-12964`) with:

```java
    private boolean handleControllerMenuKey(int kc, boolean down) {
        if (drawerLayout == null || environment == null) return false;
        boolean drawerOpen = drawerLayout.isDrawerOpen(GravityCompat.START);
        int level = DrawerNavBridge.INSTANCE.getLevel();
        DrawerController.DrawerMenuAction action = DrawerController.menuActionAtLevel(
                kc, down, drawerOpen, inGameControlsEditor != null, level);
        if (action == DrawerController.DrawerMenuAction.PASS_THROUGH) return false;
        if (action == DrawerController.DrawerMenuAction.CLOSE_DRAWER) {
            drawerLayout.closeDrawers();
            return true;
        }
        if (action == DrawerController.DrawerMenuAction.PANEL_TO_RAIL) {
            DrawerNavBridge.INSTANCE.panelToRail();
            return true;
        }
        if (!down) return true;

        if (kc == KeyEvent.KEYCODE_BUTTON_A || kc == KeyEvent.KEYCODE_DPAD_CENTER) {
            DrawerNavBridge.INSTANCE.activate();
            if (level == DrawerNavModel.LEVEL_RAIL) {
                DrawerNavBridge.INSTANCE.railToPanel();
            }
            return true;
        }

        if (level == DrawerNavModel.LEVEL_RAIL) {
            DrawerNavBridge.INSTANCE.moveRail(DrawerNavModel.railIndexDelta(kc));
            return true;
        }

        DrawerNavBridge.INSTANCE.movePanel(DrawerNavModel.panelMove(
                kc, DrawerNavBridge.INSTANCE.getPanelRow(), DrawerNavBridge.INSTANCE.getPanelRowCount(),
                DrawerNavBridge.INSTANCE.getPanelCol(), DrawerNavBridge.INSTANCE.getPanelColCount()));
        return true;
    }
```

Note the deliberate change from the old code: `A` is still consumed, but it no longer dispatches a synthetic `DPAD_CENTER` into the ComposeView — the bridge's `activate()` is what makes the Composable act. If any other caller depended on the synthetic dispatch, it is now dead; verify with the Grep tool that `dispatchToDrawer` still has at least one caller, and if not, remove it and its now-unused imports. Only remove it if the Grep shows zero callers.

In the `DrawerLayout.SimpleDrawerListener.onDrawerOpened` block (`XServerDisplayActivity.java:1934` area, right after `XServerDialogState.INSTANCE.setMenuOpen(true);`), add the reset:

```java
            DrawerNavBridge.INSTANCE.resetOnOpen();
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/XServerDisplayActivity.java app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java
git commit -m "feat(input): drive drawer navigation from controller keys via DrawerNavModel"
```

---

## Task 6: Drawer renders the rail highlight and performs selection

**Files:**
- Modify: `app/src/main/java/com/winlator/star/ui/XServerDrawer.kt` (rail at `:194-305`, helpers at `:647-774`)
- Modify: `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`

**Interfaces:**
- Consumes: `DrawerNavBridge.level`, `railIndex`, `railCount`, `activateSignal`.
- Produces: the rail reports `railCount`, draws a highlight ring on `railIndex` while `level == LEVEL_RAIL`, and selects the highlighted tab when `activateSignal` changes.

**Implementation notes (read before coding):**
- The rail currently gets `Modifier.focusRequester(firstFocus)` on the first button (`:234`) purely for the cosmetic Compose focus ring. Keep it; it is harmless. The controller highlight is ADDITIVE, driven by `DrawerNavBridge`, not by Compose focus.
- The rail order to encode (must match composition order): GRAPHICS, HUD, RESHADE, CONTROLS, AUDIO, ADVANCED, then FRIENDS (only if `friendsSource.tabVisible`, `:257`), then TV (only if `FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported)`, `:271`). The bottom group (TASK_MANAGER, pause, exit at `:280-302`) is NOT part of the navigable rail in this task — leave it as-is.
- `railCount` must equal the number of navigable (top-group) entries actually composed.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`:

```java
    @Test public void drawerReadsTheNavBridge() throws Exception {
        File f = new File("src/main/java/com/winlator/star/ui/XServerDrawer.kt");
        if (!f.isFile()) f = new File("app/src/main/java/com/winlator/star/ui/XServerDrawer.kt");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue("the drawer must read the nav bridge", src.contains("DrawerNavBridge"));
        assertTrue("the drawer must report its rail count", src.contains("railCount"));
        assertTrue("the drawer must react to the activation signal", src.contains("activateSignal"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: FAIL — `XServerDrawer.kt` does not mention `DrawerNavBridge`.

- [ ] **Step 3: Write minimal implementation**

In `XServerDrawer.kt`, inside `XServerDrawer()` after `val state = XServerDrawerState` (`:162`), add:

```kotlin
    // Controller navigation: the Activity pushes positions here; this composable renders the
    // highlight and performs the selection. Starts on the rail (level 0), index 0.
    val navLevel = DrawerNavBridge.level
    val navRailIndex = DrawerNavBridge.railIndex
    val navActivateRail = DrawerNavBridge.activateSignal
```

Add a `LaunchedEffect` that selects the highlighted tab whenever `activateSignal` changes, placed after the existing `LaunchedEffect(menuOpen)` at `:188`:

```kotlin
    // A on the rail: select whatever the highlight is on. Mirrors the tap path exactly.
    LaunchedEffect(navActivateRail) {
        if (navActivateRail == 0) return@LaunchedEffect
        if (DrawerNavBridge.level != DrawerNavModel.LEVEL_RAIL) return@LaunchedEffect
        val railOrder = listOf(
            TabType.GRAPHICS, TabType.HUD, TabType.RESHADE, TabType.CONTROLS,
            TabType.AUDIO, TabType.ADVANCED,
        ) + (if (friendsSource.tabVisible) listOf(TabType.FRIENDS) else emptyList()) +
            (if (com.winlator.star.FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported)) {
                listOf(TabType.TV)
            } else emptyList())
        railOrder.getOrNull(DrawerNavBridge.railIndex)?.let { handleTabClick(it, state) }
    }
```

Report the rail count and draw the highlight. The cleanest minimal change is to give the rail a shared "highlight modifier" and apply it to whichever entry sits at the highlighted index. Add this helper next to `TabIconButton` (near `:647`):

```kotlin
/** Draws the controller-navigation ring on the rail entry the Activity is currently on. */
@Composable
private fun Modifier.railControllerHighlight(index: Int, accent: Color): Modifier {
    val highlighted = DrawerNavBridge.level == DrawerNavModel.LEVEL_RAIL &&
        DrawerNavBridge.railIndex == index
    return this.then(
        if (highlighted) Modifier.border(2.dp, accent, RoundedCornerShape(14.dp)) else Modifier
    )
}
```

Then, in the top-group `Column` (`:233-277`), wrap each of the six unconditional buttons in an indexed application. The simplest correct approach that does not reorder composition: introduce a local counter that increments once per composed top-group entry, and use it as the index. Replace the top-group `Column` body with:

```kotlin
                    // Top group: section tabs. railIndex counts these in this exact order
                    // (FRIENDS and TV are conditional, so the index must be assigned at
                    // composition time, not hard-coded).
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        var railIndex = 0
                        TabIconButton(R.drawable.icon_display, selectedTab == TabType.GRAPHICS,
                            Modifier.focusRequester(firstFocus).railControllerHighlight(railIndex++, accent)) {
                            handleTabClick(TabType.GRAPHICS, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        FpsTabButton(isSelected = selectedTab == TabType.HUD) {
                            handleTabClick(TabType.HUD, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_screen_effect, selectedTab == TabType.RESHADE,
                            Modifier.railControllerHighlight(railIndex++, accent)) {
                            handleTabClick(TabType.RESHADE, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_input_controls, selectedTab == TabType.CONTROLS,
                            Modifier.railControllerHighlight(railIndex++, accent)) {
                            handleTabClick(TabType.CONTROLS, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_audio, selectedTab == TabType.AUDIO,
                            Modifier.railControllerHighlight(railIndex++, accent)) {
                            handleTabClick(TabType.AUDIO, state)
                        }
                        Spacer(Modifier.height(6.dp))
                        TabIconButton(R.drawable.icon_debug, selectedTab == TabType.ADVANCED,
                            Modifier.railControllerHighlight(railIndex++, accent)) {
                            handleTabClick(TabType.ADVANCED, state)
                        }
                        if (friendsSource.tabVisible) {
                            Spacer(Modifier.height(6.dp))
                            FriendsTabButton(
                                isSelected = selectedTab == TabType.FRIENDS,
                                unread = friendsUnread.values.any { it > 0 },
                            ) {
                                handleTabClick(TabType.FRIENDS, state)
                            }
                            railIndex++
                        }
                        if (com.winlator.star.FeatureFlags.TV_OUTPUT_ENABLED && (tvConnected || castSupported)) {
                            Spacer(Modifier.height(6.dp))
                            TvTabButton(selectedTab == TabType.TV) {
                                handleTabClick(TabType.TV, state)
                            }
                            railIndex++
                        }
                    }
```

**Important correctness issue to resolve during implementation:** `FpsTabButton`, `FriendsTabButton`, and `TvTabButton` do not currently accept a `modifier` parameter, so the ring cannot be applied to them by the snippet above alone. Add a `modifier: Modifier = Modifier` parameter to each of those three composables (`FpsTabButton` at `:691`, `FriendsTabButton` in `XServerFriendsTab.kt:93`, and `TvTabButton` at `:735`), pass it through to their root `Box`/container `Modifier.then(modifier)`, and pass `Modifier.railControllerHighlight(railIndex++, accent)` at each call site. Do NOT skip the ring on HUD/FRIENDS/TV — the highlight must appear on every rail entry.

Report the count so the Activity's clamping works. Immediately after the top-group `Column` closes (still inside the rail `Column` at `:224`), add:

```kotlin
                    LaunchedEffect(railIndex) { DrawerNavBridge.railCount = railIndex }
```

(If `railIndex` is not in scope there, hoist it: declare `var railIndex = 0` at the top of the top-group `Column` and move the `LaunchedEffect` inside that Column, after the last entry.)

Add the imports if missing:

```kotlin
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import com.winlator.star.inputcontrols.DrawerNavModel
```

`DrawerNavBridge` is in the same package (`com.winlator.star.ui`), so it needs no import. The existing `RoundedCornerShape` and `border` imports may already be present at the top of the file — check before adding duplicates.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/ui/XServerDrawer.kt app/src/main/java/com/winlator/star/ui/XServerFriendsTab.kt app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java
git commit -m "feat(input): highlight and activate the rail from controller navigation"
```

---

## Task 7: Panel cell highlight for the Controls tab

**Files:**
- Modify: `app/src/main/java/com/winlator/star/ui/XServerDrawer.kt` (the content `when` at `:319-337` and `ControlsContent`)
- Modify: `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`

**Interfaces:**
- Consumes: `DrawerNavBridge.level`, `panelRow`, `panelCol`, `panelRowCount`, `panelColCount`, `activateSignal`.
- Produces: a `Modifier.drawerNavCell(row, col, colCount, accent)` helper that reports the grid shape and draws the ring; applied to the Controls tab's controls.

**Scope (deliberate, per S2):** wire ONLY `ControlsContent` (the composable behind `TabType.CONTROLS`). Other tabs adopt the helper incrementally. State this in the release report.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`:

```java
    @Test public void drawerExposesPanelNavigationHelper() throws Exception {
        File f = new File("src/main/java/com/winlator/star/ui/XServerDrawer.kt");
        if (!f.isFile()) f = new File("app/src/main/java/com/winlator/star/ui/XServerDrawer.kt");
        String src = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        assertTrue("the drawer must expose a panel cell helper",
                src.contains("fun Modifier.drawerNavCell"));
        assertTrue("the panel must report its row count", src.contains("panelRowCount"));
        assertTrue("the panel must report its column count", src.contains("panelColCount"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: FAIL — `drawerNavCell` does not exist.

- [ ] **Step 3: Write minimal implementation**

Add this helper to `XServerDrawer.kt` (near the rail helpers, around `:774`):

```kotlin
/**
 * Marks a panel control as a controller-navigable cell at (row, col) and draws the highlight ring
 * when the Activity's controller navigation is sitting on it. Rows/columns are reported to
 * DrawerNavBridge so the Activity can clamp D-pad movement without knowing this tab's layout.
 */
@Composable
private fun Modifier.drawerNavCell(row: Int, col: Int, colCount: Int, accent: Color): Modifier {
    val highlighted = DrawerNavBridge.level == DrawerNavModel.LEVEL_PANEL &&
        DrawerNavBridge.panelRow == row && DrawerNavBridge.panelCol == col
    LaunchedEffect(row, colCount) {
        if (row + 1 > DrawerNavBridge.panelRowCount) DrawerNavBridge.panelRowCount = row + 1
        if (colCount > DrawerNavBridge.panelColCount) DrawerNavBridge.panelColCount = colCount
    }
    return this.then(
        if (highlighted) Modifier.border(2.dp, accent, RoundedCornerShape(10.dp)) else Modifier
    )
}
```

Then apply it inside `ControlsContent`. Use the Grep tool to find `private fun ControlsContent` in `XServerDrawer.kt`, read that composable, and for each top-level control row append `.drawerNavCell(row = N, col = M, colCount = K, accent = accent)` to the row's modifier chain. Number rows top-to-bottom from 0; within a row, number columns left-to-right from 0 with `colCount` equal to the number of controls in that row. Use a row with a single control as `col = 0, colCount = 1`. If `accent` is not already in scope inside `ControlsContent`, read the top of the composable — if it is defined there, use it; otherwise add `val accent = MaterialTheme.colorScheme.primary` at the top of the composable.

The panel must also activate: add this near the rail activation effect from Task 6, so A inside the panel triggers the highlighted control's click. Since activation is per-control, the simplest robust mechanism is for the cell helper to remember the click and fire it on `activateSignal` change. Extend the helper's signature to accept the click and wire it:

```kotlin
@Composable
private fun Modifier.drawerNavCell(
    row: Int,
    col: Int,
    colCount: Int,
    accent: Color,
    onActivate: () -> Unit = {},
): Modifier {
    val highlighted = DrawerNavBridge.level == DrawerNavModel.LEVEL_PANEL &&
        DrawerNavBridge.panelRow == row && DrawerNavBridge.panelCol == col
    val signal = DrawerNavBridge.activateSignal
    LaunchedEffect(row, colCount) {
        if (row + 1 > DrawerNavBridge.panelRowCount) DrawerNavBridge.panelRowCount = row + 1
        if (colCount > DrawerNavBridge.panelColCount) DrawerNavBridge.panelColCount = colCount
    }
    LaunchedEffect(signal) {
        if (signal != 0 && highlighted) onActivate()
    }
    return this.then(
        if (highlighted) Modifier.border(2.dp, accent, RoundedCornerShape(10.dp)) else Modifier
    )
}
```

Pass each row's existing tap handler as `onActivate` so the controller path performs exactly the same action as a tap.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/ui/XServerDrawer.kt app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java
git commit -m "feat(input): panel cell highlight and activation for the Controls tab"
```

---

## Task 8: Route stick/hat motion through the same path

**Files:**
- Modify: `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` (`translateDrawerControllerMotion` at `:12749-12759`, `sendDrawerDpadTap` at `:12694-12698`)

**Interfaces:**
- Consumes: `handleControllerMenuKey` (Task 5).
- Produces: stick/hat movement enters `handleControllerMenuKey` with a synthetic D-pad code so it behaves identically to physical D-pad.

- [ ] **Step 1: Write the failing test**

Append to `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`:

```java
    @Test public void stickMotionUsesTheControllerMenuHandler() throws Exception {
        String src = activitySource();
        assertTrue("stick/hat translation must go through handleControllerMenuKey",
                src.contains("handleControllerMenuKey(KeyEvent.KEYCODE_DPAD"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: FAIL if the translation currently dispatches into the ComposeView instead of calling `handleControllerMenuKey`. If it already calls `handleControllerMenuKey(...)`, the test passes — record that in the report and skip to Step 5.

- [ ] **Step 3: Write minimal implementation**

Read `translateDrawerControllerMotion` (`XServerDisplayActivity.java:12749-12759`) and `sendDrawerDpadTap` (`:12694-12698`). Change the translation so that where it currently sends a synthetic D-pad key into the ComposeView, it instead calls:

```java
            handleControllerMenuKey(KeyEvent.KEYCODE_DPAD_UP, true);
```

(or `KEYCODE_DPAD_DOWN` / `_LEFT` / `_RIGHT` for the other directions), added to the imports if `KeyEvent` is not already imported. This gives the stick and the physical D-pad one code path. If `sendDrawerDpadTap` becomes unused, remove it only after a Grep confirms zero callers.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testStandardDebugUnitTest --tests "com.winlator.star.DrawerRegressionGuardTest"`
Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/XServerDisplayActivity.java app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java
git commit -m "refactor(input): route stick/hat drawer navigation through handleControllerMenuKey"
```

---

## Task 9: Green CI and release

**Files:**
- No source changes unless CI reveals a compile error, in which case fix forward in the owning task's file.

- [ ] **Step 1: Push and run the full CI gate**

```bash
git push origin main
gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork
```

Wait ~5-10 minutes, then:

```bash
gh run list --repo i0trost01/BannerlatorFork --workflow=fork-ci.yml --limit 1
gh run view <RUN_ID> --repo i0trost01/BannerlatorFork --json status,conclusion
```

Expected: `"conclusion":"success"`. If it fails, run `gh run view <RUN_ID> --repo i0trost01/BannerlatorFork --log-failed` and fix the owning task, then repeat. Do NOT proceed on red.

- [ ] **Step 2: Confirm the whole suite ran (not a filtered class)**

```bash
gh run view <RUN_ID> --repo i0trost01/BannerlatorFork --log | Select-String -Pattern "tests completed|BUILD SUCCESSFUL"
```

Expected: `BUILD SUCCESSFUL`, and no `tests completed, X failed`.

- [ ] **Step 3: Cut the release**

```bash
gh workflow run fork-release.yml --repo i0trost01/BannerlatorFork -f version=3.1.3-fork.21
```

Wait for `"conclusion":"success"`, then:

```bash
gh release view 3.1.3-fork.21 --repo i0trost01/BannerlatorFork --json tagName,assets
```

Expected: one asset, `Bannerlator-Fork-3.1.3-fork.21-Standard-signed.apk`.

- [ ] **Step 4: Report to the user**

Report the release URL and this on-device checklist. The first three are REGRESSION checks for the already-working behavior and must still pass:

1. (P1) Drawer closed, press Back → the drawer opens.
2. (P2) Drawer closed, press B → the game receives B; the drawer stays shut.
3. Highlight appears on the top rail icon when the drawer opens.
4. D-pad Up/Down moves the highlight along the rail.
5. Press A on a rail icon → that tab opens and the highlight moves into the panel.
6. D-pad moves the highlight among the Controls tab's controls; A activates the highlighted one.
7. Press B while in the panel → back to the rail. Press B again → the drawer closes.
8. Press Back while the drawer is open → the drawer closes.
9. With the drawer closed, the game receives D-pad/A/B normally (no input swallowed).

---

## Self-Review

**1. Spec coverage:**
- P1 (Back opens) → preserved: Task 5 Step 3 leaves `backAction` untouched; guard `backStillOpensThroughTheExistingPath` asserts `DrawerController.backAction(` is present and that `KEYCODE_BACK` is NOT redirected into `handleControllerMenuKey`.
- P2 (B never opens) → preserved: `menuActionAtLevel` returns `PASS_THROUGH` when `!drawerOpen` (Task 3); test `bNeverOpensEvenWithALevel`.
- P3 (B closes when open at rail) → preserved: Task 3; test `bFromTheRailStillCloses`.
- P4 (existing API/tests) → Task 3 keeps `menuAction(int,boolean,boolean,boolean)` as a 1-line delegate; existing `backAction`/`isDrawerButton` unchanged.
- N1 two levels / rail start → Tasks 1, 4 (`resetOnOpen` → `LEVEL_RAIL`, 0), Task 6 (rail highlight).
- N2 reset on open → Task 4 `resetOnOpen`, called in Task 5 Step 3.
- N3 A semantics → Task 5 Step 3 (A/CENTER: activate; rail → `railToPanel`), Task 6 (rail activation effect), Task 7 (panel activation).
- N4 B one level → Task 3 `menuActionAtLevel` + tests, Task 5 Step 3.
- N5 Back closes when open → unchanged existing `backAction(true, ...)` → `CLOSE`; `handleNavigationBackPressed` (`:7194`) already calls it. No task needed; noted here for completeness.
- N6 D-pad at rail → Task 1 `railIndexDelta`/`clampRailIndex`/`isRailNavigationKey`, Task 5 Step 3.
- N7 D-pad at panel + Up-on-first-row → Task 2 `panelMove`, Task 4 `movePanel`, Task 5 Step 3.
- N8 stick/hat → Task 8.
- N9 consume while open → Task 3 `menuActionAtLevel` returns non-`PASS_THROUGH` for nav keys while open; Task 5 returns true.
- S1 Steam pads → explicitly out of scope.
- S2 non-Controls tabs → explicitly out of scope, stated in Task 7.

**2. Placeholder scan:** no "TBD"/"implement later". The conditional instructions (`mutableIntStateOf` availability, whether `sendDrawerDpadTap`/`dispatchToDrawer` become unused, whether the three tab composables need a `modifier` parameter, whether stick translation already routes correctly) each state exactly what to do in both branches, and Task 6 calls out the `modifier`-parameter issue as a required change rather than an optional one.

**3. Type consistency:** `LEVEL_RAIL`/`LEVEL_PANEL` are `int` constants used identically in Tasks 1, 3, 4, 5, 6, 7. `PanelMove` values (`UP_FROM_FIRST_ROW`, `UP`, `DOWN`, `LEFT`, `RIGHT`, `NONE`) are defined in Task 2 and consumed in Task 4's `movePanel` `when` — all six arms present. `DrawerMenuAction.PANEL_TO_RAIL` is defined in Task 3 and consumed in Task 5. Bridge member names in Task 4's guard exactly match Task 4's implementation and Task 5/6/7's usage. `railCount` is written in Task 6 and read in Task 4's `moveRail` — both present.
