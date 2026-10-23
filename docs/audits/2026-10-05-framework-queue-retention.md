# Framework queue retention — 2026-10-05

Inspected Muon main `a6df5a1841fdde1ecd8eea9758edbca052f48373`. GPT-6 / Codex desktop, effort not reported: author/source self-review, no independent review. This narrows #253's framework-retention hypothesis; no application change, cap, benchmark, phone work or new confirmed runtime defect. Existing metadata/retention reports remain prerequisites. Installed phone/vendor framework equivalence is **not verified**.

## Exact source basis

Pinned Media3 **1.11.0**, from app/build.gradle.kts. Published [session sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-session/1.11.0/media3-session-1.11.0-sources.jar) downloaded read-only; SHA256 `9fa36c24ead02c89325d5b89d5322f2781b1b50511d1b432b4b033ab3c38e715` matches the prior [metadata IPC report](2026-10-04-metadata-ipc-budget.md). No source classes executed.

Framework: immutable AOSP `platform/frameworks/base` commit **94b4c163b7dfe5ce3607f7bb8456f9573f7de57d**, HTTPS Gitiles TEXT decoded to ignored audit files, never built or executed. This is the prior report's source snapshot, not proof of Android 17/QPR1 or the user's installed builds.

| File in this framework snapshot | SHA256 |
| --- | --- |
| `media/java/android/media/session/MediaSession.java` | `7ceebecf9ab3722cb640c2f154c8fb2547727b6f39b35ff3de349b7e30e7ea06` |
| `media/java/android/media/session/ParcelableListBinder.java` | `1588c11fee1420a18e5ed08f13dd2e1771066332378a112a4bd6f1073e6546fc` |
| `services/core/java/com/android/server/media/MediaSessionRecord.java` | `8fac679462661c00d9233bcec91a3a5522e5b636f980fef22ffbee0b2a467b5d` |
| `core/java/android/content/pm/BaseParceledListSlice.java` | `da4bf211b728ce2b64c7787f3aae18bdaf754aa4b252980fdb90898ecfc13cee` |
| `core/java/android/content/pm/ParceledListSlice.java` | `eb3bb064a0347ad65df3e3404eb694b2528a3a1508ea6775cb1bb7d37516937f` |

Web page inspection failed to render these Gitiles pages; their immutable public HTTPS TEXT responses were fetched and read instead. No authenticated user API or private data was involved.

## Muon to framework

- `MuonApp.startQueue` (263–269) maps the selected complete queue to MediaItems. Search starts the whole Songs library (594–599); this preserves explicitly approved playback semantics.
- `TauonTrack.mediaItem` (`PlaybackService.kt` 110–120) supplies display metadata and encoded song bytes under SONG_EXTRA. This audit does not establish a total transaction-size bound.
- In pinned `MediaSessionLegacyStub`, `isQueueEnabled` (1262–1265) requires GET_TIMELINE in both session and player commands. `onTimelineChanged` (1624–1632) also has the existing skip-update gate. If enabled, `updateQueue` converts the whole timeline, then produces a QueueItem per item (1635–1685).
- `LegacyConversions.convertToMediaDescriptionCompat` (805–807, 865–872) copies the extras Bundle into each description. This is a shallow Bundle copy, **not evidence that every byte array is deep-copied within the app**.
- Pinned `MediaSessionCompat.setQueue` (2056–2066) retains the compat queue, creates the framework-item list and calls the platform session. Muon grants GET_TIMELINE to trusted controllers, but the complete platform-connection/skip-gate outcome was not exercised; source capability is not proof of an active queue on a phone.

## Incoming transport is not ParceledListSlice in this snapshot

[MediaSession.setQueue](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/media/java/android/media/session/MediaSession.java) (640–650) uses resetQueue for null, otherwise obtains a queue Binder and calls **ParcelableListBinder.send**. The Media3 comment naming ParceledListSlice does not identify this snapshot's incoming route. The framework recommends a reasonable queue or sliding window, but no Muon queue truncation/window policy is approved here.

[ParcelableListBinder](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/media/java/android/media/session/ParcelableListBinder.java) (73–108, 117–138) sends batches using a **before-item** size check, then writes each item whole. The receiver accumulates entries and calls its consumer after the advertised count arrives. No aggregate byte/count budget or single-item splitting is established on this path. The suggested IPC size is not an item-size safety guarantee.

## System-service retention and outgoing transport

[MediaSessionRecord](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/services/core/java/com/android/server/media/MediaSessionRecord.java) (1498–1513) stores the received list as mQueue under its lock; null reset clears that field. Queue notification (890–910) makes one shallow ArrayList snapshot, then wraps it per callback with ParceledListSlice and an inline-count limit of one. getQueue (1261–1264) wraps the stored list. These establish retention/callback-copy routes in this source snapshot, not actual heap size, object reclamation time or a demonstrated leak. Session-close/service-ownership reclamation is outside this bounded pass.

[BaseParceledListSlice](https://android.googlesource.com/platform/frameworks/base/+/94b4c163b7dfe5ce3607f7bb8456f9573f7de57d/core/java/android/content/pm/BaseParceledListSlice.java) (74–135, 187–280) reconstructs the list through additional Binder transactions. Both initial and later writes check size **before** writing a whole item. Later replies can exceed the suggested size, with warnings rather than item truncation. The retriever clears its list reference after full extra transfer or a runtime failure; that does not clear MediaSessionRecord.mQueue. An inline-count limit of one limits item count, not that item's bytes.

## Consequence and next bounded checks

The earlier framework-retention hypothesis is now **verified for this immutable source snapshot**: list chunking does not prevent a complete queue being retained by the system media service. No practical OOM, TransactionTooLargeException, phone jank, exploit or measured improvement was reproduced. Do not multiply encoded bytes by an assumed copy count and call it measured heap.

Keep #253 open. The pending #302 per-record budget addresses incoming metadata refusal, not an aggregate queue limit or existing retained records. Before a production resource policy, use separately authorized emulator/device measurement of legitimate queue sizes, ASCII/CJK metadata, bounded-item serialization and refresh overlap. Preserve complete playback order, duplicates, external queue-index selection and old offline records; no silent shrinking, truncation, deletion or migration. A windowed external presentation would be a separate compatibility/design decision, not permission to shorten Muon's queue.

Local diff and changed-prose checks only. Documentation-only Actions verification and exact head are recorded on the PR. No new APK needed for this report.
