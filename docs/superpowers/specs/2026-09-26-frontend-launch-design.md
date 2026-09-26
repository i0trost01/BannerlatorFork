# Front-End Launcher Export — Design

**Date:** 2026-09-26
**Status:** Approved (design), pending implementation
**Issue:** Tapping a game shortcut exported to a front end (Daijisho / Beacon / ES-DE) opens
BannerlatorFork's games list instead of launching the game. The WinNative fork launches the game
correctly from the same front end.

## Problem Statement

The user configures their front end to launch BannerlatorFork with the WinNative-style player
argument:

```
-n com.winnative.cmod.fork/com.winlator.cmod.app.shell.UnifiedActivity -a android.intent.action.VIEW -d {file.uri}
```

(adapted to BannerlatorFork's package). Tapping an exported shortcut does not start the game; it just
opens BannerlatorFork's default screen (the games list).

## Root Cause (confirmed by source inspection)

The **parsing and forwarding logic is already present** in BannerlatorFork, ported from WinNative. In
`MainActivity.kt`:

- `maybeForwardFrontendLaunch()` (`:316-370`) runs in `onCreate` (`:194`) and `onNewIntent`
  (`:545-555`).
- It already handles the `-d {file.uri}` route: `resolveIncomingDesktopPath(src)` (`:358`, `:446-459`)
  → `materializeDesktop` (`:461-470`) → `materializeDesktopUri` (`:472-494`), which handles both
  `file://` and `content://` URIs, checks the content looks like a `.desktop` shortcut
  (`looksLikeDesktopFile`, `:496-502`), and forwards to `XServerDisplayActivity` with
  `shortcut_path` + `container_id` (`:362-367`).

**The blocker is the manifest.** `MainActivity` declares only an `intent-filter` of `MAIN`/`LAUNCHER`
(`AndroidManifest.xml:96-99`) — no `VIEW` action and no `<data>` element. `XServerDisplayActivity`
declares `app.gamenative.LAUNCH_GAME` and a `gamenative://run` VIEW filter, but **no `file`/`content`
data scheme** (`AndroidManifest.xml:118-127`).

Therefore an implicit `-a android.intent.action.VIEW -d {file.uri}` matches **no activity** in
BannerlatorFork. The front end falls back to a plain launcher start (`MAIN`/`LAUNCHER`), which opens
`MainActivity`'s default route — the games list. WinNative does not have this problem because its
`XServerDisplayActivity` declares `VIEW` + `file`/`content` + `application/x-desktop` +
`.*\.desktop` filters (`WinNative AndroidManifest.xml:118-132`).

### WinNative's filter block (reference)

```xml
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="file" />
    <data android:scheme="content" />
    <data android:mimeType="application/x-desktop" />
</intent-filter>
<intent-filter>
    <action android:name="android.intent.action.VIEW" />
    <category android:name="android.intent.category.DEFAULT" />
    <data android:scheme="file" />
    <data android:scheme="content" />
    <data android:host="*" />
    <data android:pathPattern=".*\\.desktop" />
</intent-filter>
```

WinNative runs this on `XServerDisplayActivity` and also routes through its launcher activity
`UnifiedActivity`. BannerlatorFork's launcher activity is `MainActivity`, which already contains the
forwarding logic.

## Decision

**Add the `VIEW` + data intent-filter to `MainActivity`**, so an implicit `-a VIEW -d {file.uri}`
resolves to it; `MainActivity.maybeForwardFrontendLaunch()` then forwards to `XServerDisplayActivity`
exactly as WinNative's `UnifiedActivity` does.

Keep **both** launch routes working:
- The URI route (`-d {file.uri}`) — new, via the added filter.
- The existing path-extra route (`-e shortcut_path {file.path}`) — unchanged; `shortcut_path` is read
  at `:323` before any URI resolution and still forwards.

### Rejected alternatives

- **Put the filter on `XServerDisplayActivity` directly.** WinNative does this, but BannerlatorFork's
  front-end parsing lives in `MainActivity`; the filter there reuses it with no new code. `MainActivity`
  is the launcher, so it is also the natural catch-all. (Direct targeting of
  `XServerDisplayActivity` already partly works because it reads `intent.getData()` at
  `XServerDisplayActivity.java:3207`/`:3251`, but `MainActivity` is the selected route.)
- **URI-only, drop the path extra.** Would break existing user configs and the shipped docs.

## Scope

**In scope:**
- Add a `VIEW` intent-filter with `file`/`content` schemes and desktop file matching to `MainActivity`
  in `AndroidManifest.xml`.
- Forward `shortcut_name` and `shortcut_uuid` extras in `MainActivity.maybeForwardFrontendLaunch()`
  for WinNative parity (the session activity reads `shortcut_uuid`).
- Update the front-end docs to document the now-working `-d {file.uri}` form against BannerlatorFork's
  component name.
- A source-assertion guard test for the manifest filter + the forward extras.

**Out of scope:**
- Changing the exported `.desktop` file format or the `.steam`/`.steamappid` companion format.
- The pinned home-screen shortcut (`ShortcutsScreen.addToHomeScreen`).
- WinNative's other scheme handlers (`gamenative`, `winnative`) beyond the existing
  `gamenative://run`.

## Detailed Changes

### Change 1 — `AndroidManifest.xml`, `MainActivity` activity (lines 90-100)

Add two intent-filters after the existing `MAIN`/`LAUNCHER` filter, adapted from WinNative. Keep
`exported="true"`.

```xml
<activity
    android:name="com.winlator.star.MainActivity"
    android:theme="@style/Theme.Bannerlator.Splash"
    android:screenOrientation="sensor"
    android:exported="true"
    android:configChanges="keyboard|keyboardHidden|orientation|screenSize|screenLayout|smallestScreenSize|density|navigation">
    <intent-filter>
        <action android:name="android.intent.action.MAIN"/>
        <category android:name="android.intent.category.LAUNCHER"/>
    </intent-filter>
    <!-- Front-end launch (Daijisho/Beacon/ES-DE): an implicit VIEW of an exported .desktop URI
         (e.g. `am start -a android.intent.action.VIEW -d {file.uri}`). MainActivity parses it in
         maybeForwardFrontendLaunch() and forwards to XServerDisplayActivity — the same role
         WinNative's UnifiedActivity plays. Without this filter the intent matches no activity and
         the front end falls back to a plain launch, opening the games list. -->
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="file"/>
        <data android:scheme="content"/>
        <data android:mimeType="application/x-desktop"/>
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.VIEW"/>
        <category android:name="android.intent.category.DEFAULT"/>
        <data android:scheme="file"/>
        <data android:scheme="content"/>
        <data android:host="*"/>
        <data android:pathPattern=".*\\.desktop"/>
    </intent-filter>
</activity>
```

### Change 2 — `MainActivity.kt`, `maybeForwardFrontendLaunch()` forward (lines 362-367)

Add the two extras WinNative sets (`UnifiedActivityStartup.kt:457-469`), so the session activity gets
the same identity fields.

```kotlin
startActivity(Intent(this, XServerDisplayActivity::class.java).apply {
    setAction(Intent.ACTION_VIEW)
    putExtra("shortcut_path", shortcutPath)
    putExtra("container_id", containerId)
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
})
```

Becomes: resolve the `Shortcut` when possible and add `shortcut_name` + `shortcut_uuid`. Because
`shortcutPath` is a string here, obtain the shortcut by path:

```kotlin
val forwarded = runCatching {
    containerManager.reloadContainers()
    containerManager.loadShortcuts().firstOrNull { it.file.absolutePath == shortcutPath }
}.getOrNull()
startActivity(Intent(this, XServerDisplayActivity::class.java).apply {
    setAction(Intent.ACTION_VIEW)
    putExtra("shortcut_path", shortcutPath)
    putExtra("container_id", containerId)
    forwarded?.let {
        putExtra("shortcut_name", it.name)
        putExtra("shortcut_uuid", it.getExtra("uuid"))
    }
    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
})
```

Guard: the extras are additive; a null `forwarded` must not change the existing behavior. The
session activity's `onCreate` reads `container_id` and `shortcut_path` regardless
(`XServerDisplayActivity.java:2494`, `:2535`, `:2540`).

### Change 3 — Docs

- `docs/daijisho.md`: document the Daijisho player argument using
  `com.winlator.banner.fork/com.winlator.star.MainActivity` with
  `-a android.intent.action.VIEW -d {file.uri}`, noting it now launches the game directly.
- `marcescence-frontends.md`: add the equivalent Beacon/ES-DE URI form alongside the existing
  `-e shortcut_path {file.path}` form (which still works).

### Change 4 — Tests

A plain JVM JUnit4 **source-assertion guard** (Robolectric cannot boot on this module). New file
`app/src/test/java/com/winlator/star/FrontendLaunchGuardTest.java`:

- Assert `AndroidManifest.xml`'s `MainActivity` block contains a `VIEW` action with `file` and
  `content` schemes (read the manifest text, slice the `MainActivity` `<activity>` block, assert
  substrings).
- Assert `MainActivity.kt`'s forward block still sets `shortcut_path` and `container_id` and now also
  `shortcut_name` and `shortcut_uuid`.
- Assert `MainActivity.kt` still has the URI route (`resolveIncomingDesktopPath`) and the path route
  (`getStringExtra("shortcut_path")`) so neither regresses.

## Testing

- **Automated:** the guard test above, run by CI (`:app:testStandardDebugUnitTest`).
- **On-device (user):**
  1. Export a game to the front end (Daijisho/Beacon).
  2. Configure the player argument to
     `am start -n com.winlator.banner.fork/com.winlator.star.MainActivity -a android.intent.action.VIEW -d {file.uri}`.
  3. Tap the exported shortcut → the game must **launch directly**, not open the games list.
  4. Regression: a front end configured with the older
     `-e shortcut_path {file.path}` form still launches the game.
  5. Regression: normal app launch still shows the games list.

## Risks

- **`MainActivity` is `MAIN`/`LAUNCHER` with a splash theme.** Receiving a VIEW intent and finishing
  immediately (after forwarding) means a brief splash flash. Acceptable and identical to WinNative's
  `UnifiedActivity` behavior. The forward uses `FLAG_ACTIVITY_NEW_TASK`.
- **`XServerDisplayActivity` is `singleTask`.** If a session is already running, the front-end launch
  reaches `MainActivity`, which forwards; the `singleTask` session activity receives it via
  `onNewIntent` (`XServerDisplayActivity.java:7152`, target-switch at `:7182`). Behavior is no worse
  than launching a shortcut from within the app.
- **URI must look like a `.desktop`.** `looksLikeDesktopFile` (`:496-502`) rejects non-desktop URIs, so
  a stray VIEW intent for some other file type will fall through to a plain start. This is correct.
- **MIME filter breadth.** `application/x-desktop` may not be what a given front end sets; the
  `pathPattern` filter (`.desktop`) is the safety net and covers the actual export format.
