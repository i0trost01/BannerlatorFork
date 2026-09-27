# Steam Input for Own-Manifest Games on the RealSteam (steamhost) Path

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Monster Train 2 (appId 2742830) and other games that ship their own Steam Input action manifest actually receive the pad in Bannerlator's genuine-Steam (SteamLite / RealSteam) launch mode, by ENABLING Steam Input for the app the way the genuine Valve client expects.

**Architecture:** The Goldberg (gbe_fork) path cannot deliver this game's input (see §Root Cause). The working reference is GameNative, whose Monster Train 2 controller support runs through the genuine Valve Steam client ("steamhost" = headless `steam.exe` driving real `steamclient`/`steamservice`). Bannerlator's counterpart is the RealSteam/SteamLite launcher (`RealSteamLauncher.prepare`), which already stages the genuine client + clean-room agent and already edits the client's `localconfig.vdf` for a *different* purpose. We add the missing piece: when a game ships its own Steam Input manifest (or the user flips the per-game toggle), write the client's Steam Input preference ON (`system/SteamController_*Support=1`, `apps/<appId>/UseSteamControllerConfig=2`), stage the game's own controller layout, and tell the agent. Today the launcher only writes those keys to `0` (the "Controller passthrough" path, which deliberately disables Steam Input).

**Tech Stack:** Android (Kotlin + Java mixed), Gradle, JVM unit tests (`:app:testStandardDebugUnitTest`). RealSteam staging is pure file I/O under the container's `drive_c`.

**Spec:** No separate spec doc. The behavioural contract is copied verbatim from the working reference `utkarshdalal/GameNative` (read at `master`):
- `app/src/main/java/app/gamenative/utils/SteamUtils.kt` → `isSteamInputEnabled`, `setSteamInputPreference`, `writeSteamHostControllerLayout`
- `app/src/main/java/app/gamenative/service/SteamService.kt` → `hasOwnSteamInputManifest`, `resolveSteamHostControllerVdfText`, `HOST_CONTROLLER_TYPES`
- `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` → `STEAMHOST_STEAMINPUT=1`

---

## Root Cause (why Goldberg cannot do this)

Evidence (gbe_fork `dev`, `dll/steam_controller.cpp`, and its `README.release.md`):

1. `SetInputActionManifestFilePath()` — the API a game uses to hand Steam its bundled action manifest — is a stub that **returns `false`** (`//TODO SteamInput005`). `BWaitForData`, `BNewDataAvailable`, `EnableDeviceCallbacks`, `EnableActionEventCallbacks` are likewise stubs.
2. `GetConnectedControllers()` returns a controller only when `GamepadIsConnected(GAMEPAD_0..3)` — i.e. only when a physical **XInput** device is visible to the Windows process.
3. The whole controller feature is compiled only in the **Windows experimental** build.

Monster Train 2 is "Full Controller Support" **and** "Steam Input API Supported" (Unity) and ships `steam_input_manifest.vdf` + `config_2742830_controller_*.vdf` in `MonsterTrain2_Data/StreamingAssets/SteamInput/`. It hands input to Steam Input, so with an emulated `ISteamInput` that cannot answer `SetInputActionManifestFilePath`/`BWaitForData` the game sees no pad. This is confirmed by the working reference: GameNative makes it work through the **genuine client**, not gbe_fork.

Do **not** spend more effort on the Goldberg path for this game.

## Agent-side follow-up (out of this repo)

This branch is the APP-SIDE HALF. The clean-room Steam agent (`agent-src`, outside this repo) must:

- **(a)** read env `BL_AGENT_STEAMINPUT=1`, and
- **(b)** load `steamhost_controller_<appId>.vdf` from the prefix Steam dir and activate it for the app.

Both names are frozen: the app writes exactly these. Until the agent is updated, merging this branch writes the localconfig keys + stages the VDF + sets the env var, but the input path stays inert.

## Global Constraints

