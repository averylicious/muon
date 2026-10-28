# Post-RC audit continuation

Updated2026-10-28. Continue authorized engineering to **80% weekly used /20% remaining**, with recovery reserve. A verified PR/checkpoint alone does not end the run. Missing five-hour readings are unavailable; no reset activation.

## Scope and ownership

GPT-6 / Codex desktop is sole writer of the owned drafts, author self-check unless a scoped review is explicitly attributed. **No phones**, installs, music/app-data or host/network changes. Main application baseline20579cf1, current documentation mainf9263f07, alphaee7ddb75 and frozen M3E RC .1420/source6b1a767c remain preserved. Published Stablev0.2.0 is separate. No runtime main merge, alpha landing, Stable tag or frozen-RC replacement; runtime PRs remain open for later acceptance.

## Exact current boundary

| Candidate | Verified evidence | Next |
| --- | --- | --- |
| Combined [#701](https://github.com/averylicious/muon/pull/701) | Last verified30-topic `c66748c6f032890ee2477213452105619df581e7` / [Android .1642](https://github.com/averylicious/muon/actions/runs/40711646130): actual1,995cases/variant, zero failures/errors/skips, lint0errors/65warnings plus3hints. Actual signed-release source/BUILD/checksum/pinned signer/identity/export/profile gates passed, APK `6169bf073f710c2c074af91da53981137069e3c95a915976057349f223f7d26d`, uninstalled. | New32-topic runtime source6c14ce84 includes verified#751/#753 and guard compatibility; its own compile/reports/APK are pending. Earlier31-topic .1645 is superseded, not verified. |
| Audio-only [#751](https://github.com/averylicious/muon/pull/751), owner[#750](../audits/issues/750.md) | `9688fb60310e66fdab7bfd136396e0734b86de62` / [Android .1643](https://github.com/averylicious/muon/actions/runs/40715489798): actual1,822cases/variant including native-selector control; zero failures/errors/skips/lint errors. Report digest verified. | In new#701 merge; integration also checks the preexisting TEXT-disabled policy survives. Mixed-file audio/tags/artwork and visual-only no-audio compatibility need later acceptance. |
| Stable Media3 patch [#753](https://github.com/averylicious/muon/pull/753), owner[#752](../audits/issues/752.md) | `ec3543ba1e1124f8622da50e5916b0072fadf342` / [Android .1644](https://github.com/averylicious/muon/actions/runs/40717264755): actual1,821cases/variant, zero failures/errors/skips/lint errors. Actual signed-release gates passed, APK37df2eee, uninstalled; inventory125 resolves the new Media3 versions and preserves Activity. | Integrated separately into new#701; native ID3 fixed-negative/unknown-positive controls and raw-PCM sample admission correction need the new combined run. No new methods or#671 resolution. |
| Current-state docs [#716](https://github.com/averylicious/muon/pull/716) | `78275dafbd9add6a29421b45a70fa68bf7bb53e3` / [.1640](https://github.com/averylicious/muon/actions/runs/40710268221) passed lightweight checks and merged to mainf9263f07. Main [.1641](https://github.com/averylicious/muon/actions/runs/40710709332) passed; no artifacts. | Closed/merged: do not push more work to that PR. No APK/device QA needed for its documentation-only change. |

[Maintained input table and integration contracts](2026-10-25-combined-post-rc-candidate.md) holds all32 topic heads and prior receipts. #749 exact repeated-row correction passed .1638 and is included in verified30-topic .1642. #747 contrast, #728 lacing, #722 nested JPEG and #745 queue action receipt are likewise included; keep their per-issue limits. Standalone passes do not verify later combinations. Actions is the Android compile, not local Gradle or phone execution.

## Decisions and pending questions

**No unanswered user questions.** Audio-only playback was approved2026-10-28; mixed-file audio/artwork stays, image/video-only sources have no playable audio. User-approved4MiB unfinished timed tags/text packets,64KiB lacing headers, provisional long-AAC/live-WebM limits and unused-subtitle skipping remain. HEVC stays playable with unresolved#730. #646 reset/SD refusal, #401 latency, #671 buffering, deep eject/restart and provenance retain their existing qualifications/deferrals. Future UAT is separate from unanswered decisions.

Fresh user-approved Opus5.5/Claude Code/explicitly High is idle. Its earlier scoped reviews found the full-player action-adapter omission (fixed403 and rechecked), motivated stronger native JPEG evidence and checked selected lacing paths. No whole-candidate independence or current-cycle Claude/Codex subagent contribution. Verify live quota before reuse; do not reload the oversized old Claude history. [Continuous engineering](../continuous-engineering.md) records quota and missed-question safeguards.

## Continue from here

1. Verify new32-topic#701 reports/APK; preserve GuardedMetadataRenderers, original extractor ordering/guards, UNSUPPORTED subtitle factory, disabled TEXT and audio-only VIDEO/IMAGE defaults, raw gain/Tracks, both queue/controller wrappers and all three action adapters.
2. Standalone .1644 is verified and#753 integrated; inspect the new combined native controls/reports before calling its combination verified. HAGC unstable APIs changed; Muon does not call removed setters. The raw-PCM fast path now applies the existing sample-byte limit before returning; the new library no longer fails before a large fixed sample. No measured battery or latency benefit claimed.
3. Continue useful broader engineering while live allowance permits. [Source-boundary evidence](../audits/2026-10-25-post-rc-source-boundaries.md) prevents repeated speculative findings. Phone/format/rendered acceptance needs renewed authorization.

Owned persistent worktrees under `/home/avery/.codex/worktrees/`: `muon-post-rc-acceptance-oct25` (#701), `audio-only-selection-oct28/muon` (#751), `media3-stable-patch-oct28/muon` (#753), `playlist-occurrence-oct28/muon` (#749), `artwork-text-contrast-oct28/muon` (#747), plus prior topic paths in their receipts. Root owns these; verify live clean/idle state before writing. The old `muon-post-rc-state-oct25` branch is closed#716. Task attachment cap100 is full; only obsolete verified-merged PR links were removed (#205/#206/#209/#210/#212/#217/#219/#221), without changing GitHub history. Do not recreate existing worktrees or archive unrelated work. Temporary files/local sessions are conveniences, not recovery prerequisites.
