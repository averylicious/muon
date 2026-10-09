# October 9: exact partition save-command ownership (#253)

Cumulative successor of verified OPEN #446 (`c93e6451f755629c1cc99ef7c227596970d8dc4c`) plus main `602933e5ad1d8ecc65048e8a776488df6acec013`, including verified handoff #447 and user-approved continuous-engineering policy #448. Documentation conflicts retained main's richer final receipts/policy; no source conflict or experiment change. This checkpoint supports continued engineering; it does not end the turn.

## Implemented boundary

`PartitionSaveCommands` binds a bounded process token, exact supported request digest and one fresh allocation/native journal ticket. It refuses an existing catalog/save/migration/legacy census claim before allocation, checks the manager's existing record before forwarding, and requires exact single-use command ownership. It retains scalar tokens/tickets/digests, not metadata/URI/cover payloads. Rechecks allocation/journal/availability and supports only full progressive saved requests within existing payload/Parcel bounds. A mutable request-data change cannot silently gain authority. Restart grants no replay/adoption; uncertain reservations and originals remain preserved.

Service-return acknowledgement is distinct from durable download completion. A service exception after forwarding retains an unconfirmed claim; known refusal releases only scalar process state and keeps reservation/journal/bytes. Terminal callbacks release scalar receipts only for the exact request and supported terminal state; COMPLETED requires actual owned native Closed. No automatic delete, record overwrite, cover transfer or migration Ready fabrication.

`PartitionCommandDownloader` composes the exact command owner with sealed new-save downloading. Validation runs INSIDE the actual download task, not in `createDownloader`: pinned Media3 creates a downloader on its internal handler, where thrown factory errors can terminate that thread. The task catches request/ownership failure and retains a FAILED manager row. The wrapper preserves cancel-before-work behavior. Removal still must be refused before manager delivery: a throwing remove task alone does not preserve Media3's row.

## Verification

Eight new controlled tests use real native SQLite journal/catalog/owned caches and actual DownloadManager/DefaultDownloadIndex: admission capacity/unsupported/census refusal without extra native allocation; full request/data identity and single-use token; durable success/retained row/untouched cover/audio; bypassed unforwarded request FAILED and same manager later succeeds; restart/changed allocation original preservation; uncertain delivery/volume loss; existing record refusal; cancellation/no false completion. Actions is first compile/test/lint; exact final-head result/report/APK belongs on PR/issues. No local Android build or physical heap/performance claim.

## Initial CI correction

The first Android run37922004477 at `abfeda9dffb6ad48f070228875d72104abee85db` compiled production sources but failed debug-test compilation: the fixture constructor capacity parameter needed to be a stored property for its method’s default argument. Corrected only the fixture declaration; tests did not run at that failed head. Refreshed exact-head CI must establish the actual test/lint/APK result.

## Remaining engineering / continuing owner

These prepared components are not yet selected by app services. Production must bind them to `OfflineStore.add` / `deliverCommand` / `admitCommand` under its app-wide source/move/removal/eviction/availability barrier and complete legacy/published name census. Existing DownloadSaveDelivery owns cover acknowledgements; this owner must be integrated with that path, not used as a parallel service entry. Follow with mixed read/completed-new-save routing, allocation/manager callback recovery, bounded space/deadline/cancel/progress migration worker, opt-in controls and legacy full-index transition investigation. #401 remains unresolved; #230 defined legacy engineering/UAT and user-deferred full #179/provenance stay separate.

Root GPT-6 / Codex desktop implementation and author source self-check, exact variant/effort not exposed, not independent review. Allocated Claude remains idle; dated high quota not assumed reset. All app PRs remain OPEN pending inherited UAT; no Stable/tag/auto-merge/bypass or experiment changes. User renewed rooted USB POCO audit-verification permission this cycle; surya/M2007J20CG identity verified read-only, no device mutation yet. Continue further actionable engineering after successful verification/checkpointing while actual quota permits; do not stop merely because this PR is ready.
