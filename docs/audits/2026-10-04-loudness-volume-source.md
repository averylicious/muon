# Loudness and volume source pass — 2026-10-04

Inspected main `8ed4e4ca9932fbb01115ec136a9c5643bf9749c2`. GPT-6 / Codex desktop, effort not reported: source inspection and self-review, no independent review or device test. This narrows playback/resource coverage without changing app behavior or claiming measured audio safety/performance.

## Verified source paths

- `ReplayGain.kt:29-42`: parsed ReplayGain gains must be finite and within ±51 dB; parsed peaks must be finite and positive. Integer R128 conversion remains range checked. `appliedGainDb`58-61 limits the selected gain by tag peak headroom and zero. `volumeForGain`72 clamps its result to [0,1]. This normal parsed-tag path cannot request amplification above unity; it is not a limiter or a guarantee that tracks have equal perceived loudness. The peak tag is trusted metadata rather than a measured waveform peak.
- `PlaybackService.onTracksChanged` selects metadata from selected audio tracks, computes `appliedGainDb`, stores it against the current media ID and reapplies player volume. Transition and settings-toggle paths also reapply volume. Normalization changes the player scalar, not the Android stream-volume slider. Persisted gain keys include the media ID's endpoint/numeric track identity; numeric-ID reuse can therefore select an older attenuation until new tags arrive. This does not resolve #213's retained-audio identity policy.
- `ReplayGainSettings.volumeFor`114-115 returns unity when disabled, otherwise the track's remembered gain or the median of remembered gains, then unity if none exist. It does not inherit only the immediately preceding track. A PlaybackService transition comment still describes unknown tracks as unchanged; the implemented fallback is the median. No behavior change is proposed merely to match that older comment.
- `ReplayGainSettings.remember`97-102 invalidates the cached median only when a gain changes. Known-track lookup short-circuits the fallback; do not describe every track transition as sorting the whole history. The preference listener is retained and explicitly unregistered in `PlaybackService.onDestroy`104-106 before session/player cleanup.
- `MediaVolumeController.start/stop`52-62 removes the prior poll before scheduling, and stops it by clearing the running flag/removing callbacks. The composable registers ON_START/ON_STOP and performs observer removal plus stop on disposal (98-115). `setVolume` checks fixed-volume state, clamps the requested step, catches an Android refusal and refreshes. The one normalized-state JVM test does not test Android audio policies, lifecycle callback timing or multiple composed controllers.
- `data_extraction_rules.xml` excludes the shared-preferences domain from both cloud backup and device transfer. This includes remembered gain keys; these source rules are not proof of every vendor's transfer behavior. No private preference values or music files were inspected here.

## Remaining questions, not new confirmed defects

- **Aggregate remembered-gain work (#253):** for an unknown track after invalidation, `typicalGain()`106-108 synchronously obtains all preference entries, filters floats and sorts them through `typicalGainDb`64-69. Entries have no explicit cardinality/byte budget or retirement policy. The work scales with retained history, but no heap size, jank, leak or OOM was measured. Do not choose a destructive cap, delete gains or assume encrypted storage would bound resources.
- **Stored-value contract:** the normal caller writes finite nonpositive applied gains. `remember` itself accepts arbitrary floats, and `gainFor` only rejects NaN; malformed local preference types/values are outside that checked normal path. No untrusted app/API writer to these preferences was found in this scoped inspection. A recovery/validation change should have focused persistence tests and preserve compatible stored values; do not label this a remote vulnerability without a reachable writer.
- **Audio/platform QA:** tag arrival timing, missing/incorrect tags, output routes, fixed-volume devices, settings toggling and interrupted playback still need authorized device/manual checks. No new phone access or settings changes occurred. Existing playback QA gates remain open.

## Existing test evidence

Downloaded Android561 report XML at `5d5b69451f797b70ddcf71092f9d0c607eee094e` contains nine passing `ReplayGainTest` cases and one passing `MediaVolumeTest` in **each** variant, zero failures/errors/skips. These app files are unchanged at the inspected main merge. Cases exercise parsing, gain/headroom conversion, median arithmetic and normalized slider state; they do not exercise SharedPreferences persistence, service listener lifecycle, tagged streams or actual sound. CI is not audio QA.

## Next bounded verification

Use disposable preferences to check settings/remembered-gain/fallback behavior across recreation and toggling, and ordinary 1k/10k remembered histories for representation counts. Timing/heap measurements need a controlled workload and should remain separate from correctness assertions. Keep the no-amplification contract and user metadata; do not turn this source pass into an arbitrary resource limit or an unrequested loudness redesign.

## Later verification receipt

[#323](https://github.com/averylicious/muon/pull/323) added four disposable settings persistence/toggle/median/reload controls after this first source note. Actual Android566 XML469 tests/variant, zero failures/errors/skips; all four cases executed each. Main mergef270cce8c646bc2e0d0f926adcbef38a89de6365 and actual Canary.568 publication verified. These controls do not establish crash-to-disk durability, service callbacks, audio or resource timing. Remembered-history capacity remains a separately scoped #253 question.
