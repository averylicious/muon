# Whole partition Move batch timing

Follow-up to #401 on cumulative #611 (`da66f416fe09213a9bddfb4198dc32bbc5f4f88a`). Codex GPT-6 / desktop, exact variant/effort not reported, implementation and source self-check. Claude Opus5.5 / CLI / High inspected read-only then was paused for quota; no code contribution or completed independent review.

Existing diagnostic native-verification/digest/root observations are nested. Their totals do not account for all selection/copy/publication/cover/retirement work and must not be summed or subtracted to claim a speedup. No safe removal of native-open, release, admission or per-unlink target verification was established in this source pass.

`MoveOperationTiming` observes the existing serial `OfflineStore.movePartitionBatch`, from entry to final result-submission, with at most32 attempted events per process. Only explicit debuggable diagnostic builds construct it (`BuildConfig.DEBUG`); ordinary Canary, Stable and non-debuggable storage-timing builds do not create it or invoke its clock/log. Fixed `MuonMoveBatch` fields: complete, totalMs, selectionMs, admissionMs, copyMs, publishMs, coverMs, retireMs, submissionMs. No paths, keys, titles, URLs, error text or per-copy history.

- Selection includes backend checks and bounded census/selection.
- Admission includes per-entry cancellation/location, destination-name check and fresh-ID selection.
- Copy includes source-route choice and source-to-target copy. Existing durable Copied/Published/Pending callbacks advance to publication, cover/Pending and source retirement respectively; repeated entries accumulate into the same fixed counters.
- Submission includes synchronous result handling and handler submission, NOT completion of main-thread UI callbacks or the worker's prior executor wait. Recovery operations/legacy batch Moves are outside this observer.
- `complete=true` requires all nonempty census entries handled with no kept/later/recovery/exception; false is partial/refused/failed, not a new storage result.

No guards, checks, durable stages, byte/metadata/cover comparisons, unlink order, cancellation semantics, native lifetime, executor or recovery policy changes. Clock/sink failures are contained. Tests cover phase composition across two copies, disabled/exhausted admission without clock/sink, and clock/log failure containment/backward time. Existing storage-failure/uncertainty regressions remain unchanged. CI is the first Android compile; no local APK build or new device result yet.

Device acceptance: explicitly dispatch diagnostic_debug on the branch; verify diagnostic identity and banner. Use only the already-reviewed disposable diagnostic fixture, snapshot saved/native rows and all audio/cover/original hashes first, and check exact preservation/retirement afterwards. Capture one uninterrupted Move in each direction and this batch event along with existing nested counters; never add overlapping totals. Restore original volume and stop the test app. Do not force-stop an active Move, install over an active driver, opt in ordinary user data or delete originals. Current user-cycle permission must be checked again at resumption. This does not clear #401/#253 or authorize Stable/activation/merging app acceptance.
