# Steam Cloud Upload Protocol Fix — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Steam Cloud uploads actually persist by aligning the upload protocol with WinNative's working call sequence: disable client encryption, honor the commit result, and upload a batch sequentially.

**Architecture:** Three targeted changes in the cloud backend/manager, each independently justified by a side-by-side comparison with the working reference. No storage-model change; the existing post-upload verification stays as the honest backstop and should now pass.

**Tech Stack:** Kotlin (Android), JavaSteam 1.8.0.1 (vendored `io.github.joshuatam`), JUnit4 source-assertion tests, Gradle (`:app:testStandardDebugUnitTest`), GitHub Actions CI.

**Spec:** `docs/superpowers/specs/2026-09-27-steam-cloud-upload-protocol-fix-design.md`

## Global Constraints

- Flavor: `standard` only. Application id: `com.winlator.banner.fork`. Do not touch other flavors.
- No local Android SDK/NDK — **never** claim a build or test passes locally. CI
  (`.github/workflows/fork-ci.yml`, task `:app:testStandardDebugUnitTest`) is the only execution
  environment.
- Robolectric **cannot** boot on this module. All tests are plain JVM JUnit4 **source-assertion
  guards** (read a source file from disk, assert substrings). Mirror
  `app/src/test/java/com/winlator/star/store/UploadVerifyGuardTest.java`.
- Change is Kotlin. Match each file's heavily-commented style.
- Uploads stay **strictly additive**: `filesToDelete` stays empty.
- Keep the existing post-upload verification (from `3.1.3-fork.25`) — do NOT remove it; it becomes the
  honest backstop.
- Do NOT change the storage model, the Save Manager UI, the SHA-1 incremental skip, or the download
  path. Do NOT add a real `client_id` (no source; documented residual).
- Every `gh` command needs `--repo i0trost01/BannerlatorFork`.
- `rg` is not installed. Use the Grep tool or PowerShell `Select-String`.
- **All work happens on branch `fix/steam-cloud-upload-protocol` in an isolated worktree**, not the
  main checkout (held by a concurrent session). Run every `git`/`gh` command from the worktree.

---

