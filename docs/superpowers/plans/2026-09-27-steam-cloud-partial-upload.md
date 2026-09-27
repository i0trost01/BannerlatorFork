# Steam Cloud Partial-Upload Acceptance + Zero-Block Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Steam Cloud uploads report success when the changed saves land (a partial upload is fine), and stop reporting deduped ("blocks=0") files as failures.

**Architecture:** Two small corrections in the upload path: (1) the Rust zero-block short-circuit returns success again (a `blocks=0` begin means Steam already holds that content); (2) the post-upload decision in `SteamCloudSaveManager` treats `verified > 0` as success with honest "X of N" wording, keeping only a truly-empty manifest as the no-retention error.

**Tech Stack:** Kotlin (Android), JUnit4 source-assertion tests, Gradle (`:app:testStandardDebugUnitTest`), GitHub Actions CI, adb for on-device verification.

**Spec:** `docs/superpowers/specs/2026-09-27-steam-cloud-partial-upload-design.md`

## Global Constraints

- Flavor: `standard` only. Application id: `com.winlator.banner.fork`. Do not touch other flavors.
- No local Android SDK/NDK — CI (`.github/workflows/fork-ci.yml`, task `:app:testStandardDebugUnitTest`)
  is the only build/test runner. Never claim a local build passes.
- Robolectric **cannot** boot this module. All tests are plain JVM JUnit4 **source-assertion guards**
  (read a source file, assert substrings). Mirror
  `app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java`.
- Kotlin; match each file's heavily-commented style.
- Uploads stay strictly additive (`filesToDelete` empty).
- Preserve the no-retention signal: a committed >0 upload whose manifest is COMPLETELY empty must still
  `markNoSteamCloud` + `NO_RETENTION_MESSAGE`.
- Do NOT change the upload protocol (canEncrypt / commit / sequential) — shipped in `3.1.3-fork.29`.
- Every `gh` command needs `--repo i0trost01/BannerlatorFork`.
- `rg` is not installed. Use the Grep tool or PowerShell `Select-String`.
- Work on branch `fix/cloud-partial-upload` in an isolated worktree (the main checkout is held by a
  concurrent session). Run all git/gh commands from the worktree.

---

### Task 1: Treat a zero-block upload as success (dedup)

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/blsteam/BlSteamSession.kt` (`uploadCloudFile`, zero-block short-circuit ~:482-483)
- Test: `app/src/test/java/com/winlator/star/store/ZeroBlockUploadGuardTest.java` (create)

**Interfaces:**
- Consumes: `nativeCloudCommitFileUpload(...)`.
- Produces: the zero-block path returns `true` (Steam already holds the content → success). Task 2
  relies on deduped files not being counted as failures.

**Context:** Device log for Cuphead (app 268910) showed 5 files as `FAILED` after
`cloud upload short-circuit: blocks=0 for … — file already in cloud, committing`. A `blocks=0` begin is
Steam's dedup signal: no blocks to PUT because it already has that content; its commit returns
`file_committed=false` because there is nothing new to store. Current code (after the protocol branch):

```kotlin
                val committed = nativeCloudCommitFileUpload(h, true, appId, fileShaHex, filename)
                return committed
```

This reports correctly-present files as failures. It must return `true`.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/ZeroBlockUploadGuardTest.java`:

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
 * Guard: a zero-block cloud upload is a dedup SUCCESS, not a failure.
 *
 * Steam returns no blocks ("blocks=0") when it already holds the file's content; the commit then
 * returns file_committed=false because there is nothing new to store. Reporting that as FAILED made
 * already-present saves look like failed uploads (device log: Cuphead app 268910, 5 of 6 files).
 */
public class ZeroBlockUploadGuardTest {

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
    public void zeroBlockUploadReturnsSuccess() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "blsteam", "BlSteamSession.kt");
        int zero = src.indexOf("cloud upload short-circuit");
        assertTrue("the zero-block short-circuit must exist", zero >= 0);
        // The short-circuit block must end in a success return (true), not the commit result.
        int ret = src.indexOf("return true", zero);
        assertTrue("zero-block path must return true (dedup success)", ret > zero && ret < zero + 1200);
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run CI (push the branch or `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork --ref fix/cloud-partial-upload`).
Expected: FAIL on `zeroBlockUploadReturnsSuccess` (the block currently ends `return committed`, not
`return true`). Intentional RED.

