# #253 saved runtime paging boundary — 2026-10-08

Based on verified #398 `ac5d46cf7172719911f3cf581ee6ff2b35a04253`, including main `2ee2da7091e14f9d98cbb8cfd2bae77ad3c22b59`. Worktree/branch: `muon-saved-runtime-paging-oct8` / `codex/saved-runtime-paging-oct8`. Main audit only; user experiment unchanged. GPT-6 / Codex desktop (exact variant/effort not exposed): implementation and author self-review. Claude explicitly unavailable this cycle and not resumed. No independent review claimed.

## Runtime change

LibraryModel no longer calls savedEntries/savedLibrary or retains the full decoded saved list. SavedPaging rebuilds the existing private catalog from the streaming native projection, then hydrates selected locators only. The model keeps at most four50-row display pages with generation-tagged LRU; current page shares its references. The saved screen has accessible Previous/Next and range/count plus Refresh, with no permanent library ceiling. New page starts at the top; same-page refresh retains LazyColumn scroll. Catalog order/title metadata remains exact and unchanged.

Offline entry and failed-connect fallback use the streamed playable count and known-origin census, not a full saved list. Worker-serialized page/rebuild/queue operations check coroutine cancellation; errors retain the last good page with an explicit refresh-needed message rather than exposing partial shelves. Rebuilds invalidate cached generations. Missing/stale locators refuse hydration/queue, never rebind to another displayed copy. Removal still uses OfflineStore's existing fresh whole-owner safeguards, never page membership.

Saved playback streams ordered pages off main into the existing2048-item/4MiB logical queue budget. Full complete-library order within budget; above it explicit Play this copy/Cancel, with the single copy freshly resolved. Refusal does not apply a prefix. Screen closure, newer request, refresh/disconnect or changed player timeline/current item/shuffle blocks stale application. No shuffle/repeat policy change.

## Tests and limits

First real Android compilation/tests/lint are GitHub Actions; local diff checks only. New native SQLite repository fixtures cover every copy/exact order,50-row hydration,4-page LRU/generation reset, source failure/cancellation rollback, missing/stale rows, full queues across pages,2051-copy refusal/single selection and incomplete single refusal. Actual LibraryModel/Media3 index/cache fixture checks75 rows through pages and refresh clamping while preserving fixture bytes. CI outcome must be recorded at the final head; test expectations alone are not pass evidence.

Remaining #253 costs: full hidden-owner/name maps, native cache key/span snapshots, compact origin census, STOPPED manager Download lifetime, remaining live/service queues and raw native row windows. Queue preparation deliberately scans full hidden-owner evidence per hydrated page; it bounds decoded-page residency, not scan CPU or all process memory. No phone heap/performance measurement, UI/TalkBack/manual acceptance or guarantee against external writers is claimed. #253 remains REQUIRED before Stable. #230's defined engineering boundary remains complete but UAT pending; full #179 and release-trust reviews remain separately deferred.

## Acceptance and recovery

Leave this and inherited application stack OPEN for acceptance. Do not merge overlapping ancestor PRs blindly. Provide final-head run/version/artifact/test evidence on the PR, then update canonical main handoff separately. User authorized POCO USB/root analysis in this cycle if needed; no historical permission assumption. No device test has been performed at this source checkpoint.

Manual checks: traverse all saved pages, including unknown/incomplete/card/played rows; refresh while scrolled or after removal; switch pages/back/reopen; tap a copy outside page1 and verify full queue order within budget; over budget offers a single/Cancel with old playback unchanged; refreshing/closing/changing queue during preparation cannot override newer playback. Check TalkBack page controls/range, and original saved metadata/bytes remain intact.
