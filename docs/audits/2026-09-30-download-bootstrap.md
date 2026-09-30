# Download bootstrap publication — 2026-09-30

Source inspected: main `d1dcfe9c11fbff05032c6bc73c04340c8b856723`, `OfflineStore.watch` / `create.record` / `create.removed`. This slice adds characterization only; no app fix, storage-format change or phone access.

## Question and impact

The watcher registers Media3 listeners, captures index entries on a background worker, then posts every captured record to the main looper. It does not discard records whose IDs received newer change/removal callbacks in between. A completed record captured before removal can therefore reach `store.record` after `store.removed`. The production record closure trusts that completed state, republishes Done and its byte count, and may schedule artwork. `OfflineUi` uses Done for badges/bulk-download decisions; Settings uses marks/bytes for counts. This can misrepresent download availability; it does not recreate a removed index entry or audio file.

A newer nonterminal state can also be replaced by the old completed callback. The ordering is narrow (startup/card watcher bootstrap overlapping manager events), not a claim that every removal fails. Cross-shelf lifecycle and retained IDs remain separate issues.

## Fixture and evidence boundary

`DownloadBootstrapCharacterizationTest` invokes the real private watcher by reflection and uses a real Media3 DownloadManager, DefaultDownloadIndex/native SQLite, SimpleCache and main-looper callbacks. A delegating index gates only the initial all-state cursor close, after closing the real cursor and releasing its database resources. The captured records are unchanged; the listener, bootstrap executor and publication code are not replaced.

The removal downloader deletes the disposable cache resource and refuses network/download requests. Media3 still owns index deletion, state transitions and listener delivery. The fixture records watcher callback output; it does not copy Muon's UI marking logic, instantiate a DownloadService, render Compose, or prove device timing. Completed control entries/bytes must remain intact. Three cases cover unchanged bootstrap, real manager removal before publication, and a real manager stopped-state replacement before publication.

Pinned Media3 1.11.0 sources were read from Google's Maven exoplayer source archive (SHA256 `2d583de9d39b48e45f9a29f1d94d23032c6fc7ca4bbe1967071d26a60ed6`), including DownloadManager application-looper dispatch, removal and mergeRequest; WritableDownloadIndex and DownloadCursor. CI is the first real Android compile/execution; source-supported risk becomes a reproduced defect only after the actual test report passes. The two race tests deliberately assert current obsolete callback output, not safe behavior.

## Proposed next fix

Track IDs changed/removed by live callbacks until the initial publication finishes, skip those IDs in the older snapshot, then release the temporary set. Keep unchanged records and subsequent live events. All ownership state belongs to the manager's application/main looper; do not introduce unbounded persistent tombstones or delete audio/index entries to repair UI state. Convert characterization into regressions and keep an app-fix PR open for phone QA.

Attribution: GPT-6 / Codex desktop / effort not reported (user nickname Sol), author implementation/source self-check. No independent review or Claude session.
