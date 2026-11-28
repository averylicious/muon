# Handoff: Artists browsing (#44 collection slice)

2026-09-25. A bounded frontend slice assigned by Astra as sole coordinator. Astra accepted #58's
grouping contract as the current implementation decision, under the user's broad trust rather than
historical user approval, and fixed the routine design choices listed below. Implemented by Claude Opus 5.5
(`claude-opus-5-5`) in the Claude Code CLI at medium effort. Astra keeps review, CI and merge.

Branch `codex/library-artists`, worktree `/home/avery/.codex/worktrees/muon-library-artists/muon`,
stacked on #58's refreshed head `33be7104fa736a489f44cb3e3d13484dd76dcdc1`. #58 has since merged to
main as `adf801a3c887d5dc36336f2b695442d5a8d8ff67`, a merge commit containing that head, so the PR
diff against main is this slice alone (`33be710..codex/library-artists`).

## What changed

- `LibraryView` is now `Songs, Artists, Playlists`, and the chips follow it. No Albums chip or
  placeholder was added. The stored view still falls back to Songs for anything unknown. Only the
  top-level view is persisted.
- `LibraryArtists.kt` (new) holds the pure helpers:
  - `artistInitials`: the first code point of each of the first two whitespace-separated words,
    uppercased independently of the device locale. A single word gives one code point.
    Words split on the same whitespace `isBlank` recognises (including no-break and ideographic
    spaces). The uppercase mapping is the simple one, so each initial stays one code point.
  - `artistLabel`: a blank name reads as "Unknown artist".
  - `artistSongCount`.
  - `artistTone`: one of three tonal container pairs, taken from the stable key.
  - `LibrarySnapshot`: one library snapshot, equal only to itself (list identity). It is both
    the grouping effect's key and what `currentArtists` checks, so the two use the same equality.
  - `currentArtists`: returns groups only for the snapshot and server they were made from,
    otherwise null.
  - `storedArtist`: None, Wait, Open or Discard, on the same terms as a stored playlist.
  - `artistPageShown`: whether the artist page is showing (connected, and the saved artist is open
    or waiting).
- `LibraryScreen.kt`:
  - `ArtistRows` shows a monogram avatar, the full name and a song count. While grouping runs it
    shows "Loading artists…". An empty result has its own message and can still be pulled to
    refresh.
  - The avatar is decoration for TalkBack (`clearAndSetSemantics`), so the row reads the full name.
  - A blank name shows the generic artist icon.
  - The avatar grows with font scale.
  - `PlaylistBar` gained a `backLabel` parameter, so the artist page says "Back to artists".
- `MuonApp.kt`:
  - The library's flattened tracks are held as one `LibrarySnapshot`, remembered per
    `model.tracksByPlaylist`. `all` is its track list, so search and Songs behave as before.
  - Artists are grouped once per snapshot (`LaunchedEffect(snapshot, origin)`) on
    `Dispatchers.Default`, and the result is labelled with that server and snapshot. A newer
    snapshot cancels an unfinished grouping, and results are never recomputed per composition.
    Disconnecting drops the groups.
  - The open artist is kept as a saveable server and key pair (plus its name, used only as the
    page title while it waits). It is cleared on disconnect, when the server changes, or when the
    current snapshot's grouping lacks it.
  - While the current snapshot is still being grouped, a saved artist waits: its page stays
    showing with placeholder rows and no count, and Back closes it for good. It is not
    discarded just because grouping is still running.
  - The interim detail page reuses the playlist bar, `LibraryPane` (pull-to-refresh) and
    `TrackList`, and plays through the existing `startQueue`, so playable filtering and queue
    construction are unchanged.
  - Back ranks below Lyrics, Player and Tab through the existing `BackTarget.Playlist`, which now
    covers an open playlist or artist.

No change to `LibraryModel`, grouping, artwork or the backend.

## Limits

- The artist page is interim: a filtered song list, not the full #45 artist page.
- After a refresh that changed the library, the artist list and an open artist page briefly show
  their loading state until the new snapshot is grouped. Nothing from the previous snapshot is shown
  or playable in that time.
- Tones come from `String.hashCode`, so they are stable across runs and refreshes but are not a
  design palette.

