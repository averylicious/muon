# Optional played-copy scheduling characterization — 2026-09-30

Follow-up to [#225](https://github.com/averylicious/muon/issues/225). Inspected/main base `cab3c0fc46abffe9c5468bc2ec1a978d233bb1f1`. Tests only: no production scheduler change, new timeout, byte-budget implementation, user files or phone access.

## Actual fixture boundary

`PlayedCopyCharacterizationTest` calls actual `OfflineStore.copyPlayed`, `clearPlayed`, `setCacheLimit` and `playedCopy`. A disposable Store uses real Media3 SimpleCache, DefaultDownloadIndex/DownloadManager, PlayedSongEvictor and OkHttpDataSource/CacheWriter. Reflection installs/restores only the singleton and reads the existing copier to await its FIFO barrier; production worker/client/copy/maintenance are not replaced. A numeric-loopback HTTP fixture sends headers/initial bytes and waits at a response-body gate. No LAN host or phone is contacted.

Four cases cover exact bytes/metadata on a successful copy, clearing delayed behind an unfinished optional copy, preference/UI-limit update preceding delayed actual resize eviction, and a truncated response followed by queued maintenance. Explicit-download-key bytes must remain untouched in every case. Gate/latch ordering establishes the interleaving; this does not measure real-device latency or demonstrate an indefinitely running slow-drip request. No complete-response check should pass on an unfinished/truncated resource.

These are expectations before CI. Inspect actual final-head XML before calling them reproduced. The desktop is not used for an Android build: CI is the first real compile and execution.

## Pinned cancellation reading

Actual build-pinned Media3 datasource and OkHttp datasource sources from Google's Maven: CacheWriter.cancel sets a flag checked between read/open calls, not a socket-cancellation handle. OkHttpDataSource cancels its local Call when interrupted while awaiting the response; it closes the ResponseBody on close. Do not claim it lacks all cancellation or that a CacheWriter flag alone interrupts active socket reads. Exact archive hashes and symbols are recorded in [the issue follow-up](https://github.com/averylicious/muon/issues/225#issuecomment-5912002314).

## Next focused implementation

Separate optional-copy lifetime/scheduling from foreground streaming. Bound/deduplicate queued work, give clear/resize an explicit cancellation/ordering policy and preserve explicit downloads and active cache ownership. Reconcile #212 byte-budget checks if implementation overlaps OfflineStore; that PR remains open for user QA. Do not introduce a broad storage migration or call this characterization a fix. User phone QA remains separate; this test-only PR needs no device QA if source/CI checks pass.

Attribution: GPT-6, Codex desktop, effort not reported (user nickname Sol), author investigation/self-check; no independent reviewer or Claude session.
