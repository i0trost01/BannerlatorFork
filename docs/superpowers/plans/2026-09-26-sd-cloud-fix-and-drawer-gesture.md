# SD Cloud-Upload Fix & Drawer Gesture Removal Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix the in-game cloud-save upload failing for games installed on an external/SD card, and remove the left-edge swipe gesture that opens the in-game drawer.

**Architecture:** (1) `SteamCloudSaveManager.resolveShortcut` is a hand-copied, half-implemented clone of `SteamCloudSavePaths.resolveContainer` — it only has the imagefs string-match fallback and is missing the drive-map branch that lays off-imagefs (SD/USB) executables onto a drive letter. We add the missing branch so the upload path resolves exactly like the download path. (2) The in-game drawer is a framework `androidx.drawerlayout.widget.DrawerLayout` which opens on a left-edge touch drag; we replace it with a tiny subclass that forwards programmatic opens but refuses touch edge drags, so the controller Back button keeps working.

**Tech Stack:** Java (Android), AndroidX `DrawerLayout`, JUnit4 JVM unit tests (no Robolectric — this module cannot boot Robolectric because it native-loads a `.so` during framework init).

**Spec:** None (direct bug-fix + small feature; derived from investigation documented in this plan's Task context blocks).

## Global Constraints

- Flavor: `standard` only (the fork removed `ludashi`/`pubg`).
- App id: `com.winlator.banner.fork`.
- Toolchain: AGP 8.8.0, Gradle 8.10.2, JDK 17, compileSdk 34, minSdk 26, targetSdk 28.
- No Android SDK/NDK is available locally; **all** compilation and test execution happens in GitHub Actions CI (`.github/workflows/fork-ci.yml`, task `:app:testStandardDebugUnitTest`). Never claim a build passes without a green CI run.
- Every `gh workflow run` / `gh run view` / `gh run rerun` / `gh release` command MUST pass `--repo i0trost01/BannerlatorFork`.
- `rg` is not installed; use the Grep tool or PowerShell `Select-String`.
- Version `versionCode` is minutes-since-epoch, computed by `fork-release.yml`; always pass an explicit `-f version=`.
- Do NOT add comments to code unless the surrounding file already documents that region in the same style; this codebase is heavily commented, so match the local idiom.
- All new tests are **plain JVM JUnit4 tests** under `app/src/test/java/com/winlator/star/` — no `android.*` imports that require a booted framework (Robolectric is unavailable here).

---

## File Structure

| File | Responsibility | Action |
|---|---|---|
| `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt` | Cloud save collect/upload/apply; contains the broken `resolveShortcut` | Modify |
| `app/src/main/java/com/winlator/star/widget/NoEdgeSwipeDrawerLayout.java` | New `DrawerLayout` subclass that blocks touch edge drags but allows programmatic opens | Create |
| `app/src/main/res/layout/xserver_display_activity.xml` | In-game layout; swaps the plain `DrawerLayout` for the subclass | Modify |
| `app/src/test/java/com/winlator/star/store/SdUploadResolveGuardTest.java` | JVM source-assertion guard that `resolveShortcut` contains the drive-map branch | Create |
| `app/src/test/java/com/winlator/star/NoEdgeSwipeDrawerLayoutTest.java` | JVM source-assertion guard for the subclass + layout usage | Create |

---

## Task 1: Fix `resolveShortcut` so SD-installed games resolve on upload

**Context (why this is the bug):**

`syncToCloud` = Collect + Upload. `collectFromContainer` calls `resolveShortcut(ctx, installDir)` (`SteamCloudSaveManager.kt:647`) and, when it returns null, errors with **"This game isn't set up in a container yet"** (`:648`). Download (`syncFromCloud` → `applyToContainer`) instead calls `SteamCloudSavePaths.resolveContainer`, which is why download works and upload does not for the same game.

`SteamCloudSavePaths.resolveContainer` has **two** matching strategies (`SteamCloudSavePaths.kt:120-145`):
1. **PRIMARY — drive-map:** `WinePath.resolveAndroidPath(sc.container, raw)` maps the shortcut's `F:\...` exec back to a real Android path and checks it is under the install dir. This branch exists specifically so off-imagefs (SD/USB) games resolve.
2. **FALLBACK — imagefs string match.**

`resolveShortcut` (`SteamCloudSaveManager.kt:783-805`) has only strategy 2. For an exec addressed as `F:\steam_games\<game>\<game>.exe`, the fallback strips the drive letter yielding `/steam_games/<game>/...`, which never matches an absolute SD install path like `/storage/<uuid>/.../steam_games/<game>` (documented in `SteamCloudSavePaths.kt:126-128`). Result: null → the error. Its own doc comment claims "Same matching rule as `SteamCloudSavePaths.resolveContainer`" — which is false.

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt:783-805`
- Test: `app/src/test/java/com/winlator/star/store/SdUploadResolveGuardTest.java` (create)

**Interfaces:**
- Consumes: `com.winlator.star.core.WinePath.resolveAndroidPath(container: Container, winPath: String): File?` (already imported in sibling code; verify import exists), `com.winlator.star.container.Shortcut.container`, `Shortcut.path`.
- Produces: no signature change — `private fun resolveShortcut(ctx: Context, installDir: String): Shortcut?` keeps its exact signature and return type. Later tasks do not depend on it.

- [ ] **Step 1: Write the failing guard test**

Create `app/src/test/java/com/winlator/star/store/SdUploadResolveGuardTest.java`. This is a source-assertion guard (this module cannot run Robolectric, so we assert the source contains the required branch, mirroring the existing `DrawerRegressionGuardTest` idiom).

```java
package com.winlator.star.store;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the SD-card cloud-upload fix.
 *
 * Upload (Collect) resolves the container via SteamCloudSaveManager.resolveShortcut, which used to
 * contain only the imagefs string-match fallback. An exec addressed through a drive letter
 * (F:\steam_games\...) never matches an absolute SD install path that way, so uploads failed with
 * "This game isn't set up in a container yet" while downloads (which use the full
 * SteamCloudSavePaths.resolveContainer) worked. This guard asserts the drive-map branch is present.
 */
public class SdUploadResolveGuardTest {

    private static String read(String... segments) throws IOException {
        Path root = Paths.get("").toAbsolutePath();
        // Walk up until we find the app/src directory (tests run from the module or repo root).
        Path dir = root;
        for (int i = 0; i < 4 && dir != null; i++) {
            Path candidate = dir.resolve(Paths.get("app", "src", "main", "java"));
            if (Files.isDirectory(candidate)) {
                Path p = candidate;
                for (String s : segments) p = p.resolve(s);
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IOException("Could not locate app/src/main/java under " + root);
    }

    @Test
    public void resolveShortcutUsesDriveMapBranch() throws IOException {
        String src = read("com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        assertTrue(
                "resolveShortcut must resolve an exec through the container drive map "
                        + "(WinePath.resolveAndroidPath) so off-imagefs / SD-card games resolve on upload",
                src.contains("WinePath.resolveAndroidPath"));
    }

    @Test
    public void resolveShortcutStillHasImagefsFallback() throws IOException {
        String src = read("com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        assertTrue(
                "resolveShortcut must keep the imagefs string-match fallback for internal (Z:) games",
                src.contains("keys.any"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

Run (CI, since no local SDK): push the test and run the CI workflow.
```
git add app/src/test/java/com/winlator/star/store/SdUploadResolveGuardTest.java
git commit -m "test(cloud): guard SD upload drive-map resolution"
git push origin main
gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork
gh run watch --repo i0trost01/BannerlatorFork
```
Expected: `resolveShortcutUsesDriveMapBranch` **FAILS** (the source does not yet contain `WinePath.resolveAndroidPath` inside `SteamCloudSaveManager.kt`), `resolveShortcutStillHasImagefsFallback` PASSES, overall CI RED.

- [ ] **Step 3: Add the drive-map branch to `resolveShortcut`**

Edit `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt`. Replace the body of `resolveShortcut` (currently lines 783-805) with the version below. The added block mirrors `SteamCloudSavePaths.resolveContainer`'s PRIMARY branch (`SteamCloudSavePaths.kt:117-136`) verbatim in behavior.

```kotlin
    /**
     * The game's launch shortcut — the `.desktop` whose exec target sits under [installDir] — or
     * null if the game isn't set up in a container. Same matching rule as
     * [SteamCloudSavePaths.resolveContainer], but returns the whole [Shortcut] so Collect can read
     * its name/path/wmClass for the [SaveLocator.discover] pass (and reach its container).
     */
    private fun resolveShortcut(ctx: Context, installDir: String): Shortcut? {
        if (installDir.isBlank()) return null

        val manager = ContainerManager(ctx)
        val shortcuts = try { manager.loadShortcuts() } catch (e: Exception) {
            Log.w(TAG, "loadShortcuts failed", e); return null
        }

        val imageFsRoot = File(ctx.filesDir, "imagefs").absolutePath.replace('\\', '/').trimEnd('/')
        val instAbs = installDir.replace('\\', '/').trimEnd('/')
        val instRel = if (instAbs.lowercase().startsWith(imageFsRoot.lowercase()))
            instAbs.substring(imageFsRoot.length).trimStart('/') else instAbs.trimStart('/')
        val keys = listOf("/${instRel.lowercase()}/", "/${instAbs.trimStart('/').lowercase()}/")

        // The game's real Android install dir, for the drive-map match below.
        val instAbsFile = File(installDir).absolutePath.replace('\\', '/').trimEnd('/')

        for (sc in shortcuts) {
            val raw = sc.path ?: continue

            // PRIMARY (drive-agnostic): resolve the shortcut's exec back through ITS container's drive
            // map to a real Android path, then check it lives under the install dir. Mirrors
            // SteamCloudSavePaths.resolveContainer so an off-imagefs game — the "Install to SD card"
            // option parks it on the card as F:\... — resolves here too. Without this, upload (Collect)
            // matched only via the string fallback below, which strips the drive letter and so never
            // matched the absolute SD install path (download already used the full resolver, which is
            // why a game could download but not upload). resolveAndroidPath returns null for a Z:\
            // imagefs game, so internal games fall through to the string match unchanged.
            val android = runCatching { WinePath.resolveAndroidPath(sc.container, raw) }.getOrNull()
            if (android != null) {
                val ap = android.absolutePath.replace('\\', '/').trimEnd('/')
                if (ap.equals(instAbsFile, ignoreCase = true) ||
                    ap.startsWith("$instAbsFile/", ignoreCase = true)) return sc
            }

            var exec = raw.replace('\\', '/').lowercase().trim()
            exec = exec.replaceFirst(Regex("^[a-z]:"), "")
            if (!exec.startsWith("/")) exec = "/$exec"
            if (keys.any { it.length > 2 && exec.contains(it) }) return sc
        }
        return null
    }
```

Then add the import if missing. Check the top of the file for `import com.winlator.star.core.WinePath`; if absent, add it alongside the other `com.winlator.star.core.*` imports:

```kotlin
import com.winlator.star.core.WinePath
```

- [ ] **Step 4: Run the tests to verify they pass**

```
git add app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt
git commit -m "fix(cloud): resolve SD-card games on upload via the container drive map"
git push origin main
gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork
gh run watch --repo i0trost01/BannerlatorFork
```
Expected: **BUILD SUCCESSFUL**, both `SdUploadResolveGuardTest` cases pass, full existing suite passes.

- [ ] **Step 5: Commit**

Already committed in Step 3/4. Confirm the working tree is clean:
```
git status --porcelain
```
Expected: empty output.

---

## Task 2: Remove the left-edge swipe that opens the in-game drawer

**Context (why the naive fix is wrong):**

The in-game drawer is a framework `androidx.drawerlayout.widget.DrawerLayout` (`app/src/main/res/layout/xserver_display_activity.xml:2`). The activity loads it and calls `drawerLayout.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)` (`XServerDisplayActivity.java:1964`) with an explicit comment that locking it closed **breaks every programmatic open** — that was the fork.18 "Back does nothing" bug. So we must NOT change the lock mode.

Programmatic opens are all `drawerLayout.openDrawer(GravityCompat.START)` API calls (`XServerDisplayActivity.java:1989`, `:7207`, `:7218`, `:10832`) plus `closeDrawers()`. A subclass that refuses only **touch** edge drags leaves those APIs untouched.

**Files:**
- Create: `app/src/main/java/com/winlator/star/widget/NoEdgeSwipeDrawerLayout.java`
- Modify: `app/src/main/res/layout/xserver_display_activity.xml:2` and `:31`
- Test: `app/src/test/java/com/winlator/star/NoEdgeSwipeDrawerLayoutTest.java` (create)

**Interfaces:**
- Consumes: nothing from Task 1.
- Produces: class `com.winlator.star.widget.NoEdgeSwipeDrawerLayout extends androidx.drawerlayout.widget.DrawerLayout`. Later tasks reference it only in XML.

- [ ] **Step 1: Write the failing guard test**

Create `app/src/test/java/com/winlator/star/NoEdgeSwipeDrawerLayoutTest.java`.

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
 * Guard for removing the in-game drawer's left-edge swipe.
 *
 * The drawer must no longer open on a touch edge drag, while programmatic opens
 * (drawerLayout.openDrawer(GravityCompat.START)) must keep working. The previous attempt at this
 * used DrawerLayout.LOCK_MODE_LOCKED_CLOSED, which makes openDrawer() a no-op and silently killed
 * the controller Back button — so this guard also asserts the lock mode is NOT locked-closed.
 */
public class NoEdgeSwipeDrawerLayoutTest {

    private static String readRepoFile(String... segments) throws IOException {
        Path root = Paths.get("").toAbsolutePath();
        Path dir = root;
        for (int i = 0; i < 5 && dir != null; i++) {
            Path app = dir.resolve("app");
            if (Files.isDirectory(app)) {
                Path p = app;
                for (String s : segments) p = p.resolve(s);
                if (Files.isRegularFile(p)) return new String(Files.readAllBytes(p), StandardCharsets.UTF_8);
            }
            dir = dir.getParent();
        }
        throw new IOException("Could not locate app/ under " + root);
    }

    @Test
    public void subclassOverridesTouchInterception() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "widget", "NoEdgeSwipeDrawerLayout.java");
        assertTrue("subclass must override onInterceptTouchEvent to block edge drags",
                src.contains("onInterceptTouchEvent"));
    }

    @Test
    public void inGameLayoutUsesTheSubclass() throws IOException {
        String xml = readRepoFile("src", "main", "res", "layout", "xserver_display_activity.xml");
        assertTrue("in-game layout must use NoEdgeSwipeDrawerLayout",
                xml.contains("com.winlator.star.widget.NoEdgeSwipeDrawerLayout"));
        assertFalse("in-game layout must no longer use the plain framework DrawerLayout tag",
                xml.contains("<androidx.drawerlayout.widget.DrawerLayout"));
    }

    @Test
    public void lockModeIsNotLockedClosed() throws IOException {
        String activity = readRepoFile("src", "main", "java", "com", "winlator", "star", "XServerDisplayActivity.java");
        assertFalse("drawer must not be locked closed — that makes openDrawer() a no-op (fork.18 bug)",
                activity.contains("setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)"));
        assertTrue("drawer must stay unlocked for programmatic opens",
                activity.contains("setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)"));
    }
}
```

- [ ] **Step 2: Run the test to verify it fails**

```
git add app/src/test/java/com/winlator/star/NoEdgeSwipeDrawerLayoutTest.java
git commit -m "test(drawer): guard removal of the left-edge swipe"
git push origin main
gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork
gh run watch --repo i0trost01/BannerlatorFork
```
Expected: `subclassOverridesTouchInterception` and `inGameLayoutUsesTheSubclass` **FAIL** (file/usage absent), `lockModeIsNotLockedClosed` PASSES, CI RED.

- [ ] **Step 3: Create the subclass**

Create `app/src/main/java/com/winlator/star/widget/NoEdgeSwipeDrawerLayout.java`.

```java
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
```

- [ ] **Step 4: Point the in-game layout at the subclass**

Edit `app/src/main/res/layout/xserver_display_activity.xml`. Change the opening tag (line 2) and closing tag (line 31) from `androidx.drawerlayout.widget.DrawerLayout` to `com.winlator.star.widget.NoEdgeSwipeDrawerLayout`. Result:

```xml
<?xml version="1.0" encoding="utf-8"?>
<com.winlator.star.widget.NoEdgeSwipeDrawerLayout
    xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:app="http://schemas.android.com/apk/res-auto"
    xmlns:tools="http://schemas.android.com/tools"
    android:id="@+id/DrawerLayout"
    android:layout_width="match_parent"
    android:layout_height="match_parent">

    <FrameLayout
        android:id="@+id/FLXServerDisplay"
        android:layout_width="match_parent"
        android:layout_height="match_parent" />

    <androidx.compose.ui.platform.ComposeView
        android:id="@+id/XServerDrawerComposeView"
        android:layout_width="380dp"
        android:layout_height="match_parent"
        android:layout_gravity="start"
        android:focusable="true"
        android:focusableInTouchMode="true" />

    <EditText
        android:id="@+id/XRTextInput"
        android:layout_width="0dp"
        android:layout_height="0dp"
        android:inputType="textMultiLine"
        android:autofillHints="no"
        tools:ignore="LabelFor"
        android:visibility="gone" />
</com.winlator.star.widget.NoEdgeSwipeDrawerLayout>
```

Do NOT touch `main_activity.xml` (the app's main menu drawer is out of scope).

- [ ] **Step 5: Verify the activity's cast still compiles**

`XServerDisplayActivity` declares `private DrawerLayout drawerLayout;` (`:287`) and assigns `drawerLayout = findViewById(R.id.DrawerLayout);` (`:1930`). `NoEdgeSwipeDrawerLayout` **extends** `DrawerLayout`, so the existing field type and every `drawerLayout.xxx()` call compile unchanged. No Java edit is required. Confirm by searching:
```
Select-String -Path "app\src\main\java\com\winlator\star\XServerDisplayActivity.java" -Pattern "DrawerLayout drawerLayout"
```
Expected: exactly one declaration line, typed `DrawerLayout`.

- [ ] **Step 6: Run the tests to verify they pass**

```
git add app/src/main/java/com/winlator/star/widget/NoEdgeSwipeDrawerLayout.java app/src/main/res/layout/xserver_display_activity.xml
git commit -m "feat(drawer): remove the in-game left-edge swipe, keep programmatic open"
git push origin main
gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork
gh run watch --repo i0trost01/BannerlatorFork
```
Expected: **BUILD SUCCESSFUL**, all `NoEdgeSwipeDrawerLayoutTest` cases pass, full suite passes.

- [ ] **Step 7: Commit**

Already committed in Step 6. Confirm clean tree:
```
git status --porcelain
```
Expected: empty output.

---

## Task 3: Deploy release `3.1.3-fork.22`

**Files:** none (release workflow only).

- [ ] **Step 1: Confirm CI is green on the exact commit to release**

```
git log --oneline -1
gh run list --repo i0trost01/BannerlatorFork --workflow fork-ci.yml --limit 3 --json status,conclusion,headSha
```
Expected: the newest CI run on the current HEAD SHA has `conclusion: success`. Do NOT release on a red or unrun commit.

- [ ] **Step 2: Trigger the release**

```
gh workflow run fork-release.yml --repo i0trost01/BannerlatorFork -f version=3.1.3-fork.22
Start-Sleep -Seconds 15
gh run list --repo i0trost01/BannerlatorFork --workflow fork-release.yml --limit 1 --json databaseId,status
```

- [ ] **Step 3: Wait for the release and verify the asset exists**

```
gh run watch <databaseId> --repo i0trost01/BannerlatorFork
gh release view 3.1.3-fork.22 --repo i0trost01/BannerlatorFork --json tagName,assets
```
Expected: `tagName: 3.1.3-fork.22` and one asset named `Bannerlator-Fork-3.1.3-fork.22-Standard-signed.apk`.

If the release run fails, inspect the failing **step** (not the setup log): 
```
gh run view <databaseId> --repo i0trost01/BannerlatorFork --json jobs | ConvertFrom-Json | %{ $_.jobs } | %{ $n=$_.name; $_.steps | ?{ $_.conclusion -eq "failure" } | %{ "JOB: $n | STEP: $($_.name)" } }
```
A known-transient failure exists in the native "Build the DirectAudio sink into the audio bundle" step (`tar: stdout: write error`); if that is the only failure, `gh run rerun <databaseId> --repo i0trost01/BannerlatorFork --failed` rather than changing any source.

- [ ] **Step 4: Report**

Output the release URL and this on-device checklist:
1. Open Cloud Saves for an SD-card-installed Steam game → press Upload → it should now collect + upload instead of erroring "This game isn't set up in a container yet."
2. Download for the same game still works (regression check).
3. In game, swipe from the left edge → the drawer must NOT open.
4. Controller Back button still opens the drawer.

---

## Self-Review

**1. Spec coverage:** No spec doc (direct bug-fix + small feature). Both user requirements are covered: cloud upload for SD games → Task 1; remove left-edge swipe interaction entirely → Task 2. Release → Task 3. Cursor work was explicitly dropped by the user and is intentionally absent.

**2. Placeholder scan:** No "TBD"/"TODO"/"add error handling"/"similar to Task N". All code steps contain complete code. The `<databaseId>` in Task 3 Steps 2-3 is a runtime value from Step 2's command, not a plan placeholder — the command to obtain it is given.

**3. Type consistency:** `resolveShortcut` keeps signature `(Context, String) -> Shortcut?` in both the "Interfaces" block and Step 3. `WinePath.resolveAndroidPath` signature matches `WinePath.kt:114`. `NoEdgeSwipeDrawerLayout` is referenced identically in the test, the class, and the XML. `DrawerLayout` field type in the activity is unchanged (subclass relation). Test class names in the File Structure table match those created in Steps 1.

**Known limits (stated honestly):**
- The two tests are source-assertion guards, not behavioral tests — this module cannot boot Robolectric (it native-loads a `.so` during framework init). They can prove the branch/subclass/XML change is present, not that the runtime behavior is correct. On-device verification is Task 3 Step 4.
- No local Android SDK exists, so every compile/test claim in this plan is gated on a green CI run.