### Task 1: Disable client encryption and honor the JavaSteam commit result

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudBackend.kt`
  (`JavaSteamCloudBackend.uploadOne`, ~:200-247)
- Test: `app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java` (create)

**Interfaces:**
- Consumes: `com.winlator.star.store.SteamCloudBackend.sha1(file)`; the JavaSteam
  `SteamCloud.beginFileUpload(...)` / `commitFileUpload(...)` signatures (verified via `javap`):
  - `beginFileUpload(int appId, int fileSize, int rawFileSize, byte[] fileSha, Date timestamp,
    String filename, int platformsToSync, int cellId, boolean canEncrypt, boolean isSharedFile,
    Integer deprecatedRealm, long uploadBatchId, CoroutineScope)`
  - `commitFileUpload(boolean transferSucceeded, int appId, byte[] fileSha, String filename)`
    → `CompletableFuture<Boolean>` (`file_committed`)
- Produces: `uploadOne` returns `true` only when BOTH the block PUTs and Steam's commit succeeded.
  Task 3 relies on `uploaded` now meaning "committed".

**Context:** Current code (`SteamCloudBackend.kt:200-247`):

```kotlin
    override fun uploadOne(appId: Int, file: File, cloudPath: String, batchId: Long): Boolean {
        val sha = SteamCloudBackend.sha1(file)
        val fileSize = file.length().toInt()
        val info = sc.beginFileUpload(
            appId = appId, fileSize = fileSize, rawFileSize = fileSize, fileSha = sha,
            timestamp = Date(file.lastModified()), filename = cloudPath, uploadBatchId = batchId,
        ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        ...
        // Commit tells the CM whether the transfer for this file succeeded. This does not delete
        // anything; on failure the CM simply drops this file's pending upload.
        sc.commitFileUpload(ok, appId, sha, cloudPath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        return ok
    }
```

Two defects: (a) `canEncrypt` is omitted, so JavaSteam's default `true` is sent — Steam may then set
`encrypt_file=true` and reject the plaintext commit; (b) `commitFileUpload`'s boolean is discarded.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java`:

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
 * Guard for the upload protocol fix (Steam Cloud uploads never persisted).
 *
 * Three differences from the working WinNative upload: JavaSteam sent canEncrypt=true (default) and
 * ignored encrypt_file; the commitFileUpload boolean was discarded; and a batch uploaded files
 * concurrently. These guards pin the corrected call shape so a revert fails.
 */
public class UploadProtocolGuardTest {

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
    public void javaSteamUploadDisablesEncryption() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudBackend.kt");
        assertTrue("beginFileUpload must pass canEncrypt = false (WinNative parity)",
                src.contains("canEncrypt = false"));
    }

    @Test
    public void javaSteamUploadHonorsTheCommitResult() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudBackend.kt");
        assertTrue("commitFileUpload's boolean must be captured",
                src.contains("val committed"));
        assertFalse("must not discard the commit result by returning the block-PUT ok alone",
                src.contains("sc.commitFileUpload(ok, appId, sha, cloudPath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)\n        return ok"));
        assertTrue("uploadOne must fold the commit result into its return",
                src.contains("return ok && committed"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI FAILS on `javaSteamUploadDisablesEncryption` (no `canEncrypt = false`) and
`javaSteamUploadHonorsTheCommitResult` (no `val committed` / `return ok && committed`). Intentional TDD
RED.

- [ ] **Step 3: Pass `canEncrypt = false`**

In `JavaSteamCloudBackend.uploadOne`, add the named argument to the `beginFileUpload(...)` call:

```kotlin
        val info = sc.beginFileUpload(
            appId = appId, fileSize = fileSize, rawFileSize = fileSize, fileSha = sha,
            timestamp = Date(file.lastModified()), filename = cloudPath, uploadBatchId = batchId,
            // WinNative parity: do NOT offer client-side encryption. JavaSteam defaults canEncrypt to
            // true, but this upload sends the RAW bytes and never acts on the response's encrypt_file,
            // so if Steam asked for encryption the commit would verify ciphertext against a raw SHA
            // and reject it (file_committed=false) while the block PUTs still returned 200.
            canEncrypt = false,
        ).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
```

- [ ] **Step 4: Read and return the commit result**

Replace the tail of `JavaSteamCloudBackend.uploadOne` (the commit + `return ok`, ~:243-246) with:

```kotlin
        // Commit tells the CM whether the transfer for this file succeeded. This does not delete
        // anything; on failure the CM simply drops this file's pending upload. WinNative parity: the
        // commit's `file_committed` is authoritative — the block PUTs returning 200 only means the CDN
        // accepted the bytes, NOT that Steam stored them. Read it and report it.
        val committed = sc.commitFileUpload(ok, appId, sha, cloudPath)
            .get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)
        if (ok && !committed) {
            Log.w(TAG, "Block upload succeeded but Steam did NOT commit $cloudPath (file_committed=false)")
        }
        return ok && committed
```

- [ ] **Step 5: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; all `UploadProtocolGuardTest` tests pass and the full suite stays green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudBackend.kt app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java
git commit -m "fix(cloud): disable upload encryption and honor the JavaSteam commit result"
```

(Do not push yet unless Step 5's CI requires a push to run — CI runs on push; push this branch:
`git push -u origin fix/steam-cloud-upload-protocol`.)

---

### Task 2: Propagate the Rust zero-block commit result

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/blsteam/BlSteamSession.kt` (`uploadCloudFile`,
  zero-block short-circuit ~:472-484)
- Test: extend `app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java`

**Interfaces:**
- Consumes: `nativeCloudCommitFileUpload(handle, transferSucceeded, appId, fileShaHex, filename): Boolean`
  (already used; returns `file_committed`).
- Produces: the zero-block path returns the real commit result instead of an unconditional `true`.
  Task 3 relies on Rust `uploadOne` also meaning "committed".

**Context:** Current code (`BlSteamSession.kt:472-484`):

```kotlin
        try {
            val blocks0 = org.json.JSONObject(beginJson).optJSONArray("blocks")
            if (blocks0 == null || blocks0.length() == 0) {
                android.util.Log.i(
                    "BlSteamSession",
                    "cloud upload short-circuit: blocks=0 for $filename — file already in cloud, treating as success"
                )
                nativeCloudCommitFileUpload(h, true, appId, fileShaHex, filename)
                return true
            }
        } catch (e: Exception) { ... }
```

The commit result is discarded and `true` returned unconditionally. (The non-zero-block path at
`:532` already returns the commit value — this makes the zero-block path consistent.)

- [ ] **Step 1: Write the failing test**

Append to `UploadProtocolGuardTest.java`:

```java
    @Test
    public void rustZeroBlockUploadPropagatesTheCommitResult() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "blsteam", "BlSteamSession.kt");
        assertTrue("zero-block short-circuit must return the real commit result, not an unconditional true",
                src.contains("val committed = nativeCloudCommitFileUpload(h, true, appId, fileShaHex, filename)"));
        assertTrue("zero-block path must return the propagated commit result",
                src.contains("return committed"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI FAILS on `rustZeroBlockUploadPropagatesTheCommitResult` (no `val committed = ...`). Intentional RED.

- [ ] **Step 3: Propagate the commit result**

Replace the zero-block short-circuit body with:

```kotlin
        try {
            val blocks0 = org.json.JSONObject(beginJson).optJSONArray("blocks")
            if (blocks0 == null || blocks0.length() == 0) {
                android.util.Log.i(
                    "BlSteamSession",
                    "cloud upload short-circuit: blocks=0 for $filename — file already in cloud, committing"
                )
                // WinNative parity: the commit's result is authoritative. Report it rather than
                // assuming success — a zero-block begin is normally "already in cloud", but only the
                // commit confirms Steam kept it.
                val committed = nativeCloudCommitFileUpload(h, true, appId, fileShaHex, filename)
                return committed
            }
        } catch (e: Exception) {
            android.util.Log.w("BlSteamSession", "uploadCloudFile early-parse failed: $filename", e)
        }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/blsteam/BlSteamSession.kt app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java
git commit -m "fix(cloud): propagate the Rust zero-block commit result"
git push origin fix/steam-cloud-upload-protocol
```

---

### Task 3: Upload a batch sequentially

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt` (upload loop ~:279-299)
- Test: extend `app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java`

**Interfaces:**
- Consumes: the upload loop's existing `toUpload: List<Pair<File, String>>`, `uploadedShas:
  ConcurrentHashMap<String, ByteArray>`, `uploaded: AtomicInteger`, `allOk: AtomicBoolean`,
  `steamCloud.uploadOne(...)`.
- Produces: the upload loop is sequential (no `runConcurrently` on the upload path); `uploadedShas`
  may remain a plain map (task leaves it as-is to keep the diff small).

**Context:** Current loop (`SteamCloudSaveManager.kt:279-299`):

```kotlin
                runConcurrently(toUpload, "steam-cloud-ul-$appId") { (file, cloudPath) ->
                    try {
                        cb.onStatus("Uploading: ${file.name}")
                        if (steamCloud.uploadOne(appId, file, cloudPath, batchId)) {
                            uploaded.incrementAndGet()
                            runCatching {
                                val key = sanitizeRelative(cloudPath)
                                if (key != null) uploadedShas[key] = SteamCloudBackend.sha1(file)
                            }
                        } else {
                            allOk.set(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Upload task failed for ${file.name}", e)
                        allOk.set(false)
                    }
                }
```

WinNative uploads strictly sequentially within a batch (`SteamAutoCloud.kt:1039`); interleaved
begin/commit calls on one batch are a plausible cause of the intermittent 0↔1 flips.

- [ ] **Step 1: Write the failing test**

Append to `UploadProtocolGuardTest.java`:

```java
    @Test
    public void uploadBatchIsSequential() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        int upload = src.indexOf("fun uploadSaves(");
        assertTrue("uploadSaves must exist", upload >= 0);
        int next = src.indexOf("fun uploadFromLibrary(", upload);
        if (next < 0) next = src.length();
        String body = src.substring(upload, next);
        assertFalse("the upload loop must NOT use runConcurrently (WinNative uploads sequentially)",
                body.contains("runConcurrently(toUpload"));
        assertTrue("the upload loop must iterate sequentially", body.contains("for (entry in toUpload)"));
    }
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI FAILS on `uploadBatchIsSequential` (still `runConcurrently(toUpload`). Intentional RED.

- [ ] **Step 3: Make the upload loop sequential**

Replace the loop with:

```kotlin
                // Upload the batch's files SEQUENTIALLY (WinNative parity). The per-file protocol is
                // beginFileUpload -> block PUTs -> commitFileUpload against ONE open batch; interleaving
                // those across a thread pool was a suspect for commits that never landed. One file at a
                // time, same order as the batch's file list.
                for (entry in toUpload) {
                    val (file, cloudPath) = entry
                    try {
                        cb.onStatus("Uploading: ${file.name}")
                        if (steamCloud.uploadOne(appId, file, cloudPath, batchId)) {
                            uploaded.incrementAndGet()
                            runCatching {
                                val key = sanitizeRelative(cloudPath)
                                if (key != null) uploadedShas[key] = SteamCloudBackend.sha1(file)
                            }
                        } else {
                            allOk.set(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Upload task failed for ${file.name}", e)
                        allOk.set(false)
                    }
                }
```

- [ ] **Step 4: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; all `UploadProtocolGuardTest` tests pass and the full suite stays green.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt app/src/test/java/com/winlator/star/store/UploadProtocolGuardTest.java
git commit -m "fix(cloud): upload a batch sequentially to match the working reference"
git push origin fix/steam-cloud-upload-protocol
```

---

### Task 4: Verify CI green on the branch head

**Files:**
- No source changes.

**Interfaces:**
- Consumes: the commits from Tasks 1-3.
- Produces: a green CI confirmation on the branch head, consumed by the merge/release decision.

- [ ] **Step 1: Confirm CI is green on the branch head**

Run:
```powershell
gh run list --repo i0trost01/BannerlatorFork --workflow fork-ci.yml --branch fix/steam-cloud-upload-protocol --limit 4 --json databaseId,headSha,status,conclusion
```
Expected: the newest run(s) for the branch head show `"conclusion":"success"`. If red, read the failing
step and do not proceed until green.

- [ ] **Step 2: Record the on-device checklist in the SDD ledger**

Append to `.superpowers/sdd/2026-09-27-steam-cloud-upload-protocol.md/progress.md` (create if absent):

```
On-device checklist (user):
1. SteamLite game -> exit -> manual push from the Save Manager: files ACTUALLY appear in Steam Cloud
   (sha/timestamp change) and the message is a real success.
2. BH_STEAM_CLOUD shows no "file_committed=false" / "did NOT commit" lines.
3. Regression: download unchanged; a genuinely empty manifest still shows the no-retention message.
```

- [ ] **Step 3: Ledger (no commit)** — `.superpowers/` is gitignored; skip.

---

## Self-Review

**1. Spec coverage:** Change 1 (`canEncrypt=false`) → Task 1 Step 3; Change 2 (commit result,
JavaSteam) → Task 1 Step 4; Change 3 (Rust zero-block) → Task 2; Change 4 (sequential) → Task 3;
CI verify → Task 4. The out-of-scope items (storage model, `client_id`, SHA-skip, download) are
untouched. The existing post-upload verification is preserved (not modified).

**2. Placeholder scan:** No TBD/TODO. Every code block is complete and paste-ready.

**3. Type consistency:** `canEncrypt` (named arg of `beginFileUpload`), `committed`
(`CompletableFuture<Boolean>` → `Boolean`), `nativeCloudCommitFileUpload(...)` returning `Boolean`,
`toUpload: List<Pair<File, String>>`, `uploadedShas: ConcurrentHashMap<String, ByteArray>`,
`sanitizeRelative`, `SteamCloudBackend.sha1` — used identically across tasks, the tests, and the prose.
`FUTURE_TIMEOUT_SEC` and `Log`/`TAG` already exist in `SteamCloudBackend.kt`.
