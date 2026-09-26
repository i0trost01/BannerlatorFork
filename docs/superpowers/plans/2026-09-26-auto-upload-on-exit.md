# Auto-Upload on Exit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the Save Manager's "Steam games: auto-upload to cloud on exit" toggle actually upload the exiting game's saves, by removing a hidden second consent gate that the toggle cannot satisfy.

**Architecture:** One condition in `XServerDisplayActivity.exit()` currently ANDs the toggle with a `steam_prefs` disclaimer flag that only a game-detail-page action can set. Remove that flag from the exit-path condition so the Save Manager toggle is the sole gate. The upload machinery (`autoUploadSteamSavesBlocking()` → `SteamCloudSaveManager.syncToCloudBlocking`) is already correct and unchanged.

**Tech Stack:** Java (Android), Kotlin, JUnit4 source-assertion tests, Gradle (`:app:testStandardDebugUnitTest`), GitHub Actions CI.

**Spec:** `docs/superpowers/specs/2026-09-26-auto-upload-on-exit-design.md`

## Global Constraints

- Flavor: `standard` only. Application id: `com.winlator.banner.fork`. Do not touch other flavors.
- No local Android SDK/NDK — **never** claim a build or test passes locally. CI
  (`.github/workflows/fork-ci.yml`, task `:app:testStandardDebugUnitTest`) is the only execution
  environment.
- Robolectric **cannot** boot on this module. All tests are plain JVM JUnit4 **source-assertion
  guards** (read a source file from disk, assert substrings). Follow the existing idiom in
  `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`.
- Change is Java in `XServerDisplayActivity.java`. Match the file's existing comment style (heavily
  commented, `//` line comments explaining *why*).
- Do not change `auto_upload_gog_on_exit`, `auto_collect_steam_on_exit`, or
  `auto_backup_custom_on_exit` behavior. Do not delete the `cloud_saves_disclaimer_accepted` flag
  globally — it still gates the game-detail-page cloud actions.
- Every `gh` command needs `--repo i0trost01/BannerlatorFork`.
- `rg` is not installed. Use the Grep tool or PowerShell `Select-String`.

---

### Task 1: Remove the hidden disclaimer gate from the exit auto-upload

**Files:**
- Modify: `app/src/main/java/com/winlator/star/XServerDisplayActivity.java` (the `if (isGenuineSteamShortcut())` block inside `exit()`, currently lines 5524-5531)
- Test: `app/src/test/java/com/winlator/star/ExitAutoUploadGateGuardTest.java` (create)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: no new symbols. Later tasks rely only on the fact that
  `auto_upload_steam_on_exit` alone gates `autoUploadSteamSavesBlocking()` in `exit()`.

**Context:** In `XServerDisplayActivity.exit()`, inside a worker thread, the current code is:

```java
if (isGenuineSteamShortcut()) {
    if (savePrefs.getBoolean("auto_collect_steam_on_exit", true)) autoCollectSteamSavesBlocking();
    // Additionally push to Steam Cloud (opt-in) — ONLY once the user has accepted
    // the third-party cloud disclaimer (steam_prefs). Absent flag → skip; we never
    // auto-upload to a real Steam Cloud without consent. The local Collect above
    // stays unconditional (independent of this cloud toggle).
    boolean cloudDisclaimerOk = getSharedPreferences("steam_prefs", MODE_PRIVATE)
            .getBoolean("cloud_saves_disclaimer_accepted", false);
    if (cloudDisclaimerOk && savePrefs.getBoolean("auto_upload_steam_on_exit", true))
        autoUploadSteamSavesBlocking();
}
```

`savePrefs` is `getSharedPreferences("save_manager_prefs", MODE_PRIVATE)`. The goal is that the
`auto_upload_steam_on_exit` toggle alone decides.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/ExitAutoUploadGateGuardTest.java`:

```java
package com.winlator.star;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the auto-upload-on-exit fix.
 *
 * The Save Manager toggle "Steam games: auto-upload to cloud on exit" persists
 * auto_upload_steam_on_exit in save_manager_prefs. The exit path used to AND it with a separate
 * steam_prefs cloud_saves_disclaimer_accepted flag that only a game-detail-page action could set,
 * so the toggle was silently ignored. The exit condition must now be gated by the toggle alone,
 * while the disclaimer flag must remain in the tree (it still gates the detail-page cloud actions).
 */
public class ExitAutoUploadGateGuardTest {

    private static String readRepoFile(String... segments) throws IOException {
        Path root = Paths.get("").toAbsolutePath();
        Path dir = root;
        for (int i = 0; i < 5 && dir != null; i++) {
            Path app = dir.resolve("app");
            if (Files.isDirectory(app)) {
                Path p = app;
                for (String s : segments) p = p.resolve(s);
                if (Files.isRegularFile(p)) {
                    return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
                }
            }
            dir = dir.getParent();
        }
        throw new IOException("Could not locate app/ under " + root);
    }

    /** Slice out the `if (isGenuineSteamShortcut()) { ... }` block from exit(). */
    private static String steamExitBlock(String src) {
        int marker = src.indexOf("if (isGenuineSteamShortcut()) {");
        assertTrue("exit() must still branch on isGenuineSteamShortcut()", marker >= 0);
        int end = src.indexOf("} else {", marker);
        assertTrue("could not delimit the genuine-Steam exit block", end > marker);
        return src.substring(marker, end);
    }

