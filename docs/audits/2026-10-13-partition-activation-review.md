# Partition activation source self-review — 2026-10-13

## Current disposition

Canary-only preview is now applied on OPEN #562 8677a144516c6710a7f70b10f617f274cf13d708; changed-head full CI is pending. Release/benchmark remain false. The earlier preparation at 41a5b67c1fa264586dbdd91ee959d2d01d05e473 kept all flags false and passed its own checks. Automatic approval review rejected an earlier attempted Canary-only activation because completion of full Delete/Move and activation review had not been established. The response is to finish those checks and retain a concrete, reviewable proposal; never bypass the rejection. This document is an author source self-review by GPT-6 / Codex desktop, exact variant/effort not reported, not independent review or user/device acceptance. No Claude, subagents, phones or experiments used. Main remains 5a2c47f8c21517de7801ccadfe108c9ea6206dbf.

The user required full supported legacy Delete/Move before activation and approved opt-in migration with old copies kept until byte-verified replacement. Ambiguous/unsupported data is preserved; that is not a protected-read-only substitute for implementing normal Delete/Move.

## Reviewed paths and evidence

Paths below refer to the cumulative source on #562, including #561. Per-boundary CI receipts are on their PRs and the linked [current checkpoint](../handoffs/2026-10-12-legacy-retirement-progress.md). Source/JVM evidence is not Android 16/17 phone, power-loss, visual, latency or measured-memory proof. Actual SQLite fixtures use Robolectric native SQLite, typically API 34; media implementation behavior was checked against pinned published Media3 sources as recorded in branch checkpoints.

| Requirement | Concrete source / regression evidence | Result / limitation |
| --- | --- | --- |
| Real original Delete | LegacyTransitionOwner.deleteCompletedOriginal; OwnedPartitionShelf.removeOriginalCompleted; actualStoreBulkDeleteUsesOwnedMutationForCompletedLegacyAndNewRecords and completeOriginalDeleteDisposesReadyBytesTerminalRecordAndCoverBeforeComplete | Exact ownership bound before durable intent; physical original/Ready copies, terminal row and own cover disposed before Complete. Existing complete/stopped/failed originals supported. Foreign claims/changed identity/unaccounted spans refuse without broad native deletion. |
| Real original Move | OfflineStore.movePartitionBatch → OwnedPartitionShelf.moveCompletedTo/moveCompletedOriginalTo → LegacyTransitionOwner copy/publish/beginMovedRetirement/completeMovedOriginal | Local target is byte-compared, actually published with full metadata/completed row and exact cover before source retirement. Target rechecked before each unlink/disposition. Public dispatch, changed-target, aliases, reader-at-EOF, cancellation and restart cases are in OwnedPartitionShelfTest. |
| New partition Delete/Move | OwnedPartitionShelf removeSavedCompleted/completeSavedDelete, moveCompletedNewSaveTo/completeCoveredSavedMove | Actual source-tree/record/cover identity and destination-bound Move proof. Private intent journals retained through uncertain I/O. New-save source is selected by actual journal, not name-prefix guessing. |
| Explicit recovery | recoverCompletedMoveTo and recoverCompletedDelete; exact Pending journal token + renewed consent; public recovery tests | Generic Delete refuses Move intents; an unrelated new click never adopts an old Pending. No automatic restart replay or hidden repair. |
| Durable result reporting | #561 LegacyTransitionOwner.deleteCompletedOriginal | Review found post-Complete cancellation could falsely report failure and quarantine after successful removal. Fix keeps actual owner/volume checks and excludes new caller cancellation after durable Complete. Two completed/stopped regressions added; both new regressions passed in corrected final .1098/.1099. The inherited startup fixture was corrected by actual-worker draining; no safeguard weakened. Existing Move/new-save equivalent boundaries are retained. |
| Bounded startup / no legacy native reopen | OfflineStore.prepareSelectedStorage; PartitionPlatformRoots; OwnedPartitionShelf; PartitionStartupReviewOwner | Exact confirmed volume/backend selection before allocation. Partition paths use bounded readonly old index projections and native admission. Default remains Legacy without opt-in; no memory-performance claim for unconverted legacy libraries. |
| Complete or interrupted setup | PartitionBootstrapOwner and PartitionStartupSettings/PartitionStartupReviewOwner | Six closed journal files are synced before atomic same-parent publication. Explicit ReviewedSetup reuses completed roots and creates only unpublished roots; partial temporary attempts preserved. Unsupported existing roots refuse. Directory sync/real removable storage still needs device acceptance. |
| Shared database protection | PreservingMediaDatabase; actual provider and app-preparer tests in #559 | Framework version mismatch refuses before default Media3 wipeDatabase. Actual 8 MiB record/native audio remain. General SQLite corruption/default corruption-handler behavior is not covered by this version guard. |
| Review entry and process exclusion | StorageReviewEntry; MainActivity; StorageStartupAdmission | Fixed shortcut requires checked persisted private UUID capability. Validation creates no authority; uncertain commit sticky across Activity recreation. Review skips MuonApp/model/controller and normal initialization; launcher/service and app sandbox trusted. Root/privileged launcher compromise is outside this capability. |
| Consent / channel gate | PartitionSetupControls; PartitionStartupReviewScreen; app/build.gradle.kts | No default migration, automatic deletion or live manager swap. Explicit checked startup choice + full Force stop/reopen required. Only Canary now exposes preview controls, while release/benchmark remain false. |

