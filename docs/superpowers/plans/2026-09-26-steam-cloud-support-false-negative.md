# Steam Cloud "doesn't support" False Negative Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Stop the upload path from refusing games whose Steam Cloud config the PICS heuristic doesn't recognize, so a game that can be downloaded from the cloud (e.g. Death's Gambit: Afterlife) can also upload to it.

**Architecture:** The upload gate trusts a PICS-metadata guess (`hasCloudSupport`), while the download path trusts the live cloud manifest. Three changes reconcile them: (A) the upload path checks the live manifest and proceeds when it is non-empty; (B) the heuristic returns `false` only on proven absence and recognizes Auto-Cloud shapes; (C) a cache epoch invalidates previously-persisted wrong verdicts.

**Tech Stack:** Kotlin (Android), JUnit4 source-assertion tests, Gradle (`:app:testStandardDebugUnitTest`), GitHub Actions CI.

**Spec:** `docs/superpowers/specs/2026-09-26-steam-cloud-support-false-negative-design.md`

## Global Constraints

- Flavor: `standard` only. Application id: `com.winlator.banner.fork`. Do not touch other flavors.
- No local Android SDK/NDK — **never** claim a build or test passes locally. CI
  (`.github/workflows/fork-ci.yml`, task `:app:testStandardDebugUnitTest`) is the only execution
  environment.
- Robolectric **cannot** boot on this module. All tests are plain JVM JUnit4 **source-assertion
  guards** (read a source file from disk, assert substrings). Follow the existing idiom in
  `app/src/test/java/com/winlator/star/DrawerRegressionGuardTest.java`.
- Change is Kotlin. Match each file's existing comment style (heavily commented, `//` and `/** */`).
- Uploads must stay **strictly additive**: `filesToDelete` stays empty; never open a batch for a wipe.
- Keep `NO_RETENTION_MESSAGE` behavior (the post-upload empty-manifest check). Only the
  `NO_CLOUD_MESSAGE` gate is being relaxed/broadened.
- Every `gh` command needs `--repo i0trost01/BannerlatorFork`.
- `rg` is not installed. Use the Grep tool or PowerShell `Select-String`.

---

### Task 1: Make the cloud-support heuristic recognize Auto-Cloud shapes and stop asserting false when unproven

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt` (`hasCloudSupport` `:345-370`, `hasUsableSaveFiles` `:376-381`)
- Test: `app/src/test/java/com/winlator/star/store/CloudSupportHeuristicGuardTest.java` (create)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `hasCloudSupport(ctx, appId): Boolean?` now returns `null` (unknown) when the `ufs` block is absent/unrecognized, and `false` only when a `ufs` block is present with no usable entry and no Auto-Cloud markers. `hasUsableSaveFiles(appKeyValues: KeyValue): Boolean` accepts the broadened shapes. Task 3 relies on `hasCloudSupport` no longer returning a false negative for unrecognized shapes.

**Context:** Current code:

```kotlin
// SteamCloudSaveManager.kt:345-370
    fun hasCloudSupport(ctx: Context, appId: Int): Boolean? {
        if (SaveSyncStore.isMarkedNoSteamCloud(appId)) return false

        cloudSupportCache[appId]?.let { return it }

        return try {
            val appKeyValues: KeyValue? =
                SteamRepository.getInstance().fetchAppKeyValues(appId, FUTURE_TIMEOUT_SEC * 1000L)

            if (appKeyValues == null || appKeyValues.children.isEmpty()) {
                null
            } else {
                val supported = hasUsableSaveFiles(appKeyValues)
                cloudSupportCache[appId] = supported
                supported
            }
        } catch (e: Exception) {
            Log.w(TAG, "hasCloudSupport: PICS product-info query failed for appId=$appId", e)
            null
        }
    }
```

```kotlin
// SteamCloudSaveManager.kt:376-381
    private fun hasUsableSaveFiles(appKeyValues: KeyValue): Boolean {
        val saveFiles = appKeyValues.get("ufs").get("savefiles").children
        return saveFiles.any { entry ->
            !entry.get("root").value.isNullOrBlank() || !entry.get("pattern").value.isNullOrBlank()
        }
    }
```

Note `KeyValue.get(name)` returns an INVALID sentinel (never null) when absent; `.children` is empty when
absent. `KeyValue.value` is a `String?`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/CloudSupportHeuristicGuardTest.java`:

