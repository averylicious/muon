# Offline metadata delimiter compatibility

Inspected main `3d55eb2069b3a3b9fe553ab9f71f354b47581691`. Tauon accepts textual JSON tags containing NUL; the offline record delimiter assumes these cannot occur. encodeSong produces extra fields, decodeSong rejects them and downloadedSongs cannot list that record. No observed user library corruption or audio/data deletion is claimed.

The focused fix leaves ordinary muon-song-1 bytes unchanged. Only tags containing NUL use muon-song-2: UTF-8 Base64 text fields inside the same field layout. decodeSong reads both, leaves malformed/unknown/old ambiguous records unreadable rather than guessing, and never modifies retained bytes. Cache/media IDs and normal records remain compatible. Older app versions cannot read the new exceptional records, just as they could not read the old ambiguous NUL records. No bulk migration or re-encoding of audio occurs.

Four added codec regressions cover exact ordinary bytes/legacy readability, NUL in each textual field, Unicode/empty/literal Base64 text, and malformed/unknown/old ambiguous records. CI is the first Android compile; final head and results are recorded on the PR. Pending phone QA: ordinary download and Listen offline; existing downloads remain listed, newly saved Unicode/empty metadata works, and played copies remain usable. A disposable NUL-tag fixture needs an authorized test server/device; no user library is edited.

This does not repair already ambiguous old records, fix retained numeric-ID identity #213, impose a Binder/aggregate metadata budget, or prove playback/device behavior. Cross-track follow-up remains separate. Implementation and author source self-check: GPT-6 / Codex desktop (Sol), effort not reported; no independent review or phone access.
