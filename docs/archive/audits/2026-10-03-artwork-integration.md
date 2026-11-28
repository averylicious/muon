# Combined main-audit acceptance candidate

Main source baseline `26c88eaed9d481f210010808f4fd35172c9724c8`, integrated in an isolated `codex/artwork-acceptance` branch. No experimental source or new dependency. GPT-6 / Codex desktop, effort not reported: integration, source review and own test changes self-reviewed; no new independent reviewer of this integration.

Exact component heads:
- #290 network/queue/library: `d004ab4046bcd7e67c0873cac7d37a4ced540cfe`.
- #210 Compose artwork identity: `1ecc1c5592876c7a8a817d04451beca6e68abab5`.
- #221 notification encoded/decode bounds: `642d8c94ea6877cc48c22125e7ed9bef6f465a22`.

These are ancestors, not copied patches. #290 includes #279/#273/#267/#258/#257/#252/#250/#245/#242/#229/#227/#224/#219/#217/#209/#206/#205. Prior source/CI evidence is recorded in those PRs; this candidate needs fresh combined CI. Storage #300 `ebbf409a478fdc4d74c6e96400de4ce0474696e0` is now also an exact ancestor: includes #289/#248/#240/#212/#237/#297 and their contained card/move fixes. No production conflict in this combination. The new candidate combines both formerly separate acceptance builds; it does not merge their app fixes to main or waive user QA.

## Deliberate reconciliation

`Artwork.fetchArtwork`: preserve #206 finite metadata client and cancellation through the encoded body read, the existing 4MiB cap, and #210 captured identity/size memory keys plus rejection of obsolete publication. A late disk write carries its captured identity, never the replacement's. Existing disk filenames and offline fallback retained. No policy for downloaded audio ID reuse or changed image bytes under unchanged tags was added.

`PlaybackService`: combine #221 `notificationBitmapLoader` encoded/decode caps with #206 `Transport.metadataClient`; keep audio's separate streaming client. A byte cap alone cannot bound a slow response below that cap. No notification/controller/lockscreen runtime verification is implied.

Canonical main STATE/audit/runbook docs are kept over older #210 checkpoint text; dated component handoff remains accessible. Checkpoint docs do not grant new device access or waive acceptance.

## Integration regressions

`ArtworkRequestIntegrationTest` uses actual `artworkBitmap` fetch/decode/disk/memory route with bounded loopback HTTP partial-body gates and existing PNG fixture:
- change identity before old body finishes: old return/publication rejected, old disk entry absent, replacement fetched once and subsequently served from memory;
- cancel while reading actual body: bounded coroutine completion, no disk publication, retry requires a fresh HTTP request.

A third integration regression checks accepted NUL tags survive the #248 safe codec through real MediaItem extras, while a same-character-count encoded expansion cannot bypass #279 incoming record byte limits. Existing SnapshotStateObserver, disk/cache/colour, actual Media3 encoded/decode bounds, network cancellation, queue/library and storage cases retained. No substitute HTTP or identity logic, no phone/Tauon/LAN request, no local Android compile. CI is the first compile; result/artifact receipts belong on the PR after execution.

## QA pending

Ordinary live library refresh, permission/reconnect, queue duplicate/remove/Undo and scroll/sheet cancellation; refreshed undownloaded track identity updates list/player colour while a previous cover is in flight; ordinary/offline covers and notification art remain usable. Missing/corrupt/large covers fail safely without disabling transport controls. Pixel lockscreen deferred. Also test ordinary download/remove/startup totals, metadata/offline playback, mounted disposable moves and played-copy cache limit/cancellation; preserve originals, no card eject/replacement/failure injection on existing downloads. Use existing acceptance checklists on #290/#300; this candidate does not resolve all audit findings or authorize Stable.

Pinned Media3 session sources were inspected for a separate notification identity question: `CacheBitmapLoader` matches only encoded bytes/URI; both session and default notification provider insert cache wrappers. #210 targets Compose identity, not those wrappers. Actual pinned-loader characterization is now on main through #303 (448 tests/variant, both new controls passed); do not claim notification identity refresh was fixed by this integration.

Prior artwork/network-only head4a6e718 passed run518 with532 tests/variant zero failures/errors/skips, including both real-fetch controls; debug artifact11277034034 BUILD.txt .518 verified. That APK excluded storage. Final combined head requires new CI/artifact receipts on its PR, not the prior subset result. #303 does not remediate notification identity.
