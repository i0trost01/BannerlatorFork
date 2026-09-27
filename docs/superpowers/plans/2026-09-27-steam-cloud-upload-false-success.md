# Steam Cloud Upload False Success Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `uploadSaves` report success only after verifying that each uploaded file is actually present in Steam Cloud with the local content's SHA-1 — eliminating the "Uploaded N changed" message that appears while the cloud still holds the old save.

**Architecture:** Replace the existence-only post-upload check (`isCloudManifestEmpty`) with a per-path content verification against a freshly fetched manifest. Capture each uploaded file's `(cloudPath, localSha1)` during the concurrent upload loop, then require the fresh manifest to report that same SHA for that path. Fail honestly on any unverified path; keep the empty-manifest no-retention signal.

**Tech Stack:** Kotlin (Android), JUnit4 source-assertion tests, Gradle (`:app:testStandardDebugUnitTest`), GitHub Actions CI.

**Spec:** `docs/superpowers/specs/2026-09-27-steam-cloud-upload-false-success-design.md`

## Global Constraints

- Flavor: `standard` only. Application id: `com.winlator.banner.fork`. Do not touch other flavors.
- No local Android SDK/NDK — **never** claim a build or test passes locally. CI
  (`.github/workflows/fork-ci.yml`, task `:app:testStandardDebugUnitTest`) is the only execution
  environment.
- Robolectric **cannot** boot on this module. All tests are plain JVM JUnit4 **source-assertion
  guards** (read a source file from disk, assert substrings). Follow the existing idiom in
  `app/src/test/java/com/winlator/star/store/CloudSupportHeuristicGuardTest.java`.
- Change is Kotlin in `SteamCloudSaveManager.kt`. Match the file's heavily-commented style.
- Uploads must stay **strictly additive**: `filesToDelete` stays empty; never open a batch for a wipe.
- Do NOT rework the SHA-1 incremental skip (`ffc1038b`) or the `SteamCloudBackend` commit plumbing in
  this pass. Only the success/report decision changes.
- Keep `NO_RETENTION_MESSAGE` behavior for the completely-empty-manifest case.
- Every `gh` command needs `--repo i0trost01/BannerlatorFork`.
- `rg` is not installed. Use the Grep tool or PowerShell `Select-String`.
- The local checkout may be on another branch — read the file from `origin/main` if needed
  (`git show origin/main:<path>`), and confirm the working tree is on `main` before committing.

---

### Task 1: Verify each uploaded file's SHA actually landed before reporting success

**Files:**
- Modify: `app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt` (upload loop `:277-294`; post-check `:296-314`; add a helper near `isCloudManifestEmpty` `:429`)
- Test: `app/src/test/java/com/winlator/star/store/UploadVerifyGuardTest.java` (create)

**Interfaces:**
- Consumes: existing `SteamCloudBackend.sha1(file)`, `sanitizeRelative(path)`, `listFiles(appId)`,
  `SHA1_LEN`, `isCloudManifestEmpty(sc, appId)`, `SaveSyncStore.markNoSteamCloud`, `NO_RETENTION_MESSAGE`.
- Produces: no new public symbols. The post-upload decision now depends on a per-path SHA comparison.

**Context:** Current upload loop and post-check (`SteamCloudSaveManager.kt:277-314`):

```kotlin
                val uploaded = AtomicInteger(0)
                val allOk = AtomicBoolean(true)
                runConcurrently(toUpload, "steam-cloud-ul-$appId") { (file, cloudPath) ->
                    try {
                        cb.onStatus("Uploading: ${file.name}")
                        if (steamCloud.uploadOne(appId, file, cloudPath, batchId)) {
                            uploaded.incrementAndGet()
                        } else {
                            allOk.set(false)
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Upload task failed for ${file.name}", e)
                        allOk.set(false)
                    }
                }

                steamCloud.completeBatch(appId, batchId, allOk.get())

                if (allOk.get()) {
                    // ── HONESTY GUARD 2: ...
                    val emptyAfterUpload = if (uploaded.get() > 0) isCloudManifestEmpty(steamCloud, appId) else null
                    if (emptyAfterUpload == true) {
                        SaveSyncStore.markNoSteamCloud(ctx, appId)
                        cb.onError(NO_RETENTION_MESSAGE)
                    } else {
                        cb.onDone("Uploaded ${uploaded.get()} changed, $upToDate already up-to-date")
                    }
                } else {
                    cb.onError("Uploaded ${uploaded.get()} of ${toUpload.size} changed; some files failed")
                }
```

