# Steam Cloud Upload False Success — Design

**Date:** 2026-09-27
**Status:** Approved (design), pending implementation
**Issue:** A manual "push to cloud" from the Save Manager reports a successful upload ("Uploaded N
changed…") but Steam's remote storage is NOT updated — the cloud still holds the old save content.
Occurred on a SteamLite (genuine Steam) launch, then a manual push from the Save Manager.

## Problem Statement

The upload path can report success without the new bytes landing in Steam Cloud. The success
message is not backed by any check that the uploaded content is actually present remotely.

## Root Cause (confirmed by source inspection)

`SteamCloudSaveManager.uploadSaves` decides success from two things, neither of which proves the
content landed:

1. **The commit leg is unverified.** On the JavaSteam backend (the one a SteamLite launch uses),
   `JavaSteamCloudBackend.uploadOne` (`SteamCloudBackend.kt:200-247`) derives `ok` solely from the
   block-PUT HTTP codes, then:

   ```kotlin
   sc.commitFileUpload(ok, appId, sha, cloudPath).get(FUTURE_TIMEOUT_SEC, TimeUnit.SECONDS)  // :245
   return ok
   ```

   `commitFileUpload` returns a `Boolean` (`fileCommitted`) that is **never read**. A rejected commit
   is therefore still counted as uploaded (`SteamCloudSaveManager.kt:283`), and `allOk` stays true.
   `completeBatch`'s result is likewise discarded (`:294`; the seam returns `Unit`,
   `SteamCloudBackend.kt:57`).

2. **The post-upload gate only checks existence, not content.** `isCloudManifestEmpty`
   (`SteamCloudSaveManager.kt:296-314`, `:429-436`) asks *"is the whole manifest empty?"*. A game that
   already has any cloud file (including the stale one) returns non-empty → the `else` branch fires →
   `cb.onDone("Uploaded ${uploaded.get()} changed, $upToDate already up-to-date")` at `:309-310`.

   Worse, the recent honesty-guard fixes (`b55a9850`, `4da576e2`, `8227262b`) only ever *proceed* when
   the live manifest is already non-empty (`:182-184`, `:204-206`), so on exactly those games the
   post-check is guaranteed non-empty and the success branch is guaranteed — even when nothing landed.

**Net:** "Uploaded N changed" with stale remote content. The **SHA-1 incremental skip**
(`:217-245`, introduced by `ffc1038b`) is a *separate*, older risk (it fails toward re-upload, and the
user's symptom is stale content, not "0 changed"); it is out of scope here.

## Decisions

1. **Verify what landed.** After `completeBatch`, re-fetch the manifest and confirm that **each
   uploaded path now reports the local file's SHA-1**. Report success only for verified paths;
   otherwise fail honestly. This mirrors GameNative's "verify, don't trust the ack" posture and does
   not depend on the commit ack being correct on either backend.
2. **Never a false success.** If any uploaded path is missing from the manifest or its manifest SHA
   differs from the local SHA, the result is `onError(...)`, not `onDone(...)`. An unreadable/short
   manifest sha for a path counts as **unverified → failure**.
3. **Preserve the no-retention signal.** If, after a committed upload of >0 files, the manifest is
   *completely empty*, keep the existing `markNoSteamCloud` + `NO_RETENTION_MESSAGE` behavior
   (FlatOut-2 class).
4. **Keep it focused.** Do NOT rework the SHA-1 incremental skip (`ffc1038b`) in this pass, and do not
   re-plumb the commit ack. Add tests + a follow-up note.

### Rejected alternatives

- **Only honor the commit ack** (`fileCommitted`): cheaper, but trusts Steam's ack rather than the
  stored content; a wrong ack would still yield a false success. Verification is the robust choice.
- **Both ack + content verify:** strongest, but more surface; the content check subsumes the ack for
  the reported bug. Not needed now.

## Scope

**In scope:**
- `SteamCloudSaveManager.kt`: replace the existence-only post-check in `uploadSaves` with a
  per-path content verification; add a helper (e.g. `verifyUploadedLanded`) that builds
  `remotePath → sha` from a fresh `listFiles` and compares against each uploaded file's local SHA-1.
- Map each uploaded local file to its `(cloudPath, localSha1)` (captured in the upload loop).
- Guard tests (plain JVM source-assertion).

**Out of scope:**
- The SHA-1 incremental skip (`ffc1038b`) and any local-hash-cache rework.
- Reworking `SteamCloudBackend` / `commitFileUpload` / `completeBatch` signatures or the root cause of
  the rejected commit.
- Download, path mapping, UI wording.

## Detailed Changes

### Change 1 — capture what was uploaded

In `uploadSaves`, the upload loop already iterates `toUpload` (`(file, cloudPath)`). Collect the
successful pairs' `(cloudPath, SteamCloudBackend.sha1(file))` into a
`ConcurrentHashMap<String, ByteArray>` (thread-safe, since the loop is concurrent), so the post-check
knows the expected content per path.

### Change 2 — verify against the fresh manifest

Replace the `isCloudManifestEmpty`-based branch at `:296-314` with:

- Re-fetch `steamCloud.listFiles(appId)` (already fetched for the emptiness check).
- Build `sanitizeRelative(remotePath) → sha`.
- For each uploaded `(cloudPath, sha1)`: `sanitizeRelative(cloudPath)` must be present AND its manifest
  sha must be 20 bytes AND `contentEquals(sha1)`. Any miss → `unverified++`.
- If `uploaded > 0` and the fresh manifest is **completely empty** → keep the existing
  `markNoSteamCloud` + `NO_RETENTION_MESSAGE`.
- Else if `unverified > 0` → `cb.onError("Uploaded ${verified} of ${uploaded} changed; " +
  "${unverified} did not reach Steam Cloud")` (no `onDone`, so no `lastUploadAt` stamp).
- Else → `cb.onDone("Uploaded ${uploaded} changed, $upToDate already up-to-date")`.

### Change 3 — helper

Add a private helper that, given the backend, appId, and the uploaded map, returns the number of
paths not verified present-and-equal. Keep the fetch's failure semantics: if the verification
`listFiles` throws, treat the batch as **unverified** (honest failure), never a success.

## Testing

- **Automated:** plain JVM JUnit4 source-assertion guards (Robolectric cannot boot this module), run by
  CI (`:app:testStandardDebugUnitTest`). Guards assert: the post-upload branch no longer decides on
  mere emptiness; it compares a per-path SHA; and the failure message path exists.
- **On-device:**
  1. Play a SteamLite/real-Steam game, exit, then manual push from the Save Manager.
  2. `BH_STEAM_CLOUD` log shows either a verified success AND the cloud file's sha/timestamp actually
     changed, or an honest failure — never "Uploaded N changed" with stale content.
  3. Regression: a genuine no-retention title still shows "doesn't keep Steam Cloud saves".
  4. Regression: a normal re-upload of an unchanged game still reports "0 changed".

## Risks

- **Steam's stored sha vs raw local SHA-1.** The fork already treats these as comparable at `:241`, and
  the reference (GameNative) compares the same field, so equality is the established convention. If it
  were ever untrue for a title, this fix fails **honestly** (unverified → error), not silently.
- **Extra manifest fetch.** One additional `listFiles` per upload; the emptiness check already fetched
  it, so this replaces rather than adds a call in the common path.
- **Stricter than before.** Games that previously showed a (false) success may now surface an honest
  failure until the underlying commit issue is resolved — the correct trade for trust.
