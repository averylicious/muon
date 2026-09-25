# Handoff: keep the Artists list position across an artist page

2026-09-25. First of two user-requested frontend slices for this cycle. Astra is unavailable for
review, so the PR stays open for Astra. Implemented by Claude Opus 5.5 (`claude-opus-5-5`) in
Claude Code; effort not reported for this cycle.

Branch `codex/artist-list-scroll`, worktree `/home/avery/.codex/worktrees/muon-artist-scroll/muon`,
based on main `a4501851b5bb5d8327cf23b24a79372a6ae71c9b` (#104 merged). No dependency on other open
PRs.

## Cause

`ArtistRows` created its `LazyListState` inside `LibraryTop`. Opening an artist replaces
`LibraryTop` with the artist page, so the list leaves composition and its remembered state goes with
it. Back then created a fresh state at the top.

## What changed

- `MuonApp` keeps the Artists list state (`rememberSaveable` with `LazyListState.Saver`), so it
  survives the artist page, rotation, chip switches and tab switches. Disconnecting replaces it, so
  the next server's list starts at the top.
- `ArtistRows` takes that state. Its loading and empty message is a separate `LazyColumn` with its
  own state. Otherwise, a brief loading moment while a refreshed library regroups would measure the
  kept state against a single row and clamp the position to the top.

## Refresh and removal (Compose foundation 1.9.3, checked in the pinned sources)

- On its first measurement with items, the list looks for the previously first visible row by key
  (`LazyListScrollPosition.updateScrollPositionIfTheFirstItemWasMoved` →
  `findIndexByKey(lastKnownFirstItemKey, index)`). Rows are keyed by artist key, so a refresh that
  moved the artist restores it.
- If the artist was removed, the saved index is kept, and measurement clamps it to the new last row.
  Nothing crashes or points at a missing item.
- `LazyListState.Saver` saves only the index and offset. After rotation, the index is restored, and
  key-following resumes from the next measurement.

## Limits

- The library header's fold is not kept. `LibraryTop` still creates its `TopAppBarState`, so on
  return the greeting is expanded above the restored list: the same row and offset within the list,
  but lower on screen by the header's height. Keeping the header too means hoisting one state shared
  by Songs, Artists and Playlists, which is best done together with the Playlists fix below.
- **Playlists has the same problem.** `PlaylistRows` also creates its list state inside `LibraryTop`,
  so returning from a playlist starts at the top. It needs a separate fix of the same shape: hoist
  its state, and give its empty or loading message its own state. That fix, plus hoisting the shared
  header state, is a small follow-up. It was not done here, to keep this slice to what was asked.
  Songs also resets when switching chips or tabs, for the same reason.
- If the process is killed and restored, the index and offset come back, but the library reconnects
  asynchronously.
- No automated test covers this. The behaviour is inside Compose's lazy layout, and the repository
  has only JVM unit tests, with no Compose UI test dependencies and no device use. The evidence is
  the source reading above, plus the CI compile, and manual QA below.

## Manual QA (user)

1. Artists: scroll well down, stopping part-way through a row, then open an artist. Press Back (the
   button, and the system gesture separately): the same row is at the top of the list at the same
   offset, with the greeting expanded above it.
2. Rotate on the artist page, then go back: the list returns to the same place.
3. On an artist page, pull to refresh, then go back: same place. If a refresh removed the rows above,
   the list shows the nearest remaining position without jumping to an empty area.
4. Switch to Songs and back to Artists, or to another tab and back: Artists keeps its place.
5. Disconnect and reconnect: Artists starts at the top.
6. With TalkBack, after returning, focus and reading work normally.

## Status

Clean and idle once pushed. Next: the artist page transition slice, only after this slice's CI,
PR and checkpoint are recorded.
