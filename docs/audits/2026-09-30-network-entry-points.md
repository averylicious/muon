# Network requests and external entry points — 2026-09-30

Workstream 2 of the [audit map](README.md), tracked in [#181](https://github.com/averylicious/muon/issues/181). **Documentation only: no code, dependency, manifest or setting changed, and no fix is claimed.** Findings are source-supported; none was reproduced on a phone or a network.

## Baseline

- Inspected main: `8d0b24b236f73a2c2b846abffcf229ec8a72ca34`, after #192 ([main run 331](https://github.com/averylicious/muon/actions/runs/36528557232) passed; Canary .331). Open PRs at inspection: only Astra's #202 (CI publication baseline), which touches different files.
- Experiment compared: `claude/m3-expressive-alpha` at `e1bf045c1fa7139c4966e480f2f06941a703ddfc` (62 ahead of, 2 behind main). The experimental checkout was not modified; only remote-tracking refs were fetched.
- Author: Claude Sonnet 5.5 (`claude-sonnet-5-5`), Claude Code desktop app; effort not reported. Independent report/source review: GPT-6 Astra (Codex; effort not reported), with the qualifications below. Astra authored the review corrections, not the original report; the report is not a runtime validation.
- Worktree: `~/.codex/worktrees/muon-network-audit`, branch `claude/audit-network-entry-points` from main.
- **Not done:** no ADB or phone, no LAN traffic, no local Android build (CI is the first compile), no fuzzing, no dependency-advisory review.

## Method and evidence

Read every path that opens a socket or accepts an external caller: `ServerEndpoint`, `TauonApi`/`Transport`, `LibraryModel`, `LocalNetwork`, `ServerDiscovery`, `DiscoveryState`, `LanProbe`, `ConnectScreen`, the permission/resume code in `MuonApp`, `PlaybackService`, `MainActivity`, `OfflineStore`/`OfflineDataSource`/`OfflineDownloads`, `Artwork`/`ArtworkCache`/`DownloadArt`, the download services and `AndroidManifest.xml`. Network clients are exactly: OkHttp through `Transport.client` (API, artwork, download art, `LanProbe`'s derived client), Media3 `OkHttpDataSource` over the same client (playback, downloads, cache writer, notification bitmap loader) and `NsdManager`. No `HttpURLConnection`, `WebView` or raw sockets.

Media3 behavior was read from **tag `1.11.0` of androidx/media** (the version pinned in `app/build.gradle.kts`), through GitHub's contents API, not from the `-sources.jar`; the Gradle-resolved artifact was not inspected. Files: `MediaSessionStub`, `MediaSessionImpl`, `MediaSession`, `MediaSessionService` (including its `MediaSessionServiceStub`), `MediaSessionLegacyStub`, `SessionUtil`, `MediaUtils`, `DefaultActionFactory`, `legacy/MediaSessionManager`, `legacy/MediaBrowserServiceCompat`, and `ExoPlayerImpl`. The framework's `MediaSessionService.isTrusted` was read at AOSP `android-16.0.0_r1` (the phones run Android 16/17; Android 17's source was not checked). Cited line numbers refer to those files at those tags.

`ServerEndpoint.parse` was also probed with a line-for-line Java port over 36 inputs on a desktop JDK. Android's `java.net.URI` may differ in edge cases. One input (a trailing NUL) behaved differently only because the port used Java's `trim()`, which strips control characters where Kotlin's does not; it is excluded from the conclusions. Treat the probe as supporting evidence only.

Existing findings reused, not repeated: A2 (`LanProbe` narrow subnets, fixed in #180), A3 (backup exclusions, fixed in #180), A5 (fresh-install discovery; extended below as Q3) and [#179](https://github.com/averylicious/muon/issues/179) (untouched).

## Summary and priority

| ID | Finding | Type | Priority | Also in experiment |
| --- | --- | --- | --- | --- |
| N1 | Any app can send media-button intents to the exported `PlaybackService`; Media3 runs them without `onConnect` | Confirmed in source | P1 | Yes |
| N2 | A trusted external controller's play request replaces the queue with nothing; trusted controllers hold every player command | Confirmed in source | P1 | Yes |
| N3 | No overall deadline, and blocking requests are not cancelled with their coroutine | Confirmed in source | P2 | Yes |
| Q1 | The only discovered server is auto-connected, saved and re-used; NSD supplies host and port | Design question for the user | P2 (decision) | Yes |
| Q4 | CI does not assert the merged manifest's exported components | Verification gap | P2 | Yes |
| Q3 | Fresh-install discovery failure (A5): ranked hypotheses, all unverified | Question | P3 (needs device go-ahead) | Yes |
| Q2 | Large or hostile responses: per-response caps but no aggregate bound, and `OutOfMemoryError` is not caught | Question | P3 | Yes |
| W1 | Package-name spoofing of the session gate | Investigated, **withdrawn** | Hardening only | Yes |

The exposure is concentrated in one component, so N1 and N2 belong in one small slice. Everything else in the network path held up (see [Reviewed without a finding](#reviewed-without-a-finding)).

## Confirmed in source

### N1 — media-button intents bypass `onConnect`

- **Symbols:** `AndroidManifest.xml:19-21` (`PlaybackService`, `exported="true"`); `PlaybackService.kt:103-106` (`onConnect`, the only external gate); Media3 `MediaSessionService.onStartCommand` (`MediaSessionService.java:520-555`) and `MediaSessionImpl.onMediaButtonEvent`/`applyMediaButtonKeyEvent` (`:1613`, `:1702`). Muon does not override `Callback.onMediaButtonEvent`, whose default returns `false`.
- **Trigger:** any installed app starts `dev.avery.muon/.PlaybackService` with `Intent.ACTION_MEDIA_BUTTON` and a `KeyEvent` extra. (Android's background-start limits on the caller still apply.)
- **Actual:** Media3 attributes the event to Muon's own media-notification controller, or a fallback caller when none is connected, and executes it. `Callback.onConnect` never runs. Handled keys: play/pause/headset hook, play, pause, next/skip forward, previous/skip back, fast-forward, rewind (seek) and stop.
- **Expected:** `PlaybackService.kt:104-105` and the comment at `:109` treat `onConnect` as the gate for external callers.
- **Impact:** Low to Medium. Any local app can pause, skip, stop or resume Muon's playback and can start the service process. No data is disclosed. The platform may already let apps send media keys to the active session; that was not checked, so the added exposure is that this targets Muon specifically, even when it is not the active session.
- **Confidence:** High that the path exists as described. Not reproduced at runtime.
- **Proposed fix:** set `android:exported="false"` on `PlaybackService`. The app's own `MediaController` binds `SessionToken(this, ComponentName(...))` in-process, and Media3's notification PendingIntents are the app's own, so neither needs export. `onMediaButtonEvent` cannot replace this: the caller identity is the notification controller or a fixed fallback, never the sender. If device QA shows an external Media3 client legitimately needs export, keep it and record N1 as an accepted risk.
- **Verification:** device QA with export off (media notification, lock screen, Bluetooth headset buttons, voice assistant transport commands, Wear if used). To demonstrate the current behavior, a disposable helper app on an authorized device. A CI assertion (Q4) keeps the manifest from regressing.

### N2 — external play requests clear the queue, and trusted controllers hold every command

- **Symbols:** `PlaybackService.kt:107-121` (`onAddMediaItems` returns an empty list for any other package); Media3 default `Callback.onSetMediaItems` (`MediaSession.java:2034-2045`, delegates to `onAddMediaItems`), `MediaUtils.setMediaItemsWithStartIndexAndPosition` (`MediaUtils.java:227-246`), `MediaSessionLegacyStub.handleMediaRequest` (`:1117`, used by play-from-media-id/search/URI and prepare-from requests), `ExoPlayerImpl.setMediaSourcesInternal` (`:2618-2631`).
- **Trigger:** a *trusted* external controller (system component, `MEDIA_CONTENT_CONTROL` holder, or an app with an enabled notification listener) calls `setMediaItem(s)`, or sends a play-from-search/media-id/URI request. Muon rejects untrusted external controllers in its own `onConnect`; the upstream read-only default does not apply to that rejected connection.
- **Actual:** the empty list flows through unchanged. With no start index Media3 calls `player.setMediaItems(emptyList, resetPosition = true)`, which clears the playing queue. With an explicit start index ExoPlayer accepts an empty timeline and also clears it (the range check is guarded by `!timeline.isEmpty()`), so this is not a crash. Separately, the default `AcceptedResultBuilder` gives trusted controllers **all** player commands (`MediaSession.java:2282-2296`), including remove, move, clear, volume and speed. The comment at `PlaybackService.kt:109` promises transport controls only.
- **Expected:** unsupported content requests are ignored or fail, and the queue stays. External controllers hold an allowlist of transport commands.
- **Impact:** Low. A voice assistant, companion app or notification-listener app sending "play X" wipes the queue, which is not persisted across process death (see [roadmap](../roadmap.md) 0.3). Which real apps send such requests was not established.
- **Confidence:** High for the code path; not reproduced.
- **Proposed fix:** for controllers other than Muon's own, return a failed future from `onSetMediaItems`/`onAddMediaItems` (Media3's own default already returns `UnsupportedOperationException` for items without a URI), and build `onConnect`'s result from `ConnectionResult.AcceptedResultBuilder` with only transport commands for external controllers. Keep the change to the callback.
- **Verification:** extract the decision into a pure function and unit-test it (the gate lives in an anonymous callback and has no tests today). The pinned library provides `ControllerInfo.createTestOnlyControllerInfo(...)`, so Robolectric can exercise the production callback with own-app, trusted external and untrusted identities. This covers callback decisions and failed futures; cross-process Binder/legacy routing still needs separate integration evidence or a helper controller on an authorized device.

### N3 — no overall deadline, and cancellation stops at the coroutine

- **Symbols:** `Transport.client` (`TauonApi.kt:12-13`: connect 5 s, read 15 s, no `callTimeout`); `TauonApi.json` (`:22-38`), `Artwork.fetchArtwork` (`Artwork.kt:115-142`) and `DownloadArt.fetch` (`DownloadArt.kt:27-44`) call the blocking `execute()` inside `withContext(Dispatchers.IO)`; `LibraryModel.connect`/`disconnect` (`:27-113`); `ConnectScreen.kt:67-80` (the address field and Connect are disabled while `busy`, with no cancel).
- **Trigger:** a server, or a stalled path, that accepts the connection and then sends bytes slowly enough to reset the 15 s read timeout; or Disconnect, navigation or a rescan while a request is in flight.
- **Actual:** only per-read timeouts. The body caps (16 MiB JSON, 4 MiB images) bound size, not time, so a dribbling response can hold "Connecting…" for hours. Cancelling the coroutine does not cancel the OkHttp `Call`: the blocking read finishes first, and `disconnect()`'s `job.cancel()` takes effect only when it returns. After that, the next `withContext` throws immediately, so leftover work is bounded to one call per job, plus other independently launched requests. The IO dispatcher's typical parallelism is not an application-level bound on queued requests or aggregate memory.
- **Expected:** a bounded wall-clock time for control-plane calls, and cancellation that reaches the socket.
- **Impact:** Low on a trusted LAN: a stuck connect screen that needs a force-stop, and wasted background requests after leaving a screen. The streaming client must keep having no call timeout.
- **Confidence:** High for the code; not reproduced.
- **Proposed fix:** a derived client with a `callTimeout` for `TauonApi`, artwork and `DownloadArt`, leaving the streaming client alone; wrap `execute()` so coroutine cancellation calls `Call.cancel()`. No wider refactor.
- **Verification:** a JVM test against a local `ServerSocket` that dribbles bytes (no new dependency needed) asserting the call ends by its deadline and that cancelling a coroutine closes the socket.

## Questions

### Q1 — auto-connecting to whatever answers (design decision for the user)

`autoConnectTarget` (`DiscoveryState.kt:70-73`, used at `ConnectScreen.kt:46-53`) connects, without asking, to the only server found once per visit. Candidates come from `LanProbe` (any host of the phone's /24 that answers `GET :7814/api1/version` with `{"version":1}`; `LanProbe.kt:75-83`, on any Wi-Fi or Ethernet, `:59-60`) and from NSD (any device advertising `_tauon-remote._tcp`; `ServerDiscovery.kt:69-77` takes `service.port` unrestricted, so an advertisement can point Muon's fixed `GET /api1/...` requests at any private host and port). A successful connect saves the origin (`LibraryModel.kt:58`), and `LibraryModel.init` reconnects to it on every launch (`:26`) on any network.

This matches the documented product choice (#39; the Connect screen says there is "nothing left to confirm", and warns that Tauon's API has no login). It is recorded because it moves the trust decision from typing an address to being the only responder. On an untrusted Wi-Fi, a LAN-adjacent device can become "your Tauon" and feed Muon library metadata (bounded, see Q2), audio to ExoPlayer's extractors and platform codecs, and images to `BitmapFactory`. No credential is exposed, since Muon holds none for Tauon.

**Decision needed:** keep as is, or confirm the first connection to a newly discovered origin (then remember it), or auto-connect only to a previously trusted origin or port 7814. This is a product call; do not implement without the user's choice.

### Q2 — memory and aggregate bounds

`TauonApi.json` copies up to 16 MiB into a `ByteArrayOutputStream`, calls `toByteArray()`, decodes a `String` and builds a `JSONObject`, so peak heap is a multiple of the response. Playlists and tracks are bounded per response but not in aggregate. `LibraryModel.connect` catches `Exception` (`:62`), so a malformed body cannot crash it, but an `OutOfMemoryError` is an `Error`; because the saved server is contacted at every launch, a hostile or oversized response would recur. A playlist over 16 MiB fails as "didn't load", and its message ("Playlist response exceeds 16 MiB") is also used for version and lyrics. The user's library (962 songs) is far below any of these limits; no measurement was made. **Verify:** peak heap for a synthetic maximum-size response, and decide the largest library Muon should support before changing the parser.

### Q3 — fresh-install discovery (A5): unverified hypotheses

A5 is not reproduced here. Source-supported candidates, in the order to test them (all need device access, which this cycle does not have):

1. **Wrong network:** `LanProbe.ownAddress` (`LanProbe.kt:55-64`) reads `activeNetwork`. With a VPN or a non-Wi-Fi default network active, it takes the tunnel's address and probes that subnet; a `/32` tunnel now yields no candidates after A2's fix. *Check:* which network `dumpsys connectivity` reports as default during a failed scan.
2. **Permission timing:** `LocalNetworkState.granted` starts `true` (`LocalNetwork.kt:39`) and is refreshed by `MuonApp.kt:302-308`. Whether `ConnectScreen`'s first `LaunchedEffect` (`:40-45`) reads it before the refresh depends on Compose effect ordering, which was not verified. If it starts early on Android 17 without the permission, the first scan is wasted and `probe` stays `null`, so the spinner shows beside the "Allow" card. This would not explain a failure with the permission granted.
3. **NSD address choice:** `ServerDiscovery.kt:70` resolves one address. A link-local scoped IPv6 result (`fe80::…%wlan0`) is rejected by `ServerEndpoint` ("Scoped IPv6 addresses are not supported yet") and counted as unresolved, which blocks auto-connect (`autoConnectTarget` requires `unresolvedCount == 0`).
4. **Probe timeouts:** 700 ms connect and 2.5 s per call for 253 hosts at 48 in parallel (`LanProbe.kt:49-52,68`) might be tight right after the radio wakes. Least likely, since typing the address worked.
5. **Variant differences:** the Benchmark package is a separate install with its own permission state.

### Q4 — the merged manifest is not asserted

There is no local build, so the merged manifest (including library components such as `androidx.profileinstaller`'s receiver) was not inspected. `tools/verify-apks.py` checks package/version identity through `aapt dump badging` only. **Proposal:** dump the built APK's manifest in CI and assert the exported components, permissions and cleartext flag against an expected list. It is small, and it makes the N1 decision enforceable.

## Investigated and withdrawn (W1)

**Hypothesis:** the `onConnect` gate (`c.packageName == packageName || c.isTrusted`, `PlaybackService.kt:104`) and `onAddMediaItems` (`:110`) trust a package name that Media3 documents as sometimes unverifiable, so an app could claim Muon's name.

**Result: no exploitable route was established in this pass. This is a withdrawn finding, not proof of absence on the platform paths left unchecked.**

- **Service bind** (the route an external app takes to the exported service): `MediaSessionServiceStub.connect` requires `PACKAGE_VALID` (`MediaSessionService.java:1051`), rejecting `PACKAGE_CANT_CHECK` as well as `PACKAGE_INVALID`. The more permissive check in `MediaSessionStub.connect` (`:727`, rejects only `PACKAGE_INVALID`) applies to a controller that connects directly through a session token's binder.
- **Direct session connect:** obtaining that binder needs access to platform sessions (notification-listener or `MEDIA_CONTENT_CONTROL`), which is already "trusted". This platform requirement was not verified here. Claiming Muon's name would not make such an app more trusted: Media3's own check (`legacy/MediaSessionManager.java:235-271`) decides listener trust from the claimed package name, and Muon is not a listener, while permission-based trust uses the real pid and UID.
- **Legacy `MediaBrowserService` bind:** the identity comes from the framework's `MediaBrowserService`, which validates package against UID. That framework class was not read in this pass.
- Media3 deliberately does **not** use the framework's `isTrusted` (whose comment states it skips the package/UID comparison), so the framework read at `android-16.0.0_r1` is not what decides trust.

**Residual hardening (low):** replace the string comparisons with `ControllerInfo.getUid() == Process.myUid()` for "our own app", so security does not depend on Media3's verification behavior or on Muon never becoming a notification listener. Include it in the N1/N2 slice; it costs a line.

## Reviewed without a finding

| Area | Result |
| --- | --- |
| `ServerEndpoint.parse` | Numeric private, loopback or ULA hosts only. In the JDK probe, userinfo, query, fragment, extra path, scoped and IPv4-mapped IPv6, hex octets, non-ASCII digits, public, link-local and just-outside-`172.16/12` addresses were all rejected. Uppercase `HTTP://` is rejected (friction only). Some inputs surface `URISyntaxException` text as the user-facing error; callers all catch `Exception`. `ServerEndpointTest` already covers the main rejection list; missing cases are scoped IPv6, IPv4-mapped IPv6 and uppercase scheme. |
| Redirects and TLS | `followRedirects(false)` on the shared client; cleartext is app-wide (`AndroidManifest.xml:11`) but every URL is built from a validated origin. |
| Response caps | 16 MiB JSON, 4 MiB images (`Artwork.kt:133`, `DownloadArt.kt:35`), 4 KiB for the probe (`LanProbe.kt:79`). Image decode is bounded by `artworkSampleSize`/`ARTWORK_MAX_SIDE`, including very elongated images. |
| Own-app queue URIs | `onAddMediaItems` validates scheme, origin, exact `/api1/file/<digits>` path, no query or fragment. It does not validate `mediaId`, `artworkUri`, subtitle or DRM fields; those are unreachable to external callers given the gate. `OfflineStore.copyPlayed` (`:211-213`) builds `/api1/fileopus/<mediaId suffix>` without checking the suffix is numeric; only Muon's own items reach it. |
| Other entry points | `MainActivity` is exported for `LAUNCHER` only and ignores intent extras. Download services are `exported="false"`. The session-activity `PendingIntent` is explicit and immutable. No receivers or providers in Muon's own manifest. Legacy browser/`onPlaybackResumption` are not wired. |
| Backups | Exclusions fixed in #180 (A3); not re-reviewed. |
| Song records | `encodeSong` joins fields with NUL, on the assumption "no tag contains" it (`OfflineDownloads.kt:76-79`). A title with an embedded NUL would make `decodeSong` return `null` and hide that download from the offline library. Referred to the offline-consistency workstream. |

## Experimental branch

Every finding applies. At `e1bf045` all audited files are byte-identical to main except `ConnectScreen.kt` (wavy loader and bottom padding only), and the app pins the same Media3 1.11.0 and OkHttp 4.12.0; the only dependency difference is the Robolectric test dependency from #192, which the experiment lacks. Port fixes main-first, then forward-sync per [parallel tracks](../parallel-tracks.md); if a fix's tests need Robolectric, sequence the #192 sync first. No experiment-specific code is needed.

## Verification still needed

- Runtime confirmation of N1, N2 and N3 (none reproduced). N1/N2 need a helper app or controller on an authorized device; N3 can be a JVM test.
- Device QA for the recommended manifest change: media notification, lock screen, Bluetooth buttons, assistant transport commands.
- Q3's checks and Q2's heap measurement, both device/emulator work.
- A resolved-graph check that the Gradle-resolved Media3 is 1.11.0 and that the tag matches the published artifact.
- The framework legacy browser-service path and the platform rule that gates access to session binders (W1), each read at Android 16/17 sources.
- Independent Astra source review completed as described below; runtime/device and resolved dependency-graph limitations remain.

## Recommended next task

**Harden `PlaybackService`'s external entry points (N1, N2 and W1's one-line hardening), as one small main-track PR** on a fresh branch from main. Pure predicates for "own app" and "allowed external command", `exported="false"` (or a recorded decision to keep it), failed futures for external item requests, and unit tests for the predicates. Stop point: PR open with CI green and the QA checklist above, pending the user's device testing. Follow it with Q4 (manifest assertion), then N3, then the Q1 decision. Q3 and Q2 wait for device authorization and a measurement plan.


## Astra review addendum — 2026-09-30

Independently checked the published [Media3 session sources JAR](https://dl.google.com/dl/android/maven2/androidx/media3/media3-session/1.11.0/media3-session-1.11.0-sources.jar) matching main's declared pin. Reviewed `MediaSessionService.onStartCommand` and service-bind package validation, `MediaSessionImpl.onMediaButtonEvent`/`applyMediaButtonKeyEvent`, `MediaSession.Callback.onSetMediaItems`, the connection-result defaults, `MediaSessionLegacyStub.handleMediaRequest` and `MediaUtils.setMediaItemsWithStartIndexAndPosition`. Main app code was unchanged by #202.

- **N1 supported with a lifecycle qualification:** the start-intent route reaches the session without Muon's connection gate. With a connected notification controller, supported key actions execute under that controller. Without it, `applyMediaButtonKeyEvent` returns false; cold-start timing and legacy fallback behavior must not be described as unconditional successful playback control. The exposed start path itself remains. `exported=false` is a proposed restriction with a compatibility tradeoff for apps binding by service token, not an already verified platform fix.
- **N2 supported:** the default set-items callback delegates to Muon's add-items callback; its successful empty result can replace the queue. Rejecting unsupported requests with failed futures and removing external content-mutation commands are independently useful. The callback can be tested directly using the pinned public test-only controller factory, including the real default set-items delegation. A tiny predicate test alone would miss the successful-empty-list failure.
- **N3 supported in the app source:** blocking execution has no coroutine-to-Call cancellation bridge or total call deadline. No network timing reproduction was performed in this documentation review. A control-plane timeout must not be applied to long-running audio streams.
- **Priority is audit triage, not a vulnerability score.** N1/N2 affect local playback integrity; the practical controller population and platform reachability remain unmeasured. The auto-connect product decision, unresolved framework paths, and aggregate resource questions remain separate.

The findings are useful and suitable to land with these limits. No original author checkout, experimental checkout, phone or music was accessed. There is no new production fix in this report. Follow-up production patches need their own tests, exact-head CI, model attribution and phone QA where behavior changes. Do not infer runtime coverage or a complete dependency audit from this source review.
