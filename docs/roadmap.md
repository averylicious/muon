# Road to 1.0

What "1.0" should mean for Muon, and what has to be true before the version number claims it. Muon is
currently a working MVP on a private Canary channel: it streams, it holds a queue, and its UI is being
rebuilt. It is not yet something to hand to someone who has never met it.

**1.0 means: a person installs it, connects once, and it behaves — on their device, in their
language of interaction, without an agent standing behind it.** Everything below follows from that.

## 0.2 — UI/UX foundation *(in flight)*

Rebuild the surface so later work is not rewritten. This is the current `UI/UX rework` milestone.

**Exit criteria**
- The remaining UI/UX issues are closed or explicitly deferred with a reason.
- No screen relies on colour alone to signal state.
- One motion, icon, colour and type system, applied from tokens rather than per call site.
- The user has QA'd each change on a real device and said so on the PR.

## 0.3 — Correctness and performance

Stop reasoning about behaviour and start measuring it.

**Exit criteria**
- **#23 is measured, not argued.** A profile exists showing where list frames go, the fix addresses
  what the profile implicates, and the user confirms it against the build they compared to.
- **#26's backend audit is complete**, with findings either fixed or filed.
- **Queue and position survive process death.** Today they do not (README says so plainly); a music
  player that forgets what it was playing is not 1.0.
- **The server disappearing mid-playback is defined behaviour**, not whatever happens. Same for a
  DHCP address change, a Wi-Fi drop, and a VPN that eats the LAN.
- Duplicate track IDs in a playlist resolve to the row the user actually tapped.

## 0.4 — Verification

The category CI cannot touch. Most 1.0 risk lives here.

**Exit criteria**
- **A TalkBack pass over every screen**, by a person. Every `contentDescription` and
  `stateDescription` in the app was written without ever being heard.
- **Insets, landscape, split screen and the largest font and display sizes** checked per screen
  (#16), with results recorded rather than assumed.
- **More than one device and more than one Android version.** Everything to date is one Pixel.
  Minimum is Android 9 (minSdk 28) and current, since the palette and launch-theme code paths
  genuinely differ across that boundary.
- **Instrumentation runs somewhere.** `DirectPlaybackTest` exists and CI never executes it; either
  wire it up or delete it, but do not keep a test that creates false confidence.
- Battery behaviour during a long background playback session is observed at least once.

## 1.0-rc — Freeze and rehearse

No new features. Prove the release machinery and the first-run experience.

**Exit criteria**
- **Feature freeze.** Anything not already in is 1.1.
- **A stable `vMAJOR.MINOR.PATCH` tag is cut and verified end to end**: the workflow's safeguards,
  the signed APK identity, the published release, and Obtainium actually fetching the update.
- **A clean-install first run** on a device that has never had Muon, following only the README.
  Anywhere the reader has to guess is a bug.
- **README is a user manual**, not a development log.
- Every open issue is closed, moved to 1.1, or closed as "won't do" with a reason.
- Attribution and validation evidence on merged PRs are accurate, since they are the audit trail.

## What is deliberately not in 1.0

Naming these prevents scope creep from being mistaken for progress.

- Shared-element transitions and gesture navigation (#2's note stands: no new gesture language
  without the user asking).
- Offline caching or downloads. Muon is a LAN streaming client.
- Controlling desktop Tauon playback. Android playback stays independent.
- Any authentication scheme for Tauon's API. It is unauthenticated by design and LAN-only.
- Play Store distribution. Obtainium is the channel.

## Sequencing note

0.3 and 0.4 can overlap — measurement and verification need the same device time — but **0.2 should
land first**, because verification of a surface that is still being rebuilt has to be redone. The
honest risk to the schedule is 0.4: it is the only phase that cannot be parallelised across agents,
since it depends entirely on the user's hands and eyes.
