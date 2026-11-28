# Storage startup dispatch — 2026-10-12

Continuation from [the October 11 ownership checkpoint](2026-10-11-partition-lifetime-integration.md), source predecessor #495 / `77c25a45b1bc81c4313d824653a77e89235b64ce`, including main `2a275286f28db78083567465a521b7ea46f32640`. GPT-6 / Codex desktop implementation and self-check; exact variant/effort not reported, independent review pending. Claude and devices are excluded this cycle and were not used.

## Implemented boundary

`OfflineStore.await` owns one process attempt with explicit NotStarted/Preparing/Binding/Ready/Failed availability. Native phone/card preparation, provider, scratch/derived roots and actual SimpleCache initialization run off main; real DownloadManager construction remains on main. Both phases retain their actual factories/resources through existing StorageStartup ownership; no failed attempt retry, live service-manager swap or legacy fallback is provided. Card preparation failure is unavailable storage, not an absent card hidden behind phone-only startup. Defaults, package/signing/release behavior and old saved bytes remain unchanged.

Screen/service wait cancellation does not cancel the process attempt. Settings/Connect expose pending or failed saved-storage status. Saved-library reload awaits readiness. Live audio streams independently of saved-storage startup; saved handles, including malformed handles, cannot fall through to network. A live played-copy attempt during unavailable startup is skipped rather than allocating native storage on a controller callback.

The concrete phone/card services now inherit an asynchronous attachment lifecycle. Until readiness, no request payload/intent is queued and no mutation reaches Media3; only start-ID/foreground scalars survive. Foreground starts (including Media3's private RESTART action) receive an immediate preparation notification. On readiness, each instance attaches its existing immutable helper and passes only INIT, never an old Add/Remove. Destroy-before-ready cancels only that service's wait and cannot attach a dead instance. Failed startup stops the service without calling Media3's helper-dependent onDestroy for an unattached instance.

Pinned Media3 1.11.0 published Google Maven sources checked: DownloadService.onCreate (synchronous helper lookup/manager.resumeDownloads/attachment), onStartCommand (private RESTART foreground semantics), onDestroy (requires attached helper), plus DownloadManager application-looper construction/lifecycle. Tests exercise actual Media3 services/managers and native SQLite/cache fixtures on the JVM; they are not device, mount, UI or performance evidence. CI is the first Android compile.

## Still required

Partition selection deliberately still refuses BEFORE any legacy provider/cache. This boundary establishes real production asynchronous startup; it does not enable incomplete partition routing. Next: provide prepared partition roots to that startup choice, route played/new-save/removal/move policies without `.cache` fallback, then explicit opt-in/restart/recovery controls. #253 remains an engineering Stable blocker. Full #179 recovery and trust questions retain their accepted deferrals; app acceptance stack remains OPEN. Fresh device permission is needed for future enabled integration QA; no phone is requested for this source-test boundary.

Verification: pending first exact-head Actions compile/test/lint and artifact receipt. Replace this pending statement in the PR/shared issue with final evidence after checks; no unrun pass or device acceptance is claimed.