- [ ] **Step 3: Return success on the zero-block path**

In `BlSteamSession.kt`, replace the zero-block body:

```kotlin
        try {
            val blocks0 = org.json.JSONObject(beginJson).optJSONArray("blocks")
            if (blocks0 == null || blocks0.length() == 0) {
                android.util.Log.i(
                    "BlSteamSession",
                    "cloud upload short-circuit: blocks=0 for $filename — Steam already holds this " +
                        "content (dedup); commit sent, treating as success"
                )
                // blocks=0 is Steam's dedup signal: it already has this file, so there is nothing to
                // PUT and the commit returns file_committed=false (nothing new to store). That is
                // SUCCESS, not failure — reporting it as FAILED made already-present saves look like
                // failed uploads. WinNative treats this as success too.
                nativeCloudCommitFileUpload(h, true, appId, fileShaHex, filename)
                return true
            }
        } catch (e: Exception) {
            android.util.Log.w("BlSteamSession", "uploadCloudFile early-parse failed: $filename", e)
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run CI. Expected: SUCCESS; `ZeroBlockUploadGuardTest` passes and the full suite stays green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/blsteam/BlSteamSession.kt app/src/test/java/com/winlator/star/store/ZeroBlockUploadGuardTest.java
git commit -m "fix(cloud): treat a zero-block (deduped) upload as success, not failure"
git push -u origin fix/cloud-partial-upload
```

---

### Task 2: Accept partial uploads (verified > 0 is success)

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt` (post-upload decision ~:350-361)
- Test: `app/src/test/java/com/winlator/star/store/PartialUploadAcceptGuardTest.java` (create)

**Interfaces:**
- Consumes: `verified`, `unverified`, `uploaded`, `remoteShaByPath`, `upToDate`, `allOk` from the
  existing upload path.
- Produces: `verified > 0` → `cb.onDone(...)` (success); completely-empty manifest → no-retention error;
  `verified == 0` with a non-empty manifest → honest error.

**Context:** Current decision (`SteamCloudSaveManager.kt:350-361`):

```kotlin
                    if (uploaded.get() > 0 && verified == 0 && unverified == uploaded.get() &&
                        remoteShaByPath != null && remoteShaByPath.isEmpty()) {
                        SaveSyncStore.markNoSteamCloud(ctx, appId)
                        cb.onError(NO_RETENTION_MESSAGE)
                    } else if (unverified > 0) {
                        cb.onError("Uploaded $verified of ${uploaded.get()} changed; $unverified did not reach Steam Cloud")
                    } else {
                        cb.onDone("Uploaded ${uploaded.get()} changed, $upToDate already up-to-date")
                    }
                } else {
                    cb.onError("Uploaded ${uploaded.get()} of ${toUpload.size} changed; some files failed")
                }
```

The user wants: most games have ONE file that needs uploading; uploading it (and not the rest) is fine
→ success. Only a genuinely empty cloud after a committed upload is an error.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/PartialUploadAcceptGuardTest.java`:

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
 * Guard: a partial cloud upload (some files verified) is SUCCESS, not an error.
 *
 * Most games have one save file that actually changed; uploading that and dedup-skipping the rest is
 * fine. Only a completely empty manifest after a committed upload is the no-retention error.
 */