- Mixed Kotlin/Java module; match each file's existing language and style.
- Comments: the two files we touch are heavily commented in a specific house style (block comments explaining *why*, with device-date provenance). New code here MUST carry a short "why" comment in that style; do NOT strip existing comments.
- Kotlin `object` is reached from Java as `Type.INSTANCE.method(...)` (e.g. `SteamPrefs.INSTANCE.getUseSteamInput(appId)`).
- Unit tests are JVM-only; `File`-based helpers are pure and must be tested with `TemporaryFolder`.
- No new Gradle dependencies.
- The RealSteam controller logic is best-effort: any failure is logged and **must never block a launch**.
- Existing behaviour when the game has no own manifest and the toggle is off MUST be byte-for-byte unchanged.

---

## File Structure

| File | Responsibility | Change |
|---|---|---|
| `app/src/main/java/com/winlator/star/store/steaminput/SteamInputLayouts.kt` | Resolve a game's own Steam Input manifest → config VDF text | Add `resolveHostConfigText()` (Xbox 360-first) and a `types` overload of `pickControllerConfigPath()` |
| `app/src/main/java/com/winlator/star/store/RealSteamLauncher.java` | Stage the genuine client + agent for a launch | Add `injectSteamInputPreference()`, `applySteamInput()`, `stageHostControllerLayout()`; wire the enable decision + agent env into `prepare()` |
| `app/src/main/java/com/winlator/star/ui/screens/LaunchMethodSheet.kt` | Launch popup | Show the existing "Use Steam Input" toggle for `STEAMLITE` too, not only `GOLDBERG` |
| `app/src/test/java/com/winlator/star/store/steaminput/SteamInputLayoutsTest.kt` | Host-layout resolution tests | Extend |
| `app/src/test/java/com/winlator/star/store/RealSteamSteamInputTest.java` | localconfig preference transform tests | Create |

No changes to `XServerDisplayActivity.java`: the enable decision is computed **inside** `prepare()` from values it already receives (`ctx`, `appId`, `hostInstallDir`), so no call-site signature churn.

---

### Task 1: Host layout resolution (prefer Xbox 360)

Why: the genuine headless host presents/identifies as an **Xbox 360** pad, so GameNative loads the `controller_xbox360` config for the host even though the game's own default preference is Xbox One. We must pick the same file.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/steaminput/SteamInputLayouts.kt`
- Test: `app/src/test/java/com/winlator/star/store/steaminput/SteamInputLayoutsTest.kt`

**Interfaces:**
- Consumes: existing `findManifest(File): File?`, `pickControllerConfigPath(String): String?`
- Produces:
  - `fun resolveHostConfigText(installRoot: File): String?` — the game's own config VDF text, preferring `controller_xbox360` → `controller_xboxone` → `controller_generic`; `null` when the game ships no manifest/config.
  - `internal fun pickControllerConfigPath(manifestText: String, types: List<String>): String?`

- [ ] **Step 1: Write the failing tests**

Append to `SteamInputLayoutsTest.kt` (inside the class body; it already has `@get:Rule val tmp = TemporaryFolder()` and imports `assertEquals/assertNotNull/assertTrue/assertNull/File`):

