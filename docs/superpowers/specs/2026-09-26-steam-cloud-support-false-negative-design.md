# Steam Cloud "doesn't support" False Negative — Design

**Date:** 2026-09-26
**Status:** Approved (design), pending implementation
**Issue:** Uploading a save for *Death's Gambit: Afterlife* reports
"This game doesn't support Steam Cloud — your saves are backed up locally in the Library." The game
demonstrably **does** support Steam Cloud — the save was downloaded from it moments earlier. Other
games upload fine.

## Problem Statement

The Save Manager / detail page / exit path all refuse to upload for some games, reporting no cloud
support, while the download path for the same game works. The contradiction is structural: the upload
direction consults a metadata-derived "cloud support" verdict; the download direction consults the
live cloud itself.

## Root Cause (confirmed by source inspection)

`SteamCloudSaveManager` has two directions that disagree about the same game:

**Download** — `downloadSaves` (`SteamCloudSaveManager.kt:85-143`) calls `requireCloud()` then
`steamCloud.listFiles(appId)` and downloads. It **never** consults `hasCloudSupport`,
`hasCloudSupportCached`, or `isMarkedNoSteamCloud`. It works purely on the live cloud manifest, so a
game with real cloud files always downloads.

**Upload** — `uploadSaves` (`:158-305`) hits three gates download never hits, the reported one being:

```kotlin
// SteamCloudSaveManager.kt:190-194
val support: Boolean? = hasCloudSupport(ctx, appId)
if (support == false) {
    cb.onError(NO_CLOUD_MESSAGE)   // "This game doesn't support Steam Cloud — ..."
    return@Thread
}
```

`hasCloudSupport` (`:345-370`) derives the verdict from PICS product-info:

```kotlin
// SteamCloudSaveManager.kt:376-381
private fun hasUsableSaveFiles(appKeyValues: KeyValue): Boolean {
    val saveFiles = appKeyValues.get("ufs").get("savefiles").children
    return saveFiles.any { entry ->
        !entry.get("root").value.isNullOrBlank() || !entry.get("pattern").value.isNullOrBlank()
    }
}
```

and treats a non-empty-but-partial metadata response as a **definitive** `false`:

```kotlin
// SteamCloudSaveManager.kt:357-364
if (appKeyValues == null || appKeyValues.children.isEmpty()) {
    null                       // genuinely unknown
} else {
    val supported = hasUsableSaveFiles(appKeyValues)
    cloudSupportCache[appId] = supported
    supported                  // <- false is cached and persisted
}
```

**Why Death's Gambit specifically fails.** Steam declares cloud config in two shapes, both under
`ufs`:
- **Legacy UFS** — `ufs/savefiles/<n>/{root,path,pattern}`. This is the only shape the heuristic
  reads.
