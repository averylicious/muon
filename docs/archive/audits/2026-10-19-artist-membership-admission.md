# Admit expanded artist memberships before grouping

Cumulative #617 624fa3630fa9bbb4ff72fd5a066e5fc143f65bae; #253 memory follow-up. GPT-6 / Codex desktop, exact variant/effort not reported; implementation/source self-check, no Claude or independent review.

## Source-supported risk

A bounded song record can contain many semicolon-separated performers. Grouping expands each distinct per-track credit into a group member, so50,000 records/16MiB encoded metadata alone did not bound derived membership near the record count. Many short names can produce substantially more group/list/key objects; cancellation and lazy splitting reduce temporary work but not the final graph. No phone OOM or concrete attacker/external exploit is claimed.

## Admission

LibraryLoadLimits now also permits at most100,000 artist-credit memberships across retained playlist entries, counted on IO before publication using the exact shared grouping parser: semicolon-only split, trimmed/case-insensitive per-track dedup and one unknown-artist fallback. Every occurrence counts, including repeated IDs/objects and retained failed-playlist lists, conservatively bounding the unique Songs projection without a second global retained ID map. Up to two credits per entry fit at the50,000-entry boundary; smaller libraries can have more. All current playlist/record/per-record/aggregate encoded limits still apply.

An over-budget library is refused whole through the existing resource-limit path, preserving the previously published library and its grouping/selection. The message names the new artist-credit limit. No silent prefix, dropped credit, partial publication or erased saved copy. Failed add batches commit neither byte/count nor credit counters; failed final fallback assembly publishes nothing. Existing immutable load-owner/fallback assumptions remain. This bounds logical cardinality, not byte-exact heap/native/Binder/process memory; large names, final group graphs, sorting/artwork and other consumers remain separate.

Two focused controls cover exact parser semantics/unknown fallback, failed-batch rollback, zero/invalid limits, repeated occurrences, a retained failed playlist exceeding the limit, successful full retry and ignored unlisted maps. Existing full grouping/HTTP/encoded/entry/fallback controls stay. CI is first Android compile/tests. Selected device acceptance should use synthetic fresh and retained-credit boundaries, ensure old Songs stay after refusal, then test normal artist/album/search navigation. No new data schema/dependency/cache or activation/release behavior; app stack stays OPEN. The new logical limit requires acceptance, not an assertion that all #253 memory work is complete.
