# Daijisho integration (Bannerlator fork)

Bannerlator's **Export all Steam** writes, next to each exported `.desktop`:

- `<Game>.steam` - bare numeric app id (ES-DE convention).
- `<Game>.steamappid` - `[steamappid] <id>` (Daijisho's stock Steam platform reads this as a
  **tag file**, not a bare id).
- `<Game>.png` - the app's cover art.

Point Daijisho's Steam platform **ROMs folder** at the export folder (default
`/storage/emulated/0/Winlator/Shortcuts`, or whatever `Settings -> shortcut export path` is set to)
so it discovers the `.steamappid` files.

The app id is resolved at export time in this order: the `steamAppId` shortcut extra, then the
installed-games DB (matched on the game's install dir / `steam_games/<folder>` name), then a
`steam_appid.txt` in the game directory. If nothing resolves, no `.steamappid` is written for that
game (the `.desktop` and `.png` still are).

## Why the stock "GameNative" player cannot launch this fork

Daijisho's bundled **GameNative** player hard-codes the real GameNative package:

```
-n app.gamenative/.MainActivity
```

The Bannerlator fork is a different package (`com.winlator.banner.fork`) whose game activity is
`com.winlator.star.XServerDisplayActivity`, so the stock player's intent never reaches it. Clone/edit
that player to this command:

```
-a app.gamenative.LAUNCH_GAME -n com.winlator.banner.fork/com.winlator.star.XServerDisplayActivity --ei app_id {tags.steamappid} --es game_source STEAM
```

`{tags.steamappid}` is resolved by Daijisho from the `<Game>.steamappid` tag file. The fork resolves
that app id back to the matching Steam shortcut and launches it (only Steam is supported by this fork).

## Fallback for games with no resolvable app id

If a game has no app id (no `.steamappid` written), the `.desktop` is still exported. Launch it
directly by absolute path with a custom player:

```
-n com.winlator.banner.fork/com.winlator.star.XServerDisplayActivity -e shortcut_path {file.path}
```

`shortcut_path` expects the exported `.desktop` **absolute path** (Daijisho's `{file.path}` token),
not a `content://` URI. This route works for every game regardless of app id.
