# Main audit checkpoint: external controller policy

## Scope and ownership

GPT-6 Astra (Codex desktop; effort not reported) implements N2 from [the independently reviewed network report](../audits/2026-09-30-network-entry-points.md), with UID-based own-app identification as defense in depth. Original findings: Claude Sonnet 5.5, Claude Code desktop. Astra's implementation review is self-review, not a second independent author review.

Own branch: `codex/controller-command-policy`, in the existing isolated `muon-main-audit` worktree. Destination: `main`. The user and their Claude session retain the experimental checkout/session. No Claude session, phone, signing identity, network settings or Stable release is touched. The exact final head, base, CI and artifacts belong on the linked PR and [#181](https://github.com/averylicious/muon/issues/181); verify them live on resumption.

## Behavior and evidence

- `PlaybackService` installs `PlaybackSessionCallback`. Same-UID controllers (including Muon's UI and in-process notification controller) retain full commands and existing stream-URI validation.
- Untrusted foreign controllers remain rejected. Trusted foreign controllers get an explicit transport/read allowlist, including seeking existing entries and shuffle/repeat. Queue/configuration/gain edits and future unspecified commands are withheld.
- Foreign item resolution returns a failed future, including empty requests. Media3's default `onSetMediaItems` propagates failure instead of resolving an empty replacement queue. Own-app intentional clears still succeed.
- Media3 can map System UI requests to its in-process notification controller. The policy applies to identities Media3 supplies to the app callback; it intentionally preserves that system integration, rather than claiming every OS request passes a foreign-UID check.
- This does **not** fix N1: the exported service's start-intent route can still run as the internal notification controller. No package-name spoofing exploit is claimed; UID identity avoids treating labels as authority.

Published sources inspected: `androidx.media3:media3-session:1.11.0` and `media3-common:1.11.0`, from Google's Maven `-sources.jar` files. Relevant symbols: `MediaSession.Callback.onConnectAsync/onSetMediaItems`, `ControllerInfo.createTestOnlyControllerInfo`, `ConnectionResult`, `MediaSessionImpl.onConnectOnHandler`, legacy set-items dispatch, and player command definitions. The implementation uses the current asynchronous callback; Media3 calls it after detecting that the deprecated default callback was not overridden.

## Validation and remaining QA

Nine Robolectric cases exercise the production callback with the pinned test-only controller factory and a real session/player: UID/package-label separation, trusted command limits, unknown legacy UID, failed external add/empty/set requests, default set-items failure with an existing queue, legitimate app resolution/clear, and invalid URI rejection. They do not simulate Binder caller authentication or Android notification/headset dispatch. No audio/network is started. CI is the first Android compile/test execution; consult the PR for results rather than treating this checkpoint as a pass.

Manual QA remains **pending user testing**:

1. In-app select/add/remove/reorder/clear, then play again; queue controls should still work.
2. With a multi-song queue, verify notification/lock-screen and headset play/pause/next/previous/seek, and shuffle/repeat where available.
3. If a trusted external controller supports play-from-URI/search or queue replacement, it should be denied without clearing or interrupting the existing queue. A cross-process helper requires fresh device authorization.
4. Verify system media volume and Muon's normalization still work. External session setters for per-player gain are intentionally unavailable.

Main-track artifacts update the same Canary package as experimental builds and can replace experimental features. Do not recommend them blindly over the user's experimental QA build.

## Next boundary

1. Check latest-head CI and review the implementation; fix failures before merge. Apply standing merge rules only after the required checks pass. Keep device results pending until supplied.
2. Handle N1 separately: source-review and document service-export compatibility, add compiled-manifest checks (Q4), and provide targeted notification/headset QA. Do not call N2 an N1 fix.
3. N3 total request deadlines/cancellation follows. Q1 auto-connect policy remains a product decision; do not silently change it.
4. Forward-integrate useful main fixes through a separately tested PR into `claude/m3-expressive-alpha`; the experimental owner lands it at their boundary.

No background agent or device task was started. Commit/checkpoint state is the source of recovery; refresh quotas rather than reusing a previous account's reading.

## Completed implementation boundary

#204 merged at `4fd3af9373bfa232e637c06e9705dae49b9b8fe1`. Reviewed head `707af27b8e454794926169ca83d4d4000214e028` passed [run 362](https://github.com/averylicious/muon/actions/runs/36603764830): 387 tests per variant, all nine callback cases, lint and signed APK checks. Two earlier runs exposed test-fixture/API mistakes, corrected without changing production validation. Main publication must be verified separately. Phone QA remains pending. The N1/Q4 follow-up is [#205](https://github.com/averylicious/muon/pull/205); use its [handoff](2026-09-30-private-service.md) and latest build for combined QA.
