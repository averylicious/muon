# Startup scan executor lifetime — 2026-10-04

Inspected main `ae25fe2149040879f10f95760135a90249db298d`, branch `codex/bootstrap-executor-lifetime`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Implementation and self-check, **not independent review**. Not compiled locally: Android CI is the first compile. User QA pending; the PR stays open.

## Finding

`OfflineStore.watch` (`OfflineStore.kt`, called once per shelf from `create`) ran its one-shot startup index scan with `Executors.newSingleThreadExecutor().execute { … }` and kept no reference to the executor, so it never explicitly shut down. Its idle worker thread was left to the platform; finalization of an unreachable wrapper may eventually shut it down.

This is an **explicit resource-lifetime fix**. No memory, thread-count or jank effect was measured, and no claim is made that the thread leaks forever.

## Change

The executor is kept in a local `bootstrap`, the existing task is submitted unchanged, and `bootstrap.shutdown()` runs in a `finally` after submission, including when submission throws.

- **`shutdown()` is orderly:** it refuses new tasks but lets the already submitted scan run to completion and post `known.forEach(store.record)` to the main thread, as before.
- **Not used:** `shutdownNow`, waiting on the main thread, and any deadline.

**Unchanged:**
- the listener registration and the cross-shelf leftover removal;
- the scan itself and its `runCatching`;
- `Handler` posting and its ordering;
- the persistent `copier`, `mover` and `artwork` executors;
- reconnect and card behaviour.

## Tests

No new test. A test here would only mirror the JDK's `shutdown()` semantics, and there's no seam to exercise `watch` without new abstractions.
- **CI:** the existing unit suite plus the CI compile.
- **Manual QA (pending):**
  1. Launch with existing downloads: the offline library, download marks and Settings counts appear as before.
  2. Do the same with a card inserted, if available, using non-destructive checks only.
  3. Start offline: downloaded songs still list and play.

## Follow-up

#302/#240 replace the same scan with newer-event bootstrap handling (`changed` filtering). When #302 is refreshed, apply the same local-executor-plus-`finally`-`shutdown()` around #240's version, and keep #240's newer-event filtering and `changed = null` cleanup. Don't take either side wholesale.
