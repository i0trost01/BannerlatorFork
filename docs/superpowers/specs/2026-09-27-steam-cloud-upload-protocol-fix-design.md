# Steam Cloud Upload Protocol Fix — Design

**Date:** 2026-09-27
**Status:** Approved (design), pending implementation
**Issue:** On a SteamLite (JavaSteam backend) launch, Steam Cloud **uploads never actually store
files**. Repeated manual pushes flip between "0 of N" and "1 of N" but the cloud is never updated.
WinNative (the working reference) uploads fine.

## Problem Statement

The fork's upload reports HTTP success but Steam never retains the content. The recently-added
verification (release `3.1.3-fork.25`) surfaced this as an honest failure — it did not cause it. The
upload protocol itself diverges from WinNative's known-working call sequence in three places, all
predating the recent work.

## Root Cause

Side-by-side comparison of the upload protocol against WinNative (`WnSteamSession.kt` /
`SteamAutoCloud.kt` / `wn-steam-client` Rust) found three concrete differences that can make Steam
**reject the commit while the block PUTs return HTTP 200**:

1. **`can_encrypt = true` (JavaSteam default) is sent, and the response's `encrypt_file` is ignored.**
   `JavaSteamCloudBackend.uploadOne` (`SteamCloudBackend.kt:203-206`) calls `beginFileUpload(...)`
   with named args and omits `canEncrypt`; the vendored JavaSteam `beginFileUpload$default` sends
   `setCanEncrypt(true)`. WinNative sends no `can_encrypt` (= false). If Steam sets
   `encrypt_file=true`, the fork still PUTs plaintext; at commit Steam verifies stored content against
   `file_sha` (SHA-1 of the *raw* file), finds plaintext where it expected ciphertext, and returns
   `file_committed=false`. The fork never reads `info.encryptFile`.

2. **The commit result is discarded.** `SteamCloudBackend.kt:245-246` calls
   `commitFileUpload(ok, appId, sha, cloudPath)` and throws away the returned
   `CompletableFuture<Boolean>` (`file_committed`), returning only the block-PUT `ok`. So a rejected
   commit is counted as uploaded. WinNative reads `file_committed` and returns it.

3. **Files are uploaded concurrently inside one batch.** `SteamCloudSaveManager.kt:283` runs
   `uploadOne` (each with its own begin/PUTs/commit) on a 4-thread pool (`TRANSFER_CONCURRENCY = 4`,
   `:58`) within a single open batch. WinNative uploads strictly sequentially
   (`SteamAutoCloud.kt:1039`). Interleaved begin/commit calls on one batch are a plausible cause of the
   intermittent 0↔1 flipping.

### Ruled out (verified identical)

- raw-SHA vs hex-SHA (both send the raw 20-byte digest)
- `fileSize` vs `rawFileSize` (both send the same value — no compression declared)
- `commitFileUpload` argument order (matches the bound signature exactly)
- proto field numbers / RPC names

### Not implementable here (scoped out)

WinNative also sends a **real `client_id`** in `BeginAppUploadBatch`; the fork sends `0L`
(`SteamCloudBackend.kt:195`; Rust `BlSteamSession`/`SteamCloudSaveManager.kt:318` also `0L`). This
codebase has **no** source for a real auth-session client id (verified repo-wide: no `getClientID`
usage; the only `clientId` references are the Rust plumbing itself and unrelated Amazon clients).
Adding it would require new login plumbing. Scoped out; documented as a residual.

## Decisions

1. **Disable client encryption on upload** — pass `canEncrypt = false` to `beginFileUpload` on the
   JavaSteam path, matching WinNative. (The Rust path has no `can_encrypt` control; unchanged.)
2. **Honor the commit result** — read `commitFileUpload`'s boolean (JavaSteam) and the Rust
   zero-block `file_committed`; `uploadOne` returns it, so `uploaded` counts only committed files.
