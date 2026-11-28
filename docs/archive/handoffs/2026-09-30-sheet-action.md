# Song-sheet cancellation checkpoint

Issue #216, branch codex/sheet-action-cancellation, main baseline 792df8c2d9ad09938630e9b516b9b90bbf536ca7. GPT-6 / Codex desktop; effort not reported. Authored fix/tests; self-review only.

SongActionsSheet.choose no longer runs side effects from invokeOnCompletion. It waits for hide in the same coroutine, checks active context and hidden state, then dismisses and performs the action. A choosing guard prevents repeated taps from launching overlapping hides. Cancellation/failure/refused hide performs no selected action. Five coroutine tests exercise success ordering, cancellation while suspended, cancelled-context return, refused hide and failure. No new dependency. Android compile/tests first run in CI, not locally.

Pinned published Google Maven Material3 Android 1.4.0 source SheetDefaults.kt checked: hide throws cancellation for interruption and can return without transition when confirmation refuses. The experiment's SongActions.kt matches inspected main; forward integration needed after a reviewed main landing. This fix does not address queue occurrence identity or Undo after replacing a queue.

Manual QA pending: choose each queue/download/navigation action normally; interrupt hiding with a drag/Back; background or leave the screen mid-hide; tap twice quickly. Expected: successful actions occur once; cancelled hides do not act later; retry works. Keep PR open for user QA and final-head checks. Known artifact-upload quota may prevent a downloadable build. No device/Claude/experimental checkout used. Exact CI/head evidence belongs on the PR.
