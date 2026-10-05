# Saved-entry inventory: test-local prototype

Inspected main `75c6a99454d8598dce3225aa5dc513d8b78b8b5f`; test-local preparation for [#213](https://github.com/averylicious/muon/issues/213), following the [selected policy and source map](2026-10-05-retained-identity-policy-map.md). GPT-6 / Codex desktop, effort not reported; implementation and author self-review. No production code, persistent schema, migration, phone work or independent review. Latest-head CI and actual executed-case counts are recorded on the PR, not assumed here.

## What is being evaluated

`SavedEntryInventoryPrototypeTest` uses real disposable SimpleCache spans and DefaultDownloadIndex records with Robolectric/native SQLite. Its private inventory observes explicit index records in every state, then unindexed cache keys, without deduplicating by numeric song ID or substituting metadata IDs for stored keys. No DownloadManager/downloader, network data source, live server or user directory is used.

A test-local handle carries location, observed cache UID, source kind, exact request ID (when indexed), and exact cache key. Phone/card/played copies can therefore remain different even with identical origin/ID/tags. The UID is an observation of a fixture cache, **not authenticated removable-volume identity, an operation lease, content proof or a finalized durable handle**. Handle constructors are private to the test; this does not define an external URI or trusted controller capability.

The inventory retains request metadata and cache metadata independently. A decoded song is a display suggestion only. Unknown/malformed/empty metadata and conflicting IDs remain represented; no record/key is removed because its tags cannot be decoded. Every entry stays Unverified regardless of matching tags or observed full span coverage. Failed/queued entries are retained as observations, not claimed playable copies.

The pending #248 `muon-song-2` fixture matches its UTF-8 Base64 textual-field layout. Main still has the v1 decoder, so this record remains opaque here; the inventory keeps the exact bytes. Decoder support is deliberately not copied into the prototype. Assertions use the current decoder's result so eventual #248 integration can recognize v2 without breaking preservation checks. This does **not** verify that all #248 application paths have been integrated.

## Focused acceptance cases

1. Same-ID explicit phone/card and played entries remain three separately addressable Unverified observations.
2. Opaque custom cache key and request ID survive a conflicting decoded song ID.
3. Empty, malformed, future and pending v2 records retain byte-exact metadata and handles.
4. Missing metadata and orphan spans remain represented without invented live identity.
5. Completed index rows with missing/partial bytes are distinguished from observed full coverage.
6. Failed/queued records remain represented without being promoted to playable downloads.
7. Request/cache metadata disagreement is preserved.
8. Released-cache failure propagates rather than becoming a successful empty inventory.
9. Unknown content length is distinguished from full coverage.
10. Repeated inventory leaves fixture index state, metadata, keys and span filenames/bytes unchanged.

These assert an observation model, not a repaired app. Indexed keys suppress a second *unindexed* row for those same physical spans; separate index records still keep separate handles. This is not permission to remove aliases or determine which record owns mutation rights. Request-without-custom-key handling uses the progressive URI fallback; adaptive/segmented format inventories need separate design before generalizing it.

## Pinned source checks and limits

Published Google Maven Media3 sources at the version pinned in `app/build.gradle.kts` were read directly:

- datasource source SHA-256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`: `SimpleCache.getKeys`, `getCachedSpans`, `getUid`, `isCached` and `Cache` API.
- exoplayer source SHA-256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`: `DefaultDownloadIndex.getDownloads` (no states means all), `DownloadRequest` fields.

Cache getters here observe existing initialized fixtures; DefaultDownloadIndex's getters can initialize/migrate its database on first use. This prototype must **not** be mounted on an uncertain user store as a supposedly forensic no-write scanner. Span coverage is a snapshot, not decoded audio, checksum identity, filesystem durability or future availability. There is no cross-object transactional inventory snapshot or lifecycle admission/drain; #179 is unresolved. Failures propagate in the test, with no partial-inventory error UI designed yet.

## Next boundary

Use this evidence to specify the smallest coherent production saved-entry boundary: separate Unverified saved access and cache-only playback, paired with live route/mark/cover/action guards and queue/Undo provenance. Missing saved bytes must fail without fetching a reused live ID. Integrate/review #248 codec and #302 ownership safeguards deliberately. Future colliding download/copy keys, safe mutation ownership (#230), inventory resource bounds (#253), persisted restart handles and #179 lifetime protection remain open. No destructive rekey/delete/migration.

Manual device QA is not needed for this test-only PR; existing application QA in #302 remains pending. CI is the first Kotlin compile and execution; the desktop does not perform a local Android build.
