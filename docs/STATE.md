# Muon: current state

A one-page snapshot for the next session, local or cloud. Update it whenever work merges. It records evidence and the user's standing decisions, but it is not a substitute for checking live state. GitHub is the truth: open PRs, `main`'s latest run, and the newest checkpoint on issue #40.

_Last updated: 2026-10-05, main source-audit continuation (no new device work), by GPT-6 (Codex desktop; effort not reported). See [progress and release gates](audits/2026-09-30-progress.md) and #181 for newer evidence._

## Where things are

- **Audit continuation:** [HTTP controls and acceptance checkpoint](handoffs/2026-10-05-http-controls-and-acceptance.md). Test-only#337 merged after exact481-test-per-variant CI and source self-review; actual#302 Canary.604 verified622 tests/variant, remains OPEN for user QA with#331/#335 and earlier candidates. Actual main608 publication/BUILD.txt verified; one final#302 QA refresh is pending in this snapshot, and live receipts supersede. No device/experiment work; allocated Claude idle near its quota boundary. Full#179/#213/#230/#253 and hardware/performance gates remain.

- **Audit:** #180 merged as `bdb2da0065e316533912269bf3a5addf7afaf617`, adding backup/transfer exclusions, subnet-safe discovery and conservative CI selection. Its exact head passed run 290. [#181](https://github.com/averylicious/muon/issues/181) tracks remaining coverage; see [the findings report](audits/2026-09-27-main.md).
- **Landscape background:** #183 merged as `2211d275523856949efdf201610e51d4553ffe56` after independent Astra source review and successful exact-head run 303. Main-based light/Pure-black phone QA remains pending.
- **Cache audit:** #192 merged the [disposable Media3 characterization harness](audits/2026-09-29-cache-characterization.md). All five cases passed in both variants, demonstrating the modeled index-loss sequence; main run 331 published Canary .331. #179 remains unfixed.
- **Publication audit:** the [published-baseline fix](audits/2026-09-29-publication.md) prevents tags without a complete published Canary from hiding app work. #202 merged at `a281073eddb90813d8d4a95078567c57a0b47e4a` after successful exact-head run 354. Check #181 for main publication status.
- **Network/entry points:** [Sonnet's report with Astra's source review](audits/2026-09-30-network-entry-points.md) identifies playback-controller and request-lifetime follow-ups. No fix or device reproduction is claimed in #203; auto-connect remains the existing approved behavior.
- **Parallel work:** [the track protocol](parallel-tracks.md) separates sessions/worktrees and defines checked forward-sync PRs. Experimental owners land those PRs; Astra does not move their active branch. Inspect live checks/protection and the checkpoint for deployment status.
- **`main` features:** the Collection redesign and everything after it through #183. Feature by feature, [features.md](features.md) is the user-facing guide, and [design/current](design/current/README.md) has real screenshots of every screen. Recent work, newest first:
  - matching landscape player background (#183);
  - first audit fixes and portable audit plan (#180);
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
3. **Parallel experimentation:** Claude and the user are developing experimental Material 3 libraries on `claude/m3-expressive-alpha`. This supersedes the earlier Stable-first sequence. That branch currently uses Actions artifacts and the existing Canary package/signing identity. Main still publishes the existing Canary feed. A newer main APK can replace experimental features, so identify the branch before recommending installation. Follow [parallel tracks](parallel-tracks.md); coordinate any future channel change explicitly.
4. **Stable release:** only on the user's explicit request, after reviewing outstanding findings and QA. It also brings the redesign to the Stable app, which is still the pre-redesign 0.1.0.

## Authorization

- **Phone-QA gate, clarified 2026-09-30:** leave any PR requiring phone QA open while broader code auditing continues until the user supplies that QA or changes this instruction. This overrides blocker-free self-merging for those PRs.

- **Standing since 2026-09-27:** merge any PR with no blockers without asking. The user's words: "You are authorized to merge PRs without any blockers without asking for permission in future sessions". Merge at the CI-verified head and record it on the PR as a self-review; Astra audits later. **Stable releases, release tags and anything with a blocker still need the user.**
- **Parallel sessions, since 2026-09-28:** the user requested autonomous audit/experiment work with repository safeguards instead of routine live coordination. The user may allocate a separate audit Claude session; that does not authorize resuming their experimental session. Main-to-experiment syncs are separate PRs landed by the experimental owner at their own boundary.
- **Device access over ADB needs the user's go-ahead in each new conversation.** Once given, it has covered:
  - screenshots and UI automation;
  - Canary updates with `adb install -r`;
  - installing, then uninstalling, the two Baseline Profile tools.
  Never uninstall Canary or Stable. Mute media volume before anything plays, and restore every setting you change (dark mode, rotation, font scale, volume).
- **Devices:**
  - Pixel 8 (Android 17): wireless ADB, and the port changes, so check `adb devices`.
  - Poco X3 NFC (Android 16, custom ROM): Wireless ADB (may disconnect), with a SanDisk SD card.

## Open items and known limits

- **Private playback service (#205):** main still has the direct exported-service route. The private-service fix and packaged-manifest gate are refreshed on their PR for a current test build, but remain open for notification/lockscreen/headset/reconnect/companion compatibility QA. Source/controller policy #204 alone does not close this route. See [the focused handoff](handoffs/2026-09-30-private-service.md).

- **#83:** the Baseline Profile ships, but its before-and-after speed measurement hasn't been done.
- **SD-card lifecycle (#179):** presence is captured at store creation, and deeper review found a possible stale-cache/index purge leading to lost downloads after removal/reinsertion. See [the source evidence and constraints](audits/2026-09-28-storage.md). The [disposable JVM harness](audits/2026-09-29-cache-characterization.md) separates the modeled cache behavior from phone/mount behavior. A data-preserving fix remains a priority; no device loss has been observed in this audit.
- **Fresh-install discovery:** on 2026-09-27, a fresh install (Muon Benchmark) scanned the network and found no Tauon, although Tauon answered and the phone could reach port 7814. Typing the address worked. This is not investigated yet; check Canary on a fresh install before assuming it's fixed.
- **Queue after process death:** the queue and position are not restored (see [roadmap](roadmap.md) 0.3).
- **Down the line:** 128 kbps downloads need a Tauon change, and "Recently liked" sorting (#174, not planned) needs Tauon to expose `loved_timestamp`.
- A new cloud session has no local context: hand it this file, [the audit map](audits/README.md) and [the coordinator runbook](coordinator-handoff.md). Verify actual heads and ownership; no local session ID is required.
