# Future iterations

Ideas/cleanups for the fork that are deliberately deferred. Not commitments.

> **DONE:** the `ludashi`/`pubg` product flavors were removed and `_build.yml` now
> builds `standard` only (CI cuts one APK); `release.yml`'s `update.json`/assets were
> trimmed to match. Completed in commit "chore: drop ludashi/pubg flavors; build standard only".

## ~~Remove the unused product flavors — keep only `standard`~~ (DONE)

**Context:** this personal fork only needs the `standard` flavor (`com.winlator.banner.fork`).
The `ludashi` (`com.ludashi.benchmark`) and `pubg` (`com.tencent.ig`) flavors exist for upstream's
benchmark/PUBG purposes and are dead weight here.

**Why:** `_build.yml` builds a matrix of all three flavors, so every release/fork-ci build compiles two
APKs nobody uses ≈ 3× the CI time (only the `standard` artifact is published by `fork-release.yml`).
Recorded as the deferred minor "build job runs full 3-flavor matrix but publishes only standard".

**Sketch of the change (not done yet):**
- `app/build.gradle`: delete the `ludashi` and `pubg` `productFlavors` blocks (and the
  `brandedVersionCode` logic + `playUnreachableVersionCodeBase` if nothing else uses them).
  Keep `standard` (`com.winlator.banner.fork`, label `Bannerlator Fork`).
- `.github/workflows/_build.yml`: drop the `ludashi` / `pubg` matrix entries.
- `.github/workflows/build-artifacts.yml`, `release.yml`: adjust artifact names/downloads accordingly.
- `.github/workflows/fork-release.yml`: unchanged (it already downloads only `standard`).

**Caveat:** `_build.yml` and `release.yml` are upstream files; editing them makes future
`git merge upstream/main` conflict-prone. A fork-only release workflow that calls a trimmed build (or
passing a flavor-scoping input) avoids touching the shared files.

## Other deferred minors (from the SDD ledger)
- `writeBoxArtPng` copies a custom cover file verbatim to `<base>.png`; if the chosen cover is
  JPEG/WebP the extension lies. Re-encode via `BitmapFactory` + `FileUtils.saveBitmapToFile`.
- `fork-release.yml` uses `secrets: inherit`; tighten to only what it needs (currently none).
- Release workflow does not verify the APK cert SHA-256 / versionName before publishing (the plan's
  original spec asked for it; `_build.yml` signs with the committed testkey, so risk is low).
- Focus ring uses a 6dp corner radius while host widgets use 8–12dp (cosmetic).
- `requestFocus()` on drawer open is wrapped in `runCatching` with no retry; a first-open attach race
  would silently leave no focus.
- Export-all runs synchronously on the main thread; a very large library could jank/ANR.
