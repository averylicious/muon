# Original Move dispatcher preparation — 2026-10-12

Cumulative successor to #523 `9d14c28ae65eb8fe82d059e7e9b1b3d5dc4ea75b`. That head is verified by [Actions .1025](https://github.com/averylicious/muon/actions/runs/38265314290): 1458 tests per variant, no failures/errors/skips; lint zero errors/69 warnings; actual downloaded APK checksum, certificate, package, nondebuggable/export policy verified. Artifact [app-debug-9d14c28ae65eb8fe82d059e7e9b1b3d5dc4ea75b](https://github.com/averylicious/muon/actions/runs/38265314290/artifacts/11754369444), SHA-256 `bb75d8b416f8d8f231b2d0282a3c0e610f54d1ec296c590227e6601dd60f8a0c`. Earlier cancelled/superseded runs are not passes.

GPT-6 / Codex desktop implementation and source self-check (exact variant/effort not reported); no independent review, Claude or phones. Main audit track; experiment untouched. Partition mode remains disabled. Full original Delete/Move support before activation remains the user's requirement.

## Change

The existing main-thread Move admission/exclusion and serial worker dispatch prepared partition shelves to a bounded name selection instead of the legacy SimpleCache getter. The census and actual cursor close before any exclusive Move stage. Only supported completed originals are copied/published/retired; fresh destination IDs and fixed byte/status receipts update the existing main-thread callbacks. Mixed storage backends refuse; new-partition-source Move remains engineering. A failed or interrupted stage preserves recoverable original/destination ownership and stops with a recovery notice; no new executor, request history or automatic recovery.

Publication and source removal also recheck the other actual shelf's scalar index/catalog/journal/raw legacy names under the borrowed same-gate exclusive permit. A caller's claim cannot override an actual alias. This does not acquire a nested shared lease or hydrate native/raw metadata. Unknown actual cursor ownership quarantines the gate and retains its owner.

New real-shelf JVM tests exercise the public OfflineStore.move dispatcher, scalar main-thread publication, source alias refusal, and a destination alias added after Pending (including renewed recovery after removing that alias). Local diff check passes. Actions is the first Android compile; latest-head result must be recorded on the PR before relying on it. No physical-card/performance evidence is claimed.

## Remaining engineering / activation gate

New-partition-source Move, complete supported original disposition (including nonterminal/unverified rows and obsolete old read-only native metadata), persisted production Fresh/Resume routing and explicit opt-in/recovery controls, and full lifecycle/failure coverage remain. Optional played-copy LRU make-room is conservative admission today; #401 saved-card startup/device evidence and inherited acceptance remain separate. Do not force preferences or enable partition mode for user testing yet. Application stack stays open for inherited acceptance; no Stable release/tag.
