# Auto-Upload to Cloud on Game Exit — Design

**Date:** 2026-09-26
**Status:** Approved (design), pending implementation
**Issue:** Enabling "Steam games: auto-upload to cloud on exit" in the Save Manager does not
auto-upload when a game closes. The user must open the Save Manager and tap the "sync needed" alert
manually.

## Problem Statement

The user enables the Save Manager toggle **"Steam games: auto-upload to cloud on exit"**. It persists
`auto_upload_steam_on_exit = true` in `SharedPreferences("save_manager_prefs")`. On closing a game the
expected behavior is an automatic cloud upload of that game's saves. Instead, no upload happens, and
the Save Manager later shows a "N game(s) need syncing" banner that the user must tap to sync.

## Root Cause (confirmed by source inspection)

The auto-upload path **is wired** to the game-exit hook. `XServerDisplayActivity.exit()` runs a
worker thread that, for a genuine Steam shortcut, conditionally calls
`autoUploadSteamSavesBlocking()`:

```java
// XServerDisplayActivity.java:5522-5531 (current)
if (isGenuineSteamShortcut()) {
    if (savePrefs.getBoolean("auto_collect_steam_on_exit", true)) autoCollectSteamSavesBlocking();
    boolean cloudDisclaimerOk = getSharedPreferences("steam_prefs", MODE_PRIVATE)
            .getBoolean("cloud_saves_disclaimer_accepted", false);
    if (cloudDisclaimerOk && savePrefs.getBoolean("auto_upload_steam_on_exit", true))
        autoUploadSteamSavesBlocking();
}
```

There is a **second, hidden gate**: `cloud_saves_disclaimer_accepted`, read from
`SharedPreferences("steam_prefs")`, defaulting to `false`. It is written in exactly one place —
`SteamGameDetailActivity.kt:1335-1338` (`setCloudDisclaimerAccepted()`), reachable only by accepting
the third-party-cloud disclaimer from a **game detail page** (`SteamGameDetailActivity.kt:394-411`,
`:1360-1368`). The Save Manager's toggle never sets or checks it.

Consequently a user who enables the auto-upload toggle in the Save Manager, but never accepted the
disclaimer on a game detail page, gets `cloudDisclaimerOk == false` and the upload is **silently
skipped**. The unconditional local Collect still runs, which re-stamps the per-game record as
`LOCAL_AHEAD` (`SaveSyncStore.kt`), which is what surfaces the Save Manager banner
(`SteamSaveManagerActivity.kt:339`, `:478-524`, `:1852`).

The manual path works because it has no disclaimer gate: `SteamSaveManagerActivity.syncOne()`
(`:311-312`) calls `SteamCloudSaveManager.syncToCloud(context, appId, installDir, cb)` directly.

## Decision

**Treat the Save Manager auto-upload toggle as the user's consent for the exit auto-upload path.**

Enabling "auto-upload to cloud on exit" is already an explicit opt-in; ANDing it with a flag the user
cannot set from that screen makes the toggle a lie. The exit path should proceed whenever the toggle
is ON.

The `cloud_saves_disclaimer_accepted` flag remains in force for the **game detail page** cloud
actions (`SteamGameDetailActivity`), which present their own disclaimer dialog. This design does not
change that path.

### Rejected alternatives

- **Prompt the disclaimer when the toggle is switched ON.** More correct consent UX, but more UI work
  and the flag could still remain unset if the user declined while the toggle stayed ON. The toggle
  itself is sufficient consent for this fork's personal use.
- **Only prompt on the first exit.** Introduces a modal at a fragile teardown moment; rejected.

## Scope

**In scope:**
- Remove the `cloud_saves_disclaimer_accepted` condition from the **exit auto-upload** decision in
  `XServerDisplayActivity.exit()`, leaving the `auto_upload_steam_on_exit` toggle as the sole gate.
