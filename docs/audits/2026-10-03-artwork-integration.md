# Network/library/queue and artwork acceptance candidate

Main source baseline `26c88eaed9d481f210010808f4fd35172c9724c8`, integrated in an isolated `codex/artwork-acceptance` branch. No experimental source or new dependency. GPT-6 / Codex desktop, effort not reported: integration, source review and own test changes self-reviewed; no new independent reviewer of this integration.

Exact component heads:
- #290 network/queue/library: `d004ab4046bcd7e67c0873cac7d37a4ced540cfe`.
- #210 Compose artwork identity: `1ecc1c5592876c7a8a817d04451beca6e68abab5`.
- #221 notification encoded/decode bounds: `642d8c94ea6877cc48c22125e7ed9bef6f465a22`.

These are ancestors, not copied patches. #290 includes #279/#273/#267/#258/#257/#252/#250/#245/#242/#229/#227/#224/#219/#217/#209/#206/#205. Prior source/CI evidence is recorded in those PRs; this candidate needs fresh combined CI. Storage #300/#297 are NOT included; installing this APK instead of the storage candidate changes that coverage, despite updating the same Canary package/data.

## Deliberate reconciliation

`Artwork.fetchArtwork`: preserve #206 finite metadata client and cancellation through the encoded body read, the existing 4MiB cap, and #210 captured identity/size memory keys plus rejection of obsolete publication. A late disk write carries its captured identity, never the replacement's. Existing disk filenames and offline fallback retained. No policy for downloaded audio ID reuse or changed image bytes under unchanged tags was added.

`PlaybackService`: combine #221 `notificationBitmapLoader` encoded/decode caps with #206 `Transport.metadataClient`; keep audio's separate streaming client. A byte cap alone cannot bound a slow response below that cap. No notification/controller/lockscreen runtime verification is implied.

Canonical main STATE/audit/runbook docs are kept over older #210 checkpoint text; dated component handoff remains accessible. Checkpoint docs do not grant new device access or waive acceptance.

## Integration regressions

`ArtworkRequestIntegrationTest` uses actual `artworkBitmap` fetch/decode/disk/memory route with bounded loopback HTTP partial-body gates and existing PNG fixture:
- change identity before old body finishes: old return/publication rejected, old disk entry absent, replacement fetched once and subsequently served from memory;
- cancel while reading actual body: bounded coroutine completion, no disk publication, retry requires a fresh HTTP request.

Existing SnapshotStateObserver, disk/cache/colour, actual Media3 encoded/decode bounds, network cancellation and queue/library cases retained. No substitute HTTP or identity logic, no phone/Tauon/LAN request, no local Android compile. CI is the first compile; result/artifact receipts belong on the PR after execution.

## QA pending

Ordinary live library refresh, permission/reconnect, queue duplicate/remove/Undo and scroll/sheet cancellation; refreshed undownloaded track identity updates list/player colour while a previous cover is in flight; ordinary/offline covers and notification art remain usable. Missing/corrupt/large covers fail safely without disabling transport controls. Pixel lockscreen deferred. Use existing acceptance checklist on #290; this candidate does not resolve all audit findings or authorize Stable.

Pinned Media3 session sources were inspected for a separate notification identity question: `CacheBitmapLoader` matches only encoded bytes/URI; both session and default notification provider insert cache wrappers. #210 targets Compose identity, not those wrappers. Further characterization/report remains separate; do not claim notification identity refresh was fixed by this integration.
