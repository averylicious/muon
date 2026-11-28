# Retained audio: next coherent production boundary

Main inspected by the allocated audit Claude: `fb43419ca2342e341eaaae09929fc6551f6a8234`. Coordinator checked relevant current-main symbols after #352 and the pending acceptance source `9c9d4896b193fc5fecf4d6cb993eedfb7506c832`. The #302 refresh adds tests/docs only; app/build behavior unchanged. This is an advisory implementation plan, **not implemented behavior or settled schema**.

Attribution: Claude Code runtime `claude-opus-5-5` confirmed; High explicitly selected, runtime effort not emitted; bounded read-only source mapping. GPT-6 / Codex desktop, effort not reported: independently checked cited routing/listing/controller/action/artwork boundaries, corrected overbroad proposals, and authored this condensed plan. Coordinator documentation self-review is distinct from its review of Claude's advisory. No phone, live server, user-store or experiment work.

## Verified coupling

- `PlaybackService.TauonTrack.mediaItem` emits the live `origin/id` media ID and URI; the saved tag payload is display metadata, not independent local provenance.
- `OfflineStore.downloadedSongs` turns retained index/cache records into ordinary tracks and suppresses same-numeric-ID played copies. `LibraryModel.allTracks` also deduplicates by numeric ID. A separate saved listing cannot simply reuse that deduplication model.
- `routeOfflineRequest` can choose an explicit saved record before checking offline mode. `Shelf.source` permits OkHttp fallback. The #352 null-upstream controls demonstrate a mechanism, not a repaired production route.
- `PlaybackSessionCallback.onAddMediaItems` admits only validated live Tauon file URIs after UID checks. A new local route needs an exact-known-entry validator for Muon's own caller; external-controller privileges must not widen.
- `QueueModel.restoreUrl` and queue removal/Undo reconstruct a live URL. Ordinary MediaItem bundles omit local URI/cache key; #349 confirms metadata extras survive both tested bundle forms. Saved provenance must survive restoration explicitly, not rely on an object tag.
- `OfflineUi.downloadMark`, `MuonApp` download toggles, `OfflineStore.add/remove/copyPlayed` and `DownloadArt.forArtwork` all couple to live origin/ID. A routing-only patch leaves wrong badges/covers/destructive actions and cannot preserve a useful saved library.

## Corrections to the advisory

1. **Derived references are not a completed durable schema.** Existing index IDs/custom keys permit legacy read-only locators, but validated serialization, unknown records, restart, stale shelf identity, alias ownership and resources still need design/tests. A cache UID is not authenticated card identity or proof of content. Do not promote a test-local handle to a trusted app API without these checks.
2. **Legacy saved-cover ownership is already ambiguous.** `DownloadArt.file` hashes only the origin/ID into one shared directory, not shelf/source/entry. Distinct existing phone/card records can therefore name one cover. Blocking a future download does not establish that this cover belongs to either old record. The pending removal callback does check for another completed record before removing shared art; do not claim unconditional deletion through that callback. Exact new cover ownership and a conservative unknown-cover fallback are needed.
3. **Do not silently approve a temporary replacement-download refusal.** Claude proposed making the current collision refusal visible while postponing collision-free keys. That is a possible narrowed UX proposal, not user approval. Prefer designing prospective collision-free saves with the coherent boundary; an intentional temporary restriction needs a user decision before shipping.
4. **#302 is an integration dependency, not permission to merge it.** Use a fresh application branch including its current reviewed head plus current main, or wait for its acceptance/merge. Codec, availability, played-work, move and occurrence-owned Undo changes must be preserved. No device QA is inferred from successful test-only CI.

## Proposed application scope

A useful first app PR must cover all of these together:

- Separately reachable Unverified saved entries, including duplicate origins/IDs across source/shelf and unknown metadata. Preserve their existing keys/bytes and avoid dropping entries because they cannot be displayed as a normal live track.
- Exact validated local provenance from selection through MediaItem/controller transport, cache-only routing and queue/Undo. Missing saved bytes fail visibly; no live-ID reconstruction, upstream retry or automatic rekey/delete fallback.
- Live selection streams when retained identity is not established, with matching live badge/cover/action guards. Saved playback must not trigger a new played-copy download.
- Exact saved action/cover ownership, with alias/move/unavailable-source handling that preserves uncertain bytes. Existing global live-ID removal is not an exact saved action.
- Prospective save/copy keys that cannot overwrite or silently adopt an older uncertain entry, **or an explicitly approved narrower collision UX**. Neither new keys nor equal tags prove live audio identity.

Final type shape, URI/metadata representation and record version remain proposals. A dedicated saved model is preferable to silently overloading Tauon's numeric ID, but no unused production scaffolding is authorized by this plan. #179 full owner/generation recovery and #230 partial-target mutation ownership remain separate unresolved gates; this slice must not enable cache release/adoption or unsafe cleanup.

## Next bounded task

Before the broader app PR, specify and test the **prospective key/cover/alias ownership contract** against real disposable index/cache records: same live ID with multiple saved entries, unknown/legacy keys, independent artwork ownership, and an attempt to remove one aliased record. Determine which mutations can be exact and which must conservatively refuse. Keep this test-local until it has a supported application caller and coherent acceptance scope. Then implement the combined app boundary on an isolated #302-plus-main branch; leave it open for user QA. Do not build a new destructive migration or temporary refusal without approval.

Acceptance must include real live B versus saved A with identical tags/different bytes; saved missing/partial data with zero upstream; forged/malformed local admission; bundle/Undo provenance and duplicates; correct live badges/covers; collision-free new saves without deleting A; cancelled/moved/absent-card requests and restart. Hardware/removable acceptance needs current user authorization and disposable data. No Stable release follows from this plan.
