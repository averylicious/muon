# Aggregate played-copy admission — October 12

Solo cumulative #253 engineering successor to #501, based on main `2a275286f28db78083567465a521b7ea46f32640`. Claude, phones and the experimental checkout were not used. Partition mode remains disabled; this is not a Stable-gate completion claim.

## Implemented

`PartitionStorageSession` streams actual mixed played bytes (including protected legacy originals) before allocating a fresh played target. A single admission owns the aggregate headroom through source/native closure, and actual shared admission pins routes against migration/deletion. No whole-key/state collection or remembered zero replaces a failed/overflowed census. Independent explicit downloads do not use played keys.

The writer gets only remaining headroom, rechecks the current selected limit while copying, and known failures clean only the fresh unpublished attempt. Existing copies remain. Unknown actual close retains exact owners through the existing gate. A concurrent second played request cannot allocate against the same headroom. Census cancellation refuses before target reservation; idle observational native pools close before the next writer without evicting active readers.

OfflineStore routes partition usage/limit changes before accessing the legacy cache. Lowering the limit preserves existing copies and explains when eligible copies need clearing; the new limit controls subsequent admission. Source/current Settings remain otherwise unchanged.

## Limits and next engineering

This is conservative aggregate admission, **not LRU eviction**. A full cache declines optional played caching; it does not remove legacy/protected audio to manufacture room. Existing over-limit originals are kept. Known-length copies are refused before reading their body; an unknown-length final block may transiently exceed headroom before known cleanup. There is no promise of a byte-exact instantaneous disk ceiling.

Production activation still requires the final automatic eviction/resize policy, general legacy/Ready-original retirement, selected-root startup/binding and persisted explicit opt-in/recovery UI. Per-copy Close claims and native slot bounds remain intact. Application PRs stay OPEN for inherited acceptance; no Stable release/tag is authorized.

`git diff --check` passed; Actions is the first actual Android compile. New native/SQLite tests cover protected legacy bytes across owner restart, remaining headroom/failed fresh-copy cleanup, concurrent admission and cancellation before reservation. Record final full head, CI, actual reports/APK receipt on the PR/#253 and current continuation checkpoint. No device/visual/performance verification claimed. Continue authorized engineering after verified boundaries while capacity permits.