```java
package com.winlator.star.store;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the Steam-Cloud-support heuristic fix.
 *
 * The upload gate used to treat a PICS metadata shape it did not recognize as "no cloud", blocking
 * uploads for Auto-Cloud games (e.g. Death's Gambit: Afterlife, saves in AppData/Local/deathsgambit397)
 * whose ufs block does not present the legacy savefiles entry shape. The heuristic must now accept
 * Auto-Cloud shapes and must return "unknown" (not false) when it cannot prove absence.
 */
public class CloudSupportHeuristicGuardTest {

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

    /** Slice the body of a private/fun function from its signature to its closing brace. */
    private static String body(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("source must contain: " + signature, start >= 0);
        // naive brace match from the first '{' after the signature
        int open = src.indexOf('{', start);
        assertTrue("no opening brace after " + signature, open > start);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return src.substring(open, i + 1);
            }
        }
        throw new AssertionError("unterminated body for " + signature);
    }

    @Test
    public void heuristicAcceptsPathAndAddpathNotJustRootOrPattern() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "private fun hasUsableSaveFiles(");
        assertTrue("must still accept a non-blank root", b.contains("root"));
        assertTrue("must also accept a non-blank path (Auto-Cloud)", b.contains("path"));
        assertTrue("must also accept addpath (rootoverride shapes)", b.contains("addpath"));
    }

    @Test
    public void heuristicNormalizesDotAndSlashPath() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "private fun hasUsableSaveFiles(");
        assertFalse("must not test a bare \".\" as content", b.contains("value == \".\""));
        assertTrue("must normalize \".\"/\"/\" to empty before blank-checks", b.contains("trim"));
    }

    @Test
    public void hasCloudSupportReturnsUnknownWhenUfsBlockUnrecognized() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun hasCloudSupport(");
        assertTrue("must gate the false verdict on an actual ufs block being present",
                b.contains("get(\"ufs\")"));
        assertTrue("must return null (unknown) for an unrecognized shape", b.contains("null"));
        assertTrue("must only cache a definitive verdict (never null)",
                b.contains("cloudSupportCache[appId] = "));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork` (no local Gradle).
Expected: CI FAILS on `heuristicAcceptsPathAndAddpathNotJustRootOrPattern`,
`heuristicNormalizesDotAndSlashPath`, and `hasCloudSupportReturnsUnknownWhenUfsBlockUnrecognized`.
Intentional TDD RED.

- [ ] **Step 3: Broaden `hasUsableSaveFiles`**

Replace the function body (`:376-381`) with:

```kotlin
    /** True if the app's PICS KeyValues declare a usable cloud-save configuration.
     *
     *  Steam declares cloud config two ways, both under `ufs`:
     *   • legacy UFS: `ufs/savefiles/<n>/{root,path,pattern}`;
     *   • Auto-Cloud: entries may carry a `root` + `path`/`addpath` but no `pattern`, and a `path`
     *     of "." or "/" means "root of this type, no subdir" (normalize to empty — GameNative #1297).
     *  The `ufs` block itself also carries `quota`/`maxnumfiles` when cloud is configured.
     *
     *  An entry counts as usable if any of root/path/addpath/pattern is non-blank after normalizing
     *  dots/slashes. `KeyValue.get` never returns null (INVALID sentinel) and `.children` is empty
     *  when a section is absent. */
    private fun hasUsableSaveFiles(appKeyValues: KeyValue): Boolean {
        fun String?.usable(): Boolean {
            val v = this?.trim()?.trim('/') ?: return false
            return v.isNotBlank() && v != "."
        }
        val ufs = appKeyValues.get("ufs")
        if (ufs.get("quota").value.usable() || ufs.get("maxnumfiles").value.usable()) return true
        return ufs.get("savefiles").children.any { entry ->
            entry.get("root").value.usable() || entry.get("pattern").value.usable() ||
                entry.get("path").value.usable() || entry.get("addpath").value.usable()
        }
    }
```

- [ ] **Step 4: Make `hasCloudSupport` return `null` for an unrecognized shape**

Replace the `else` branch of `hasCloudSupport` (`:361-365`) so a missing `ufs` section is `null`, not
`false`:

```kotlin
            } else {
                // Only a PRESENT ufs block can prove anything. Absent/unrecognized => unknown (null),
                // never a cached false: upload will fall back to the live-manifest check instead.
                val ufs = appKeyValues.get("ufs")
                if (ufs.children.isEmpty() && ufs.get("quota").value.isNullOrBlank() &&
                    ufs.get("maxnumfiles").value.isNullOrBlank()) {
                    null
                } else {
                    val supported = hasUsableSaveFiles(appKeyValues)
                    cloudSupportCache[appId] = supported
                    supported
                }
            }
```

(The `null` produced here is not cached — that is already how the surrounding code works.)

