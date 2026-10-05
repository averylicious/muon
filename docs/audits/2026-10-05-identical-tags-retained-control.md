# Retained identity: identical tags, different bytes

GPT-6 / Codex desktop, effort not reported; test author and source self-review, no new independent agent review. Source base main `9eb20f92e1ff3f036ea395b92a6e633a9bff40ef`; final head/CI evidence belongs on the PR. This is test-only preparation for [#213](https://github.com/averylicious/muon/issues/213), not a production identity fix.

The user chose live streaming plus separately accessible Unverified saved copies. [Policy/source map](2026-10-05-retained-identity-policy-map.md) records the approved behavior and proposed implementation scopes. Installed Tauon's inspected serializer does not provide established content identity; matching metadata must not be upgraded to Verified.

## Two disposable controls

`RetainedIdentityCharacterizationTest.identicalTagsDoNotEstablishThatExplicitSavedBytesAreTheLiveAudio` and `identicalTagsDoNotEstablishThatOfflinePlayedBytesAreTheLiveAudio` use actual existing `OfflineDataSource` / `routeOfflineRequest`, a real SimpleCache/native SQLite fixture, and a loopback-only HTTP peer. An independent real OkHttp request reads live payload B. The saved record, current MediaItem SONG_EXTRA and track ID are identical; the real retained spans contain different payload A. The current online explicit-download route and offline played-copy route still return A without a second peer request. Assertions also require intact retained cache size, a completed source close and joined peer ownership before disposable cache teardown.

The server listens only on 127.0.0.1 with bounded accept/read waits; the independent client has a bounded call deadline. All sockets, response bodies, data sources and client resources are closed; setup assertions are enclosed by the socket's use block. No user music, public/private server, device, migration, file deletion or production key modification. Existing mismatched-tag, ordinary-match and different-origin controls remain.

These are synthetic byte payloads, not audio decoding, a Tauon rebuild or real phone playback. The offline played control explicitly supplies offline mode with a current matching-tag fixture; it does not claim ordinary LibraryModel replaces saved tags with a live library in that mode. Passing assertions characterize current unsafe identity reuse; they do not implement the requested future routing. In particular, metadata equality alone cannot justify selecting old cache bytes for a live entry. No timing/performance, full #179 drain or removable-card claim.

## Verification and next boundary

No local Android/Gradle compile; GitHub Android APKs is the first actual Kotlin/Robolectric/native SQLite execution. Exact-head CI and actual XML results must be recorded on the PR before merging this test-only boundary. No phone QA needed for tests, and all application acceptance gates remain unchanged. Next: a test-local read-only saved-entry handle/inventory prototype before a coherent application change spanning separately available saved entries and live routing/mark/cover/action guards. Preserve #248 codec and pending #302 storage/queue ownership dependencies; no automatic rekey, replacement or deletion.