## Concrete Canary change (applied only after renewed approval)

After completed source/CI review and renewed automatic approval, the candidate enables ONLY the debug/Canary BuildConfig field:

```diff
 getByName("debug") {
-    buildConfigField("boolean", "PARTITION_OPT_IN_AVAILABLE", "false")
+    buildConfigField("boolean", "PARTITION_OPT_IN_AVAILABLE", "true")
```

Keep defaultConfig/release/benchmark false. Update the compiled-gate regression assertion to require `PARTITION_OPT_IN_AVAILABLE == (BuildConfig.BUILD_TYPE == "debug")`, retaining the actual explicit-disabled selector refusal and actual explicitly-enabled selector/composed tests. This is a reviewable two-file activation boundary, not a workflow environment override. It must receive its own exact-head full CI; do not claim that the disabled preparation's success verifies a changed activation head.

Canary would expose preview/Review controls but continue normal legacy startup until the user explicitly opts in. No file move/deletion occurs merely from installing the APK or saving a choice. First setup creates new empty journal folders after restart; migration remains per-copy and explicit. Delete/Move remain separately requested operations. Release/benchmark stay unavailable pending acceptance and separately reviewed promotion. No Stable tags/releases or phone use are authorized this cycle.

## Required acceptance after an enabled candidate is verified

Use disposable saved data/backups on Canary first. POCO with its original card is useful only in a later device-authorized cycle. Verify ordinary pre-opt-in behavior; explicit choice/cancel/Force stop; first setup; selected optimize/progress/Cancel/retry; original and new-copy Delete/Move; insufficient space/changed destination; exact Pending restart recovery; absent original card phone reads and frozen mutations; launcher shortcut/process recreation; large-library memory/startup (#401). Inherited hardware/queue/library/spoken TalkBack UAT stays pending. This review does not clear #253 or Stable gates.

## Completed exact-head review evidence, 2026-10-13 04:36 local

Source self-review of the planned supported Delete/Move, selector, process admission, consent, startup and recovery paths is complete with no additional blocking source finding identified after #561. This is author self-review, not independent review or phone UAT.

- #561 head 2a08bd15469413bbeb4a1de547c31febbc442202: Android APKs .1098 / 38303597924 and required Branch direction passed. Downloaded actual reports: 1,624 tests per variant, zero failures/errors/skips; lint zero errors/68 warnings. Actual signed APK certificate/package/non-debuggable/export policy verified; SHA256 ccc6defbd23a717ebcd17aee019b2d0474975438fa24b30c879461c12f242feb.
- #562 inactive preparation head 41a5b67c1fa264586dbdd91ee959d2d01d05e473: Android APKs .1099 / 38303636960 and required Branch direction passed. Downloaded reports: same 1,624 tests per variant and zero failures/errors/skips; lint zero errors/68 warnings. Actual APK safeguards verified; SHA256 034612e008d69d2001d253a85e36c1ea0cf36aa13f214d83c4161f006065e147. Generated BuildConfig compiles with all gates false.
- Both latest heads include full supported original Delete/Move and the corrected durable Delete completion boundary. The user requirement is not replaced with read-only originals. Existing tests include public production dispatch and actual files/row/cover/restart operations.
- Main is still 5a2c47f8c21517de7801ccadfe108c9ea6206dbf; no phone installation, user-file changes, merge or Stable publication occurred.

These were materially new checks since the rejected attempt. Renewed automatic approval assessed and allowed the exact Canary-only preview change above; it was not an indirect workaround. The changed source head still needs full CI. Enabling controls on an OPEN branch is distinct from Stable promotion or deployment: the backend remains Legacy until separate in-app user consent and full restart; Move/Delete/migration remain explicit. The changed activation head must run full CI before recommending an artifact. Stable/benchmark remain false; application PRs stay open for acceptance.

Known conservative retention: cancellation after local copy but before publication keeps an unpublished Closed destination, not a completed saved row or Move. It preserves the original and may consume temporary space; no cleanup, secure-erasure or automatic recovery claim. Partial setup attempts likewise remain evidence. Do not describe these states as completed operations.
