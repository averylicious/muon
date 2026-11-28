# #179 service and reader stop contract — 2026-10-04

Main `9957a02b173bc13350807dbe1d83b243b622c9fc`; acceptance candidate `e04c4ecacd8f3917b4b471eed9b8141888106d1b` (#302). GPT-6 / Codex desktop, effort not reported: source investigation and proposed contract/self-review. Extends [writer boundaries](2026-10-03-cache-writer-boundaries.md) and [downloader drain prototype](2026-10-03-download-drain-prototype.md). No production barrier, test execution, device work or recovery/data-preservation fix is added.

## Confirmed service behavior

Read pinned Media3 exoplayer1.11.0 published sources (SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`), `DownloadService.java`:

| Entry | Actual behavior | Requirement for a future generation stop |
| --- | --- | --- |
| `onCreate`595–612 | Looks up a static helper by concrete service class. Only when missing does it call getDownloadManager, resumeDownloads, make/register a helper; then attaches the live service. | Changing OfflineStore's manager pointer alone does not rebind an existing service/helper. Construction's automatic resume must be included in admission design. |
| `onStartCommand`631–678 | Sends add/remove/remove-all/resume/pause/stop-reason/requirements directly to the helper's retained manager. | Application call-site checks alone cannot drain already queued service intents. Cover service entry as well as application submissions. |
| `onDestroy`710–715; helper990–993 | Detaches the service and stops periodic notification updates; it does not release the manager or remove the helper map entry. | Service stop and manager/worker drain are separate receipts. |
| `clearDownloadManagerHelpers`574–583 | Clears the map globally. It does not detach a live service, release a manager or remove the helper listener. | This is not a close/drain operation and affects phone and card classes together. Never call it as a substitute for stopping old owners. |
| helper constructor960–972; callbacks1039–1057 | Retains the manager, registers a listener and can restart its service on work changes. attach975–986 also posts an initial notification callback. | Stop new callbacks/restarts and establish old-manager release before considering a new helper safe. Map removal does not revoke retained helper references. |

Muon `MuonDownloadService.kt`24/43 selects the phone manager or `(card ?: phone).manager`; neither class overrides onStartCommand or owns a generation token. This file is identical in main and #302. #302's application availability checks narrow ordinary dispatch while a card is absent, but do not implement the service/helper generation stop above. The card class's phone fallback is consequently still relevant when designing rebind/adoption. This is source evidence, not a reproduction of a wrong-device command.

## Playback reader boundary

Main and #302 use `OfflineDataSource.open` to select a Shelf, create its source and retain it until close; changing a later route does not change that active source. `OfflineStore.playbackSource/route` delegates routing for each open. There is no generation read lease around open/read/close in either inspected version. Existing source evidence shows cache lookups can remove stale spans, so reading is not safely classified as a side-effect-free owner during disappearance.

A future stop must cover a lease from **before source construction/open through completed close**, including failed open and concurrent cancellation. ExoPlayer stop/release requests alone are not a measured acknowledgment that every old loading read/close has completed. Do not release/cache-capture a generation from an assumed reader count or merely a changed routing pointer. This report adds no active-reader test or runtime acknowledgment.

## Existing evidence and next bounded controls, before production wiring

1. Existing `CardServiceCharacterizationTest` already establishes restart-without-clear reuse through actual services and disposable managers. The companion test-only reset-control PR adds a live-service/no-rebind negative control and a destroyed-service/fresh-lookup positive control. It reuses existing fixture reflection for Store injection/helper inspection, not private helper-map mutation. CI results belong on that PR; no active tasks, complete drain or mounted-card behavior are established. Next extend to independently active phone/card services and queued commands; do not duplicate the existing restart test.
2. Characterize an active actual CacheDataSource reader separately: hold a deterministic read, request cancellation/close, and establish when the last old-generation method exits. A timeout grants no capture/release/reuse.
3. Only after those controls, implement a generation coordinator which freezes app/service commands, stops both affected services, releases managers, drains downloader/mover/copier/read owners and callbacks, then captures while healthy. Helpers may be cleared/rebound only at the verified stopped boundary; failure leaves the generation unavailable.

The global helper reset may require a coordinated phone/card pause, or a different supported service ownership design. That tradeoff is unresolved, not authorization to disrupt unrelated downloads. No cache should be released while missing; drain is only one prerequisite for the separate durable catalog/adoption design. #179/#213/#230 remain open and #302 user acceptance remains pending.