```kotlin
    // ── host layout resolution (Xbox 360-first) ────────────────────────────────────────────────────

    @Test
    fun pickControllerConfigPath_prefersXbox360_whenTypesSaySo() {
        val m = """
            "Action Manifest"
            {
                "configurations"
                {
                    "controller_xboxone" { "0" { "path" "one.vdf" } }
                    "controller_xbox360" { "0" { "path" "three60.vdf" } }
                }
            }
        """.trimIndent()
        assertEquals("three60.vdf",
            SteamInputLayouts.pickControllerConfigPath(m, listOf("controller_xbox360", "controller_xboxone")))
    }

    @Test
    fun resolveHostConfigText_readsTheXbox360Config() {
        val root = tmp.newFolder("Monster Train 2")
        val si = File(root, "MonsterTrain2_Data/StreamingAssets/SteamInput").apply { mkdirs() }
        File(si, "steam_input_manifest.vdf").writeText(
            "\"Action Manifest\"\n{\n\"configurations\"\n{\n" +
                "\"controller_xboxone\" { \"0\" { \"path\" \"one.vdf\" } }\n" +
                "\"controller_xbox360\" { \"0\" { \"path\" \"three60.vdf\" } }\n}\n}\n")
        File(si, "one.vdf").writeText("\"controller_mappings\"\n{\n\"title\" \"ONE\"\n}\n")
        File(si, "three60.vdf").writeText("\"controller_mappings\"\n{\n\"title\" \"XBOX360\"\n}\n")

        val text = SteamInputLayouts.resolveHostConfigText(root)
        assertNotNull(text)
        assertTrue("expected the Xbox 360 config", text!!.contains("XBOX360"))
    }

    @Test
    fun resolveHostConfigText_noManifest_returnsNull() {
        val root = tmp.newFolder("PlainGame")
        File(root, "game.exe").writeText("MZ")
        assertNull(SteamInputLayouts.resolveHostConfigText(root))
    }
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `gradlew.bat :app:testStandardDebugUnitTest --tests "com.winlator.star.store.steaminput.SteamInputLayoutsTest"`
Expected: FAIL — `pickControllerConfigPath` has no 2-arg overload / `resolveHostConfigText` unresolved.
(No local Android SDK? Dispatch the CI gate instead — see Task 5 "Test gate" — and expect the same failure.)

- [ ] **Step 3: Implement in `SteamInputLayouts.kt`**

Replace the `CONTROLLER_TYPES` block and `resolveOwnManifestConfig`/`pickControllerConfigPath` with the following (keep `findManifest`, `findIn`, `firstNamed`, `chooseLayoutSource`, `resolve`, `hasOwnManifest` exactly as they are):

```kotlin
    private val CONTROLLER_TYPES = listOf("controller_xboxone", "controller_xbox360", "controller_generic")

    /** The headless genuine-Steam host identifies as an Xbox 360 pad, so it wants the 360 config
     *  (GameNative HOST_CONTROLLER_TYPES). */
    private val HOST_CONTROLLER_TYPES = listOf("controller_xbox360", "controller_xboxone", "controller_generic")

    /**
     * The controller config VDF the genuine Steam client should use for this game, read from the game's
     * OWN action manifest and preferring the Xbox 360 mapping. Null when the game ships none.
     */
    fun resolveHostConfigText(installRoot: File): String? =
        resolveManifestConfig(installRoot, HOST_CONTROLLER_TYPES)

    internal fun resolveOwnManifestConfig(installRoot: File): String? =
        resolveManifestConfig(installRoot, CONTROLLER_TYPES)

    private fun resolveManifestConfig(installRoot: File, types: List<String>): String? {
        val manifest = findManifest(installRoot) ?: return null
        val dir = manifest.parentFile ?: return null
        val text = runCatching { manifest.readText() }.getOrNull() ?: return null
        val rel = pickControllerConfigPath(text, types) ?: return null
        val cfg = File(dir, rel.replace('\\', '/'))
        return runCatching { cfg.takeIf { it.isFile }?.readText() }.getOrNull()
    }

    internal fun pickControllerConfigPath(manifestText: String): String? =
        pickControllerConfigPath(manifestText, CONTROLLER_TYPES)

    internal fun pickControllerConfigPath(manifestText: String, types: List<String>): String? {
        val lines = manifestText.lines()
        val pathRe = Regex("\"path\"\\s+\"([^\"]+)\"")
        for (type in types) {
            val start = lines.indexOfFirst { it.trim().startsWith("\"$type\"") }
            if (start < 0) continue
            for (i in start + 1 until lines.size) {
                val line = lines[i]
                if (line.trim().startsWith("\"controller_")) break
                pathRe.find(line)?.let { return it.groupValues[1] }
            }
        }
        return null
    }
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `gradlew.bat :app:testStandardDebugUnitTest --tests "com.winlator.star.store.steaminput.*"`
Expected: PASS (all four existing steaminput test classes plus the three new cases).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/steaminput/SteamInputLayouts.kt app/src/test/java/com/winlator/star/store/steaminput/SteamInputLayoutsTest.kt
git commit -m "feat(steam-input): resolve the Xbox 360 host layout from a game's own manifest"
```

---

### Task 2: localconfig Steam Input preference transform

Why: this is the only thing that actually makes the genuine client turn Steam Input ON for a title (GameNative `setSteamInputPreference`). Pure string transform → unit-testable without a device.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/RealSteamLauncher.java`
- Test: `app/src/test/java/com/winlator/star/store/RealSteamSteamInputTest.java` (create)

