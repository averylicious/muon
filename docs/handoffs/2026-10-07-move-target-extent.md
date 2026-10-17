# Move destination extent boundary — October 7

Source baseline #370 `97e2fa0b96032374a84c604c1e7ccccf82406338`, incorporating main `89bb29acc098d98250b33f062284ea0b48012c96`. GPT-6 / Codex desktop; exact model variant and effort not reported. Implementation and source self-review, not independent review. No device or experimental access.

## Confirmed gap and change

`OfflineStore.copy` previously returned early for an indexed, completed, byte-equal destination without checking its spans beyond the expected length. `completeMovedCopyNow` likewise compared only `[0, length)`, so a trailing fragment introduced after hand-over could survive the comparison while source removal was requested. The ordinary unindexed preflight already checked extents; these other paths did not. This is source-supported, not a user-file loss reproduction.

Both paths now refuse a destination with spans outside the expected positive length. Copy also checks again after CacheWriter and before changing content-length metadata. Refusal preserves source/index/target bytes; it never truncates or deletes an uncertain fragment. Two controls use actual OfflineStore calls, native indexes and SimpleCache: a completed recorded destination with extra bytes, and a late disjoint fragment after publication. Existing successful move/removal controls remain.

These are snapshots. A writer can still race later; no global drain, cache/index transaction or failed-copy ownership/cleanup is implemented. Unindexed partial bytes left by a failed copy are still retained. #230 remains open for those outcomes and normal move acceptance. Full #179 recovery stays a separately user-deferred follow-up; GitHub currently marks #179 CLOSED (October6), which does not establish recovery. Earlier dated checkpoint wording saying open was inaccurate.

## Verification and handoff

Actions is the first Android compile; exact-head results/artifact belong on the PR. No local Android build, phone QA, measured performance gain or Stable release. This branch includes the open acceptance stack; leave it OPEN for its inherited gates. Next source-only slice: prevent oversized retained metadata from being decoded before its existing display budget. No new Claude assignment: allocated audit session was idle, last97% five-hour used, not confirmed exhausted.

Initial Android704 compiled and ran702 debug tests but failed the new late-fragment fixture before assertions: SimpleCache refused committing beyond its known length. The fixture now explicitly unsets length before the late write and restores the prior value afterward, as a stale metadata interleaving; all source-preservation/no-remove assertions remain.704 is not a pass; replacement CI required.

Further source review found the symmetric source-length gap: a stale shorter source length could publish only its prefix and remove retained bytes beyond it. Both shelves now need extents within the expected length before copy/completion and after comparison. Two additional controls preserve an oversized source and a late source fragment after publication. This supersedes target-only wording above; four new controls total. All extent checks remain snapshots, not atomic writer ownership.
