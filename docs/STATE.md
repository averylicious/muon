# Muon: current state

A one-page snapshot for the next session, local or cloud. Update it whenever work merges. It records evidence, not permission (see `CLAUDE.md`). Live truth is GitHub: open PRs, `main`'s latest run, and the newest checkpoint on issue #40.

_Last updated: 2026-09-27, after #172, by Claude Opus 5.5 (Claude Code project thread)._

## Where things are

- **`main`:** the Collection redesign is complete, plus:
  - offline listening: downloads, played-song cache, SD card;
  - auto-connect: an mDNS scan and a LAN port probe;
  - the Android 17 target (SDK 37, local network permission);
  - motion pass 2;
  - landscape layouts (#157), sharp thumbnails (#158), deal-then-skip on the artwork swipe (#159) and the QA polish (#160);
  - Lyrics and Queue in landscape Now Playing's top bar (#162), and the landscape player as a panel beside the list (#164, mockup in `docs/design/landscape/`);
  - volume normalization (#97): an opt-in **Even out volume** switch applying ReplayGain track gain (#163), with untagged songs turned down by the library's typical gain (#165).
  - offline leftovers (#168): Download all on playlists, and moving downloads between phone and SD card, so #112 is closed;
  - design pass (#169–#172): Play/Shuffle on playlists, clearer library subtitle, lyrics on the 16 dp keyline, artist pictures and playlist rows made from album covers, and Now Playing coloured from the song's cover;
  - CI (#167): a docs-only merge on `main` can no longer skip a Canary.
- **Canary:** each `main` build publishes `0.1.0-canary.<run>` automatically. Stable has not been released since the redesign.
- **Package IDs:** Canary is `dev.avery.muon`, Stable is `dev.avery.muon.release`. They keep separate storage, so downloads don't cross channels. Don't change them.

## The user's agreed roadmap

1. **Polish and bug-fixing** (in progress). The device QA findings are on #16. Items 1–16 have shipped, and the after-screenshots were taken on .250 (only item 12 was left as it was).
2. **Volume normalization (#97), before Stable:** shipped.
   - all 962 songs in `~/Music/random playlist` were tagged with `rsgain` on 2026-09-27 (tags backed up, logged in `~/CHANGES.md` with manual steps for new songs);
   - Muon applies the track gain as the player's volume (#163, #165). Listening QA is the user's.
3. **Stable release,** only on the user's explicit request.
4. **Experimental material3 1.5 alpha motion,** on a Canary-only branch after Stable.

## Authorization

- **Standing since 2026-09-27:** merge any PR with no blockers without asking (the user's words: "You are authorized to merge PRs without any blockers without asking for permission in future sessions"). Merge at the CI-verified head and record it on the PR as a self-review; Astra audits later. Releases, Stable tags and anything with a blocker still need the user.
- **Device QA over ADB** on the Pixel 8 (Android 17, wireless) and the Poco X3 NFC (Android 16, USB, SD card), for the polish phase. Settings are changed only temporarily and restored. On 2026-09-27 the user also allowed Canary updates with `adb install -r` (never uninstalls).
- Device QA over ADB still needs the user's go-ahead in each new cycle.

## Open items and known limits

- #83 baseline profiles: needs device measurement.
- #109 "recently liked" sorting: needs Tauon to expose `loved_timestamp`.
- Material 3 Expressive components wait for material3 1.5.0 stable (see #40).
- A new Claude Code on the web session has no local context. Hand it a bounded slice and this file.
