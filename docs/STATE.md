# Muon: current state

A one-page snapshot for the next session, local or cloud. Update it whenever work merges. It records evidence, not permission (see `CLAUDE.md`). Live truth is GitHub: open PRs, `main`'s latest run, and the newest checkpoint on issue #40.

_Last updated: 2026-09-27, by Claude Opus 5.5 (local Claude Code session)._

## Where things are

- **`main`:** the Collection redesign is complete, plus:
  - offline listening: downloads, played-song cache, SD card;
  - auto-connect: an mDNS scan and a LAN port probe;
  - the Android 17 target (SDK 37, local network permission);
  - motion pass 2;
  - landscape layouts (#157), sharp thumbnails (#158), deal-then-skip on the artwork swipe (#159) and the QA polish (#160).
- **Canary:** each `main` build publishes `0.1.0-canary.<run>` automatically. Stable has not been released since the redesign.
- **Package IDs:** Canary is `dev.avery.muon`, Stable is `dev.avery.muon.release`. They keep separate storage, so downloads don't cross channels. Don't change them.

## The user's agreed roadmap

1. **Polish and bug-fixing** (in progress). The device QA findings are on #16. Items 1–16 have shipped; after-screenshots are pending.
2. **Volume normalization (#97), before Stable:**
   - the user tags the library with `rsgain` on the desktop, with their go-ahead, tags only, logged in `~/CHANGES.md`;
   - then Muon applies ReplayGain track gain as an opt-in setting with clipping protection.
3. **Stable release,** only on the user's explicit request.
4. **Experimental material3 1.5 alpha motion,** on a Canary-only branch after Stable.

## Authorization in the current cycle (2026-09-26 and 27)

- Self-merge at the CI-verified head, recording the review on the PR (the user's go-ahead; Astra audits later).
- **Device QA over ADB** on the Pixel 8 (Android 17, wireless) and the Poco X3 NFC (Android 16, USB, SD card), for the polish phase. Settings are changed only temporarily and restored; no APK installs or uninstalls.
- Confirm both again in a new cycle.

## Open items and known limits

- #112 leftovers: playlist downloads, and moving downloads between the phone and the card.
- #83 baseline profiles: needs device measurement.
- #109 "recently liked" sorting: needs Tauon to expose `loved_timestamp`.
- Material 3 Expressive components wait for material3 1.5.0 stable (see #40).
- A new Claude Code on the web session has no local context. Hand it a bounded slice and this file.