The bug: `isCloudManifestEmpty` only asks "is the whole manifest empty?", so a game that already has a
cloud file reports success even when the commit did not persist the new content.

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/winlator/star/store/UploadVerifyGuardTest.java`:

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
 * Guard for the upload "verify what landed" fix.
 *
 * Previously uploadSaves reported success from isCloudManifestEmpty - "is the whole manifest
 * non-empty?" - so a game that already had a cloud file reported "Uploaded N changed" even when the
 * commit did not persist the new bytes. The post-upload decision must now verify each uploaded path's
 * SHA against a fresh manifest, and never claim success for an unverified path.
 */
public class UploadVerifyGuardTest {

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
        assertTrue("no opening brace after " + signature, open > start);
        int depth = 0;
        for (int i = open; i < src.length(); i++) {
            char c = src.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') { depth--; if (depth == 0) return src.substring(open, i + 1); }
        }
        throw new AssertionError("unterminated body for " + signature);
    }

    @Test
    public void postUploadDecisionVerifiesContentNotMereExistence() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("must verify per-path SHA after upload",
                b.contains("contentEquals"));
        assertTrue("must reuse the manifest sha map for verification",
                b.contains("sanitizeRelative("));
        assertTrue("must count unverified paths so success is not claimed blindly",
                b.contains("unverified"));
    }

    @Test
    public void uploadCapturesEachUploadedFilesSha() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertFalse("must not still decide success from isCloudManifestEmpty alone in the success branch",
                b.contains("val emptyAfterUpload = if (uploaded.get() > 0) isCloudManifestEmpty(steamCloud, appId) else null\n                    if (emptyAfterUpload == true) {\n                        SaveSyncStore.markNoSteamCloud(ctx, appId)   // remember → short-circuit next time\n                        cb.onError(NO_RETENTION_MESSAGE)             // onError: no false success, no lastUploadAt stamp\n                    } else {\n                        cb.onDone(\"Uploaded ${uploaded.get()} changed, $upToDate already up-to-date\")\n                    }"));
        assertTrue("upload must record the sha of each successfully uploaded file",
                b.contains("SteamCloudBackend.sha1("));
    }

    @Test
    public void emptyManifestStillMarksNoRetention() throws IOException {
        String src = readRepoFile("src", "main", "java", "com", "winlator", "star", "store", "SteamCloudSaveManager.kt");
        String b = body(src, "fun uploadSaves(");
        assertTrue("must keep the no-retention message for a completely empty manifest",
                b.contains("NO_RETENTION_MESSAGE"));
        assertTrue("must keep marking no-retention",
                b.contains("markNoSteamCloud"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork` (no local Gradle).
Expected: CI FAILS on `postUploadDecisionVerifiesContentNotMereExistence` (no `contentEquals` in
`uploadSaves`) and `uploadCapturesEachUploadedFilesSha`. Intentional TDD RED.

- [ ] **Step 3: Capture uploaded SHAs in the upload loop**

Replace the upload loop (`:277-294`) with a version that records each successfully uploaded file's
`(cloudPath, sha1)`:

```kotlin
                val uploaded = AtomicInteger(0)
                val allOk = AtomicBoolean(true)
                // What we actually uploaded and the content we expect to find remotely, for the
                // post-upload verification below. Concurrent map: the loop runs on a bounded pool.
                val uploadedShas = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()
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

                // Close the batch with the aggregate result (OK only if every file committed).
                steamCloud.completeBatch(appId, batchId, allOk.get())
```

- [ ] **Step 4: Replace the post-check with content verification**

Replace the `if (allOk.get()) { ... } else { ... }` block (`:296-314`) with:

