# Reject stale move publication — 2026-09-30

Narrow partial fix for [#230](https://github.com/averylicious/muon/issues/230), based on main fbfeaa21f751b30884205e210865bcb0ee4ffed4. The real production characterization in #233/run390 proved late Add after Remove/RemoveAll and partial unindexed target bytes. This slice rejects only stale publication; it does not claim safe partial-copy cleanup or a complete #230/#179/#213 solution.

## Change

Register ownership when move is requested, before worker/index snapshot. Remove IDs invalidates those IDs in all older active batches; RemoveAll invalidates all older active batches. A pre-copy check skips already-invalid work, and the main completion checks ownership again before sending Add. Invalidation and final command publication share a synchronized ordering boundary. A later explicit move receives fresh ownership, so removal does not create a permanent ban. The worker posts finish after its callbacks, retaining ownership until they drain and releasing per-batch state afterward. Existing FIFO mover, progress, copy, normal source retention and completion-driven source removal remain.

A running copy is not cancelled at the socket/file-read level. It may leave target spans; do not delete them blindly because another legitimate download can share that key. #230's partial cleanup and concurrent destination ownership remain open, alongside #179 SD identity/lifecycle and #225 scheduler/backlog. No cache/index format or identity migration, storage release or dependency change is made.

## Verification

Five production fixture cases are retained; the two removal expectations now assert no late Add. Exact-byte control and partial/unknown-input cases are unchanged. Three focused ownership tests check multiple older batches/per-ID invalidation, fresh explicit intent after RemoveAll, and finished-batch rejection. CI is the first real compile/execution; record actual final-head results on the PR. No local Android build or phone QA is claimed.

Pending user QA for the eventual successful artifact: normal move keeps original until target completion; removing one song during a move does not resurrect it while other songs proceed; RemoveAll during a move does not repopulate the list; a deliberate later download/move still works. Use disposable copies/authorized storage when appropriate. This is not authorization for this agent to access a phone or experiment.

Attribution: GPT-6, Codex desktop, effort not reported (Sol); implementation/source self-check, no independent review. Leave this app PR open for the user's phone QA even when CI succeeds.
