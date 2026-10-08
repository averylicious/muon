# Library resources and incoming metadata boundary — 2026-10-01

Inspected main `d60fe09fa0d401e69049a1d1da6d1084237ec0c1`; pending network candidate #257 at `36fbf83a295e282b3cc23213566b31ddf3cefc1b` is a separate branch. Follow-up to #203 Q2 / #253, not a new OOM/performance incident. GPT-6 / Codex desktop, effort not reported; author source review and implementation, no independent review.

## Retained representation inventory

Paths are relative to app/src/main/java/dev/avery/muon/. Counts below describe ownership/representation, not measured heap bytes or timings.

| Boundary | Source evidence | Remaining risk |
| --- | --- | --- |
| Wire/DOM | TauonApi.json holds ByteArrayOutputStream, toByteArray copy, UTF-8 String and JSONObject; each response capped at 16 MiB | Cap does not bound DOM overhead, flat fields or aggregate load. #257 adds per-call deadline/depth/IO safeguards, not aggregate bounds |
| Refresh | LibraryModel.connect retains prior tracksByPlaylist while accumulating new per-playlist DTO lists, then combineLoad may reuse prior failed-playlist lists | Same track in several responses may have separate DTO/text instances. Per-playlist success is not a whole-library budget |
| Snapshot | LibraryModel.allTracks flattens references then distinctBy(id); MuonApp remembers LibrarySnapshot and sorting/artwork identity maps per library | Final unique snapshot does not free retained duplicate DTOs in playlist lists; temporary flattened list and new/old UI snapshots can overlap |
| Groups | groupAlbums uses canonical keys and member references; groupArtists splits semicolon credits and adds each track to each credited artist | Membership slots grow with credits. Grouping is off-main in MuonApp; cancellation of its effect does not turn synchronous group/sort loops into cooperative checks |
| Search/sort | searchTracks filters references off-main; song sorting and artworkIdentities construction run in remembered composition work; collection search filters/sorts before take(4/12) | Display limits bound returned results, not initial traversal/materialization; no measured jank from this inspection |
| Queue | TauonTrack.mediaItem copies display text and encodeSong bytes to metadata/extras; Media3 serializes actual BundleListRetriever replies | List chunking cannot split an oversized single Bundle; #255 characterized this |
| Download/retained | OfflineStore.add sends entire encodeSong data in DownloadService Intent; downloadedSongs/played metadata decode existing records | New-ingestion checks do not migrate existing retained records or provide per-aggregate IPC/heap guarantees |

## First production boundary

Incoming Tauon tracks now require **both** an encoded offline record no larger than 16 KiB and combined displayed title/artist/album/album-artist UTF-8 text no larger than 16 KiB. A cheap combined character preflight rejects enormous fields before encoding/credit formatting. The exact encodeSong implementation is used, so a later codec integration must retain this check rather than assuming byte sizes remain identical. Display credits can expand semicolon separators, so their formatted text is checked separately.

This is an explicit product resource policy, not a platform-guaranteed safe Binder allowance. Typical titles/credits have substantial room; unusually long legitimate tags can be refused. Chosen conservatively against the actual pinned serializers: accepted boundary fixtures must remain below Media3's suggested IPC size while preserving every accepted tag and the offline record. Other Bundle fields, list-chunk overshoot, concurrent transactions, different platform behavior and aggregate queue size remain separate questions.

TauonApi.tracks applies the check before returning its list. Oversized metadata raises IOException with track ID, limit and tag/retry guidance; raw tag contents are not included. It refuses the affected playlist rather than trimming tags or dropping individual tracks silently. LibraryModel already catches a playlist failure and combineLoad preserves that server's previous playlist when available; with no previous copy it reports partial failure or the connection error. Retrying after fixing tags loads the full data. Old downloads/caches are not deleted or rewritten. This protects newly ingested data; an already-retained exceptional record may still reach queue/download transport and needs a preservation-safe separate policy.

## Verification and limits

TrackMetadataBudgetTest exercises exact record boundary and actual Media3 BundleListRetriever writer/DownloadService Intent serialization, one-byte overflow, UTF-8 and cross-field accounting, display-credit expansion, ordinary multilingual tag preservation, actual HTTP/JSON refusal followed by retry, and existing combineLoad prior-playlist retention. CI is the first compilation; exact final-head results are on the PR. The isolated preservation call does not claim a full ViewModel/device lifecycle test. Existing oversized characterization remains intentionally possible through the low-level factory to document the retained-record residual route.

No phone, user music, uncontrolled heap stress, remote Binder delivery or performance measurement. Existing #248 exceptional codec, #257 network candidate and other app fixes are not included. This is one #253 boundary only: aggregate playlist/item/credit budgets, bounded retained-record handling and controlled device measurements remain open. Future limits should account for shared playlists/previous-library retention and report refusals rather than silently truncate collections.
