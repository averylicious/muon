# October 9: production saved-audio read boundary (#253)

Built on complete OPEN #441 (`6947c7a1a2dd6b65b489d2ab6af0d876f6e0bf35`) with main `b036d91811be3c6560d55dd6f1a948d248253bc8` reconciled. The only merge conflict was the richer verified new-save handoff receipt from main; retained it. Root is the sole writer of this branch. The experimental checkout/session is untouched and allocated audit Claude remains idle; its last quota observation was high, not refreshed or assumed current.

## Production change

`SavedAudio.kt` provides per-key inspection/membership, callback key enumeration and a `DataSource.Factory` saved reader. It exposes no native cache, writer, downloader, deletion or migration authority. `LegacySavedAudio` implements exactly the existing shared-cache reads with no upstream/sink. Coverage still counts all spans, preserves unknown/partial/missing cases and returns immutable content metadata. Its legacy native index/key snapshots remain unbounded; this slice does NOT claim a memory improvement or enable partition storage.

`OfflineStore.Shelf` uses that interface for saved playback, saved inventory/page projection, playable-copy count and played-copy admission. Existing constructor callers retain their availability predicate/trailing-lambda contract. Production still selects the legacy implementation. `SavedEntries` inspects only included rows and uses the existing disk played-key census; compatibility overloads preserve legacy characterization fixtures. Existing source-close and two-shelf containment fixtures inject the same real file reader at construction instead of mutating a concrete Media3 factory afterward.

The concrete legacy `Shelf.cache`/`Store.cache` remains explicitly required by downloader/played writers, moves, removals and naming scans. No partially converted partition backend is selected for those paths. Manager command/removal guards and saved row/cover ownership are unchanged. An adapter-only refusal cannot clear their future production integration gate.

## Verification

Five new native-SQLite/real-cache and cache-free backend controls cover coverage/metadata-only membership, no-upstream missing-span failure with record/byte preservation, selected-row/disk-census inventory/count, failed inspection with real cursor closure, and actual OfflineDataSource saved/live separation. Existing actual manager/removal/move/close controls rerun in the full workflow. Actions is the first compile/test/lint; final exact-head receipt belongs on the PR and issue checkpoint. No local Android compile, phone command, native heap/startup/power-loss or physical-card claim.

Pinned Media3 1.11.0 datasource published sources (same SHA/source inventory as the preceding native-cache checkpoint) were checked for CacheDataSource.Factory's null upstream and null sink behavior. No library/dependency/schema changes.

## Next engineering / QA

Next route the read interface to published partitions under native pool pins, with exact Ready/Closed admission and bounded disk key enumeration. Then integrate real new-save allocation/request/command/cover/completion and the progressive downloader, with pre-manager destructive exclusion and actual index-record preservation tests. Production played writes/move/removal/source/eviction/availability coordination, bounded migration worker/free-space/deadline/cancel/progress, restart recovery and opt-in controls remain REQUIRED. Existing full-index legacy import still has an unresolved transition peak; #401 startup cause/fix is separate and unresolved.

Keep this cumulative app PR and ancestors OPEN pending acceptance; do not independently squash overlapping ancestors. No Stable release/tag or experimental forward-sync is performed. No POCO is needed yet; notify the user with the precise test/build when production routing and opt-in/recovery controls can exercise disposable private-index/card/restart/cancel/original-byte checks. Renew device authorization then. Existing Bluetooth/headset, notifications, library/queue/Undo and TalkBack spoken/focus UAT remain pending. Full #179 recovery and dependency/tool/cache provenance retain explicit post-release deferrals.
