# Steam Cloud: No-Error Uploads + Content-Based Sync State — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Steam Cloud uploads report success for every client-side no-op (nothing to upload / all already in cloud / partial), and stop the Save Manager sticking on "Local is ahead" after a successful upload.

**Architecture:** Three targeted changes: (1) `uploadSaves` reports `onDone` for all non-exceptional outcomes, keeping `onError` only for transport/auth/exception; (2) `recordAfterUpload` stamps `lastUploadAt` with the newest local mtime AND the state uses `librarySnapshotHash == cloudManifestHash` as an IN_SYNC tie-breaker, so a synced game never reads LOCAL_AHEAD; (3) malformed `%Root%`-without-slash library paths are ignored by the snapshot/state computation.

**Tech Stack:** Kotlin (Android), JUnit4 source-assertion tests, Gradle (local build: `:app:testStandardDebugUnitTest`), adb for on-device verification.

**Spec:** `docs/superpowers/specs/2026-09-27-steam-cloud-no-error-and-sync-state-design.md`

## Global Constraints

- Flavor: `standard` only. Application id: `com.winlator.banner.fork`. Do not touch other flavors.
- **Local build works** (JDK 17 + Android SDK; `local.properties` present). Compile:
  `& "<worktree>\gradlew.bat" :app:compileStandardDebugKotlin --console=plain`; test:
  `& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain`. Use these for TDD; CI is
  not required per task. Never claim a build passes without running it.
- Robolectric **cannot** boot this module. All tests are plain JVM JUnit4 **source-assertion guards**
  (read a source file, assert substrings). Mirror
  `app/src/test/java/com/winlator/star/store/PartialUploadAcceptGuardTest.java`.
- Kotlin; match each file's heavily-commented style.
- Uploads stay strictly additive (`filesToDelete` empty). Download path unchanged. Upload protocol
  (canEncrypt/commit/sequential/zero-block) unchanged.
- Work on branch `fix/cloud-sync-state` in an isolated worktree (the main checkout is held by a
  concurrent session). Run all git/gh commands from the worktree.

---

### Task 1: Uploads never error on client-side no-ops

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt` (`uploadSaves` decision, ~:350-375)
- Test: `app/src/test/java/com/winlator/star/store/NoErrorUploadGuardTest.java` (create)

**Interfaces:**
- Consumes: `verified`, `unverified`, `uploaded`, `remoteShaByPath`, `allOk`, `toUpload`, `upToDate`.
- Produces: every non-exceptional outcome calls `cb.onDone(...)`; `cb.onError(...)` remains only for
  `requireCloud()==null`, `batchId==0`, and exceptions.

**Context:** Current decision block (`SteamCloudSaveManager.kt:350-375`):

```kotlin
                    if (uploaded.get() > 0 && verified == 0 && unverified == uploaded.get() &&
                        remoteShaByPath != null && remoteShaByPath.isEmpty()) {
                        SaveSyncStore.markNoSteamCloud(ctx, appId)
                        cb.onError(NO_RETENTION_MESSAGE)
                    } else if (verified > 0) {
                        val failed = toUpload.size - uploaded.get()
                        val extra = buildString {
                            if (unverified > 0) append("; $unverified already in cloud or skipped")
                            if (failed > 0) append("; $failed failed")
                        }
                        cb.onDone("Uploaded $verified of ${toUpload.size} changed$extra, $upToDate already up-to-date")
                    } else if (allOk.get()) {
                        cb.onError("Uploaded 0 of ${uploaded.get()} changed; no file reached Steam Cloud")
                    } else {
                        cb.onError("Uploaded ${uploaded.get()} of ${toUpload.size} changed; some files failed")
                    }
```

The user's rule: **if nothing gets uploaded, it must not be an error.** Every client-side no-op (nothing
changed, all already in cloud, partial, none verified) is a plain success/summary.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/NoErrorUploadGuardTest.java`:

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
 * Guard: an upload that has nothing to do (or uploads partially) must NOT report an error.
 *
 * The only errors uploadSaves may raise are transport/auth/exception: not signed in, a refused batch,
 * or a thrown exception. Every client-side no-op outcome is a plain success summary.
 */
public class NoErrorUploadGuardTest {

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

