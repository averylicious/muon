# Move copy failure feedback — October 7

Scope: one continuation of #230, based on combined candidate #302 `4921c68dc7290ea43f5f287da1c908ff67a62219`, including main documentation `3291aa3a7df74e45829bdcdfa3c71fef14c9b6c4`. GPT-6 / Codex desktop / exact variant and effort not exposed authored and self-reviewed this slice. Allocated Claude remains idle after its saved-access work; no new Claude assignment or phone/experimental access.

## Finding and change

`OfflineStore.move` runs the real `copy` through `runCatching`, but failed copies were not counted in the final preservation notice. Progress cleared without saying why an attempted move produced no destination entry. The existing `kept` count covers ownership refusals, not copy/preflight/read failures.

The loop now separately counts permitted attempts that fail copy/preflight. One final message states the failure count and that the saved entry was kept. Canceled/unpermitted moves are not counted as copy failures; successful copies continue the same tracked publication path. No new deletion, repair, span adoption, network request or index mutation is added. The message describes entry preservation, not proof that already-missing source bytes are intact.

## Verification boundary

The existing real missing-later-source-span case now asserts the delivered failure message alongside no destination Add/index row and retained source bytes. Two new actual OfflineStore/native-index/SimpleCache cases cover:

1. A target prefix identical to the source passes preflight, then a later missing source span fails the actual copy. Existing prefix and source entry remain, no Add is posted, and the failure is reported. Partial new target spans may remain; no cleanup claim.
2. Two completed source rows whose cache length/bytes cannot be read produce one plural count, no destination commands/spans, and both saved entries remain. Separate keys avoid alias refusal masquerading as an attempted failure.

CI is the first Android compile/test; record its exact head and actual report before claiming a pass. No device validation or local Android build.

## What remains

General #230 spans/late-writer ownership and accounting remain unresolved. Partial copies can leave unindexed bytes; blanket removeResource/removeSpan is unsafe without current writer/ownership proof. Existing #213 fresh-name producers prevent new explicit saves from adopting an old key merely because a live track ID matches, but this does not make cache/index/file operations atomic or clean leftovers.

This branch contains the open application acceptance prerequisites. Leave its PR and #302/#365/#366 open for user QA; do not merge a child alone. Full #179 recovery remains user-deferred/open. Broader #253 resources/measurement and hardware gates remain. Normal test build will update Canary, not a separate app or Obtainium release; do not install it without current device authorization. No Stable release/tag.

Portable parent evidence: [saved-access/budget checkpoint](2026-10-07-saved-access-and-budgets.md). Before takeover refresh live heads, checks and ownership. Current author owns only the dedicated `codex/move-failure-feedback` worktree; once a final receipt is recorded, leave it clean/pushed/idle. Latest Claude allowance is a dated97% five-hour /48% weekly reading, not exhaustion or a successor allowance.
