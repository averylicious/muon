# Disabled channel gate preparation for storage acceptance — 2026-10-13

## Scope and channel gate

GPT-6 / Codex desktop implementation/source self-check; exact variant/effort not reported. No independent review, Claude, phones or subagents. Branch codex/partition-canary-optin-oct13 inherits #561 4a20b1f7054ff177a797cbebcbe9dc7ff8e08db0 and main 5a2c47f8c21517de7801ccadfe108c9ea6206dbf. Experiment untouched. Do not push/install/recommend this candidate as verified until inherited exact-head CI and this candidate's full checks pass.

ALL variants compile PARTITION_OPT_IN_AVAILABLE=false, including Canary. No controls are exposed. This prepares BuildConfig generation and preview wording so a future separately approved change can adjust only the Canary gate; preferences cannot enable a compiled-off variant. Normal startup stays Legacy unless the user confirms a checked exact partition choice. No default migration, live backend swap, automatic audio removal or Stable publication. The new preview label explains opt-in status and retaining backups. Ordinary and diagnostic Canary package/signing paths remain unchanged; no update-feed or CI secret changes.

Full supported original Delete/Move, destination byte verification before source retirement, bounded native/metadata admission, source-read-only routing, migration progress/Cancel/retry, explicit setup recovery, absent-card phone reads/mutation freeze, global DB version refusal and authenticated pre-startup review are inherited. User-required full Delete/Move is not replaced by read-only originals. This build DOES NOT enable the acceptance path and cannot clear #253 or Stable gates. Automatic approval review rejected an earlier attempted Canary activation because completion of full Delete/Move and activation review was not established. No activation edit was applied. This safe alternative keeps all gates false and is not a workaround to activate storage. Full supported original Delete/Move remains required. The source self-review found/fixed a post-Complete cancellation-reporting defect in #561; verify its exact-head tests before any renewed approval.

## Planned acceptance (needs user authorization for a device cycle)

Use Canary and a disposable saved library first. Source/CI tests are not phone acceptance. No phone commands this cycle. POCO with its original card is useful after the candidate is verified; Pixel can cover phone storage only.

1. Before opting in, existing normal playback/download/library still work. Settings labels the optional preview; cancelling changes nothing.
2. Reconnect the original SD card, stop saved playback/downloads, confirm the exact choice, wait for success, then Android App info → Force stop → ordinary reopen. Existing phone/card originals and saved records remain; no bulk migration happens.
3. Play an original; optimize one copy with temporary space; verify audio and original preservation; check progress/Cancel and exact interrupted retry. Never infer readiness from equal byte counters.
4. Delete and Move selected originals/new copies with disposable data. Move destination must play before source retirement is accepted. Check cancellation, failed/insufficient-space paths and restart recovery without losing either record/copy.
5. With a selected card absent at fresh Ready startup, phone copies remain readable and saved mutations/Optimize are clearly paused. Reinsert the same card plus full restart; no replacement card should be adopted.
6. Check authenticated app-icon Review entry without normal model/controller/native initialization. Already-running normal process must refuse review until Force stop. Activity recreation must not reset admission. Explicit interrupted setup review must reuse completed roots and finish only unpublished ones, retaining partial temporary folders.
7. Large saved library/metadata and startup latency (#401) still need characterization; JVM limits alone are not observed memory/performance proof. Inherited Bluetooth/hardware, queue/library and spoken TalkBack UAT also remains pending.

## Verification and next steps

Verify BuildConfig generation with false channel flags in both variants; explicit-disabled selector regression remains regardless of variant. Full inherited JVM/lint/identity/export checks run on the final inactive preparation head. Record downloaded JUnit/lint reports and actual APK receipt before use. Leave preparation/app stack OPEN. A later concrete activation proposal would change debug only from false to true, keep release/benchmark false and still require an exact confirmed private choice plus full restart; no default migration. It requires activation approval and its own exact-head CI before device acceptance. Do not present this disabled artifact as an opt-in test build. No Stable release/tags are authorized.
