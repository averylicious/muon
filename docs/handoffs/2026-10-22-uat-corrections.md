# October22 user acceptance corrections

User tested the minified Stable-identity candidate .1325 (`0e110692c186a5c4b7672dfaf600e699dedf24fd`) on Pixel8. The authorized wireless-ADB update from installed Stable versionCode173 succeeded in place; APK hash and unchanged Canary package/version were checked. This is not a new published Stable or a full existing-data preservation test.

## User results and finite corrections

- Bluetooth controls: **passed user acceptance** for the controls the user tried. Do not extrapolate to every headset model.
- Queue removal Undo: user reports a removed third-position entry returning at the end, believes Shuffle was on. Expected behavior is the same visible slot with or without Shuffle. Existing code restored the playlist index but not its shuffled placement. Correction captures both orders, rechecks occurrence ownership, uses the existing same-UID URI/saved-copy/queue-budget admission gates and acknowledges the service's insertion plus exact shuffle restoration. Deliberate intervening reshuffles are not overwritten; queue replacement/reordering still refuses stale Undo. Fresh identity prevents the removed row's dismissed state from hiding the replacement. New actual-player/controller tests cover this boundary.
- TalkBack mini-player: user clarified the desired order is **Mini-player, title, artist**, retaining track information. Its decorative progress indicator must not merge a changing percentage into that parent. Playback controls remain separately accessible.
- TalkBack sliders: replace the generic/raw-number description with **Playback position** (elapsed of duration) and **Media volume** (percent), retaining range, adjustment and disabled semantics. No blanket live region is added.
- Queue current song: merge its title/artist/duration with Now playing state into one row announcement. Album artwork becomes identifiable with accessible Previous/Next actions.
- Storage setup: the Pixel screenshot exposed unconditional SD instructions and migration-heavy text on an empty library. Explain phone storage/future downloads, make card instructions conditional in wording, and retain consent/restart, original-preservation and temporary-space disclosures. No backend/default/migration behavior changes.

## Verification and ownership

Coordinator GPT-6 / Codex desktop; exact variant/effort not reported. Author implementation/self-check, not independent review. No Claude session, phone operation or phone-setting change was used for these corrections. The original M3E checkout is untouched. Existing allocated Claude remains idle.

Pinned published sources inspected: Compose BOM2025.10.00 resolves Material3 Android1.4.0 and Compose UI1.9.3; Material3 `Slider.kt` and `ProgressIndicator.kt` confirm built-in range/SetProgress behavior. Media3 ExoPlayer/session1.11.0 sources were downloaded from Google's Maven and inspected for shuffle mutation and acknowledged custom-session commands. Local Android compilation/device speech verification is not implied.

The documentation report #637 landed on main at `6eaeb766604d22e5e4f70b7fbacbb82ad924b648`; it was documentation-only. Its newer state was reconciled into the isolated #636 candidate before these changes. The updated app candidate remains DRAFT/OPEN until final-head CI and focused user retesting clear. Record the new head/run/artifacts on #636 after CI.

## Remaining acceptance

Retest the corrected announcements/slider adjustment and Undo in shuffled and ordinary queues, including duplicate songs. Remaining interactions (library/artist scroll restoration, player-sheet cancellation/Back/reopening, discovery/layout and identity/error feedback) need explicit user results. Full spoken/focus TalkBack remains pending; Bluetooth's reported pass is retained. Previously approved performance, notification-only artwork, deeper SD lifecycle and provenance deferrals remain follow-ups. No new general audit or Stable publication is authorized by this checkpoint.
