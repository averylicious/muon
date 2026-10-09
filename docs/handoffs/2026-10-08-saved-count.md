# #253 count-only saved discovery — 2026-10-08

GPT-6 (Codex desktop; exact variant/effort not exposed), implementation and self-review. Branch codex/saved-count-oct8, from #392 ebce29276a850311f18f024a88c1b6fe363cdf8e. Contains #390/#391 and all inherited acceptance changes, main5a123109. No device/experiment changes or local Android build; Actions is first compile/test.

Connect previously built the full decoded saved inventory, ownership maps and sorted list just to count playable copies. savedCompleteCount uses one sequential index pass per available shelf, plus phone played-key snapshot. Completeness/visibility use the same locator bounds, states and cache coverage as savedInventory, including retained STOPPED/213 and complete played copies. No metadata decode, display entries, cover checks, sorting or ownership grant. Long count avoids Int wrap.

Failed shelves contribute zero as before; the helper throws/closes its cursor instead of publishing a partial scan. Card availability remains unchanged. Counts are snapshots, not a transaction/identity proof. Normal saved-library opening still builds the full list pending paging implementation.

Three native-SQLite/disposable-cache tests compare against savedInventory: completed/retained/aliased/hidden/partial/failed/removing rows with oversized tags; claimed/unknown/partial played copies and shelf distinction; mid-cursor failure/closure plus retry. Requests/tags/audio stay unchanged. Media3 Download constructor failureReason invariant was checked against pinned sources for fixtures. CI not run locally; exact-head evidence on the PR.

Native per-row allocation and cache-key/span snapshots remain; no heap/timing measurement. #253 broader paging/offline queue/Media3-cache memory redesign remains REQUIRED before Stable. User chose full-library queueing within a budget, explicit refusal plus smaller-selection offer above budget. That policy is not implemented here. Keep application acceptance OPEN for Connect count/Listen offline and inherited UAT.