**Interfaces:**
- Consumes: existing `injectVdfKeys(String content, String[] path, Map<String,String> keys): String?` (package-private) and `STEAM_INPUT_KEYS` (private, same file).
- Produces: `static String injectSteamInputPreference(String content, int appId, boolean enabled)` — package-private; `null` when `content` is not a `UserLocalConfigStore` shape.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/RealSteamSteamInputTest.java`:

```java
package com.winlator.star.store;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** Pure tests for the RealSteam localconfig Steam Input preference (mirrors GameNative's
 *  setSteamInputPreference: system/SteamController_*Support + apps/&lt;id&gt;/UseSteamControllerConfig). */
public class RealSteamSteamInputTest {

    private static final String EMPTY = "\"UserLocalConfigStore\"\n{\n}\n";

    /** Collapse all runs of whitespace so the indentation injectVdfKeys picks doesn't matter. */
    private static String norm(String s) { return s.replaceAll("\\s+", " "); }

    @Test
    public void enable_setsSupportKeysToOne_andPerAppForceOn() {
        String out = RealSteamLauncher.injectSteamInputPreference(EMPTY, 2742830, true);
        assertNotNull(out);
        String n = norm(out);
        assertTrue(n.contains("\"SteamController_XBoxSupport\" \"1\""));
        assertTrue(n.contains("\"SteamController_GenericGamepadSupport\" \"1\""));
        assertTrue(n.contains("\"2742830\""));
        assertTrue(n.contains("\"UseSteamControllerConfig\" \"2\""));
    }

    @Test
    public void disable_setsSupportKeysToZero_andPerAppGlobalDefault() {
        String out = RealSteamLauncher.injectSteamInputPreference(EMPTY, 2742830, false);
        assertNotNull(out);
        String n = norm(out);
        assertTrue(n.contains("\"SteamController_XBoxSupport\" \"0\""));
        assertTrue(n.contains("\"UseSteamControllerConfig\" \"0\""));
    }

    @Test
    public void enable_isIdempotent() {
        String once = RealSteamLauncher.injectSteamInputPreference(EMPTY, 2742830, true);
        String twice = RealSteamLauncher.injectSteamInputPreference(once, 2742830, true);
        assertEquals(once, twice);
    }

    @Test
    public void notALocalConfig_returnsNull() {
        assertNull(RealSteamLauncher.injectSteamInputPreference("\"Other\"\n{\n}\n", 2742830, true));
    }