- **Auto-Cloud** — configured from Root + Subdirectory + Pattern in the Steamworks backend; the
  `ufs`/`savefiles` entry may carry a `root` and `path`/`addpath` but no `pattern`, or express the
  config through `rootoverrides`, or normalize `path` to `"."`. (Reference: Steamworks Auto-Cloud
  Root Paths; GameNative PRs #1157, #1297 fix exactly these shape mismatches for real titles.)

Death's Gambit: Afterlife stores saves in `AppData\Local\deathsgambit397` — i.e. Auto-Cloud with Root
`WinAppDataLocal`. Its `ufs` block does not present the `savefiles` entry shape the heuristic requires,
so `hasUsableSaveFiles` returns `false` → `hasCloudSupport` returns `false` → upload blocked, while
download (no gate) succeeds.

**Why it is sticky.** The `false` verdict is persisted two ways:
- `SteamPrefs.setCloudSupportCached` writes `steam_prefs["cloud_support_<appId>"] = false`
  (`SteamPrefs.kt:258,269-272`), read by `hasCloudSupportCached` (`SteamCloudSaveManager.kt:498-503`)
  and used by the exit path's `syncToCloudBlocking` short-circuit (`:515-518`).
- `SaveSyncStore.markNoSteamCloud` writes `noSteamCloud: true` into
  `Bannerlator/SteamCloudSaves/_status.json` (`SaveSyncStore.kt:242-250`), read at
  `SteamCloudSaveManager.kt:178` (upload short-circuit) and `:348` (forces support=false).

Once mis-classified, the game stays broken across app restarts.

## Decisions

1. **Trust evidence over heuristic.** A metadata-derived `false` must not, by itself, block an upload.
   The upload path checks the **live cloud manifest** (a `listFiles` — the same cheap call download
   makes) and proceeds when it is non-empty, because a non-empty manifest is direct proof the game has
   a cloud store.
2. **Make the heuristic accurate for Auto-Cloud/UFS variants.** Broaden `hasUsableSaveFiles` so the
   verdict itself is more correct, rather than only bypassing it.
3. **Do not persist a negative we cannot prove.** Only positive evidence of absence yields `false`; an
   unrecognized shape yields `null` (unknown), which is never cached — the existing `null` path.
4. **Invalidate the stale cache.** A cache epoch makes previously-persisted wrong verdicts
   (`cloud_support_<appId>`) ignored/refreshed, so an already-mis-marked game is fixed without the user
   clearing app data.
5. **Keep the honest no-retention message.** The post-upload empty-manifest check
   (`:290-293` → `markNoSteamCloud` → `NO_RETENTION_MESSAGE`) still catches games that genuinely keep
   nothing (e.g. FlatOut 2). Its wording ("doesn't **keep**") is distinct and correct.

### Rejected alternatives

- **Only broaden the heuristic** (no manifest evidence): still fallible for the next unrecognized
  shape; the user would hit the same wall. The manifest check is the robust half.
- **Fully remove the gate and always open a batch:** loses the honest pre-check and does needless work
  for genuinely cloud-less titles.

## Scope

**In scope:**
- `SteamCloudSaveManager.kt`: `uploadSaves` gate (add the manifest-evidence path), `hasCloudSupport`
  (only `false` on proven absence), `hasUsableSaveFiles` (accept Auto-Cloud/`rootoverride` shapes and
  normalize `path` `.`/`/`), `syncToCloudBlocking` (do not short-circuit on a stale `false` when a live
  manifest is non-empty).
- `SteamPrefs.kt`: a `cloud_support_epoch` key; `getCloudSupportCached` ignores entries from an older
  epoch.
- `SaveSyncStore.kt`: expose a way to clear/ignore the `noSteamCloud` mark when a live manifest is
  non-empty (adjust `isMarkedNoSteamCloud` usage or add a clear).
- Tests: plain JVM source-assertion guards.

**Out of scope:**
- The download path (already correct — no gate).
- `SteamCloudSavePaths` root-token translation (file placement, unrelated to the verdict).
- UI wording changes.
- Rewriting the sync engine or the PICS fetch layer.

## Detailed Changes

### Change 1 — `hasUsableSaveFiles` accepts Auto-Cloud / variant shapes

Broaden the test so an entry counts as usable if **any** of: non-blank `root`, non-blank `pattern`,
non-blank `path`/`addpath`, or the `ufs` block itself declares `quota`/`maxnumfiles`. Normalize a
`path` of `"."` or `"/"` to empty (Steam's "root of this type, no subdir") so it is not mistaken for
content — matching GameNative PR #1297.

### Change 2 — `hasCloudSupport` returns `false` only on proven absence

- `null` when PICS returned nothing, or when the `ufs` block is **absent/unrecognized** (cannot prove
  absence).
- `false` only when a `ufs` block is **present** and yields no usable savefiles entry **and** shows no
  Auto-Cloud markers (result of the broadened test).
- Unknown (`null`) is never cached (unchanged).

### Change 3 — `uploadSaves` gains a manifest-evidence path

Before opening a batch, when `support == false`, call `steamCloud.listFiles(appId)`:
- Non-empty → proceed with the upload (evidence overrides the heuristic).
- Empty → emit `NO_CLOUD_MESSAGE` and return (the honest local-only posture).

`support == null` continues to fall through to the post-upload emptiness check, unchanged.

### Change 4 — `syncToCloudBlocking` does not short-circuit on a stale negative

When `hasCloudSupportCached == false`, do not immediately do collect-only; first check the live
manifest. Non-empty → run the normal `syncToCloud`. Empty → keep the current collect-only summary.

### Change 5 — Cache epoch in `SteamPrefs`

Add `K_CLOUD_SUPPORT_EPOCH = "cloud_support_epoch"` and a constant `CLOUD_SUPPORT_EPOCH = 2`.
`getCloudSupportCached` returns `null` when the stored epoch is below the current constant (stale
verdicts ignored); `setCloudSupportCached` stamps the current epoch. This refreshes every
previously-cached verdict once.

### Change 6 — `noSteamCloud` mark is not "no support"

The mark means "uploads were not retained", not "no cloud store". Where it forces `support=false`
(`:348`) or short-circuits an upload (`:178`), allow the live-manifest evidence to override, so a
mis-marked retaining game recovers. Add `SaveSyncStore.clearNoSteamCloud(ctx, appId)` for the
manifest-non-empty case.

## Testing

- **Automated:** plain JVM JUnit4 source-assertion guards (Robolectric cannot boot this module), run by
  CI (`:app:testStandardDebugUnitTest`). Guards assert: the upload path no longer hard-blocks on
  `support == false` without a manifest check; `hasUsableSaveFiles` accepts the broadened shapes; the
  epoch key exists and is consulted.
- **On-device:**
  1. Death's Gambit: Save Manager row upload → succeeds; the "doesn't support Steam Cloud" error is
     gone; the game appears synced.
  2. A genuinely cloud-less title still shows the honest local-only message.
  3. Regression: FlatOut 2 (or any no-retention title) still shows the "doesn't keep" message.
  4. Regression: download still works for Death's Gambit.

## Risks

- **Opening a batch for a game that then retains nothing.** Mitigated by the post-upload
  empty-manifest check, which already handles this honestly and marks the game.
- **Broader heuristic admitting a false positive.** A false positive now merely attempts an upload that
  the post-check may reject — no data loss; the local Library copy is unaffected.
- **Epoch bump re-queries PICS once per game.** One extra PICS read per game after upgrade; bounded and
  self-limiting.
- **`noSteamCloud` unmarking.** A genuinely no-retention game could re-attempt one upload after the
  epoch change, then be re-marked. Bounded, honest.
