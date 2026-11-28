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

## User QA and follow-up (same day)

The user's phone QA of `0.1.0-canary.153` confirmed the Artists position is kept, but reported that
the greeting ("Your music, nearby.") unfolds again on return, pushing the kept row down the screen.
That was this slice's recorded limit. The follow-up commit keeps the fold too:

- `LibraryTop` takes its `TopAppBarState` from the caller, and `MuonApp` keeps it
  (`TopAppBarState.Saver`).
- The fold is shared by Songs, Artists and Playlists. If only the fold were kept, a list that still
  reset would come back at its top under a folded greeting: the reverse of the reported jump. So the
  Songs and Playlists positions are now kept as well. This is the Playlists follow-up this handoff had
  recommended.
  - `PlaylistRows` takes its state, with a separate placeholder list, as `ArtistRows` does.
  - `TrackList` gains an optional `state`, used only by its real list and never by its placeholder or
    empty message. Search and detail pages keep their own states, as before.
- Disconnecting resets all three lists and the fold.

The user also reported that no scroll bar is visible. Compose's lazy lists have no built-in scroll bar
on Android, so this needs its own design decision, and it is not part of this slice.

## Limits

- If the process is killed and restored, the index, offset and fold come back, but the library
  reconnects asynchronously.
- Switching chips keeps each list's own position, but the fold is shared. For example, if Songs is
  deep and Artists is at its top, switching to Artists shows it under a folded greeting until you pull.
  This is unchanged in kind: the fold was already shared between chips.
- No automated test covers this. The behaviour is inside Compose's lazy layout and Material's app bar.
  The repository has only JVM unit tests, with no Compose UI test dependencies and no device use. The
  evidence is the source reading above, the CI compile, and manual QA below.

## Manual QA (user)

1. Artists: scroll well down, stopping part-way through a row, then open an artist. Press Back (the
   button, and the system gesture separately): the same row is in the same place on screen, and the
   greeting stays folded.
2. Rotate on the artist page, then go back: the list returns to the same place.
3. On an artist page, pull to refresh, then go back: same place. If a refresh removed the rows above,
   the list shows the nearest remaining position without jumping to an empty area.
4. Switch to Songs and back to Artists, or to another tab and back: each list keeps its place.
5. Playlists: scroll down, open a playlist, then go back: same row, same place, greeting still folded.
6. Scroll back to the top of a list and keep pulling: the greeting unfolds first, then a further pull
   refreshes, as before.
7. Disconnect and reconnect: every list starts at the top, with the greeting unfolded.
8. With TalkBack, after returning, focus and reading work normally.

## Status

Clean and idle once pushed. Next: the artist page transition slice, only after this slice's CI,
PR and checkpoint are recorded.
