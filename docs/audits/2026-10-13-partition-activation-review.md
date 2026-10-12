# Partition activation source self-review — 2026-10-13

## Current disposition

No activation has been applied. All variants remain false at #562 41a5b67c1fa264586dbdd91ee959d2d01d05e473. Automatic approval review rejected an earlier attempted Canary-only activation because completion of full Delete/Move and activation review had not been established. The response is to finish those checks and retain a concrete, reviewable proposal; never bypass the rejection. This document is an author source self-review by GPT-6 / Codex desktop, exact variant/effort not reported, not independent review or user/device acceptance. No Claude, subagents, phones or experiments used. Main remains 5a2c47f8c21517de7801ccadfe108c9ea6206dbf.

The user required full supported legacy Delete/Move before activation and approved opt-in migration with old copies kept until byte-verified replacement. Ambiguous/unsupported data is preserved; that is not a protected-read-only substitute for implementing normal Delete/Move.

## Reviewed paths and evidence

Paths below refer to the cumulative source on #562, including #561. Per-boundary CI receipts are on their PRs and the linked [current checkpoint](../handoffs/2026-10-12-legacy-retirement-progress.md). Source/JVM evidence is not Android 16/17 phone, power-loss, visual, latency or measured-memory proof. Actual SQLite fixtures use Robolectric native SQLite, typically API 34; media implementation behavior was checked against pinned published Media3 sources as recorded in branch checkpoints.

| Requirement | Concrete source / regression evidence | Result / limitation |
| --- | --- | --- |
| Real original Delete | LegacyTransitionOwner.deleteCompletedOriginal; OwnedPartitionShelf.removeOriginalCompleted; actualStoreBulkDeleteUsesOwnedMutationForCompletedLegacyAndNewRecords and completeOriginalDeleteDisposesReadyBytesTerminalRecordAndCoverBeforeComplete | Exact ownership bound before durable intent; physical original/Ready copies, terminal row and own cover disposed before Complete. Existing complete/stopped/failed originals supported. Foreign claims/changed identity/unaccounted spans refuse without broad native deletion. |
| Real original Move | OfflineStore.movePartitionBatch → OwnedPartitionShelf.moveCompletedTo/moveCompletedOriginalTo → LegacyTransitionOwner copy/publish/beginMovedRetirement/completeMovedOriginal | Local target is byte-compared, actually published with full metadata/completed row and exact cover before source retirement. Target rechecked before each unlink/disposition. Public dispatch, changed-target, aliases, reader-at-EOF, cancellation and restart cases are in OwnedPartitionShelfTest. |
| New partition Delete/Move | OwnedPartitionShelf removeSavedCompleted/completeSavedDelete, moveCompletedNewSaveTo/completeCoveredSavedMove | Actual source-tree/record/cover identity and destination-bound Move proof. Private intent journals retained through uncertain I/O. New-save source is selected by actual journal, not name-prefix guessing. |
| Explicit recovery | recoverCompletedMoveTo and recoverCompletedDelete; exact Pending journal token + renewed consent; public recovery tests | Generic Delete refuses Move intents; an unrelated new click never adopts an old Pending. No automatic restart replay or hidden repair. |
| Durable result reporting | #561 LegacyTransitionOwner.deleteCompletedOriginal | Review found post-Complete cancellation could falsely report failure and quarantine after successful removal. Fix keeps actual owner/volume checks and excludes new caller cancellation after durable Complete. Two completed/stopped regressions added; both new regressions passed in .1095; a separate inherited startup fixture race required correction, and final .1098/.1099 are pending at this draft. Existing Move/new-save equivalent boundaries are retained. |
| Bounded startup / no legacy native reopen | OfflineStore.prepareSelectedStorage; PartitionPlatformRoots; OwnedPartitionShelf; PartitionStartupReviewOwner | Exact confirmed volume/backend selection before allocation. Partition paths use bounded readonly old index projections and native admission. Default remains Legacy without opt-in; no memory-performance claim for unconverted legacy libraries. |
| Complete or interrupted setup | PartitionBootstrapOwner and PartitionStartupSettings/PartitionStartupReviewOwner | Six closed journal files are synced before atomic same-parent publication. Explicit ReviewedSetup reuses completed roots and creates only unpublished roots; partial temporary attempts preserved. Unsupported existing roots refuse. Directory sync/real removable storage still needs device acceptance. |
| Shared database protection | PreservingMediaDatabase; actual provider and app-preparer tests in #559 | Framework version mismatch refuses before default Media3 wipeDatabase. Actual 8 MiB record/native audio remain. General SQLite corruption/default corruption-handler behavior is not covered by this version guard. |
| Review entry and process exclusion | StorageReviewEntry; MainActivity; StorageStartupAdmission | Fixed shortcut requires checked persisted private UUID capability. Validation creates no authority; uncertain commit sticky across Activity recreation. Review skips MuonApp/model/controller and normal initialization; launcher/service and app sandbox trusted. Root/privileged launcher compromise is outside this capability. |
| Consent / channel gate | PartitionSetupControls; PartitionStartupReviewScreen; app/build.gradle.kts | No default migration, automatic deletion or live manager swap. Explicit checked startup choice + full Force stop/reopen required. All flags currently false, so no opt-in controls are available. |

## Concrete proposed later change (not applied)

If completion/activation review clears, enable ONLY the debug/Canary BuildConfig field:

```diff
 getByName("debug") {
-    buildConfigField("boolean", "PARTITION_OPT_IN_AVAILABLE", "false")
+    buildConfigField("boolean", "PARTITION_OPT_IN_AVAILABLE", "true")
```

Keep defaultConfig/release/benchmark false. Update the compiled-gate regression assertion to require `PARTITION_OPT_IN_AVAILABLE == (BuildConfig.BUILD_TYPE == "debug")`, retaining the actual explicit-disabled selector refusal and actual explicitly-enabled selector/composed tests. This is a reviewable two-file activation boundary, not a workflow environment override. It must receive its own exact-head full CI; do not claim that the disabled preparation's success verifies a changed activation head.

Canary would expose preview/Review controls but continue normal legacy startup until the user explicitly opts in. No file move/deletion occurs merely from installing the APK or saving a choice. First setup creates new empty journal folders after restart; migration remains per-copy and explicit. Delete/Move remain separately requested operations. Release/benchmark stay unavailable pending acceptance and separately reviewed promotion. No Stable tags/releases or phone use are authorized this cycle.

## Required acceptance after an enabled candidate is verified

Use disposable saved data/backups on Canary first. POCO with its original card is useful only in a later device-authorized cycle. Verify ordinary pre-opt-in behavior; explicit choice/cancel/Force stop; first setup; selected optimize/progress/Cancel/retry; original and new-copy Delete/Move; insufficient space/changed destination; exact Pending restart recovery; absent original card phone reads and frozen mutations; launcher shortcut/process recreation; large-library memory/startup (#401). Inherited hardware/queue/library/spoken TalkBack UAT stays pending. This review does not clear #253 or Stable gates.
