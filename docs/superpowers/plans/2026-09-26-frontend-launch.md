# Front-End Launch Export Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make an exported front-end shortcut launch its game directly when the front end starts BannerlatorFork with `-a android.intent.action.VIEW -d {file.uri}`, instead of opening the games list.

**Architecture:** The parsing/forwarding logic already exists in `MainActivity.maybeForwardFrontendLaunch()` and already handles a `.desktop` data URI (`resolveIncomingDesktopPath`). The blocker is the manifest: `MainActivity` declares only `MAIN`/`LAUNCHER`, so an implicit `VIEW` intent matches nothing and the front end falls back to a plain launch. Add WinNative's `VIEW` + `file`/`content` + `.desktop` intent-filters to `MainActivity`, then forward `shortcut_name`/`shortcut_uuid` for parity, and update the docs.

**Tech Stack:** Android manifest XML, Kotlin (`MainActivity.kt`), JUnit4 source-assertion tests, Gradle (`:app:testStandardDebugUnitTest`), GitHub Actions CI.

**Spec:** `docs/superpowers/specs/2026-09-26-frontend-launch-design.md`

## Global Constraints

- Flavor: `standard` only. Application id: `com.winlator.banner.fork`. Do not touch other flavors.
- No local Android SDK/NDK — **never** claim a build or test passes locally. CI
  (`.github/workflows/fork-ci.yml`, task `:app:testStandardDebugUnitTest`) is the only execution
  environment.
- Robolectric **cannot** boot on this module. All tests are plain JVM JUnit4 **source-assertion
  guards** (read a source file from disk, assert substrings). Follow the existing idiom in
  `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`.
- Keep BOTH launch routes working: the URI route (`-d {file.uri}`, new) and the existing path-extra
  route (`-e shortcut_path {file.path}`).
- Do not change the exported `.desktop` / `.steam` / `.steamappid` file formats, and do not touch the
  pinned home-screen shortcut code.
- Every `gh` command needs `--repo i0trost01/BannerlatorFork`.
- `rg` is not installed. Use the Grep tool or PowerShell `Select-String`.

---

### Task 1: Add the VIEW intent-filter to MainActivity and forward shortcut identity extras

**Files:**
- Modify: `app/src/main/AndroidManifest.xml` (the `MainActivity` `<activity>` block, currently lines 90-100)
- Modify: `app/src/main/java/com/winlator/star/MainActivity.kt` (the forward block in `maybeForwardFrontendLaunch()`, currently lines 362-367)
- Test: `app/src/test/java/com/winlator/star/FrontendLaunchGuardTest.java` (create)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: a `MainActivity` that matches an implicit `VIEW` + `file`/`content`+`.desktop` intent, and
  a forward intent carrying `shortcut_path`, `container_id`, `shortcut_name`, `shortcut_uuid`. The
  session activity consumes these via `getIntent().getStringExtra("shortcut_path")` /
  `getIntExtra("container_id", 0)` / `getStringExtra("shortcut_uuid")`
  (`XServerDisplayActivity.java:2535`, `:2540`).

**Context:** `MainActivity.maybeForwardFrontendLaunch()` (`MainActivity.kt:316-370`) already resolves a
`.desktop` URI from the intent data (`resolveIncomingDesktopPath(src)` at `:358`) and forwards. The
forward block currently is:

```kotlin
        if (shortcutPath.isNullOrEmpty()) return false
        startActivity(Intent(this, XServerDisplayActivity::class.java).apply {
            setAction(Intent.ACTION_VIEW)
            putExtra("shortcut_path", shortcutPath)
            putExtra("container_id", containerId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
        return true
```

`containerManager` is an `Activity` field (initialized at `MainActivity.kt:189`) and exposes
`reloadContainers()` and `loadShortcuts()` (already used at `:331-334` and `:434-437`).
`Shortcut` (`app/src/main/java/com/winlator/star/container/Shortcut.java:21`) has
`public final String name` and `public final File file`, and `getExtra(String)`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/FrontendLaunchGuardTest.java`:

```java
package com.winlator.star;

import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for front-end (Daijisho/Beacon/ES-DE) game launching.
 *
 * A front end starts the app with `-a android.intent.action.VIEW -d {file.uri}`. For the intent to
 * land on MainActivity (which forwards it to the session activity), MainActivity must declare a VIEW
 * intent-filter with file/content data schemes, and its forward must carry the shortcut identity
 * extras. Both launch routes (URI and shortcut_path extra) must keep working.
 */
public class FrontendLaunchGuardTest {

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

    /** Slice the MainActivity <activity ...> ... </activity> block out of the manifest. */
    private static String mainActivityBlock(String manifest) {
        int start = manifest.indexOf("android:name=\"com.winlator.star.MainActivity\"");
        assertTrue("manifest must declare MainActivity", start >= 0);
        int open = manifest.lastIndexOf('<', start);
        int close = manifest.indexOf("</activity>", start);
        assertTrue("could not delimit the MainActivity block", open >= 0 && close > start);
        return manifest.substring(open, close);
    }

