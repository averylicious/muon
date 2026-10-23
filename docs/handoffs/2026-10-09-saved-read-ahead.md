# October 9: measured saved-card startup bottleneck (#401)

Independent cumulative successor of verified #449 at `08fe3c0197474d019ef483286045d3e11eb028de`, including main `602933e5ad1d8ecc65048e8a776488df6acec013`. Parallel #450 adds prepared completed-new-save routing but is not required by this existing-player fix; preserve/merge both in the later complete acceptance candidate. App stack remains OPEN/UAT pending. Continuous-engineering policy applies: successful verification/checkpoints do not end authorized work.

## Actual diagnostic baseline

User renewed rooted USB POCO verification for this task. Current device identity surya/M2007J20CG. Verified diagnostic build862/run37922519306, commit `c93e6451f755629c1cc99ef7c227596970d8dc4c`, installed as an update of the separate Muon diag package only; normal Canary/Stable untouched. Its existing disposable fixture has two COMPLETED card copies/two audio spans. No eject, security/SELinux/root-manager changes, originals or extra downloads.

First unprofiled selection: PREPARATION49ms, ADMISSION20ms, route1ms, open30/15ms, first reads269/8ms, READY39115ms. Second Java-sampled selection: READY44726ms (sampling overhead means it is not a fair timing comparison). Loader sample inclusive read42.724s, cardPresent41.835s, Environment/getStorageVolume30.698s (canonical path30.175s), folder attributes10.675s, inside DefaultOggSeeker.readGranuleOfLastPage/skipToNextPage. This identifies repeated path/availability checks for tiny extractor peeks, not a native-index startup diagnosis or raw-card throughput claim. Raw method trace/fixture metadata remain local/private; only aggregates are portable.

After stopping diagnostic playback, actual DB-family and cache snapshots show both saved records identical in EVERY column and both audio span SHA256s identical. Sampling stopped and its own device temp trace removed. Original media volume19 restored. Current authorization does not become standing phone permission.

## Implementation

`SavedReadAheadSource` wraps the production OfflineDataSource used by PlaybackService. Only saved handles allocate at most64KiB per source. Tiny extractor reads reuse bytes already obtained from the selected local saved copy; every actual refill still goes through OfflineDataSource's unchanged per-I/O availability and sticky-loss check. No TTL/mount-state caching, network fallback, card writes or storage-policy change. Live streams delegate unchanged and allocate no read-ahead buffer.

Already-buffered RAM may be consumed after card removal, just as existing player/load buffers may be; the next refill refuses unavailable storage. This distinction is explicit, not a claim that playback stops immediately on physical eject. Failed refill is sticky until actual close; close/reopen clears/zeros the buffer, respects exact range/length, closes failed opens and refuses unknown-close reuse. Active callback reentrancy cannot close/replace a read in progress. No independent background read-ahead tasks or retained request queue.

Seven focused wrapper tests plus one actual OfflineDataSource/Shelf/CacheDataSource/FileDataSource native-SQLite test cover tiny-peek refill counts/byte identity, unchanged live URI/headers/listeners, ranges/unknown length, buffered-RAM versus failed refill/no later revival, zero-length/zero reads, failed open/close, bounds/reentrancy and actual card-loss refusal/no upstream/preserved record+bytes/new-open recovery. CI is first compile/test/lint. Same-fixture unprofiled diagnostic comparison and final hash/record check still required before claiming #401 fixed.

## Remaining work / ownership

Root GPT-6 / Codex desktop sole writer, exact variant/effort not exposed; author self-check not independent review. Allocated audit Claude idle, stale quota not assumed reset. Experimental checkout untouched. Publish exact head/checks/reports/APK receipts and comparison on PR/#401/#40. Normal branch Canary APK updates the ordinary Canary app/data; ONLY an explicitly diagnostic manual build is for the separate app, never published. Do not confuse these artifacts.

#253 still needs production bounded index projection, coordinator/exclusion barriers, mixed routing, command/cover/manager integration, explicit restart-safe migration/recovery controls, opt-in UI and legacy transition-peak investigation. Full #179/provenance user-deferred; inherited app/hardware/accessibility UAT pending. Continue other actionable engineering after this verification while capacity remains. No Stable/tag/auto-merge/bypass or experiment changes.
