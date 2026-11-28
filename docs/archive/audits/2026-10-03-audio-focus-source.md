# Playback audio-focus/source boundary — 2026-10-03

Inspected main `69d78619875042077cdc104a1714ce43796454c3`. GPT-6 / Codex desktop, effort not reported: source self-review only. No playback, emulator or phone testing and no code changes. This narrows one remaining playback-platform source question; hardware/OS acceptance stays pending, Pixel lockscreen explicitly deferred.

## Wiring and pinned implementation

`PlaybackService.onCreate` configures `USAGE_MEDIA`, `AUDIO_CONTENT_TYPE_MUSIC`, automatic audio focus (`true`), noisy-output handling and network wake mode. ReplayGain applies the player's `volume`; the UI's `MediaVolumeController` separately controls Android's music stream. No Muon focus listener or unconditional focus-gain playback restart was found in these inspected paths. `onDestroy` unregisters the loudness preference listener and releases its player/session; abrupt process death need not call this method.

Read actual pinned Media3 sources (version from app/build.gradle.kts), not latest API memory:
- common source JAR SHA-256 `a1fdf302c059a4d75b3005996a85d96619ccff4a4bf53435bf1f9fd053d86e3e`, `audio/AudioFocusManager.java` and `AudioBecomingNoisyManager.java`;
- exoplayer source JAR SHA-256 `2d583de9d39b48e45f9a29f1d94d23032c0642cfc7ca4bbe1967071d26a60ed6`, `ExoPlayerImplInternal.java` and `ExoPlayerImpl.java`.

Source-confirmed behavior:
- Focus manager 176–214 derives the focus command from requested play state and attributes; 233–249 maps granted/delayed results versus denial. Delayed-focus platform behavior remains runtime-unverified; no patch is inferred from its source branch alone.
- Focus-change handler 376–402 distinguishes permanent loss (do not play), transient loss (wait/suppress), music ducking (multiplier0.2) and gain. Music does not use the speech-specific pause-on-duck branch.
- `ExoPlayerImplInternal` 569–580 applies focus commands to the existing `playWhenReady` and reuses stored base volume on multiplier changes; 1144–1155 passes `volume * focusMultiplier` to renderers. Applying ReplayGain volume therefore does not bypass this source-level duck multiplier.
- Its 1199–1230/4440–4458 focus command logic distinguishes play intent from transient suppression. The source path preserves a manual pause rather than unconditionally setting playWhenReady true on focus regain. This is not an audible/OS confirmation.
- `AudioBecomingNoisyManager` 81–115 registers the system noisy broadcast when enabled and only delivers while enabled; ExoPlayerImpl3618–3621 turns playWhenReady off. Release disables the receiver and wake/Wi-Fi locks (1142–1147); internal release2062 abandons audio focus.

## Limits and remaining device checks

No Muon-specific blocker established in these focus/noisy wiring paths by this reading. It does not settle focus-denial/delay behavior on the target OS, Bluetooth routing, headset unplug timing, interruptions, audibility, service restart, background restrictions or lockscreen rendering.

Framework wake/Wi-Fi handling at ExoPlayerImpl3116–3129 follows playWhenReady in READY/BUFFERING, not suppression alone. A transient focus suppression can therefore retain those locks; that is pinned framework behavior, not proof of a Muon leak or measured battery cost. Do not classify it from source alone without duration/device traces.

Later authorized QA: interrupt playing music with another audio app and transient audio, verify intended pause/resume/duck behavior; pause manually during interruption and ensure it does not restart; unplug a headset and check it pauses rather than blasting the speaker; check notification/Bluetooth transport controls and ordinary normalization/device-volume interactions. Use normal safe listening volume. Deferred Pixel lockscreen settings stay untouched.

Queue/position process-death behavior and broader retained ReplayGain data/resource/identity questions remain in their existing workstreams. A source audit does not prove release readiness or replace the app PR acceptance gate.
