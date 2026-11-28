# Notification artwork identity and failed-request retention

Inspected main `26c88eaed9d481f210010808f4fd35172c9724c8` and artwork integration `4a6e718a05b662a0fd5f804b2c4bbe87f42b7fce`. Follow-up to #207, distinct from #210 Compose identity invalidation and #221 encoded/decode bounds. GPT-6 / Codex desktop, effort not reported: investigation and own controls self-reviewed. No phone or new dependency.

## Source-supported finding (P2 correctness)

`TauonTrack.mediaItem` in `PlaybackService.kt` forms artworkUri from origin + `/api1/pic/medium/<id>`. Title/artist/album can change without changing that URI. `PlaybackService.onCreate` supplies a `CacheBitmapLoader`; `MediaSession` also wraps its supplied loader, and `DefaultMediaNotificationProvider` wraps the resulting session loader.

Pinned Media3 `CacheBitmapLoader.loadBitmapFromMetadata` reuses the last future if URI matches **or** encoded artwork bytes match. It does not compare title/artist/album, refresh generation or song identity. The future is cached immediately, including a future that later fails. Normal title/artist/album changes do not invalidate any of these wrappers. Notification provider then consumes that future as the large icon; it catches failure rather than starting a new fetch at the same key.

Trigger: same-URI artwork stays the last request while that track is reselected/replaced with changed metadata, or an art load fails and subsequent notification updates request the same URI. Actual loader behavior is old/failing future reuse rather than another delegated request. Expected app behavior: refreshed identity can fetch the replacement cover, and a transient failure has a bounded retry/invalidation policy. Ordinary transitions to different artwork URIs replace the last request; a fresh session/loader also resets it.

Impact: potentially stale or absent notification/session artwork, not playback/control failure or cross-origin disclosure. High confidence in the pinned loader/source path; which Android system surface displays cached art, live same-URI queue refresh and actual device timing are unverified. This is not evidence the notification icon issue recurred; the small icon is separate.

## Published source and controls

Source: Google's Maven `https://dl.google.com/dl/android/maven2/androidx/media3/media3-session/1.11.0/media3-session-1.11.0-sources.jar`, SHA256 `9fa36c24ead02c89325d5b89d5322f2781b1b50511d1b432b4b033ab3c38e715`; version is from inspected Gradle files.

Relevant published source lines: `CacheBitmapLoader.java`59–90,143–146 (matching/future retention); `MediaSession.java`2843–2863 (unconditional outer cache); `DefaultMediaNotificationProvider.java`334–375 (another cache, metadata load and failure handling). Removing only Muon's explicit inner wrapper cannot establish invalidation through the outer two.

`NotificationArtworkIdentityControlTest` executes the actual pinned `CacheBitmapLoader` with a recording delegate, Robolectric Bitmap/Uri/MediaMetadata and immediate futures. Two controls: changed title/artist/album at same URI reuses the same old future and delegate call; a failed future is likewise reused. Different URI positive controls show the replacement load succeeds. This is not a simulated cache implementation, but it does not instantiate/render the notification provider or phone OS. Actions is first compile; CI receipts and actual execution results must be recorded on its PR.

## Bounded follow-up

Keep the source/characterization and fix separate. #210/#221/#302 do not fix notification identity. Define an identity-aware request representation and failure retry policy that survives all outer caches while retaining bounded loading, origin restrictions, offline artwork lookup and stable URI/API behavior. Do not append unverified Tauon query parameters, remove every cache, or rewrite the provider without checking those contracts. A changed image with unchanged tags remains a separate freshness policy, not settled by identity alone.

Acceptance for a later app fix: changed metadata at same art endpoint while current/last notification is retained; in-flight old request; transient failure followed by retry; ordinary skip, offline art and unchanged-cache reuse; transport controls still work. Pixel lockscreen remains deferred; no device changes or access in this slice. Keep #207 open and do not claim Stable readiness.
