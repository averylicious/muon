# #179 service helper reset controls — 2026-10-04

Baseline main `9957a02b173bc13350807dbe1d83b243b622c9fc`. GPT-6 / Codex desktop, effort not reported: tests/report author and own self-review, not independent review. Test-only; no app change, network, phone, real card, download worker or user data.

Adds two controls to the existing `CardServiceCharacterizationTest`, using actual pinned Media3 DownloadService and actual MuonCardDownloadService with disposable SimpleCache/managers/native SQLite on RobolectricSDK34. Existing fixture reflection injects the disposable OfflineStore and reads the helper's manager; no private helper-map mutation or production reflection is added. Source exoplayer1.11.0 SHA256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`.

- `clearingHelpersDoesNotRebindALiveService`: create actual service bound to original card manager; replace the Store pointer and call the public helper clear. The live service must still hold the original manager. Characterizes an unsafe shortcut only with idle disposable owners, not an endorsed production sequence.
- `destroyedServiceWithClearedHelpersSelectsReplacementManager`: destroy/remove the original controller, set the replacement manager, clear helpers and create a fresh actual service. It must select the replacement, not the old or phone manager. This is the positive control missing beside the existing restart-without-clear reuse test.

All managers deliberately admit no downloader tasks; cleanup destroys services and releases every manager/cache/database. This controls lookup/reference behavior only. Destroying the service and clearing the map does not prove manager release, admitted-worker/callback/reader drain or safe capture/recovery. The global clear also affects phone helpers; simultaneous phone/card coordination and service intents remain untested. #179 stays open.

CI is the first compile/execution; exact-head results are recorded on the PR. No device QA is needed for this test-only change. Production cache fixes and #302 still require their separately recorded user acceptance.
