# Played-copy resource audit checkpoint — 2026-09-30

## Ownership and base

GPT-6, Codex desktop, effort not reported (Sol), owns `codex/played-copy-budget` in the isolated main-audit worktree. Destination main `4fd3af9373bfa232e637c06e9705dae49b9b8fe1`. No experimental checkout, Claude session or device access. The user's phone-QA gate keeps app-changing PRs open. No Stable release or artifact deletion authorized.

## Finding and bounded change

[#211](https://github.com/averylicious/muon/issues/211): the played-cache evictor protects the song currently being written; CacheWriter had no per-copy budget or completion trim. A single exceptionally large/unknown-length copy could exceed the selected played-cache limit and remain protected after it completed. Startup loaded spans with the same protection and never performed a final trim.

`copyPlayedWithinLimit` checks the current selected cache budget against declared length and actual cached bytes (including resumed bytes) through the pinned Media3 CacheWriter progress callback. An oversized opportunistic played copy is rejected and its committed spans removed after CacheWriter closes. Only `played:` keys may use this helper. Settings changes are read during progress. Normal playback/explicit downloads are unchanged; these are not generic download limits or audio network timeouts. After startup, the production evictor trims with no active-write exception, recovering oversized played resources left by an older app while preserving explicit downloads.

Unknown-length copies can cross the byte budget by one CacheWriter read buffer before rejection/cleanup; existing cache fragmentation can also cause transient aggregate overage. This is bounded copy growth, not an assertion that every moment of aggregate filesystem use exactly matches the setting. Slow-drip latency below the byte budget and the copier's executor backlog remain separate audit questions. Storage/cleanup IO failures are still possible; no full-disk/SD-card safety claim.

Reviewed the pinned Media3 datasource published sources on Google's Maven: CacheWriter invokes progress before/after opening and after reads, closes its source when the callback throws; SimpleCache calls onCacheInitialized under its cache lock after loading spans. No dependency changes.

Five actual-production Media3/Robolectric tests use disposable byte fixtures and native SQLite: ordinary readable copy; declared oversize without payload reads and without deleting downloads; unknown length with bounded read overshoot, cleanup and retry; lowered budget during transfer; restart trimming an old oversized played copy while retaining an explicit download. No network/device/personal music files used by those tests. Actions is the first Android compile.

## Verification / QA

Local whitespace and branch-direction checks. Consult the PR/#181 for exact final head/run and results. Artifact uploads currently fail GitHub storage quota, so a compiled APK is not a downloadable test build. No local Android build or phone test.

Phone QA pending: normal playback then offline played-copy fallback; explicit downloads survive cache operations/restart; change the played-cache limit while a copy is in progress and confirm playback is unaffected. Synthetic oversize behavior is covered by the JVM fixtures, not exercised on the user's library/phone. Do not use their only copy to test full-disk failure.

## Parked slices / latest evidence

- #205 head `b3e86da810810d51c1bf91769642119a2e810971`: successful run 364/artifact, private-service compatibility QA pending. Leave open.
- #206 head `d85c602eb2bc27b7852e14f75c4e229c73cf1c63`: run 366 build/test/lint/identity success; artifact quota failure, zero artifacts, phone QA pending. Not included here.
- #209 head `0cfdf05b3abd6886b8ebbcaeaeaa1b4da925ea47`: run 367 build/test/lint/identity success; overall artifact quota failure, zero artifacts, library ownership phone QA pending. [Checkpoint](2026-09-30-library-load-ownership.md) is on that PR.
- #210 head `1ecc1c5592876c7a8a817d04451beca6e68abab5`: run 368 build/test/lint/identity success; overall artifact quota failure, zero artifacts, artwork identity/colour phone QA pending. [Checkpoint](2026-09-30-artwork-identity.md) is on that PR.
- Source branches are independent from current main, not stacked. Combining them must be reviewed/tested again; #206/#210 overlap in Artwork.kt. Experimental forward integration remains separate and owner-landed.
- Main Canary .363 remains the last verified publication. Open slices are not Obtainium releases. Artifact cleanup approval is still pending; generic continuation is not deletion approval. Avoid unchanged-head reruns until storage changes.

## Remaining audit and resume

Source audit estimate is roughly 60% first-pass coverage, not measured file coverage or release readiness. Outstanding deep areas include #179 removable-storage data preservation, whole-response/library memory bounds, background copy scheduling/latency, playback lifecycle, resolved dependency/advisory/integrity review and real phone compatibility/interaction QA. Pinned action SHAs, wrapper checksum/repository restrictions and separated CI token permissions were inspected; absent Gradle dependency verification metadata/lockfiles are an integrity gap/question, not evidence of compromise. No resolved graph/advisory verdict yet.

Verify live heads/checks/ownership before resuming, finish this exact-head check, then choose the next bounded audit question. Leave all phone-QA-dependent PRs open. Reserve capacity to fix CI and save evidence; never borrow a previous account's usage reading or rely on local session history.
