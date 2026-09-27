# Muon: current state

A one-page snapshot for the next session, local or cloud. Update it whenever work merges. It records evidence and the user's standing decisions, but it is not a substitute for checking live state. GitHub is the truth: open PRs, `main`'s latest run, and the newest checkpoint on issue #40.

_Last updated: 2026-09-27, main audit foundation, by GPT-6 Astra (Codex). See the audit PR for its exact reviewed head and CI evidence._

## Where things are

- **Audit baseline:** `main` at `f0909f0c5174174a999240e8cebd7900702d2cc0`, through #178. This audit branch adds backup/transfer exclusions, subnet-safe discovery and conservative CI selection; see [the findings report](audits/2026-09-27-main.md). Check its PR before assuming the fixes have merged.
- **`main` features:** the Collection redesign and everything after it through #178. Feature by feature, [features.md](features.md) is the user-facing guide, and [design/current](design/current/README.md) has real screenshots of every screen. Recent work, newest first:
  - README hero and CI test cleanup (#178);
  - README simplified, and its technical parts moved into [features.md](features.md), [desktop-setup.md](desktop-setup.md) and [building.md](building.md) (#177);
  - reference screenshots in `docs/design/current/` (#176);
  - a Baseline Profile recorded on the Pixel, and the tools to re-record it (#175, [baseline-profile.md](baseline-profile.md));
  - design pass (#169–#172): Play and Shuffle on playlists, artist pictures and playlist rows made of album covers, and Now Playing coloured from the cover;
  - offline leftovers (#168): Download all on playlists, and moving downloads between the phone and the SD card;
  - CI (#167): a docs-only merge right after an app merge can no longer skip a Canary;
  - volume normalization (#163, #165) and landscape layouts (#157, #162, #164).
- **Canary:** each `main` build that changes the app publishes `0.1.0-canary.<run>`. **Stable** has not been released since the redesign.
- **Package IDs:** Canary is `dev.avery.muon`, Stable `dev.avery.muon.release`, and the Baseline Profile tool `dev.avery.muon.benchmark` (never published). Don't change them.
- **Desktop:** all 962 songs in `~/Music/random playlist` carry ReplayGain tags (2026-09-27). `~/CHANGES.md` has the manual steps for tagging new songs.

## The user's agreed roadmap

1. ~~Polish, volume normalization, Baseline Profile~~: done.
2. **Main audit, now authorized:** Astra reviews correctness, security, privacy, CI and general code quality. Follow [the audit map and continuation plan](audits/README.md). Agents may follow findings across components; the workstreams organize handoff rather than impose scope restrictions.
3. **Parallel experimentation:** Claude and the user may develop experimental Material 3 libraries on a separate branch while main is audited. This supersedes the earlier Stable-first sequence. No experimental release feed has been selected. Main still publishes the existing Canary feed; feature branches produce Actions artifacts and share the Canary package/signing identity. Coordinate any future channel change explicitly.
4. **Stable release:** only on the user's explicit request, after reviewing outstanding findings and QA. It also brings the redesign to the Stable app, which is still the pre-redesign 0.1.0.

## Authorization

- **Standing since 2026-09-27:** merge any PR with no blockers without asking. The user's words: "You are authorized to merge PRs without any blockers without asking for permission in future sessions". Merge at the CI-verified head and record it on the PR as a self-review; Astra audits later. **Stable releases, release tags and anything with a blocker still need the user.**
- **Device access over ADB needs the user's go-ahead in each new conversation.** Once given, it has covered:
  - screenshots and UI automation;
  - Canary updates with `adb install -r`;
  - installing, then uninstalling, the two Baseline Profile tools.
  Never uninstall Canary or Stable. Mute media volume before anything plays, and restore every setting you change (dark mode, rotation, font scale, volume).
- **Devices:**
  - Pixel 8 (Android 17): wireless ADB, and the port changes, so check `adb devices`.
  - Poco X3 NFC (Android 16, custom ROM): USB, with a SanDisk SD card.

## Open items and known limits

- **#83:** the Baseline Profile ships, but its before-and-after speed measurement hasn't been done.
- **SD-card lifecycle (#179):** source review found that `OfflineStore.card` is initialized only at process-store creation. Mid-process insertion/ejection/reinsertion handling needs a fix and user SD-card QA; see A4 in [the audit report](audits/2026-09-27-main.md). No data-loss claim or phone reproduction yet.
- **Fresh-install discovery:** on 2026-09-27, a fresh install (Muon Benchmark) scanned the network and found no Tauon, although Tauon answered and the phone could reach port 7814. Typing the address worked. This is not investigated yet; check Canary on a fresh install before assuming it's fixed.
- **Queue after process death:** the queue and position are not restored (see [roadmap](roadmap.md) 0.3).
- **Down the line:** 128 kbps downloads need a Tauon change, and "Recently liked" sorting (#174, not planned) needs Tauon to expose `loved_timestamp`.
- A new cloud session has no local context: hand it this file, [the audit map](audits/README.md) and [the coordinator runbook](coordinator-handoff.md). Verify actual heads and ownership; no local session ID is required.
