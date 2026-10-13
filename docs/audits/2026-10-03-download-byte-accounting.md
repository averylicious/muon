# #253 download byte accounting — 2026-10-03

Inspected main `c357694c64fde20762e7a88de44c5ab03c8feecb`, branch `codex/download-byte-accounting`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Implementation and self-check, **not independent review**. Not compiled or run locally: CI is the first compile and test run. Manual QA pending.

## Finding

`OfflineStore.create` keeps finished download sizes in a `HashMap` (`sizes`) and recomputed `sizes.values.sum()` in both `record` and `removed`. `watch` posts each shelf's known downloads to the main thread and records them one at a time (`known.forEach(store.record)`). So N finished downloads cost on the order of N² additions at startup, and each later change costs O(N).

This is source evidence only. **No timing, heap or jank was measured.** #240's ownership fix is separate and still open.

## Change

**New `DownloadByteTotals`:** a per-id size map plus a running total:
- `put` replaces an id's size and adjusts the total by the difference;
- `remove` subtracts a known size; an unknown id is a no-op.

**`OfflineStore.create`:** only the two call sites changed:
- `record`: `put` for finished downloads, otherwise `remove`;
- `removed`: `remove`;
- both assign `DownloadMarks.bytes = sizes.total`.

**Unchanged:**
- the cross-shelf guards (an early `return` while another shelf holds the song complete) and every ordering and scheduling decision;
- the semantics: one entry per id, so a song on both shelves counts once, at its most recently accepted completion;
- overflow behaviour: Kotlin's `Iterable<Long>.sum()` wraps, and `total += new - old` with Long arithmetic gives the same result modulo 2^64, so the total matches the old sum exactly, with no new caps or validation.

**Cost:** the map is still O(N) entries; each update is O(1) expected. No bound on overall memory is claimed.

## Tests (`DownloadByteTotalsTest`; CI pending)

Pure JVM tests against an independent reference, a map summed in full after every change (the old code):
- replacements, unknown and repeated removals, zero sizes, and a finished → requeued → finished transition;
- overflow wrapping identical to `List<Long>.sum()`;
- a seeded 5,000-step mixed sequence over 40 ids with sizes from 0 to `Long.MAX_VALUE`, compared after every step.

The mapping from download state to put or remove at the call sites is unchanged and checked by reading the source only.

## Manual acceptance (pending user QA)

1. Launch with existing downloads: Settings shows the same song count and total size as before.
2. Download a song: the total grows by its size once it finishes.
3. Remove a download: the total shrinks by that size.
4. Move downloads to the card and back: the total is unchanged throughout, and doesn't count songs twice mid-move.
5. Use offline mode with downloads: the totals and list look right.