- [ ] **Step 5: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; all `CloudSupportHeuristicGuardTest` tests pass and the full suite stays green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt app/src/test/java/com/winlator/star/store/CloudSupportHeuristicGuardTest.java
git commit -m "fix(cloud): recognize Auto-Cloud ufs shapes; return unknown instead of a false no-cloud"
git push origin main
```

---

### Task 2: Cache epoch so previously-persisted wrong verdicts are refreshed

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamPrefs.kt` (`:252-272`)
- Test: `app/src/test/java/com/winlator/star/store/CloudSupportEpochGuardTest.java` (create)

**Interfaces:**
- Consumes: nothing from other tasks.
- Produces: `SteamPrefs.getCloudSupportCached(ctx, appId)` returns `null` when the stored verdict was
  written under an older epoch. `setCloudSupportCached` stamps the current epoch. No signature change;
  Task 3 relies on the cache no longer serving stale `false` values.

**Context:** Current code (`SteamPrefs.kt:252-272`):

```kotlin
    private const val K_CLOUD_SUPPORT_PREFIX = "cloud_support_"

    fun getCloudSupportCached(ctx: Context, appId: Int): Boolean? {
        init(ctx)
        val key = K_CLOUD_SUPPORT_PREFIX + appId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }

    fun setCloudSupportCached(ctx: Context, appId: Int, v: Boolean) {
        init(ctx)
        prefs.edit().putBoolean(K_CLOUD_SUPPORT_PREFIX + appId, v).apply()
    }
```

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/CloudSupportEpochGuardTest.java`:

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
 * Guard for the cloud-support cache epoch.
 *
 * Wrong "no cloud" verdicts were persisted to steam_prefs["cloud_support_<appId>"]. A cache epoch
 * makes previously-stored verdicts be ignored/refreshed, so an already-mis-marked game is fixed
 * without the user clearing app data.
 */
public class CloudSupportEpochGuardTest {

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

    @Test
    public void epochKeyAndConstantExist() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamPrefs.kt");
        assertTrue("must define a cloud-support epoch key", src.contains("cloud_support_epoch"));
        assertTrue("must define a current epoch constant", src.contains("CLOUD_SUPPORT_EPOCH"));
    }

    @Test
    public void getCloudSupportCachedConsultsTheEpoch() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamPrefs.kt");
        int get = src.indexOf("fun getCloudSupportCached(");
        assertTrue("getCloudSupportCached must exist", get >= 0);
        int end = src.indexOf("fun setCloudSupportCached(", get);
        assertTrue("setCloudSupportCached must follow", end > get);
        String getBody = src.substring(get, end);
        assertTrue("getCloudSupportCached must read the stored epoch and return null when stale",
                getBody.contains("CLOUD_SUPPORT_EPOCH") && getBody.contains("return null"));
    }

    @Test
    public void setCloudSupportCachedStampsTheEpoch() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamPrefs.kt");
        int set = src.indexOf("fun setCloudSupportCached(");
        assertTrue("setCloudSupportCached must exist", set >= 0);
        String setBody = src.substring(set, Math.min(src.length(), set + 400));
        assertTrue("setCloudSupportCached must write the current epoch",
                setBody.contains("CLOUD_SUPPORT_EPOCH"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI FAILS on `epochKeyAndConstantExist` (no epoch key/constant yet). Intentional TDD RED.

- [ ] **Step 3: Add the epoch**

In `SteamPrefs.kt`, add the constant next to `K_CLOUD_SUPPORT_PREFIX` (`:258`) and rewrite the getter
and setter:

```kotlin
    private const val K_CLOUD_SUPPORT_PREFIX = "cloud_support_"
    private const val K_CLOUD_SUPPORT_EPOCH = "cloud_support_epoch"

    /** Bump when the cloud-support heuristic changes, to invalidate verdicts cached by older logic. */
    private const val CLOUD_SUPPORT_EPOCH = 2
