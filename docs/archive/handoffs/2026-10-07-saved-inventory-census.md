# Saved inventory ownership census — October 7

Bounded #253 follow-up, based on #369 `d2900901eed8698a1c62382a0bb65cf451a89de2`, whose ancestry includes combined #302 `4921c68dc7290ea43f5f287da1c908ff67a62219` and current main `3291aa3a7df74e45829bdcdfa3c71fef14c9b6c4`. GPT-6 / Codex desktop / exact variant and effort not exposed authored/self-reviewed this slice. No Claude assignment, phone testing or experimental mutation.

## Source finding

`savedInventory` previously invoked `soleOwner(downloads, request.id)` for each listed download. That function scans all rows for the ID and again for its effective key. Even ordinary unique owned rows therefore cause quadratic list traversal as saved copies grow. It runs off main, but that does not eliminate the CPU cost or inventory delay. No measured phone jank/heap claim.

The inventory now builds one per-ID/per-effective-key census and projects the removable set once. Every row/state counts, including REMOVING and unlistable/keyless/played-key aliases. A removable entry still requires one ID, exactly one effective-key claimant, explicit key equal to ID and a non-played namespace. Existing per-command fresh censuses remain untouched; this is a listing snapshot, not a new removal capability or transaction lock. No saved entry, metadata, byte or index row is deleted/truncated/rewritten.

## Regression boundary

- Existing actual native-index/SimpleCache alias/played-claim controls remain.
- A new native-index case places a REMOVING alias behind a complete visible saved copy. It must stay unlisted but still prevent the visible copy's removable flag; both index state and saved bytes remain.
- A 2,048-entry production inventory case counts input-list visits rather than elapsed time: work must be proportional to input, not one scan per entry. It also verifies duplicate IDs remain conservatively nonremovable and no cache keys are created. This asserts a work bound without mirroring the map algorithm or claiming a device speedup.
- CI is the first Android compile/test. Record exact-head reports before claiming these controls passed; no local Android build/device result.

## Remaining scope and handoff

This reduces one repeated census. It does not bound total inventory/DOM/decoded metadata/aggregate Binder/native heap, optimize sort projections or all move/removal censuses, or measure performance. General #253 remains open; general #230 failure ownership remains and full #179 is user-deferred/open.

Leave the application PR and prerequisites open for acceptance. Shared identities, libraries, saved access and update channels are preserved. No Stable tag/release or Obtainium publication. [Move failure feedback checkpoint](2026-10-07-move-failure-feedback.md) and [parent saved-access receipts](2026-10-07-saved-access-and-budgets.md) record earlier boundaries.

Current author owns only `codex/saved-inventory-census` in its dedicated persistent worktree. Allocated audit Claude is idle after a normal completion, last97% five-hour used /48% weekly; those are dated readings, not a reset or a successor allowance. On resume refresh live checks, current heads/main and user QA. Final receipt should leave the worktree clean/pushed/idle; no private databases, session logs or audio are uploaded.

First CI attempt Android700 at5cc70e8 compiled and ran700 debug tests; one new fixture failed before assertions because DefaultDownloadIndex appends its name into an SQL identifier and the fixture used a hyphen. Rename only that fixture index to removing_alias, preserving the removing row ID and all assertions. The final replacement head/run still needs both variants and artifact verification; the failed run is not a pass.
