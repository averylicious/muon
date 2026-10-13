# Partition played-copy Clear — October 12

Solo #253 engineering; no Claude, phones or experimental checkout changes. Cumulative main-audit successor to #500. Main included: `2a275286f28db78083567465a521b7ea46f32640`. Partition selection remains disabled; this does not clear the Stable engineering gate.

## Implemented boundary

- `PartitionStorageSession.removePlayedDetailed` reports actual removed bytes only after the exact native owner closes successfully. Durable Closed remains the no-fallback claim; repeated Clear cannot count nonexistent bytes as another deletion.
- `OwnedPartitionShelf.clearPlayed` traverses fixed journal pages, releasing each observational page lease before actual per-target exclusive deletion. Active readers/writers, manager aliases, caller claims, legacy originals and migration copies stay protected. There is no retained all-key set or per-copy result history.
- Remaining usage is read through the mixed saved-audio route with checked arithmetic; failure is unknown, never a fabricated zero. Protected originals count as remaining. The actor drains/cancels its played source before Clear, and does not nest target-exclusive operations inside a shared maintenance lease.
- Production `OfflineStore.clearPlayed` dispatches to this boundary for partition shelves before accessing the legacy cache getter, updates observed usage/revision and reports partial/protected work truthfully.

## Verification and limits

`git diff --check` passed. Android Actions is the first actual compile and native SQLite/Robolectric test run; final exact-head receipt belongs on the PR and #253. Added tests exercise more than one fixed page, repeated Clear, protected legacy bytes, a live reader and alias rows with oversized metadata. No device or visual verification is claimed.

All application PRs remain open for pending user acceptance. New owned played-copy Clear is not aggregate cache-budget enforcement, general legacy/Ready-copy retirement, production selected-root startup or opt-in/recovery UI. Those remain engineering; originals are deliberately preserved until their destructive ownership protocol is implemented.

Continue authorized work across verified boundaries while capacity permits. Keep one writer per branch/worktree, verify live heads and checks, and retain uncertainty rather than resetting a possibly live owner. No Stable tag/release is authorized.
