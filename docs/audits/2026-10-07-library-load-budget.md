# Aggregate library refresh budget (#253)

Implementation base: `448a9ba641caa647fe11b9c098a2db2f923a3de0` (combined #302, including main `718a0bb` and pending #362/#363). Author: GPT-6 in Codex, implementation and self-check; no independent review claimed. GitHub Actions is the first Android compile. This branch contains the pending app acceptance stack and cannot be merged independently of that stack's gates.

## Boundary

`TauonApi.playlists` refuses more than 2,048 playlist records; `TauonApi.tracks` refuses more than 50,000 track records in one response, after JSON parsing but before projecting the entire array into Kotlin records. The existing wire-body cap, JSON parser guard and per-track metadata cap remain.

`LibraryModel.load` checks the aggregate records retained by the whole refresh: at most 50,000 playlist-entry occurrences and 16 MiB of encoded song records plus UTF-8 playlist identifiers/names. Repeated IDs and shared songs count again in each playlist. `LibraryLoadBudget` runs serially on IO, with a character preflight before encoding. A rejected batch does not consume its counters.

The combined result is checked again before publishing so a previously loaded playlist retained after a failed request cannot bypass the aggregate cap. `LibraryResourceLimit` aborts the refresh with a readable error, without silently truncating songs or replacing the current library. Ordinary partial-load fallback, playlist ordering and complete metadata remain within the limits. Cancellation and the existing load-owner checks are preserved.

## Verification

Nine new regression cases exercise count boundaries, repeated objects/IDs, UTF-8 and NUL-safe encoded expansion, failed-batch retry, retained failed-playlist accounting, and real HTTP/JSON ingestion followed by retry. Existing library-load, metadata, parser and cancellation tests remain. CI and actual result counts are pending; no local compile, device or performance claim.

Manual acceptance is pending: normal library/refresh retains ordering, metadata, partial-load retry and current screen state. A resource refusal should show an error and keep the last loaded library. The selected capacities are conservative policy bounds, not measured maximum device capacities; compatibility with a user's very large libraries needs acceptance testing.

## Remaining #253 scope

This is not a byte-exact heap limit. JSON DOM creation still occurs before the count guard; a wide array can expand memory within the existing body cap. The prior library remains allocated during refresh, and an already oversized prior library is preserved rather than discarded. Saved-index enumeration, aggregate Binder transactions, native decoder/caches, actual peak heap and UI/frame performance need separate evidence. No saved audio/index bytes, device settings, experimental branch or release policy are changed.