## Tests

`LibraryArtistsTest` covers:
- initials: two words, one word, digits and punctuation, runs of ASCII and Unicode whitespace
  (tab, newline, vertical tab, no-break, figure, narrow and ideographic spaces), surrogate pairs
  including a cased supplementary letter, CJK, one code point per initial, locale-independent
  uppercasing, and blank names;
- the label and song-count wording;
- that tones are stable and fall in 0..2;
- snapshot binding, checked two ways. First, for every pair of snapshots (including an equal-content
  new list), the effect's key equality matches whether the guard accepts the groups. Second, a model
  of the effect that restarts on unequal keys, as Compose does, runs these cases:
  - a reshaped library with the same songs (playlists re-split, an empty one removed) regroups
    rather than loading forever;
  - an unchanged snapshot keeps its groups without regrouping;
  - another server, a same-server refresh, and a disconnect then reconnect to the same server never
    offer the old groups, and a saved artist waits and then opens;
- that a waiting artist keeps its page, Back targets it, and the grouping that arrives afterwards
  does not reopen it;
- stored-selection cases: nothing saved, waiting for the server or its grouping, open only on its
  own server, and discarded after a server change or a refresh that removes the artist;
- keys produced by #58's `groupArtists` against a selection.

The existing `LibraryPreferencesTest` still covers the view fallback.

There was no local Android build. CI was triggered by the push; see the PR for its status.
Astra review and phone QA are pending.

## Review correction (Astra review of `608e6fb`)

Astra found that groups were checked by server only, so a same-server refresh, or a disconnect then
reconnect to the same server, could briefly offer the previous library's artists and tracks. The
correction binds groups to the exact snapshot, keeps a saved artist waiting (page and Back
available) instead of discarding it, and replaces the `\s+` split with Unicode-whitespace splitting.
Implemented by Claude Opus 5.5, as above.

## Second correction (Astra review of `105e3b6`)

Astra found that the effect and the guard disagreed about keys. The effect was keyed on `all`, which
compares by contents, but the guard compared the list by identity. If the playlists changed while the
flattened songs stayed equal, `remember` built a new list, so the guard refused the old groups, but the
effect did not restart. Artists could then load forever. Both now use one `LibrarySnapshot` whose
equality is identity. Identity was chosen over a structural contract because comparing a whole
library's contents on every composition would cost a pass over it.

`artistInitials` used `Char.MIN_SUPPLEMENTARY_CODE_POINT`, which Kotlin's `Char` does not publicly
expose. It now calls Java's `Character.isWhitespace(int)` and `Character.isSpaceChar(int)` directly;
for BMP characters these are exactly Kotlin's `Char.isWhitespace`.

A note on the original split, as committed in `608e6fb`: the source was `Regex("\\s+")`, which Kotlin
unescapes to the regex `\s+`, so it was a whitespace class, not a literal backslash-s. On the JVM,
`\s` without `UNICODE_CHARACTER_CLASS` is ASCII-only, and Android's ICU-backed regex may differ. The
explicit scanner behaves the same in unit tests and on device.

Implemented by Claude Opus 5.5. Astra's re-review is pending.

## Manual QA (user)

1. Library shows the chips Songs, Artists and Playlists. Choose Artists, leave the app and reopen
   it: Artists is still selected.
2. Artist rows show initials on coloured circles, the full name and "N songs". An artist with no
   name shows the artist icon and "Unknown artist".
3. Tap an artist. The page lists their songs with "Back to artists", and tapping a song plays the
   artist's playable songs from that song.
4. On the artist page, system Back returns to the Artists list. With the player or lyrics open,
   Back closes those first.
5. Rotate on the artist page: it stays open. Pull to refresh there: it stays open while the artist
   still exists, briefly showing placeholder rows after a refresh that changed the library. Pressing
   Back in that moment returns to Artists, and the page does not reopen.
6. Disconnect, or connect to another server: no artist page or old artists remain.
7. At the largest font size, the initials stay inside their circles and rows remain usable.
8. With TalkBack, a row reads the artist's name and song count, not the initials.

## Status

Clean and idle once pushed. Not starting another task.