    @Test
    public void exitAutoUploadIsGatedOnlyByTheToggle() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "XServerDisplayActivity.java");
        String block = steamExitBlock(src);

        assertFalse("exit auto-upload must NOT be gated by cloud_saves_disclaimer_accepted",
                block.contains("cloud_saves_disclaimer_accepted"));
        assertFalse("the cloudDisclaimerOk boolean must be gone from the exit block",
                block.contains("cloudDisclaimerOk"));
        assertTrue("exit auto-upload must still read the Save Manager toggle",
                block.contains("auto_upload_steam_on_exit"));
        assertTrue("exit auto-upload must still call autoUploadSteamSavesBlocking()",
                block.contains("autoUploadSteamSavesBlocking()"));
    }

    @Test
    public void disclaimerFlagStillExistsForTheDetailPagePath() throws IOException {
        String activity = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamGameDetailActivity.kt");
        assertTrue("the game-detail-page disclaimer flag must remain (it gates detail-page cloud actions)",
                activity.contains("cloud_saves_disclaimer_accepted"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork` (no local Gradle).
Expected: CI FAILS on `exitAutoUploadIsGatedOnlyByTheToggle` because the block still contains
`cloud_saves_disclaimer_accepted`. (This is the intentional TDD RED.)

- [ ] **Step 3: Write minimal implementation**

In `app/src/main/java/com/winlator/star/XServerDisplayActivity.java`, inside `exit()`'s
`if (isGenuineSteamShortcut())` block, replace:

```java
                            // Additionally push to Steam Cloud (opt-in) — ONLY once the user has accepted
                            // the third-party cloud disclaimer (steam_prefs). Absent flag → skip; we never
                            // auto-upload to a real Steam Cloud without consent. The local Collect above
                            // stays unconditional (independent of this cloud toggle).
                            boolean cloudDisclaimerOk = getSharedPreferences("steam_prefs", MODE_PRIVATE)
                                    .getBoolean("cloud_saves_disclaimer_accepted", false);
                            if (cloudDisclaimerOk && savePrefs.getBoolean("auto_upload_steam_on_exit", true))
                                autoUploadSteamSavesBlocking();
```

with:

```java
                            // Additionally push to Steam Cloud (opt-in). The Save Manager toggle
                            // "Steam games: auto-upload to cloud on exit" (save_manager_prefs) IS the user's
                            // consent: enabling it on that screen is an explicit opt-in, so the exit path must
                            // honor it directly. (The separate steam_prefs cloud_saves_disclaimer_accepted flag
                            // still gates the game detail page's cloud actions, which show their own disclaimer
                            // dialog; it deliberately does NOT gate this exit auto-upload.) The local Collect
                            // above stays unconditional.
                            if (savePrefs.getBoolean("auto_upload_steam_on_exit", true))
                                autoUploadSteamSavesBlocking();
```

Do not change any other line in the block (leave the `auto_collect_steam_on_exit` Collect call
untouched).

- [ ] **Step 4: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; both tests in `ExitAutoUploadGateGuardTest` pass and the full suite stays green.

- [ ] **Step 5: Commit**

```bash
git add app/src/test/java/com/winlator/star/ExitAutoUploadGateGuardTest.java app/src/main/java/com/winlator/star/XServerDisplayActivity.java
git commit -m "fix(saves): honor the auto-upload-on-exit toggle without the hidden disclaimer gate"
git push origin main
```

---

### Task 2: Verify the fix in CI and prepare the on-device checklist

**Files:**
- No source changes. Verification + documentation of results.

**Interfaces:**
- Consumes: the commit from Task 1.
- Produces: a green CI confirmation on the final commit sha, consumed by the release step.

- [ ] **Step 1: Confirm the fix commit's CI is green**

Run:
```powershell
gh run list --repo i0trost01/BannerlatorFork --workflow fork-ci.yml --limit 3 --json databaseId,headSha,status,conclusion
```
Expected: the newest run(s) for the Task 1 commit show `"conclusion":"success"`. If the newest run is
`failure`, open it and read the failing step
(`gh run view <id> --repo i0trost01/BannerlatorFork --json jobs`) — do not proceed until green.

- [ ] **Step 2: Record the on-device checklist in the plan's SDD ledger**

Append to `.superpowers/sdd/2026-09-26-auto-upload-on-exit.md/progress.md` (create the
`.superpowers/sdd/2026-09-26-auto-upload-on-exit.md/` directory if absent):

```
On-device checklist (user):
1. Save Manager toggle "Steam games: auto-upload to cloud on exit" must be ON.
2. Launch a Steam game (SD-card or internal), make a save, exit via the in-game drawer Exit.
3. Reopen the Save Manager: that game must NOT appear in the "needs syncing" banner.
4. Regression: launching a game with a newer cloud save still downloads it.
5. Regression: the game-detail-page cloud action still shows the disclaimer on first use.
```

- [ ] **Step 3: Commit the ledger (outside app/, so it does not affect CI), if the directory is tracked**

Note: `.superpowers/` is gitignored in this repo. Do NOT add it. This step is a no-op unless the
directory is tracked; verify with `git status --porcelain` and skip if clean. No commit is required.

---

## Self-Review

**1. Spec coverage:** The spec's single in-scope source change (remove the disclaimer gate from the
exit path, keep it for the detail page) is Task 1. Silent behavior and exited-game-only scope need no
code (already true). Task 2 covers CI verification. The spec's "out of scope" items are untouched.

**2. Placeholder scan:** No TBD/TODO. All code blocks are complete and paste-ready.

**3. Type consistency:** The guarded identifiers — `auto_upload_steam_on_exit`,
`cloud_saves_disclaimer_accepted`, `cloudDisclaimerOk`, `autoUploadSteamSavesBlocking()`,
`isGenuineSteamShortcut()`, `save_manager_prefs`, `steam_prefs` — are used identically in the test,
the implementation snippet, and the prose.