```

```kotlin
    /** Cached Steam-Cloud-support verdict for [appId]: true/false if resolved before, null if never.
     *  A verdict written by an older heuristic (epoch below [CLOUD_SUPPORT_EPOCH]) is treated as
     *  absent, so the caller re-resolves it. */
    fun getCloudSupportCached(ctx: Context, appId: Int): Boolean? {
        init(ctx)
        if (prefs.getInt(K_CLOUD_SUPPORT_EPOCH, 0) < CLOUD_SUPPORT_EPOCH) return null
        val key = K_CLOUD_SUPPORT_PREFIX + appId
        if (!prefs.contains(key)) return null
        return prefs.getBoolean(key, false)
    }

    /** Persist a DEFINITIVE Steam-Cloud-support verdict for [appId]. Only call with a known true/false. */
    fun setCloudSupportCached(ctx: Context, appId: Int, v: Boolean) {
        init(ctx)
        prefs.edit()
            .putBoolean(K_CLOUD_SUPPORT_PREFIX + appId, v)
            .putInt(K_CLOUD_SUPPORT_EPOCH, CLOUD_SUPPORT_EPOCH)
            .apply()
    }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; all `CloudSupportEpochGuardTest` tests pass and the full suite stays green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamPrefs.kt app/src/test/java/com/winlator/star/store/CloudSupportEpochGuardTest.java
git commit -m "fix(cloud): version the cloud-support cache so stale no-cloud verdicts are refreshed"
git push origin main
```

---

### Task 3: Let live-manifest evidence override a negative verdict on the upload paths

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt` (`uploadSaves` gate `:190-194`; `syncToCloudBlocking` `:515-518`; `isMarkedNoSteamCloud` usage `:178`)
- Test: `app/src/test/java/com/winlator/star/store/CloudUploadEvidenceGuardTest.java` (create)

**Interfaces:**
- Consumes: `hasCloudSupport` (Task 1) now returns `null` for unrecognized shapes; `getCloudSupportCached`
  (Task 2) no longer serves stale `false`.
- Produces: the upload paths proceed when the live cloud manifest is non-empty. No new public symbols.

**Context:** Current `uploadSaves` gate (`:183-194`):

```kotlin
                // ── HONESTY GUARD 1: does this game even support Steam Cloud? ───────────
                ...
                val support: Boolean? = hasCloudSupport(ctx, appId)
                if (support == false) {
                    cb.onError(NO_CLOUD_MESSAGE)
                    return@Thread
                }
```

And `syncToCloudBlocking` (`:511-519`):

```kotlin
    fun syncToCloudBlocking(ctx: Context, appId: Int, installDir: String): String {
        return try {
            if (hasCloudSupportCached(ctx, appId) == false) {
                val r = runBlockingMove(BLOCKING_BOUND_MS) { cb -> collectFromContainer(ctx, appId, installDir, cb) }
                return "No Steam Cloud support — saved locally only (${r.summary})"
            }
            ...
```

`steamCloud.listFiles(appId)` is the manifest call the download path already uses
(`uploadSaves` has `steamCloud` in scope at `:161`; `syncToCloudBlocking` can get it via
`requireCloud()`). It returns a `List<...>`; empty means no cloud files.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/CloudUploadEvidenceGuardTest.java`:

```java
package com.winlator.star.store;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertFalse;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.Test;

/**
 * Guard for the live-manifest evidence override.
 *
 * A metadata-derived "no cloud" verdict must not block an upload when the live cloud manifest is
 * non-empty - that is direct proof the game has a cloud store (download already relies on it). This
 * is what unbreaks games like Death's Gambit: Afterlife.
 */
public class CloudUploadEvidenceGuardTest {

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

