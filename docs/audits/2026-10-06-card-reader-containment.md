# Card reader containment in OfflineDataSource (#179)

Branch `codex/card-reader-containment`, base `7aa2ec225b8a8d7fb2b549e96d8301c04afd8856`. That base is main `c4d48a145421ffde5ebff4cadc0e8cb400cdd059` plus the open #302 at `54f2cd390cd8e364cebc0a11c0be0c7c4ebe6521`.

Attribution: Claude Opus 5.5 (`claude-opus-5-5`) in Claude Code. High effort was selected at launch; the runtime does not report it. Role: implementation and author self-check. GPT-6 / Codex desktop (exact variant/effort not exposed) independently reviewed the source and fixtures, and added zero-length read compatibility plus its regression test. That compatibility change is its own authored contribution, not independent review of that addition.

Not compiled locally. GitHub Actions is the first Kotlin compile and the first run of the tests.

## What changed

`OfflineDataSource` used to check the shelf only once, when routing. It now checks the routed `Shelf.available()` at two more points:

- **After routing, before the shelf's source is made.** A card that went between the route's decision and the open fails with `IOException`. No cache source or file is created, and nothing is streamed in its place.
- **Before every delegated read.** A shelf found unavailable fails that read with `IOException` without passing it on. That open then stays invalid until `close()`, even if the shelf appears to come back: its old reader is never used again. The caller closes and opens again, and the new open routes afresh. It reads the returned card through a new reader, or streams through the phone shelf if the card is still gone.

Unchanged:

- phone reads, transfer listeners, URI and response headers;
- a failed open still leaves `close()` to the caller;
- `close()` is always passed on, whether or not the shelf is available.

Nothing here closes or releases a cache or manager, adopts a new card, or retries the stream under the old reader.

## Tests

`OfflineReaderContainmentTest` has 7 Robolectric tests. They use the actual route, `Shelf`, `CacheDataSource` and a counted real `FileDataSource`, over disposable cached bytes and native SQLite, with an in-memory upstream in place of OkHttp. They cover:

- a card hit that becomes unavailable between reads;
- the invalidation staying in place when the card appears to return;
- close, then a fresh reopen that selects the phone or the returned card;
- the card going between route selection and source creation;
- ordinary phone download and stream behavior, including listener, URI and header propagation;
- one availability query per non-empty card read, and zero-byte reads returning zero without I/O or reviving an invalidated reader.

A test-controlled availability switch is not a physical eject. These tests do not establish what removal does to open files, mounts, the cache index or reads already under way. Existing `ReaderRouteCompositionTest`, `SourceCloseControlTest` and `DownloadReleaseBoundaryTest` remain test-local characterizations, not production gates.

## Tradeoffs and gaps

- **Cost:** each delegated read of a card copy now calls `cardPresent`. That is an `Environment.getExternalStorageState(File)` lookup, which goes through StorageManager, plus a directory stat. Phone shelves answer from a constant. I did not throttle the check, because that would allow reads after the card was observed gone. The cost on real devices is unmeasured; device profiling would show whether a time-bounded check is needed.
- **Race:** each check is a snapshot. Storage can still go between a check and the read after it, so that read may fail with whatever error the file system or `SimpleCache` gives. This is containment, not an atomic mount or I/O guarantee.
- **Not addressed:**
  - the cache's own response to files disappearing, such as a stale-span scan;
  - writers and downloads;
  - a different card at the same path;
  - a card inserted while Muon runs;
  - the full #179 owner and generation recovery.
- **ExoPlayer's response to the failure:** how ExoPlayer retries, or reports the read `IOException` to the user, is unchanged and untested here. Playback behavior on a real card needs device QA.

## Next step

1. Get an independent review of this patch.
2. Then run the Actions build and its new tests.
3. With user authorization, do removable-card QA on a device: card playback, removal mid-track and reinsertion, using disposable data.

## Pinned source review

The coordinator checked the matching published Google Maven media3-datasource and media3-common 1.11.0 sources. CacheDataSource.Factory creates its cache reader and upstream per source; transfer listeners reach both; cached reads honor content length; a read-only/no-upstream factory has no write sink. DataReader requires zero-length reads to return zero, which the coordinator preserved without delegating or querying storage. These are source checks, not compilation or device evidence.

## Why physical eject terminated the diagnostic app

The private POCO test log records vold sending SIGINT to the diagnostic process, followed by Zygote recording that same process exiting due to signal 2. This was not evidence of an uncaught Kotlin exception. Android 16 upstream vold Utils.cpp, UnmountUserFuseEnhanced, tries unmounting first and then signals processes holding volume references if necessary. The exact custom-ROM implementation and particular held descriptor were not inspected. Keeping an audio file open during playback is expected and does not alone establish a leak. An IOException handler cannot prevent an OS process termination; durable saved-record preservation and restart recovery remain separate requirements.

Source: https://android.googlesource.com/platform/system/vold/+/refs/heads/android16-release/Utils.cpp#1796
