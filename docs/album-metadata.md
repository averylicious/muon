# Album metadata for the 2.0 library

`TauonTrack` now retains two optional tags from `/api1/tracklist/{playlistId}`:

| Property | Meaning | Missing/blank/malformed value |
| --- | --- | --- |
| `albumArtist: String` | Trimmed `album_artist`, separate from the track's artist credit | Use the existing track artist |
| `trackNumber: String` | Trimmed `track_number`, preserving notation such as `3/12` or `B2` | Empty string (unknown) |

Only JSON strings are accepted for these optional tags. Numbers, arrays, objects and null are not
converted into labels. Constructor defaults preserve compatibility with callers that only supply
the original fields. No new network request or Tauon extension is required.

Source evidence: installed Tauon 12.0.0-1.1, `t_webserve.py:get_track`, matches the recorded upstream
snapshot `8ff7721373b3abc13447e1df5f652312fa26e155`. It emits album artist (falling back to track
artist) and a string track number with leading zeroes removed. A desktop response confirmed both
field names; see the [backend investigation in #44](https://github.com/averylicious/muon/issues/44#issuecomment-5742148841).
Muon preserves any remaining tag notation rather than assuming every track number is an integer.

This is a data prerequisite, not the Albums feature. The grouping contract below defines the initial
album/artist helper; queue behavior and later display sorting remain separate work. Do not use Tauon's `album_id` as a global
album key: it is assigned from positions of directory groups within each playlist. Disc number and
year are not part of the inspected response shape.

Current playback, search, track identity, metadata display and playlist ordering are unchanged.
The fields are available for the later frontend/grouping work; no new screen consumes them yet.

## Grouping helper for the frontend

`groupAlbums(tracks)` and `groupArtists(tracks)` are pure functions in `LibraryGroups.kt`.
Pass tracks from **one server** (normally `LibraryModel.allTracks`). Compute once when that library
changes, off the UI thread for large libraries; do not rebuild groups in every composition.

- Album identity is trimmed, locale-independent lowercase album title + album artist. Display
  spelling comes from the first occurrence. Missing album artist falls back to track artist.
- Artist groups use track performer credits, split **only** at semicolons. Blank credits form an
  unknown-artist group. Repeated credit spellings within one track count only once.
- Both helpers deduplicate track IDs, keeping the first record, and preserve first-appearance
  group order and playlist track order. Unplayable tracks remain visible; playback actions must
  still apply the existing playable filter.
- Keys are stable strings suitable for Compose lazy-list keys. Album keys use length prefixes to
  avoid delimiter collisions. They are identities within a server's current library, not a promise
  of stability after a tag is edited or a server is changed.
- Empty labels remain empty for the UI to display its own unknown-album/artist text. Blank albums
  are grouped per album artist; no synthetic label is used as an identity.

These rules deliberately avoid sorting numeric track tags without disc metadata. Albums sharing
the same title and album artist (including different editions/years) cannot currently be separated
by these fields. Artist-name aliases and Unicode normalization are not inferred. The helper makes
no artwork requests and adds no Albums screen; the #23 assessment still gates the grid.