    @Test
    public void preserveExistingKeys() {
        String in = "\"UserLocalConfigStore\"\n{\n\t\"system\"\n\t{\n\t\t\"EnableGameOverlay\"\t\t\"0\"\n\t}\n}\n";
        String out = RealSteamLauncher.injectSteamInputPreference(in, 2742830, true);
        assertNotNull(out);
        assertTrue(norm(out).contains("\"EnableGameOverlay\" \"0\""));
        assertTrue(norm(out).contains("\"SteamController_XBoxSupport\" \"1\""));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gradlew.bat :app:testStandardDebugUnitTest --tests "com.winlator.star.store.RealSteamSteamInputTest"`
Expected: FAIL — `injectSteamInputPreference` not defined.

- [ ] **Step 3: Implement in `RealSteamLauncher.java`**

Insert immediately **after** the existing `injectSteamInputKeys(...)` wrapper (around line 596):

```java
    /**
     * Force the genuine client's Steam Input preference for one app into a localconfig.vdf: the four
     * SteamController_*Support keys under {@code UserLocalConfigStore > system}, and the per-app
     * {@code apps/<appId>/UseSteamControllerConfig} (2 = force on, 0 = global default). Mirrors
     * GameNative's setSteamInputPreference, which is what makes the real client hand a game Steam
     * Input for titles that ship their own action manifest (Monster Train 2). Returns null when the
     * content isn't a UserLocalConfigStore shape (caller skips the write).
     */
    static String injectSteamInputPreference(String content, int appId, boolean enabled) {
        LinkedHashMap<String, String> support = new LinkedHashMap<>();
        for (String k : STEAM_INPUT_KEYS) support.put(k, enabled ? "1" : "0");
        String withSupport = injectVdfKeys(content, new String[] {"system"}, support);
        if (withSupport == null) return null;
        LinkedHashMap<String, String> perApp = new LinkedHashMap<>();
        perApp.put("UseSteamControllerConfig", enabled ? "2" : "0");
        return injectVdfKeys(withSupport, new String[] {"apps", String.valueOf(appId)}, perApp);
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gradlew.bat :app:testStandardDebugUnitTest --tests "com.winlator.star.store.RealSteamSteamInputTest"`
Expected: PASS (5 tests).

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/RealSteamLauncher.java app/src/test/java/com/winlator/star/store/RealSteamSteamInputTest.java
git commit -m "feat(realsteam): add localconfig Steam Input preference transform"
```

---

### Task 3: Wire Steam Input into a RealSteam launch

Why: connect the transform to a real launch — decide when Steam Input is wanted, write the preference, stage the layout, and tell the agent.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/RealSteamLauncher.java`

**Interfaces:**
- Consumes: `injectSteamInputPreference` (Task 2), `SteamInputLayouts.INSTANCE.resolveHostConfigText(File)` and `.hasOwnManifest(File)` (Task 1), `SteamPrefs.INSTANCE.getUseSteamInput(int)`, existing `editLocalConfig`, `resolveLocalConfig`, `STEAM_DIR_WIN`.
- Produces: nothing imported elsewhere (all private/static). The decision is internal to `prepare()`.

- [ ] **Step 1: Replace the passthrough block in `prepare()` (around lines 232-242)**

Replace:

```java
            if (controllerPassthrough) {
                applyControllerPassthrough(steamDir, repo);
            }
```

with:

```java
            // A game that ships its OWN Steam Input action manifest hands input to Steam Input and gets
            // nothing while the client keeps the pad to itself — enable Steam Input for it instead of
            // stepping aside. Same decision + localconfig shape as GameNative (isSteamInputEnabled /
            // setSteamInputPreference). A game with no own manifest is unchanged: the passthrough toggle
            // still wins, and with neither set nothing is written. Best-effort; never blocks a launch.
            boolean steamInput = false;
            try {
                SteamPrefs.INSTANCE.init(ctx.getApplicationContext());
                steamInput = SteamPrefs.INSTANCE.getUseSteamInput(appId)
                        || com.winlator.star.store.steaminput.SteamInputLayouts.INSTANCE
                                .hasOwnManifest(new File(hostInstallDir));
            } catch (Throwable ignored) {}
            if (steamInput) {
                applySteamInput(steamDir, repo, appId, new File(hostInstallDir));
            } else if (controllerPassthrough) {
                applyControllerPassthrough(steamDir, repo);
            }
```

- [ ] **Step 2: Add the agent env line in section 4 (after the `BL_STEAM_REGION` line, around line 328)**

Add:

```java
            // Steam Input for this app: the agent loads the game's own action manifest and activates the
            // Steam Input action set for the pad instead of handing Steam the raw device (see applySteamInput).
            if (steamInput) env.put("BL_AGENT_STEAMINPUT", "1");
```

- [ ] **Step 3: Add `applySteamInput` + `stageHostControllerLayout` (next to `applyControllerPassthrough`, around line 434)**

```java
    // ── Steam Input ON (own-manifest games) ────────────────────────────────────────────────────────

    /** The layout filename the headless host loads and activates for a game (GameNative convention). */
    private static String hostLayoutFileName(int appId) { return "steamhost_controller_" + appId + ".vdf"; }

    /**
     * Turn Steam Input ON for this app so a game that hands input to Steam Input (own action manifest,
     * e.g. Monster Train 2) actually receives the pad. Two levers, both best-effort:
     * <ol>
     *   <li>localconfig.vdf: the four SteamController_*Support keys → "1" and the per-app
     *       apps/&lt;appId&gt;/UseSteamControllerConfig → "2" (force on) — GameNative's proven shape;</li>
     *   <li>stage the game's own controller config VDF (Xbox 360 mapping) as the layout the host loads.</li>
     * </ol>
     * Any failure is logged and swallowed — this must never block a launch.
     */
    private static void applySteamInput(File steamDir, SteamRepository repo, int appId, File installDir) {
        try {
            File localConfig = resolveLocalConfig(steamDir, repo);
            if (localConfig != null) {
                String content = localConfig.isFile() ? FileUtils.readString(localConfig) : null;
                String base = (content == null || content.trim().isEmpty()) ? EMPTY_LOCAL_CONFIG : content;
                String updated = injectSteamInputPreference(base, appId, true);
                if (updated == null) {
                    Log.w(TAG, "steaminput: localconfig.vdf shape unexpected — preference skipped");
                } else if (!updated.equals(content)) {
                    File dir = localConfig.getParentFile();
                    if (dir != null && !dir.exists()) dir.mkdirs();
                    if (FileUtils.writeString(localConfig, updated)) {
                        Log.i(TAG, "steaminput: enabled for appId=" + appId
                                + " (SteamController_*Support=1, UseSteamControllerConfig=2)");
                    } else {
                        Log.w(TAG, "steaminput: failed writing localconfig.vdf");
                    }
                } else {
                    Log.i(TAG, "steaminput: already enabled for appId=" + appId);
                }
            } else {
                Log.w(TAG, "steaminput: no localconfig.vdf resolvable — Steam Input keys skipped");
            }
            stageHostControllerLayout(steamDir, appId, installDir);
        } catch (Throwable t) {
            Log.w(TAG, "steaminput: apply failed (non-fatal): " + t.getMessage());
        }
    }

    /**
     * Stage the game's own controller config VDF into the prefix Steam dir as the layout the headless
     * host loads for this app. Missing manifest/config leaves the client on its own default.
     */
    private static void stageHostControllerLayout(File steamDir, int appId, File installDir) {
        try {
            String text = com.winlator.star.store.steaminput.SteamInputLayouts.INSTANCE
                    .resolveHostConfigText(installDir);
            if (text == null || text.isEmpty()) {
                Log.i(TAG, "steaminput: no own layout for appId=" + appId + " — host default");
                return;
            }
            if (!steamDir.exists() && !steamDir.mkdirs()) {
                Log.w(TAG, "steaminput: could not create Steam dir — layout skipped");
                return;
            }
            File layout = new File(steamDir, hostLayoutFileName(appId));
            if (FileUtils.writeString(layout, text)) {
                Log.i(TAG, "steaminput: staged " + layout.getName() + " (" + text.length() + " bytes)");
            } else {
                Log.w(TAG, "steaminput: failed writing " + layout.getName());
            }
        } catch (Throwable t) {
            Log.w(TAG, "steaminput: layout staging failed (non-fatal): " + t.getMessage());
        }
    }
```

- [ ] **Step 4: Verify it compiles and existing behaviour is untouched**

Run: `gradlew.bat :app:compileStandardDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.
(No SDK: `gh workflow run fork-ci --ref <branch> -R i0trost01/BannerlatorFork` and wait for green.)

Run: `gradlew.bat :app:testStandardDebugUnitTest --tests "com.winlator.star.store.*"`
Expected: PASS, including `RealSteamSteamInputTest` and the untouched `steaminput.*`.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/RealSteamLauncher.java
git commit -m "feat(realsteam): enable Steam Input for own-manifest games (Monster Train 2)"
```

---

### Task 4: Surface the toggle in the SteamLite launch popup

Why: the same per-game pref is currently only shown for Goldberg (`showSteamInput = method == LaunchMethod.GOLDBERG`), so a SteamLite user has no way to opt in/out. Auto-enable covers Monster Train 2; the toggle covers every other title.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/ui/screens/LaunchMethodSheet.kt:439` and `:547`

**Interfaces:**
- Consumes: existing `steamInput` state (`SteamPrefs.getUseSteamInput(appId)` / `setUseSteamInput`, lines 228-252), which is appId-keyed and shared by both launch methods.
- Produces: no signature change — only the visibility condition.

- [ ] **Step 1: Change both `showSteamInput` assignments**

At line 439 and again at line 547, replace:

```kotlin
                    showSteamInput = method == LaunchMethod.GOLDBERG,
```

with:

```kotlin
                    showSteamInput = method == LaunchMethod.GOLDBERG || method == LaunchMethod.STEAMLITE,
```

- [ ] **Step 2: Verify it compiles**

Run: `gradlew.bat :app:compileStandardDebugKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/winlator/star/ui/screens/LaunchMethodSheet.kt
git commit -m "feat(ui): offer the Use Steam Input toggle for SteamLite launches too"
```

---

### Task 5: Device verification (the only real proof)

Unit tests cannot prove the genuine client picks the preference up. This task is the acceptance gate.

**Files:** none (manual device test) — record the outcome in `docs/superpowers/plans/2026-09-27-steam-input-realsteam-mt2-results.md`.

- [ ] **Step 1: Build + install the branch**

Gate: `gh workflow run fork-ci --ref <branch> -R i0trost01/BannerlatorFork` (unit tests) and `gh workflow run fork-ci --ref <branch> ...` / `build-artifacts` for the APK. Install on the handheld.

- [ ] **Step 2: Launch Monster Train 2 in SteamLite (RealSteam) mode with a physical pad connected**

Expected logcat (filter `BH_REALSTEAM`):
- `steaminput: enabled for appId=2742830 (SteamController_*Support=1, UseSteamControllerConfig=2)`
- `steaminput: staged steamhost_controller_2742830.vdf (N bytes)`

- [ ] **Step 3: Inspect the staged prefix on device**

Ensure the prefix contains:
- `Program Files (x86)/Steam/userdata/<accountid>/config/localconfig.vdf` with `system/SteamController_XBoxSupport "1"`, `system/SteamController_GenericGamepadSupport "1"`, `apps/2742830/UseSteamControllerConfig "2"`.
- `Program Files (x86)/Steam/steamhost_controller_2742830.vdf` (the Xbox 360 config; content contains `controller_xbox360`).

- [ ] **Step 4: Verify in-game**

Success = the pad drives Monster Train 2's menus and game (A/B/X/Y prompt, left stick navigation) with no on-screen controls. Failure modes to check, in order:
1. Client has no Steam Input for the app → the localconfig write didn't take (check the account id path; `resolveLocalConfig` may have written to the wrong `userdata/<id>`).
2. Client has Steam Input on but no layout → the agent ignored `BL_AGENT_STEAMINPUT` / `steamhost_controller_<appId>.vdf`. **This needs a matching agent-side change** (the clean-room agent lives outside this repo, `agent-src/main.cpp`); capture `steamlite.txt` and open a follow-up. Mirror GameNative's `STEAMHOST_STEAMINPUT=1` read.
3. Steam Input on but the virtual pad still doesn't reach the game → this is the known "Deck mode" blocker recorded in `PROGRESS_LOG.md` (2026-09-22 late); escalate before adding more app-side code.

- [ ] **Step 5: Commit the results note**

```bash
git add docs/superpowers/plans/2026-09-27-steam-input-realsteam-mt2-results.md
git commit -m "docs: Monster Train 2 RealSteam Steam Input device results"
```

---

## Self-Review

**Spec coverage:**
- *Enable Steam Input for own-manifest games* → Tasks 1 (detect/read manifest), 2+3 (localconfig preference ON), 3 (auto-enable `toggle || hasOwnManifest`).
- *Stage the host layout* → Task 1 (`resolveHostConfigText`, Xbox 360-first) + Task 3 (`stageHostControllerLayout`, `steamhost_controller_<appId>.vdf`).
- *Tell the agent* → Task 3 (`BL_AGENT_STEAMINPUT=1`).
- *User opt-in/out* → Task 4.
- *Proof* → Task 5.
- Gap (explicit): the agent-side read of `BL_AGENT_STEAMINPUT` / the layout file is **not** in this repo; Task 5 step 4-2 defines the escalation.

**Placeholder scan:** none — every code step shows full code; run steps give exact commands and expected results.

**Type consistency:** `resolveHostConfigText(File): String?` (Task 1) is called in Task 3; `injectSteamInputPreference(String,int,boolean)` (Task 2) is called in Task 3's `applySteamInput`; `hostLayoutFileName(int)` and `EMPTY_LOCAL_CONFIG` are existing/new in the same file; `SteamPrefs.INSTANCE.getUseSteamInput(int)` matches the confirmed Java-interop usage at `RealSteamLauncher.java:325`. `steamInput` is read in section 4 (Step 2) after being assigned in the 3a-bis block (Step 1) — both inside the same `try` in `prepare()`.

---

## Execution Handoff

Plan complete and saved to `docs/superpowers/plans/2026-09-27-steam-input-realsteam-mt2.md`. Two execution options:

**1. Subagent-Driven (recommended)** — dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** — execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