    @Test
    public void uploadDoesNotHardBlockOnAFalseVerdict() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        int upload = src.indexOf("fun uploadSaves(");
        assertTrue("uploadSaves must exist", upload >= 0);
        int next = src.indexOf("fun uploadFromLibrary(", upload);
        if (next < 0) next = src.length();
        String body = src.substring(upload, next);
        assertTrue("upload must consult the live manifest before refusing",
                body.contains("listFiles("));
        assertFalse("upload must not unconditionally return NO_CLOUD_MESSAGE on support == false",
                body.contains("if (support == false) {\n                    cb.onError(NO_CLOUD_MESSAGE)\n                    return@Thread\n                }"));
    }

    @Test
    public void blockingSyncChecksManifestBeforeLocalOnly() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        int fn = src.indexOf("fun syncToCloudBlocking(");
        assertTrue("syncToCloudBlocking must exist", fn >= 0);
        int end = src.indexOf("fun ", fn + 10);
        String body = src.substring(fn, end < 0 ? src.length() : end);
        assertTrue("must consult the live manifest before the local-only summary",
                body.contains("listFiles("));
        assertTrue("must keep the honest local-only summary for a truly empty manifest",
                body.contains("No Steam Cloud support"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI FAILS on both tests (no `listFiles(` in `uploadSaves`; no `listFiles(` in
`syncToCloudBlocking`). Intentional TDD RED.

- [ ] **Step 3: Add the manifest-evidence path to `uploadSaves`**

Replace the gate at `:190-194` with:

```kotlin
                val support: Boolean? = hasCloudSupport(ctx, appId)
                if (support == false) {
                    // The PICS verdict is a metadata GUESS and can be a false negative for Auto-Cloud
                    // shapes it doesn't recognize (e.g. Death's Gambit: Afterlife). The live cloud
                    // manifest is ground truth — download already trusts it. If the game has cloud
                    // files, upload; only a genuinely empty manifest honors the no-cloud message.
                    val hasCloudFiles = runCatching { steamCloud.listFiles(appId).isNotEmpty() }.getOrDefault(false)
                    if (!hasCloudFiles) {
                        cb.onError(NO_CLOUD_MESSAGE)
                        return@Thread
                    }
                }
```

(`support == null` still falls through to the post-upload emptiness check, unchanged. Do not alter the
`isMarkedNoSteamCloud` guard above, or the post-upload empty-manifest check below.)

- [ ] **Step 4: Add the manifest-evidence path to `syncToCloudBlocking`**

Replace the short-circuit at `:515-518` with:

```kotlin
            val cloudKnownNoSupport = hasCloudSupportCached(ctx, appId) == false
            val hasCloudFiles = if (cloudKnownNoSupport) {
                runCatching { requireCloud()?.listFiles(appId)?.isNotEmpty() == true }.getOrDefault(false)
            } else true
            if (cloudKnownNoSupport && !hasCloudFiles) {
                // No cloud files AND the heuristic says no support → honest local-only Collect.
                val r = runBlockingMove(BLOCKING_BOUND_MS) { cb -> collectFromContainer(ctx, appId, installDir, cb) }
                return "No Steam Cloud support — saved locally only (${r.summary})"
            }
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; all `CloudUploadEvidenceGuardTest` tests pass and the full suite stays green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt app/src/test/java/com/winlator/star/store/CloudUploadEvidenceGuardTest.java
git commit -m "fix(cloud): let a non-empty cloud manifest override a false no-cloud verdict on upload"
git push origin main
```

---

### Task 4: Verify CI and prepare the on-device checklist

**Files:**
- No source changes.

**Interfaces:**
- Consumes: the commits from Tasks 1-3.
- Produces: a green CI confirmation on the final commit sha, consumed by the release step.

- [ ] **Step 1: Confirm CI is green on the final commit**

Run:
```powershell
gh run list --repo i0trost01/BannerlatorFork --workflow fork-ci.yml --limit 4 --json databaseId,headSha,status,conclusion
```
Expected: the newest run(s) for the final commit show `"conclusion":"success"`. If red, read the failing
step and do not proceed until green.

- [ ] **Step 2: Record the on-device checklist in the SDD ledger**

Append to `.superpowers/sdd/2026-09-26-steam-cloud-support-false-negative.md/progress.md` (create the
directory if absent):

```
On-device checklist (user):
1. Death's Gambit: Afterlife -> Save Manager upload -> succeeds; no "doesn't support Steam Cloud" error.
2. Death's Gambit download still works (regression).
3. A genuinely cloud-less title still shows the honest local-only message.
4. A no-retention title (FlatOut 2) still shows the "doesn't keep Steam Cloud saves" message.
5. Exit auto-upload for Death's Gambit no longer logs "No Steam Cloud support — saved locally only".
```

- [ ] **Step 3: Ledger (no commit)**

`.superpowers/` is gitignored; verify `git status --porcelain` is clean and skip. No commit required.

---

## Self-Review

**1. Spec coverage:** Spec Change 1 → Task 1 Step 3; Change 2 → Task 1 Step 4; Change 3 → Task 3
Step 3; Change 4 → Task 3 Step 4; Change 5 → Task 2; Change 6 (noSteamCloud not "no support") →
Task 3 Step 3/4 (the manifest check now governs both paths, so a stale mark no longer blocks a game
with cloud files). Out-of-scope items untouched.

**2. Placeholder scan:** No TBD/TODO. Every code block is complete and paste-ready.

**3. Type consistency:** Guarded identifiers — `hasCloudSupport`, `hasUsableSaveFiles`,
`cloudSupportCache`, `listFiles(`, `CLOUD_SUPPORT_EPOCH`, `cloud_support_epoch`,
`getCloudSupportCached`, `setCloudSupportCached`, `NO_CLOUD_MESSAGE`, `NO_RETENTION_MESSAGE`,
`hasCloudSupportCached`, `syncToCloudBlocking`, `uploadSaves` — are used identically across tasks, the
tests, and the prose. `steamCloud` is in scope in `uploadSaves` (`:161`); `requireCloud()` in
`syncToCloudBlocking`.
