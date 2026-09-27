# Steam Cloud Partial-Upload Acceptance + Zero-Block Fix — Design

**Date:** 2026-09-27
**Status:** Approved (design), pending implementation
**Issues:**
1. Uploads now land (protocol fix confirmed working on-device), but they report an **error**.
2. A partial upload should be **acceptable** — most games have only one file that genuinely needs
   uploading; failing to upload everything is fine.

## Evidence (real device log, Cuphead app 268910)

```
CLOUD: changelist app=268910 files=3
CLOUD: upload batch app=268910 files=6 → batch ...
CLOUD: upload app=268910 34013 B → committed
cloud upload short-circuit: blocks=0 for %WinAppDataRoaming%/Cuphead/steam_autocloud.vdf — file already in cloud, committing
CLOUD: upload app=268910 52 B → FAILED
... (5× "blocks=0 ... FAILED") ...
CLOUD: upload batch app=268910 complete allOk=false acked=true
```

## Root Cause

**Two independent problems:**

### A. The zero-block fix was too strict (regression introduced by the protocol branch)

`BlSteamSession.uploadCloudFile`'s zero-block short-circuit means Steam returned **no blocks to PUT**
because it **already holds that file's content** (`blocks=0` = dedup). For such a file Steam's
`ClientCommitFileUpload` returns `file_committed=false` (there is nothing new to commit). Task 2 of the
previous plan changed this path to return that `false`, so files that are **correctly present in the
cloud** are now reported `FAILED`:

```
CLOUD: upload app=268910 52 B → FAILED
```

That `allOk=false` then feeds `completeBatch(Fail)` and the post-upload verification, producing the
user-visible error. The pre-existing code returned `true` here (as does WinNative) precisely because a
zero-block begin is success-by-dedup. **The correct behavior is to treat `blocks=0` as success.**

### B. Reporting treats any partial result as an error

`SteamCloudSaveManager.uploadSaves` currently errors on ANY unverified path (`:354-355`) or ANY failed
file (`:359-360`). The user wants partial uploads accepted: `verified > 0` should be a success
(`onDone`), with honest "X of N" wording. Only a genuinely empty cloud after a committed upload should
remain an error (the no-retention signal).

## Decisions

1. **Zero-block = success.** In `BlSteamSession.uploadCloudFile`, a zero-block begin commits and
   returns `true` (dedup means the file is already in the cloud). Keep the honest commit-result check
   for the non-zero-block path (real bytes committed).
2. **Partial upload = success.** In `uploadSaves`, when `verified > 0`, report `onDone` with
   "Uploaded X of N changed" wording (noting any that didn't land) instead of `onError`.
3. **Only a truly-empty manifest stays an error** (`markNoSteamCloud` + `NO_RETENTION_MESSAGE`), which
   is the FlatOut-2 no-retention signal.
4. **All-zero-verified with a non-empty manifest still fails honestly** — nothing at all landed and the
   cloud is known to hold *something*, so we cannot claim success.

### Rejected alternatives

- **Revert the whole protocol branch**: would restore the never-lands bug. Rejected.
- **Keep zero-block strict and only relax reporting**: the FAILED lines are wrong facts; fixing the
  facts AND the reporting is correct. Both are small.
- **`allOk=false → onDone` unconditionally**: would mask a genuine all-files-failed case. Rejected;
  the `verified > 0` gate keeps that honest.

## Scope

**In scope:**
- `BlSteamSession.kt`: zero-block short-circuit returns `true` (dedup success); keep the log line but
  reword to reflect "already in cloud".
- `SteamCloudSaveManager.kt`: the post-upload decision — `verified > 0` → `onDone` with "X of N";
  keep the empty-manifest error; keep the all-unverified-non-empty error.
- Guard tests (plain JVM source-assertion).

**Out of scope:**
- The upload protocol (canEncrypt/commit/sequential) — unchanged from `3.1.3-fork.29`.
- The `client_id` residual.
- Whether `steam_autocloud.vdf` / `output_log.txt` should be uploaded at all (Steam dedups them
  harmlessly; separate concern).

## Detailed Changes

### Change 1 — zero-block returns success

`BlSteamSession.kt` zero-block branch (~:472-484): commit `true` and return `true` (revert Task 2's
strictness). The log should say the file is already in the cloud and is treated as success.

### Change 2 — partial upload is success

`SteamCloudSaveManager.kt` post-upload decision (`:350-361`): reorder so that:
- completely-empty manifest after a committed >0 upload → `markNoSteamCloud` + `NO_RETENTION_MESSAGE`
  (error, unchanged);
- else if `verified > 0` → `cb.onDone("Uploaded $verified of ${uploaded.get()} changed" +
  (if (unverified > 0) "; $unverified already in cloud or skipped" else "") + ", $upToDate already
  up-to-date")`  (success);
- else (`verified == 0`, non-empty manifest) → `cb.onError(...)` (honest failure, unchanged);
- and the `allOk==false` outer branch: still `onError` ONLY when nothing verified; otherwise treat the
  same as partial success.

## Testing

- **Automated:** JVM source-assertion guards: zero-block returns `true`; the `verified > 0` branch uses
  `onDone`; the empty-manifest branch still marks + errors.
- **On-device (user + adb):** Cuphead (or any multi-file Auto-Cloud game) → exit/push → the log shows
  the previously-FAILED zero-block files as success, and the Save Manager shows a success message; the
  cloud content is unchanged for deduped files (correct) and updated for changed ones.

## Risks

- **Zero-block returning `true` could mask a genuine failure** where Steam returns no blocks for a
  reason other than dedup. Mitigated: zero-block is Steam's dedup signal; the post-upload verification
  still requires the path to be present in the manifest for the file to count as verified.
- **Partial-success could hide a systemic upload failure** (0 landed). Mitigated: `verified == 0` with
  a non-empty manifest stays an error.