public class PartialUploadAcceptGuardTest {

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
    public void partialUploadWithAtLeastOneVerifiedIsSuccess() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        // The verified>0 branch must call onDone, and the old "did not reach Steam Cloud" hard error
        // on any unverified path must be gone.
        assertTrue("verified>0 must be reported as success", b.contains("verified > 0"));
        assertTrue("the partial-success branch must use onDone", b.contains("onDone(\"Uploaded $verified"));
        assertTrue("the blanket 'did not reach Steam Cloud' error must be removed",
                !b.contains("did not reach Steam Cloud"));
    }

    @Test
    public void emptyManifestStillErrorsAsNoRetention() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("must keep the no-retention mark", b.contains("markNoSteamCloud"));
        assertTrue("must keep the no-retention message", b.contains("NO_RETENTION_MESSAGE"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run CI. Expected: FAIL on
`partialUploadWithAtLeastOneVerifiedIsSuccess` (no `verified > 0`, still has "did not reach Steam
Cloud"). Intentional RED.

- [ ] **Step 3: Make partial uploads succeed**

Replace the decision block (`:350-361`) with:

```kotlin
                    if (uploaded.get() > 0 && verified == 0 && unverified == uploaded.get() &&
                        remoteShaByPath != null && remoteShaByPath.isEmpty()) {
                        // Nothing at all is in the cloud after a committed >0 upload → no retention.
                        SaveSyncStore.markNoSteamCloud(ctx, appId)
                        cb.onError(NO_RETENTION_MESSAGE)
                    } else if (verified > 0) {
                        // At least one file landed → SUCCESS. Most games have a single changed save;
                        // skipping/deduping the rest is fine (the cloud already holds them).
                        val extra = if (unverified > 0) "; $unverified already in cloud or skipped" else ""
                        cb.onDone("Uploaded $verified of ${uploaded.get()} changed$extra, $upToDate already up-to-date")
                    } else {
                        // Nothing verified and the cloud is known non-empty → honest failure.
                        cb.onError("Uploaded 0 of ${uploaded.get()} changed; no file reached Steam Cloud")
                    }
                } else {
                    // A per-file commit failed before verification ran (verified/unverified are not in
                    // scope here). Keep the existing honest error — do NOT invent a partial success.
                    cb.onError("Uploaded ${uploaded.get()} of ${toUpload.size} changed; some files failed")
                }
```

Do NOT move `verified`/`unverified` out of the `if (allOk.get())` block: they are declared at
`:332-333` inside it, and the `else` branch intentionally keeps its existing error. This is the
decisive choice — no compiler-safety workaround needed.

- [ ] **Step 4: Run test to verify it passes**

Run CI. Expected: SUCCESS; both `PartialUploadAcceptGuardTest` tests pass.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt app/src/test/java/com/winlator/star/store/PartialUploadAcceptGuardTest.java
git commit -m "fix(cloud): accept a partial upload as success (verified > 0)"
git push origin fix/cloud-partial-upload
```

---

### Task 3: Verify CI and the on-device behavior (adb)

**Files:**
- No source changes.

**Interfaces:**
- Consumes: Tasks 1-2.
- Produces: green CI + a device-verified result.

- [ ] **Step 1: Confirm CI is green on the branch head**

```powershell
gh run list --repo i0trost01/BannerlatorFork --workflow fork-ci.yml --branch fix/cloud-partial-upload --limit 4 --json databaseId,headSha,status,conclusion
```
Expected: newest run for the branch head `success`.

- [ ] **Step 2: On-device (adb) — verify the Cuphead case**

adb path: `C:\Users\i0tro\AppData\Local\Android\Sdk\platform-tools\adb.exe`. Ask the user to flash the
new build, then:

```powershell
& "<adb>" logcat -c
# user: exit a Steam game / tap Sync Now for Cuphead
& "<adb>" logcat -d -v time | Select-String "BH_STEAM_CLOUD|BlSteamSession|CLOUD:"
```
Expected: the previously-FAILED zero-block files now report success; the Save Manager shows an
"Uploaded X of N" success (not an error). Confirm against Steam's remote-storage page (the changed
save's timestamp advances; unchanged files keep their timestamp).

- [ ] **Step 3: Ledger (no commit)** — `.superpowers/` is gitignored; skip.

---

## Self-Review

**1. Spec coverage:** Change 1 (zero-block success) → Task 1; Change 2 (partial success) → Task 2;
verification → Task 3. The no-retention error and additive guarantee are preserved and guarded. The
out-of-scope items (protocol, client_id, which files to upload) are untouched.

**2. Placeholder scan:** No TBD/TODO. Code blocks are complete; Task 2 Step 3 includes an explicit
compiler-safety note with two concrete options rather than a vague instruction.

**3. Type consistency:** `verified`, `unverified`, `uploaded`, `remoteShaByPath`, `upToDate`, `allOk`,
`markNoSteamCloud`, `NO_RETENTION_MESSAGE`, `nativeCloudCommitFileUpload` — used identically across the
tests, snippets, and prose.