- Auto-upload **only the game that just exited** (already the behavior of
  `autoUploadSteamSavesBlocking()` — it resolves the current game's appId + installDir).
- Silent operation: no toast or dialog. Failures continue to surface in the Save Manager banner via
  `LOCAL_AHEAD`.

**Out of scope:**
- The game-detail-page cloud actions and their disclaimer.
- Uploading other pending games on exit.
- Any change to `auto_upload_gog_on_exit`, `auto_collect_steam_on_exit`, or
  `auto_backup_custom_on_exit` behavior.
- The Save Manager banner itself.

## Detailed Changes

### Change 1 — `XServerDisplayActivity.java` (`exit()`, ~line 5524-5531)

Replace the two-condition gate with the single toggle gate and update the comment to reflect the new
consent model.

**Before:**
```java
// Additionally push to Steam Cloud (opt-in) — ONLY once the user has accepted
// the third-party cloud disclaimer (steam_prefs). Absent flag → skip; we never
// auto-upload to a real Steam Cloud without consent. The local Collect above
// stays unconditional (independent of this cloud toggle).
boolean cloudDisclaimerOk = getSharedPreferences("steam_prefs", MODE_PRIVATE)
        .getBoolean("cloud_saves_disclaimer_accepted", false);
if (cloudDisclaimerOk && savePrefs.getBoolean("auto_upload_steam_on_exit", true))
    autoUploadSteamSavesBlocking();
```

**After:**
```java
// Additionally push to Steam Cloud (opt-in). The Save Manager toggle
// "Steam games: auto-upload to cloud on exit" (save_manager_prefs) IS the user's consent:
// enabling it on that screen is an explicit opt-in, so the exit path must honor it directly.
// (The separate steam_prefs cloud_saves_disclaimer_accepted flag still gates the game
// detail page's cloud actions, which show their own disclaimer dialog; it deliberately does
// NOT gate this exit auto-upload.) The local Collect above stays unconditional.
if (savePrefs.getBoolean("auto_upload_steam_on_exit", true))
    autoUploadSteamSavesBlocking();
```

Notes:
- `savePrefs` (`save_manager_prefs`) is already in scope at that point.
- `autoUploadSteamSavesBlocking()` (`:6530-6563`) already calls
  `SteamCloudSaveManager.syncToCloudBlocking(ctx, appId, installDir)` for the **currently exiting**
  game — no signature or plumbing change is needed. No appId/installDir resolution work is required.
- Collection runs on the exit worker thread **after** `terminateAllWineProcesses()` and the bounded
  wait (`:5488-5496`); the game has flushed, so save files are safe to read. This ordering is
  unchanged.

### Change 2 — Tests

A plain JVM JUnit4 **source-assertion guard** (Robolectric cannot boot on this module). New file
`app/src/test/java/com/winlator/star/ExitAutoUploadGateGuardTest.java`:

- Assert `exit()`'s auto-upload condition no longer references `cloud_saves_disclaimer_accepted`.
- Assert it still reads `auto_upload_steam_on_exit` and calls `autoUploadSteamSavesBlocking()`.
- Assert the game-detail-page disclaimer path still references `cloud_saves_disclaimer_accepted`
  (i.e. that flag was not globally deleted) — guards against over-removal.

## Testing

- **Automated:** the guard test above, run by CI (`:app:testStandardDebugUnitTest`). No local Android
  SDK; CI is the only execution environment.
- **On-device (user):**
  1. Ensure the Save Manager toggle "Steam games: auto-upload to cloud on exit" is ON.
  2. Launch an SD-card or internal Steam game, make a save, exit via the in-game drawer Exit.
  3. Reopen the Save Manager: the game should **not** appear in the "needs syncing" banner; the
     cloud copy should be up to date.
  4. Regression: a game whose cloud has a newer save should still download on launch as before.
  5. Confirm the game detail page's cloud actions still show the disclaimer on first use.

## Risks

- **Uploading without a disclaimer the user might expect.** Mitigated by the fact that the toggle is
  itself the opt-in, and it defaults to `true`. For a personal fork this is acceptable; the spec calls
  it out explicitly.
- **Exit-time network op.** Already bounded (~15s) inside `syncToCloudBlocking`, already runs on the
  `BH-ExitSaveBackup` worker with the shutdown overlay up. No change to that machinery.
- **Silent failure.** Unchanged — a failed upload leaves state `LOCAL_AHEAD`, which the banner shows.