```kotlin
                if (allOk.get()) {
                    // ── HONESTY GUARD 2 (content-verified): did the cloud actually STORE our bytes? ──
                    // A commit ack is not proof (JavaSteam's commitFileUpload boolean is dropped, and a
                    // zero-block begin "succeeds" without transferring). So we re-fetch the manifest and
                    // require EACH uploaded path to now report the local file's SHA-1. Mere
                    // non-emptiness is NOT enough — a game that already had a cloud file would otherwise
                    // report success while the cloud still held the OLD save (the reported bug).
                    //   • fetched manifest completely empty  → no-retention signature (old games):
                    //     mark + honest message.
                    //   • any uploaded path missing or sha!=local → honest failure, never a false success.
                    //   • all verified                       → success.
                    val verified: Int
                    val unverified: Int
                    if (uploaded.get() == 0) {
                        verified = 0
                        unverified = 0
                    } else {
                        val remoteShaByPath: Map<String, ByteArray>? = try {
                            val m = HashMap<String, ByteArray>()
                            for (f in steamCloud.listFiles(appId)) {
                                val key = sanitizeRelative(f.remotePath) ?: continue
                                val sha = f.sha
                                if (sha.size == SHA1_LEN) m[key] = sha
                            }
                            m
                        } catch (e: Exception) {
                            Log.w(TAG, "post-upload verification fetch failed for appId=$appId", e)
                            null // unknown → treat every uploaded path as unverified (honest failure)
                        }
                        if (remoteShaByPath == null) {
                            verified = 0
                            unverified = uploaded.get()
                        } else {
                            var ok = 0
                            var bad = 0
                            for ((key, sha) in uploadedShas) {
                                val remote = remoteShaByPath[key]
                                if (remote != null && remote.contentEquals(sha)) ok++ else bad++
                            }
                            verified = ok
                            unverified = bad
                        }
                    }

                    if (uploaded.get() > 0 && verified == 0 && unverified == uploaded.get() &&
                        runCatching { steamCloud.listFiles(appId).isEmpty() }.getOrDefault(false)) {
                        // Nothing is in the cloud at all after a committed >0 upload → no retention.
                        SaveSyncStore.markNoSteamCloud(ctx, appId)   // remember → short-circuit next time
                        cb.onError(NO_RETENTION_MESSAGE)             // onError: no false success, no lastUploadAt stamp
                    } else if (unverified > 0) {
                        // Honest failure: some bytes did not land. No onDone → no lastUploadAt stamp.
                        cb.onError("Uploaded $verified of ${uploaded.get()} changed; $unverified did not reach Steam Cloud")
                    } else {
                        cb.onDone("Uploaded ${uploaded.get()} changed, $upToDate already up-to-date")
                    }
                } else {
                    cb.onError("Uploaded ${uploaded.get()} of ${toUpload.size} changed; some files failed")
                }
```

Notes:
- `isCloudManifestEmpty` may remain defined (unused) or be removed; leaving it is acceptable, but the
  guard does not require its presence. If you remove it, ensure nothing else references it
  (`Select-String "isCloudManifestEmpty"`).
- Keep `uploadedShas` keyed by the SAME `sanitizeRelative` normalization the manifest uses.

- [ ] **Step 5: Run test to verify it passes**

Run: `gh workflow run fork-ci.yml --repo i0trost01/BannerlatorFork`
Expected: CI SUCCESS; all `UploadVerifyGuardTest` tests pass and the full suite stays green.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/winlator/star/store/SteamCloudSaveManager.kt app/src/test/java/com/winlator/star/store/UploadVerifyGuardTest.java
git commit -m "fix(cloud): verify uploaded saves actually landed before reporting success"
git push origin main
```

---

### Task 2: Verify CI and prepare the on-device checklist

**Files:**
- No source changes.

**Interfaces:**
- Consumes: the commit from Task 1.
- Produces: a green CI confirmation on the final commit sha, consumed by the release step.

- [ ] **Step 1: Confirm CI is green on the final commit**

Run:
```powershell
gh run list --repo i0trost01/BannerlatorFork --workflow fork-ci.yml --limit 4 --json databaseId,headSha,status,conclusion
```
Expected: the newest run(s) for the final commit show `"conclusion":"success"`. Do not proceed until green.

- [ ] **Step 2: Record the on-device checklist in the SDD ledger**

Append to `.superpowers/sdd/2026-09-27-steam-cloud-upload-false-success.md/progress.md` (create the
directory if absent):

```
On-device checklist (user):
1. SteamLite/real-Steam game: exit, then manual push from the Save Manager.
2. BH_STEAM_CLOUD logcat: either a verified success AND the cloud file's sha/timestamp changed, or an
   honest failure - NEVER "Uploaded N changed" with stale cloud content.
3. Regression: a genuine no-retention title still shows "doesn't keep Steam Cloud saves".
4. Regression: re-uploading an unchanged game still reports "0 changed".
```

- [ ] **Step 3: Ledger (no commit)**

`.superpowers/` is gitignored; verify `git status --porcelain` is clean and skip. No commit required.

---

## Self-Review

**1. Spec coverage:** Change 1 (capture uploaded shas) → Task 1 Step 3; Change 2 (verify against fresh
manifest + honest failure) → Task 1 Step 4; Change 3 (helper semantics folded into Step 4 via the
inline map + null-on-throw) → Task 1 Step 4. The no-retention signal is preserved. Out-of-scope items
(the SHA-skip, backend plumbing) are untouched.

**2. Placeholder scan:** No TBD/TODO. All code blocks are complete and paste-ready.

**3. Type consistency:** `uploadedShas` (`ConcurrentHashMap<String, ByteArray>`), `sanitizeRelative`,
`SteamCloudBackend.sha1`, `f.sha`, `SHA1_LEN`, `listFiles`, `verified`, `unverified`,
`NO_RETENTION_MESSAGE`, `markNoSteamCloud` — used identically across the test, the snippets, and the
prose. `runConcurrently`'s lambda destructures `(file, cloudPath)` exactly as the current code does.
