# Superseded grouping work and transient retention

Cumulative #616 58fc0880c35099210933f6bf4dbb9d4d51225df7; #253 memory follow-up. GPT-6 / Codex desktop, exact variant/effort not reported; implementation/source self-check, no Claude or independent review.

A new LibrarySnapshot already causes currentArtists/currentAlbums to hide the old groups, but their state holders kept those old graphs throughout replacement grouping. The effect was cancellable, yet its CPU-only groupArtists/groupAlbums loops had no cooperative cancellation checks, so a replaced job could finish traversing/materializing its old library before cancellation was observed at the context boundary. These are source-supported unnecessary lifetimes, not a reproduced phone OOM or a heap/latency measurement.

The existing effect now clears already-hidden group holders before replacement work. It supplies the actual current coroutine context's ensureActive checkpoint to both grouping helpers. Source traversal checks even duplicate IDs; artist splitting checks each token and group-result projection also checks cancellation. Full final groups remain published together only after both traversals succeed; cancelled jobs cannot publish a partial replacement. Existing snapshot/server guards, loading/selection behavior and default callers stay intact.

Both helpers lazily deduplicate input instead of allocating another full unique input list. Artist credits lazily split/trim/filter/deduplicate instead of building the intermediate split/map/filter lists. Semicolon-only splitting, first spelling/order, case handling, empty fallback, duplicate tracks and complete result membership remain unchanged. trackArtistKeys still returns a materialized list. Per-track distinct sets, final groups/members and result lists remain; no whole-process memory ceiling or speedup claimed.

Existing grouping/album/artist/credit/locale cases remain. One actual coroutine Job cancellation control, run for both factories, cancels while reading a duplicate-ID input and requires traversal to stop before reading the rest. CI is first Android compile/tests. No Compose UI test; repeated wide-library refresh/artist/album/search/page acceptance still needs selected runtime testing. Many distinct credits per track can still amplify derived membership; source admission remains a separate #253 question, not cleared by cancellation.

No dependency, storage/settings/API format, activation or release change. App stack stays OPEN; no main/experiment/Stable merge or phone command in this source boundary.
