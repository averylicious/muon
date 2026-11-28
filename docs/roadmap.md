# Road to 1.0

What "1.0" should mean for Muon, and what has to be true before the version number claims it.

**1.0 means: a person installs it, connects once, and it behaves — on their device, without an agent standing behind it.**

> **Status, 2026-11-28:** Stable [`v0.3.0`](https://github.com/averylicious/muon/releases/tag/v0.3.0) ships Gen 3 (M3 Expressive) on top of the Gen 2 Collection redesign, offline saved copies (downloads, the played-song cache and SD card storage), volume normalization and a Baseline Profile. Releases are public through Obtainium. Gen 4 - Progressive Blur is in progress on `gen-4-progressive-blur` (#796). [STATE.md](STATE.md) has the live detail; the [release plan](release-plan.md) has the current gates.

## Done

- **UI/UX foundation** (Gen 2 and Gen 3): one motion, icon, colour and type system from tokens; no screen relies on colour alone for state; each change QA'd by the user on a device.
- **Measured performance and audits:** list frame pacing (#23), the backend correctness audit (#26) and the insets/landscape/font-scale pass (#16) are closed.

## Before 1.0

**Correctness**
- **Queue and position survive process death.** Play modes are remembered; the queue itself is not yet.
- **The server disappearing mid-playback is defined behaviour**, as are a DHCP address change, a Wi-Fi drop and a VPN that hides the LAN.

**Verification** (most 1.0 risk lives here, and only the user's hands can do it)
- **A TalkBack pass over every screen**, by a person.
- **More than one device and Android version**, from minSdk 28 to current: palette and launch-theme code differ across that range.
- **Instrumentation runs somewhere.** `DirectPlaybackTest` exists and CI never executes it; wire it up or delete it.
- Baseline Profile benefit measured (#83), and battery observed over one long background session.

**Release candidate**
- **Feature freeze:** anything not already in is 1.1.
- **A clean-install first run** on a device that has never had Muon, following only the README.
- **README is a user manual**, not a development log.
- Every open issue is closed, moved to 1.1, or closed as "won't do" with a reason; PR attribution and validation evidence are accurate.

## Deliberately not in 1.0

- Controlling desktop Tauon playback. Android playback stays independent.
- Any authentication scheme for Tauon's API. It is unauthenticated by design and LAN-only.
- Play Store distribution. Obtainium is the channel.
- New gesture language the user has not asked for.

Verification cannot be parallelised across agents: it depends on the user's device time, so it is the honest risk to the schedule.