    private static String body(String src, String signature) {
        int start = src.indexOf(signature);
        assertTrue("source must contain: " + signature, start >= 0);
        int open = src.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return src.substring(open, i + 1); }
        }
        throw new AssertionError("unterminated body for " + signature);
    }

    @Test
    public void uploadNoOpOutcomesAreSuccessNotError() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        // No client-side no-op may call onError with a "nothing/partial" style message.
        assertTrue("must not error with 'no file reached Steam Cloud'", !b.contains("no file reached Steam Cloud"));
        assertTrue("must not error with 'some files failed'", !b.contains("some files failed"));
        // The no-retention message must no longer be delivered via onError.
        assertTrue("no-retention must not be an onError", !b.contains("cb.onError(NO_RETENTION_MESSAGE)"));
    }

    @Test
    public void transportAndExceptionErrorsRemain() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("must keep the not-signed-in error", b.contains("Not signed in"));
        assertTrue("must keep the refused-batch error", b.contains("refused to open a cloud upload batch"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain`
Expected: FAIL on `uploadNoOpOutcomesAreSuccessNotError` (still has the errors). Intentional RED.

- [ ] **Step 3: Make every no-op outcome success**

Replace the decision block (`:350-375`) with:

```kotlin
                    // Every CLIENT-SIDE outcome is a success summary — an upload with nothing to do (all
                    // files already in the cloud, or no changes) must never alarm the user. The only
                    // errors uploadSaves raises are transport/auth/exception (handled above/below).
                    val failed = toUpload.size - uploaded.get()
                    val extra = buildString {
                        if (unverified > 0) append("; $unverified already in cloud or skipped")
                        if (failed > 0) append("; $failed failed")
                    }
                    if (uploaded.get() > 0 && verified == 0 && unverified == uploaded.get() &&
                        remoteShaByPath != null && remoteShaByPath.isEmpty()) {
                        // The cloud kept nothing this round — remember it (informational), but do not
                        // treat it as a user-facing error; the saves are safe in the Library.
                        SaveSyncStore.markNoSteamCloud(ctx, appId)
                        cb.onDone("Uploaded 0 of ${toUpload.size} changed; cloud kept none (saved locally)$extra")
                    } else {
                        cb.onDone("Uploaded $verified of ${toUpload.size} changed$extra, $upToDate already up-to-date")
                    }
```

(Keep the `requireCloud()`/`batchId==0`/exception `onError` calls elsewhere unchanged. The two
`cb.onError(...)` strings removed here must be gone.)

- [ ] **Step 4: Run test to verify it passes**

Run: `& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain`
Expected: SUCCESS; both `NoErrorUploadGuardTest` tests pass and the suite stays green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt app/src/test/java/com/winlator/star/store/NoErrorUploadGuardTest.java
git commit -m "fix(cloud): never report a client-side no-op upload as an error"
```

---

### Task 2: Content-based sync state (stop stuck LOCAL_AHEAD)

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SaveSyncStore.kt` (`recordAfterUpload` ~:205-206; `computeState` ~:259-299; `statusOf` ~:94-140 to pass the hashes into `computeState`)
- Test: `app/src/test/java/com/winlator/star/store/SyncStateContentGuardTest.java` (create)

**Interfaces:**
- Consumes: `rec.optString("librarySnapshotHash")`, `rec.optString("cloudManifestHash")`, `staleness`,
  `lastUploadAt`, `lastDownloadAt`.
- Produces: `recordAfterUpload` stamps `lastUploadAt` with the newest LOCAL mtime (not wall-clock);
  `computeState` returns `IN_SYNC` when `librarySnapshotHash == cloudManifestHash` (content match), even
  if `newestLocalMtime > lastSync`.

**Context:** Two facts cause the stuck pill:
- `recordAfterUpload` (`:206`) stamps `System.currentTimeMillis()`, which races the local mtime and
  leaves `newestLocalMtime > lastUploadAt` whenever a save's mtime is newer than the upload moment.
- `computeState` (`:287`) uses only mtime: `if ((hasLibrary || containerFileCount > 0) && newestLocalMtime > lastSync) return SaveState.LOCAL_AHEAD`.

`refreshCommonFields` already stores `librarySnapshotHash` (Library content) and the download/refresh
path stores `cloudManifestHash` (cloud content). When those are equal, the game is genuinely in sync.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/SyncStateContentGuardTest.java`:

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
 * Guard: a game whose Library content matches the cloud must read IN_SYNC, not LOCAL_AHEAD.
 *
 * The stuck "local is ahead" came from comparing raw mtimes (newestLocalMtime > lastSync). A successful
 * upload must clear it: lastUploadAt is stamped with the newest local mtime, and a content-hash match
 * between Library and cloud is an IN_SYNC tie-breaker.
 */
public class SyncStateContentGuardTest {

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
    public void computeStateUsesContentHashForInSync() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SaveSyncStore.kt");
        assertTrue("computeState must consult the content hashes",
                src.contains("librarySnapshotHash") && src.contains("cloudManifestHash"));
        assertTrue("must short-circuit to IN_SYNC on a content match",
                src.contains("contentInSync"));
    }

    @Test
    public void recordAfterUploadStampsNewestLocalMtime() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SaveSyncStore.kt");
        int idx = src.indexOf("fun recordAfterUpload(");
        assertTrue("recordAfterUpload must exist", idx >= 0);
        String b = src.substring(idx, Math.min(src.length(), idx + 500));
        assertTrue("must stamp lastUploadAt with the newest local mtime, not wall-clock",
                b.contains("newestLocalMtime") || b.contains("libraryNewestMtime"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain`
Expected: FAIL on both tests (no `contentInSync`; `recordAfterUpload` uses `System.currentTimeMillis()`).
Intentional RED.

- [ ] **Step 3: Stamp the newest local mtime at upload**

Replace `recordAfterUpload` (`:205-206`):

```kotlin
    /** Library → Cloud succeeded. Stamp lastUploadAt with the NEWEST LOCAL MTIME (not wall-clock) so
     *  the state machine sees newestLocalMtime == lastSync immediately and does not flag LOCAL_AHEAD;
     *  only a later local change (a newer file) re-triggers it. */
    fun recordAfterUpload(ctx: Context, appId: Int) {
        writeHook(ctx, appId) { rec ->
            val (_, _) = librarySnapshot(SteamCloudSavePaths.libraryDir(ctx, appId))
            val st = staleness(ctx, appId)
            rec.put("lastUploadAt", maxOf(st.libraryNewestMtime, st.containerNewestMtime))
        }
    }
```

(If `staleness(...)` is not directly reachable here, read the existing `staleness` helper's signature
and use it; the point is to write the newest local mtime, not `System.currentTimeMillis()`.)

- [ ] **Step 4: Add the content-match IN_SYNC tie-breaker**

In `computeState`, add two params `librarySnapshotHash: String, cloudManifestHash: String` (pass them
from `statusOf` where `rec` is read) and, before the mtime check at `:287`:

```kotlin
        // Content match: the Library holds exactly what the cloud holds → in sync, regardless of
        // mtimes (a save's mtime can be newer than the upload moment without being a real change).
        val contentInSync = librarySnapshotHash.isNotEmpty() && librarySnapshotHash == cloudManifestHash
        if (contentInSync && hasLibrary) return SaveState.IN_SYNC
```

Then leave the existing `newestLocalMtime > lastSync` check after it (so a genuine newer local file with
different content still flags LOCAL_AHEAD).

- [ ] **Step 5: Run test to verify it passes**

Run: `& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain`
Expected: SUCCESS; both `SyncStateContentGuardTest` tests pass.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SaveSyncStore.kt app/src/test/java/com/winlator/star/store/SyncStateContentGuardTest.java
git commit -m "fix(cloud): use content match + newest-mtime stamp to clear LOCAL_AHEAD"
```

---

### Task 3: Ignore malformed %Root%-without-slash library paths

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SaveSyncStore.kt` (`librarySnapshot` ~:411-420) and/or `SteamCloudSavePaths.kt` (path parse) — whichever is the right chokepoint
- Test: `app/src/test/java/com/winlator/star/store/MalformedLibraryPathGuardTest.java` (create)

**Interfaces:**
- Consumes: the Library enumeration / snapshot.
- Produces: entries whose first path segment is a `%Root%` token NOT followed by `/` (e.g.
  `%WinAppDataRoaming%Cuphead`) are excluded from the snapshot count/hash (and thus from the state and
  upload set).

**Context:** The Library for Cuphead contains a malformed `%WinAppDataRoaming%Cuphead/` folder (root
token immediately followed by the game folder, no `/`). It inflates `libraryFileCount` (8 vs cloud 3)
and its stale copy always looks "newer". Such entries are never valid `%Root%/rest` paths.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/MalformedLibraryPathGuardTest.java`:

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
 * Guard: a malformed library rel path ("%Root%" token not followed by '/', e.g. "%WinAppDataRoaming%Cuphead")
 * must be rejected so it neither inflates the local snapshot nor the upload set.
 */
public class MalformedLibraryPathGuardTest {

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
    public void malformedRootTokenIsRejected() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSavePaths.kt");
        assertTrue("must validate a %Root% token is followed by '/' or end",
                src.contains("%") && (src.contains("isValidRootPath") || src.contains("rootPathValid") ||
                    src.contains("looksLikeRootedPath") || src.contains("indexOf('/')")));
    }

    @Test
    public void snapshotSkipsMalformedEntries() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SaveSyncStore.kt");
        int idx = src.indexOf("private fun librarySnapshot(");
        assertTrue("librarySnapshot must exist", idx >= 0);
        String b = src.substring(idx, Math.min(src.length(), idx + 900));
        assertTrue("snapshot must skip malformed rooted paths",
                b.contains("malformed") || b.contains("isValidRootPath") || b.contains("looksLikeRootedPath"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain`
Expected: FAIL (no validity check). Intentional RED.

- [ ] **Step 3: Add a root-path validity check and use it**

Add a small helper (in `SteamCloudSavePaths`, public so both call sites use it):

```kotlin
    /**
     * True if [rel] is a well-formed rooted path: a leading `%Token%` MUST be immediately followed by
     * '/' (or be the entire path). Rejects malformed forms like `%WinAppDataRoaming%Cuphead` (token
     * glued to the next segment), which are not valid `%Root%/rest` library paths.
     */
    fun isValidRootedPath(rel: String): Boolean {
        val r = rel.replace('\\', '/').trimStart('/')
        if (!r.startsWith("%")) return true // not a rooted path; leave judgement to other validators
        val end = r.indexOf('%', startIndex = 1)
        if (end < 0) return false
        val after = end + 1
        return after >= r.length || r[after] == '/'
    }
```

Then in `SaveSyncStore.librarySnapshot` (and the upload enumeration if separate), skip files whose rel
path fails `isValidRootedPath`:

```kotlin
        root.walkTopDown().filter { it.isFile }.forEach { f ->
            val rel = f.absolutePath.removePrefix(base).trimStart('/')
            if (!SteamCloudSavePaths.isValidRootedPath(rel)) return@forEach
            ... // existing line/hash accumulation
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain`
Expected: SUCCESS; `MalformedLibraryPathGuardTest` passes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudSavePaths.kt app/src/main/java/com/winlator/star/store/SaveSyncStore.kt app/src/test/java/com/winlator/star/store/MalformedLibraryPathGuardTest.java
git commit -m "fix(cloud): ignore malformed %Root%-without-slash library paths"
```

---

### Task 4: Verify locally and on-device (adb)

**Files:** none.

- [ ] **Step 1: Local build + full unit suite green**

```powershell
& "<worktree>\gradlew.bat" :app:compileStandardDebugKotlin --console=plain
& "<worktree>\gradlew.bat" :app:testStandardDebugUnitTest --console=plain
```
Expected: BUILD SUCCESSFUL both.

- [ ] **Step 2: On-device (adb) after flashing**

adb: `C:\Users\i0tro\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Ask the user to flash, then:
```powershell
& "<adb>" logcat -c
# user: Cuphead -> Save Manager -> Sync Now (or exit the game)
& "<adb>" logcat -d -v time | Select-String "BH_STEAM_CLOUD|BlSteamSession|CLOUD:"
& "<adb>" shell cat /storage/emulated/0/Bannerlator/SteamCloudSaves/_status.json
```
Expected: the upload reports success; the Cuphead entry's `lastUploadAt` equals the newest local mtime;
the Save Manager shows IN_SYNC (not LOCAL_AHEAD); a push with nothing to upload shows success.

- [ ] **Step 3: Ledger (no commit)** — `.superpowers/` is gitignored; skip.

---

## Self-Review

**1. Spec coverage:** Change 1 (no-error no-ops) → Task 1; Change 2 (content state + newest-mtime stamp)
→ Task 2; Change 3 (malformed paths) → Task 3. Verification → Task 4. Out-of-scope items untouched.

**2. Placeholder scan:** No TBD/TODO. Task 2 Step 3 and Task 3 Step 3 include an explicit "if the helper
signature differs, read it and adapt" note tied to a concrete requirement, with the exact target
behavior — not a vague instruction.

**3. Type consistency:** `verified`, `unverified`, `uploaded`, `toUpload`, `remoteShaByPath`, `allOk`,
`upToDate`, `librarySnapshotHash`, `cloudManifestHash`, `newestLocalMtime`, `libraryNewestMtime`,
`containerNewestMtime`, `isValidRootedPath`, `staleness` — used identically across tests, snippets, and
prose.