3. **Upload a batch sequentially** — replace the concurrent `runConcurrently` upload loop with a plain
   sequential loop, matching WinNative, eliminating interleaved begin/commit on one batch.
4. **Keep the existing verification** (from `3.1.3-fork.25`) as the honest backstop — now it should
   pass, because the commit will actually succeed.

### Rejected alternatives

- **Revert the recent honesty/verification commits** (user floated this): would restore the false
  success while files still do not land — the original bug. Rejected; the verification exposed the
  real defect, it did not create it.
- **Port WinNative's whole save manager**: a different storage model (per-file catalog + local hash
  cache + change-number bookkeeping), a multi-week rewrite that cannot be built locally and would drop
  Bannerlator's GSE/Goldberg path mapping, no-retention guards and SD-card container resolution. Not
  justified by a protocol bug.
- **Add a real `client_id`**: no source available; would need login plumbing. Out of scope.

## Scope

**In scope:**
- `SteamCloudBackend.kt`: `JavaSteamCloudBackend.uploadOne` — pass `canEncrypt = false`; read and
  return `commitFileUpload`'s boolean.
- `SteamCloudBackend.kt`: `BlCloudBackend.uploadOne` — ensure the Rust commit result is propagated
  (the zero-block short-circuit must not force `true`).
- `SteamCloudSaveManager.kt`: the upload loop becomes sequential.
- Guard tests (plain JVM source-assertion).

**Out of scope:**
- The storage model / save-manager UI (no port).
- A real `client_id` (no source; documented residual).
- The SHA-1 incremental skip (separate latent risk).
- Download path.

## Detailed Changes

### Change 1 — `canEncrypt = false`

`SteamCloudBackend.kt:203-206`, add `canEncrypt = false` to the named-arg `beginFileUpload(...)`
call. The vendored signature's `canEncrypt` param exists (verified via `javap`).

### Change 2 — return the commit result

`SteamCloudBackend.kt:245-246`: capture `commitFileUpload(...)`'s boolean and return
`ok && committed` (only claim success when Steam actually committed). Log a warning when the block
PUTs succeeded but the commit was rejected (so the log distinguishes the two).

### Change 3 — Rust zero-block commit

`BlSteamSession.kt` (~:474-481): the zero-block short-circuit commits `true` unconditionally; verify
it does not mask a rejected commit (propagate `nativeCloudCommitFileUpload`'s value). If the value is
already propagated on the non-zero path, make the zero-block path consistent.

### Change 4 — sequential upload

`SteamCloudSaveManager.kt:279-299`: replace `runConcurrently(toUpload, ...) { ... }` with a plain
`for (entry in toUpload) { ... }` loop (same body, `uploadedShas` may become a plain map). Preserve
the per-file try/catch and `allOk` semantics exactly.

## Testing

- **Automated:** plain JVM JUnit4 source-assertion guards (Robolectric cannot boot this module), run by
  CI (`:app:testStandardDebugUnitTest`). Guards assert: `canEncrypt = false` is present; the commit
  boolean is read and folded into the return; the upload loop is sequential (no `runConcurrently` on
  the upload path).
- **On-device:**
  1. SteamLite game → exit → manual push: files actually appear in Steam Cloud (sha/timestamp
     change), and the message is a real success.
  2. `BH_STEAM_CLOUD` shows no `file_committed=false` rejections.
  3. Regression: download unchanged; no-retention message still fires for a genuinely empty manifest.

## Risks

- **`canEncrypt=false` mismatch with a server that requires encryption.** WinNative does exactly this
  and works, so the risk is low; if Steam ever requires encryption the commit will honestly fail and be
  logged.
- **Sequential upload is slower for many files.** Bounded by the same batch; acceptable for parity.
- **`client_id = 0L` remains.** If the commit still fails after these fixes, the next suspect is the
  missing client id; that would be a follow-up requiring login plumbing.
