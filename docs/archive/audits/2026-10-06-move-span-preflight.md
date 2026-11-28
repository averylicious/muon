# Check retained target fragments before a move — #230

Application base: 7aa2ec225b8a8d7fb2b549e96d8301c04afd8856, current main c4d48a145421ffde5ebff4cadc0e8cb400cdd059 plus open #302 at 54f2cd390cd8e364cebc0a11c0be0c7c4ebe6521. Implementation and source self-review: GPT-6, Codex desktop; exact variant and effort not exposed. No independent review of this authored patch is claimed.

## Defect and behavior

OfflineStore.copy uses CacheWriter to fill holes around existing destination spans. The existing post-copy comparison rejects a mismatched destination, but only after source bytes may have been appended around unrelated older fragments.

Before CacheWriter starts, compare every existing destination span with the corresponding source range using the existing bounded, cache-only sameBytes readers. Refuse a mismatch or destination bytes extending past the complete source length before appending any source bytes. Preserve both existing destination fragments and the source; do not send an Add command. Matching prefix or interior fragments can still be completed, and the full post-copy comparison remains required.

This keeps the current request/cache keys, cover names, move publication/removal fence and successful move behavior. An empty target adds no preflight payload read. A partial target requires reading its existing spans from both caches before copying. Those reads are bounded to 64 KiB buffers and run on the existing move worker; the added I/O cost is unmeasured.

## Verification

Three added real-cache/index characterization cases cover a conflicting interior fragment without filling its holes, a matching interior fragment that still completes, and a longer pre-existing target preserved without truncation or publication. Existing conflicting-prefix and later-block mismatch checks now require the destination to remain its original bytes. Source bytes and index records are checked separately. Existing missing-later-source-span and removal controls remain.

The coordinator checked the matching published Google Maven media3-datasource 1.11.0 sources: a CacheDataSource factory with no upstream has no write sink. Git diff whitespace checks passed. No local Android compile or tests were run; GitHub Actions is the first compile/test execution. Latest-head receipts belong on the PR.

## Boundaries and remaining work

This is an actual pre-write rejection fix, not full #230 completion. Preflight is a snapshot: a late writer can change the destination afterward. The post-copy check still catches a differing final result, but does not prevent every concurrent or mid-copy mutation. A failure after copying starts can still leave partial output. Cache reads can touch metadata or reconcile stale spans; they are not a missing-volume preservation guarantee. Closing readers is not a supported owner-drain receipt.

Do not delete unindexed fragments to pass the check. Safe staging, owner/admission coordination across explicit downloads, played-copy work, moves and cover work, and eventual publication/removal remain coupled to #179 and #213. Byte comparison is not proof that a reused server ID names the original saved audio. The approved #213 policy remains streaming uncertain live entries with separately accessible Unverified saved copies.

Leave this PR open: its ancestry includes the unmerged application candidate #302, and real phone/move acceptance remains pending. The user experimental checkout and ordinary phone apps were not changed by this slice.
