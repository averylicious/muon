# Main audit checkpoint: private playback service and APK export gate

## Ownership and scope

GPT-6 Astra (Codex desktop; effort not reported) owns `codex/private-playback-service` in the isolated main-audit worktree. This slice addresses N1 and Q4 from [Sonnet's network audit](../audits/2026-09-30-network-entry-points.md), reviewed and merged as #203 at `916a50597b4577bea2ffcb192bcd14e525b0b30d`. N2's separate controller policy is #204, now merged at `4fd3af9373bfa232e637c06e9705dae49b9b8fe1`; its exact-head run 362 passed all 387 tests per variant, lint and APK checks. This branch includes that merge. No Claude session, experimental checkout, phone or network configuration is touched.

## Change and compatibility boundary

`PlaybackService` becomes non-exported. Android then blocks ordinary foreign-UID direct service start/bind requests, including the unauthenticated media-button route described in N1. This is a manifest boundary, not a caller UID check inside `onStartCommand` (that method does not expose the original start caller reliably).

Muon binds from its own UID. Media3's default notification uses immutable PendingIntents created by Muon; token-based platform media sessions are a distinct route. These are source-supported reasons to expect notification/headset playback to continue, **not device proof**. External clients trying to discover/bind directly to Muon's service will no longer be supported. Do not silently call that a transparent change. The [Android Media3 guide](https://developer.android.com/media/media3/session/background-playback) uses an exported service for discoverable external-client access; [the service manifest contract](https://developer.android.com/guide/topics/manifest/service-element) defines private-service access.

Pinned Media3 `DefaultActionFactory.createMediaActionPendingIntent`, `MediaSessionService.onStartCommand`, `MediaSessionImpl.onMediaButtonEvent/applyMediaButtonKeyEvent` and notification controller creation were inspected in the published session sources JAR. N2 alone cannot block N1 because start intents can act as Muon's internal controller.

## CI gate and evidence

`tools/verify-apks.py` now runs the SDK's `apkanalyzer manifest print` on **both signed APKs**, after manifest merging/shrinking, then validates the components. CI already installs/uses `cmdline-tools/latest` for sdkmanager. Missing tooling or unreadable output fails the check; it does not skip validation.

The allowlist permits the launcher without a permission, and two dependency components only with their current protections: Media3's `BluetoothValidationActivity` with `BLUETOOTH_PRIVILEGED`, and `ProfileInstallReceiver` with `DUMP`. Required Muon services must be private. Unknown exports, changed/missing protective permissions, ambiguous export booleans and missing required components fail closed. New explicitly private dependency components are allowed. Dependency updates that alter this contract require review, not blind allowlist expansion.

Nine Python regression cases pass locally. The checker rejects the actual decoded main-track Canary .357 manifest because PlaybackService is exported; changing only that flag in memory makes that same manifest shape pass. That experiment validates the gate, not a newly built APK. The PR must record the exact-head compile, tests, lint, signing/manifest checks and artifact results before any merge decision.

## Required user QA before landing this compatibility change

Leave this PR open even if CI passes until this boundary is resolved. No device access has been requested or used.

- Start a multi-track queue in Muon, lock the phone/background the app, and verify uninterrupted playback plus notification/lock-screen play/pause, previous/next and seek.
- Check Bluetooth or wired headset media buttons, including paused/resumed playback and noisy-device unplug handling where applicable.
- Reopen Muon while playback is active and after the service has stopped; internal controller reconnection must work.
- Identify any external companion/automation/controller app that binds directly to Muon. Such binding will now be denied intentionally. If needed, choose an authenticated exported bridge design instead of dropping compatibility without agreement.
- A separate unprivileged helper should verify explicit media-button service starts are denied. This is pending runtime verification and requires new phone authorization if run on the user's device.

The main-track artifact updates the same Canary package; it can replace experimental features and should not be installed blindly over the experimental build. No Stable release/tag is part of this work.

## Resume

Check #204 and this PR, current main, exact-head checks and the latest #181 comment. Preserve clean branch boundaries and the user-owned experimental checkout. If main moves, merge it into this branch, reconcile checkpoint pointers and rerun CI before landing. The production N1 change and export gate must travel together. Forward integration goes through a separate tested experimental PR; its owner lands it.

N3 request deadlines/cancellation is the next independent code slice. Q1 discovery/remembered-address behavior remains a product decision. Existing offline-card preservation work (#179) remains unresolved. No active local test/build, Claude or device process is needed to resume these commits.

## Recorded boundary before refreshed CI

- Open PR: [#205](https://github.com/averylicious/muon/pull/205). Initial head `b32494a2fed30e0d03f27587c4b3c5d69cba233d` passed [run 360](https://github.com/averylicious/muon/actions/runs/36602311933), including the packaged export gate on both signed variants. That initial artifact lacked #204.
- Main `4fd3af9373bfa232e637c06e9705dae49b9b8fe1` is incorporated; only the three latest-checkpoint links conflicted, resolved to this handoff while retaining both implementation histories. The updated combined head needs fresh CI; its result/artifact is recorded on #205 and #181.
- #202's CI fix is merged and published as main-track Canary .356. #203's report is merged; main run 358 passed the lightweight path with no APK. #204's main publication is a separate run to verify, not implied by its branch result.
- Astra's checkout is clean when committed/pushed; no Claude or device task is active. This PR stays open for the compatibility gate. A successor starts from current GitHub heads/checks and this checkpoint, not old account quota percentages.
