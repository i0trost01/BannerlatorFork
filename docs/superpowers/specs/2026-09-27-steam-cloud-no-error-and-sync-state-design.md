# Steam Cloud Upload: No-Error Semantics + Stuck LOCAL_AHEAD — Design

**Date:** 2026-09-27
**Status:** Approved (design), pending implementation
**Issues:**
1. If nothing gets uploaded (everything already in the cloud / nothing changed), the app should NOT
   error out.
2. The Save Manager keeps showing **"Local is ahead"** even after a successful upload.

## Evidence (device state, Cuphead app 268910)

`_status.json`:
```
268910 (Cuphead): lastUploadAt=1790492119352 (06:55 UTC), lastDownloadAt=1790485766384,
                  libraryFileCount=8, cloudFileCount=3
```

Library tree `Bannerlator/SteamCloudSaves/268910/`:
```
%WinAppDataRoaming%/Cuphead/slot_0.sav        34255 B  mtime 1790547366 (NEWEST)
%WinAppDataRoaming%/Cuphead/slot_1.sav        29575 B  mtime 1780961961
%WinAppDataRoaming%/Cuphead/slot_2.sav        29575 B  mtime 1780961961
%WinAppDataRoaming%/Cuphead/steam_autocloud.vdf  52 B
%WinAppDataLocalLow%/Studio MDHR/Cuphead/output_log.txt 3968 B  mtime 1790547366
%WinAppDataRoaming%Cuphead/slot_0.sav         34013 B  mtime 1790467370   <- MALFORMED (no '/')
%WinAppDataRoaming%Cuphead/slot_1.sav         29575 B
%WinAppDataRoaming%Cuphead/slot_2.sav         29575 B
```

Upload log (repeatable, twice): all 6 batch files `→ committed`, `allOk=true` — the upload itself
works. Steam's remote storage holds exactly 3 files (`slot_0` updated; `slot_1/2` unchanged).

## Root Causes

### A. LOCAL_AHEAD is computed from raw mtimes vs the last sync, and sticks

`SaveSyncStore.computeState` (`:287`):
```kotlin
if ((hasLibrary || containerFileCount > 0) && newestLocalMtime > lastSync) return SaveState.LOCAL_AHEAD
```
where `newestLocalMtime = max(libraryNewestMtime, containerNewestMtime)` and
`lastSync = max(lastUploadAt, lastDownloadAt)`.

For Cuphead the newest local file is `slot_0.sav` at `1790547366`, while `lastUploadAt` is
`1790492119` — so the pill reports LOCAL_AHEAD. Two contributing facts:
- The Library contains a **malformed duplicate tree** `%WinAppDataRoaming%Cuphead/` (root token `%WinAppDataRoaming%` immediately followed by `Cuphead` with **no `/`**). It is never a valid
  `%Root%/rest` path, is not what Steam stores, and its presence inflates `libraryFileCount` (8 vs the
  cloud's 3).
- A file's content being **already in the cloud** (deduped, `blocks=0`) does not change the local
  mtime, so an unchanged-or-already-synced save can still look "ahead" by mtime.

### B. Client-side no-ops are reported as errors

The upload decision (`SteamCloudSaveManager.uploadSaves`) errors in cases that are not real failures:
- nothing to upload is handled (`toUpload.isEmpty()` → `onDone`), which is fine, but
- "0 files reached the cloud" and "some files failed" both surface as `onError`, and a game whose
  every file is already in the cloud can still land in an error branch depending on manifest timing.

The user's rule: **if nothing gets uploaded, that must not be an error.**

## Decisions

1. **Uploads never error on client-side no-ops.** Every non-exceptional outcome — nothing to upload,
   all files already in cloud, partial upload, or none verified — reports `onDone` (a plain,
   non-alarming success/summary). Only a genuine transport/auth failure (`requireCloud()` null, a
   thrown exception, a refused batch) reports `onError`. The no-retention case stays a *message* but
   should not present as a hard failure the user must act on.
2. **LOCAL_AHEAD must reflect "un-uploaded content," not raw mtime.** A game whose local saves are
   already present in the cloud must read IN_SYNC. Concretely: when the Library's file set/hashes match
   what the cloud holds (the same comparison the upload already computes), the game is IN_SYNC
   regardless of local mtimes.
3. **Reject/clean the malformed `%Root%`-without-slash library paths.** A `%Root%` token must be
   followed by `/` (or be the whole path). Entries like `%WinAppDataRoaming%Cuphead` are invalid; they
   must not be created, and existing ones should be ignored by the state/hash computation (a targeted
   cleanup is acceptable).

### Rejected alternatives

- **Bump `lastUploadAt` on every upload unconditionally**: would mask genuine local changes made after
  the upload (a real "local ahead" the user should see). Rejected — state must be content-based.
- **Only relax the error text**: leaves the stuck pill. Rejected; both are required.
- **Delete the whole Library on any mismatch**: destructive; rejected.

## Scope

**In scope:**
- `SteamCloudSaveManager.uploadSaves`: make every client-side no-op outcome `onDone`; keep only
  transport/auth/exception as `onError`.
- `SaveSyncStore` / the status computation: compute IN_SYNC from **content** (Library vs cloud set),
  not raw mtime, so an uploaded game stops reading LOCAL_AHEAD.
- The `%Root%`-without-slash path handling in `SteamCloudSavePaths` / the enumeration: reject malformed
  library entries so they neither count nor inflate the local snapshot.
- Guard tests (plain JVM source-assertion) + an on-device verification via adb.

**Out of scope:**
- The upload protocol (canEncrypt/commit/sequential/zero-block) — shipped and working.
- Download path.
- Any storage-model rewrite.

## Detailed Changes

### Change 1 — no-op outcomes are success

`uploadSaves` decision: replace the two `onError` branches (verified==0 non-empty manifest; partial
"some files failed") with `onDone` summaries. Keep `onError` only for: `requireCloud()` null ("Not
signed in"), `batchId == 0`, and thrown exceptions. The no-retention case (`markNoSteamCloud`) may stay
an informational outcome but must not block or alarm.

### Change 2 — content-based sync state

`SaveSyncStore.computeState`: when the local Library content equals the cloud content (same path→sha
set), return IN_SYNC even if `newestLocalMtime > lastSync`. Practically: derive an "up to date" signal
from the upload's verification (or a cheap Library-vs-cloud compare) and prefer IN_SYNC over
LOCAL_AHEAD when nothing is actually newer *in content*.

### Change 3 — reject malformed library paths

In the enumeration/`toLibraryRel`/`toContainerPath` path handling, require a `%Root%` token to be
followed by `/` or end-of-string; treat `%Root%X` (no separator) as invalid → skip (and, best-effort,
have the state/hash computation ignore such entries so `libraryFileCount` reflects real saves).

## Testing

- **Automated:** JVM source-assertion guards: the no-op branches use `onDone`; the state prefers IN_SYNC
  on content match; malformed `%Root%` paths are rejected.
- **On-device (adb):** after a successful upload, Cuphead reads IN_SYNC (not LOCAL_AHEAD); a push with
  nothing to upload shows success, not an error; `_status.json`'s Cuphead entry stops reading ahead.

## Risks

- **Content comparison cost**: comparing Library to cloud on every status read could be expensive; use
  the already-computed snapshot hash + cloud manifest hash instead of re-hashing.
- **Over-relaxing errors** could hide a real failure; mitigated by keeping transport/auth/exception as
  errors.
- **Legacy malformed dirs** may persist on disk; ignoring them in computation is sufficient (a cleanup
  is optional).