    @Test
    public void mainActivityDeclaresViewFilterForDesktopUris() throws IOException {
        String manifest = readRepoFile("src", "main", "AndroidManifest.xml");
        String block = mainActivityBlock(manifest);

        assertTrue("MainActivity must declare the VIEW action",
                block.contains("android:name=\"android.intent.action.VIEW\""));
        assertTrue("MainActivity's VIEW filter must accept file scheme",
                block.contains("android:scheme=\"file\""));
        assertTrue("MainActivity's VIEW filter must accept content scheme",
                block.contains("android:scheme=\"content\""));
        assertTrue("MainActivity must match exported .desktop files by pathPattern",
                block.contains("android:pathPattern=\".*\\\\.desktop\""));
        assertTrue("MainActivity must keep its MAIN/LAUNCHER filter",
                block.contains("android.intent.category.LAUNCHER"));
    }

    @Test
    public void forwardCarriesShortcutIdentityExtras() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "MainActivity.kt");

        assertTrue("forward must still set shortcut_path", src.contains("putExtra(\"shortcut_path\""));
        assertTrue("forward must still set container_id", src.contains("putExtra(\"container_id\""));
        assertTrue("forward must now set shortcut_name", src.contains("putExtra(\"shortcut_name\""));
        assertTrue("forward must now set shortcut_uuid", src.contains("putExtra(\"shortcut_uuid\""));
    }

    @Test
    public void bothLaunchRoutesRemain() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "MainActivity.kt");

        assertTrue("URI route (resolveIncomingDesktopPath) must remain", src.contains("resolveIncomingDesktopPath("));
        assertTrue("path-extra route must remain", src.contains("getStringExtra(\"shortcut_path\")"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork` (no local Gradle).
Expected: CI FAILS on `mainActivityDeclaresViewFilterForDesktopUris` (no VIEW filter yet) and on
`forwardCarriesShortcutIdentityExtras` (no `shortcut_name`/`shortcut_uuid` yet). This is the
intentional TDD RED.

- [ ] **Step 3: Add the intent-filters to MainActivity**

In `app/src/main/AndroidManifest.xml`, replace the `MainActivity` `<activity>` block (lines 90-100)
with:

```xml
        <activity
            android:name="com.winlator.star.MainActivity"
            android:theme="@style/Theme.Bannerlator.Splash"
            android:screenOrientation="sensor"
            android:exported="true"
            android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
            <intent-filter>
                <action android:name="android.intent.action.MAIN"/>
                <category android:name="android.intent.category.LAUNCHER"/>
            </intent-filter>
            <!-- Front-end launch (Daijisho/Beacon/ES-DE): an implicit VIEW of an exported .desktop URI
                 (e.g. `am start -a android.intent.action.VIEW -d {file.uri}`). MainActivity parses it in
                 maybeForwardFrontendLaunch() and forwards to XServerDisplayActivity — the same role
                 WinNative's UnifiedActivity plays. Without this filter the intent matches no activity and
                 the front end falls back to a plain launch, opening the games list instead of the game. -->
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <data android:scheme="file"/>
                <data android:scheme="content"/>
                <data android:mimeType="application/x-desktop"/>
            </intent-filter>
            <intent-filter>
                <action android:name="android.intent.action.VIEW"/>
                <category android:name="android.intent.category.DEFAULT"/>
                <data android:scheme="file"/>
                <data android:scheme="content"/>
                <data android:host="*"/>
                <data android:pathPattern=".*\\.desktop"/>
            </intent-filter>
        </activity>
```

- [ ] **Step 4: Forward the shortcut identity extras**

In `app/src/main/java/com/winlator/star/MainActivity.kt`, replace the forward block:

```kotlin
        if (shortcutPath.isNullOrEmpty()) return false
        startActivity(Intent(this, XServerDisplayActivity::class.java).apply {
            setAction(Intent.ACTION_VIEW)
            putExtra("shortcut_path", shortcutPath)
            putExtra("container_id", containerId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
        return true
```

with:

```kotlin
        if (shortcutPath.isNullOrEmpty()) return false
        // WinNative parity: forward the shortcut identity too. Best-effort — a null lookup must not
        // change the existing behavior (the session activity works from shortcut_path alone).
        val forwarded = runCatching {
            containerManager.reloadContainers()
            containerManager.loadShortcuts().firstOrNull { it.file.absolutePath == shortcutPath }
        }.getOrNull()
        startActivity(Intent(this, XServerDisplayActivity::class.java).apply {
            setAction(Intent.ACTION_VIEW)
            putExtra("shortcut_path", shortcutPath)
            putExtra("container_id", containerId)
            forwarded?.let {
                putExtra("shortcut_name", it.name)
                putExtra("shortcut_uuid", it.getExtra("uuid"))
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        })
        finish()
        return true
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; all `FrontendLaunchGuardTest` tests pass and the full suite stays green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/java/com/winlator/star/MainActivity.kt app/src/test/java/com/winlator/star/FrontendLaunchGuardTest.java
git commit -m "fix(frontend): accept VIEW .desktop URIs so exported shortcuts launch the game"
git push origin main
```

---

### Task 2: Document the working front-end launch command

**Files:**
- Modify: `docs/daijisho.md`
- Modify: `marcescence-frontends.md`
- Test: none (documentation only)

**Interfaces:**
- Consumes: the working manifest filter from Task 1.
- Produces: nothing consumed by later tasks.

**Context:** `docs/daijisho.md` currently documents only a path-extra route (line 44) and a
`LAUNCH_GAME` route (line 32). Add the URI route now that it works. `marcescence-frontends.md`
documents the Beacon/ES-DE command; the file has changed since this plan was written, so **read the
current file before editing** and match its existing structure and heading style.

- [ ] **Step 1: Add the URI route to `docs/daijisho.md`**

After the existing "Fallback for games with no resolvable app id" section (line 48), append:

```markdown
## Launching via the exported `.desktop` URI (WinNative-compatible)

The fork also accepts the exported `.desktop` itself as the intent **data**, which is how the
WinNative fork is configured. Point the player at `MainActivity` and pass Daijisho's `{file.uri`}
token:

```

-n com.winlator.banner.fork/com.winlator.star.MainActivity -a android.intent.action.VIEW -d {file.uri}

```

`{file.uri}` is a `content://` or `file://` URI to the exported `.desktop`; `MainActivity` resolves it,
verifies it looks like a shortcut, and forwards the launch to the game activity. This launches the game
directly and does not require an app id.
```

(Note: the inner fenced block uses three backticks — write it as a normal fenced code block exactly as
it will appear; the surrounding markdown above is the literal text to add.)

- [ ] **Step 2: Read and update `marcescence-frontends.md`**

Read the current file first, then add the equivalent `-a android.intent.action.VIEW -d {file.uri}` form
next to the existing `-e shortcut_path {file.path}` command, in the same style as the surrounding
entries. Do not remove the `shortcut_path` form — it still works.

- [ ] **Step 3: Commit**

```bash
git add docs/daijisho.md marcescence-frontends.md
git commit -m "docs(frontend): document the -d {file.uri} launch route"
git push origin main
```

---

### Task 3: Verify CI and prepare the on-device checklist

**Files:**
- No source changes. Verification + ledger.

**Interfaces:**
- Consumes: the commits from Tasks 1-2.
- Produces: a green CI confirmation on the final commit sha, consumed by the release step.

- [ ] **Step 1: Confirm CI is green on the final commit**

Run:
```powershell
gh run list --repo i0trost01/BannerlatorFork --workflow fork-ci.yml --limit 3 --json databaseId,headSha,status,conclusion
```
Expected: the newest run(s) for the final commit show `"conclusion":"success"`. If red, open it and
read the failing step; do not proceed until green.

- [ ] **Step 2: Record the on-device checklist in the SDD ledger**

Append to `.superpowers/sdd/2026-09-26-frontend-launch.md/progress.md` (create the directory if
absent):

```
On-device checklist (user):
1. Export a game to the front end (Daijisho/Beacon).
2. Set the player arg to:
   am start -n com.winlator.banner.fork/com.winlator.star.MainActivity -a android.intent.action.VIEW -d {file.uri}
3. Tap the exported shortcut -> the game must launch directly (not the games list).
4. Regression: the older `-e shortcut_path {file.path}` form still launches the game.
5. Regression: a normal app launch still shows the games list.
```

- [ ] **Step 3: Ledger commit is a no-op**

`.superpowers/` is gitignored; verify with `git status --porcelain` and skip. No commit required.

---

## Self-Review

**1. Spec coverage:** The spec's in-scope items map to Task 1 (manifest filter, forward extras) and
Task 2 (docs). The guard test covers both. Out-of-scope items are untouched.

**2. Placeholder scan:** No TBD/TODO. The manifest block and Kotlin block are complete and
paste-ready. Task 2 Step 2 intentionally says "read the current file first" because that file changed
since planning — the edit is a documentation addition following existing structure, not a code action.

**3. Type consistency:** Guarded identifiers — `VIEW`, `file`, `content`, `application/x-desktop`,
`.*\.desktop`, `shortcut_path`, `container_id`, `shortcut_name`, `shortcut_uuid`,
`resolveIncomingDesktopPath`, `maybeForwardFrontendLaunch` — are used identically in the test, the
manifest/source snippets, and the prose. `Shortcut.name` and `Shortcut.getExtra(String)` exist
(`Shortcut.java:21-23`, used at `MainActivity.kt:333`).
