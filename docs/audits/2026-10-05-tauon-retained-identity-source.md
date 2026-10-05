# #213 Tauon API track identity: what the server exposes — 2026-10-05

Inspected main `aca170516b84bb2198d7ad5a94a55193be8eff74`, branch `codex/tauon-retained-identity-source`. Author: Claude Opus 5.5 (`claude-opus-5-5`), Claude Code, effort High as selected (the runtime does not expose effort). Source reading by the author, **not independent review**. Report only: no code, build, device or network change. Tauon wasn't run, and no library, playlist, config or music file was read. Earlier fixtures (`RetainedIdentityCharacterizationTest`) are cited as prior evidence only. #213 remains open.

**Question:** does Tauon's API carry a stronger persistent track identity that Muon could use, or only metadata hints?

## Sources

- **Installed Tauon:** `tauon-music-box` **12.1.0-1.1** (CachyOS package, built 2026-09-23), as installed on this desktop. It is **not verified** as the instance Muon talks to.
- **`/usr/lib/python3.14/site-packages/tauon/t_modules/t_webserve.py`** (1178 lines), SHA256 `acfd2181989c7e634d01347e608409f40a6a854b9acf0a63c215e878906bead4`, the same file as the [2026-09-30 report](2026-09-30-retained-identity.md).
- **`.../t_modules/t_main.py`**, SHA256 `9c71ad66010c518660b844db0f0b1b17107bed035f8289e35a149550e1c85764`.
- **Not read:** no upstream Git revision was fetched, so other Tauon versions aren't covered.

## Server capability (installed 12.1.0)

- **The track record:** `get_track` (`t_webserve.py` 589-621), used by `/api1/tracklist/`, `/api1/albumtracks/`, `/api1/albums/` and `/api1/status`, returns:
  - tags: `title`, `artist`, `album`, `album_artist`, `track_number`;
  - `duration`;
  - **`id` = `track.index`**;
  - `position` and `album_id`;
  - **`path` = `track.fullpath`**;
  - `has_lyrics` and `can_download`.

  Nothing else.
- **What `track.index` is:** an allocation from the in-process `master_count` (`t_main.py` 8473-8478, 8718-8742, 18073-18100), which is reset to 0 on library clear (5594). It is a library slot, not a content identity, and it can be reused.
- **Fields held but not exposed:** `TrackClass` (`t_main.py` 2127 onwards) has `size`, `modified_time`, `subtrack`, `start_time`, cue flags, and **tag-derived** `musicbrainz_recordingid`/`musicbrainz_trackid` and others. `get_track` serializes none of them, and no API1 route carries a content hash or stable UUID.
- **Other identifiers:**
  - The web client's `get_track_id` (356-357) is `md5(index + title + artist)`, which is still index-based.
  - `/api1/playlists` exposes `uuid_int` for **playlists**, not tracks (824-834).
- **What would help if exposed, and what each can't do:**
  - **`size`/`modified_time`:** cheap change detectors, but they don't establish identity.
  - **MusicBrainz IDs:** tags that may be missing, stale or shared by different encodings.
  - **Content-addressed identity:** would need a server-side hash or fingerprint. That is an **unresolved backend dependency**: an upstream Tauon API change, which Muon cannot add on its own.

## Metadata hints Muon could use (probabilistic only)

The live record's `path`, tags and duration can **discriminate** a changed track. They cannot prove that two records are the same audio:
- the same path can hold replaced audio;
- edited tags change without the audio changing;
- identical tags can sit on different audio;
- tags can be missing.

None of this permits treating a match as equivalence, or a mismatch as permission to delete.

## Current client behaviour (Muon main)

- **Projection:** `TauonApi` (46-62) projects `id`, tags, duration and the flags into `TauonTrack`. It reads `path` only for the display-title fallback and **doesn't keep it**.
- **Keying:** `downloadId(origin, id)` keys downloads, played copies, marks, covers and gain.
- **The saved record:** `encodeSong` stores `id`, title, artist, album, album artist, duration and track number.
- **Earlier evidence:** `RetainedIdentityCharacterizationTest` showed a reused origin/ID serving a retained copy of different audio. Its controls aren't repeated here.

## Recommendations (not approved semantics)

- **Smallest next production slice: a mismatch guard, no rekeying.**
  - **What it stores:** a versioned saved record that adds a **digest** of the server path, so raw paths stay out of local storage.
  - **When it applies:** online, when the live track's discriminator (path digest, tags or duration) differs from the record retained under the same origin/ID.
  - **What it does:** don't route the live item to the retained bytes, and don't mark it downloaded; stream it instead. **Keep** the retained copy, and keep it reachable offline under its own saved record.
  - **Legacy records** (no path) keep today's behaviour until a policy says otherwise.
  - **What it doesn't do:** no deletion, rekeying or migration.
  - **Use case:** after a Tauon library clear or rescan reuses indexes, the wrong song stops playing from downloads.
  - **QA:** use a **disposable** Tauon library or profile to force index reuse, never the user's library. Check that:
    - the downloaded song still plays offline;
    - the live mismatched song streams;
    - the download mark, cover and gain don't attach to the wrong song;
    - restart and legacy records behave as before.
- **Unresolved policy (user decision):**
  - what Muon shows when online data conflicts with a saved record: two entries, an "outdated download" state, or a hidden orphan;
  - whether and how the user replaces or removes the old copy;
  - how marks, covers, ReplayGain and played copies follow the decision;
  - whether a strong ID is worth requesting upstream.
- **#179:** removable-storage preservation remains separate.

**Checks run locally:** `git diff --check` and the CI prose check. CI for this document is pending. No Android build.
