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

This is a data prerequisite, not the Albums feature. Album grouping, ordering, multi-artist splitting
and cross-playlist duplicate handling still need agreement. Do not use Tauon's `album_id` as a global
album key: it is assigned from positions of directory groups within each playlist. Disc number and
year are not part of the inspected response shape.

Current playback, search, track identity, metadata display and playlist ordering are unchanged.
The fields are available for the later frontend/grouping work; no new screen consumes them yet.

## MediaSession metadata for future queue rows

`TauonTrack.mediaItem` now copies `albumArtist` and a positive `durationMs` into
Media3's `MediaMetadata`. Missing/zero/negative Tauon durations become null
(unknown), rather than a fabricated zero-length song or a rejected metadata
builder. Queue totals must distinguish unknown durations instead of claiming an
exact total from partial data. The existing performer, album, artwork, stream URI
and origin/track mediaId remain unchanged.

The pinned Media3 common 1.11.0 source defines `setDurationMs(Long?)` as optional,
non-negative milliseconds for informational use only. Actual playback duration
still comes from `Player.getDuration()`; no seek, timeline or transport behavior
is changed. No track-number parsing, queue identity, reorder, shuffle or Undo rule
is introduced. This is a small prerequisite for implementation-map B4 / #47,
not the Queue feature itself.

Validation: build/lint and existing tests in Actions; source checked against the
pinned Media3 API. No device/MediaSession round-trip test was performed. The
simple metadata copy adds no new test dependency. Later Queue QA must check known
and unknown lengths and repeated occurrences separately.
