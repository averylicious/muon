# Post-RC audit continuation

Updated2026-10-29. Continue authorized engineering to **80% weekly used /20% remaining**, with recovery reserve. A verified PR/checkpoint alone does not end the run. Missing five-hour readings are unavailable; no reset activation.

## Scope and ownership

GPT-6 / Codex desktop is sole writer of the owned drafts, author self-check unless a scoped review is explicitly attributed. **No phones**, installs, music/app-data or host/network changes. Main application baseline20579cf1, current documentation mainf9263f07, alphaee7ddb75 and frozen M3E RC .1420/source6b1a767c remain preserved. Published Stablev0.2.0 is separate. No runtime main merge, alpha landing, Stable tag or frozen-RC replacement; runtime PRs remain open for later acceptance.

## Exact current boundary

- **Verified33-topic HEIC-inclusive #701/18bc713481059e224ac06be7a9d3e956a66b3c59 / [Android .1652](https://github.com/averylicious/muon/actions/runs/40732993240):** actual1,997cases/variant, zero failures/errors/skips, lint0errors/62warnings plus3hints. All6nested/13MP4/11Matroska/5extractor/1selector methods passed both. Actual release gates passed; APK `c275988d9e8d5fb5611425c00175c941e745961378336d37b111757ff72a581d`, uninstalled. User accepted32MiB HEIC rare-preamble restriction.
- **New build-Guava integration:** standalone #755/a7ec5ca682f98bcf7f409a77cac5777d12798d92 / [Android .1653](https://github.com/averylicious/muon/actions/runs/40733686893) passed actual1,821cases/variant and inspected release gates; APK97076cdb, uninstalled. Inventory129 selects patched JRE, all app configurations exactly equal prior app-only127. Current combined refresh needs its own compile/reports/inventory/APK; topic count remains33.
- Selected AGP local-state deserialization and Gradle plugin resolution/filtering are source-checked; graph and hash-matched JRE bytecode observed. No loaded-class/Binder/OOM exploit. Separate Gradle distribution source pin33.4.6 remains an unresolved caller/upstream question, not fixed by plugin constraints. [#754](../audits/issues/754.md) and [advisory refresh](../audits/2026-10-29-dependency-advisory-refresh.md) preserve qualifications and prior tool deferrals.
- Exact frozen .1420 offline R8/APK inspection narrows the selected Guava gadgets without replacing/inspecting any phone. Published Stable/main runtime/alpha unchanged. Prior #701/.1650 and selected runtime graph checks remain historical evidence; no duplicate prior build findings.
- Docs#716 merged mainf9263f07; main.1641 passed lightweight checks without APK/app change. Do not reuse that closed PR.

[Maintained input table/contracts](2026-10-25-combined-post-rc-candidate.md) holds33 exact topic heads and receipts. Standalone passes do not verify combinations. Actions is the Android compile, not local Gradle or phone execution.

## Decisions and pending questions

**No unanswered user questions.** The 32 MiB HEIC preamble read-ahead limit was accepted 2026-10-29 with rare-file disclosure. Audio-only playback was approved2026-10-28; mixed-file audio/artwork stays, image/video-only sources have no playable audio. User-approved4MiB unfinished timed tags/text packets,64KiB lacing headers, provisional long-AAC/live-WebM limits and unused-subtitle skipping remain. HEVC stays playable with unresolved#730. #646 reset/SD refusal, #401 latency, #671 buffering, deep eject/restart and provenance retain their existing qualifications/deferrals. Future UAT is separate from unanswered decisions.

Fresh user-approved Opus5.5/Claude Code/explicitly High is idle. Its earlier scoped reviews found the full-player action-adapter omission (fixed403 and rechecked), motivated stronger native JPEG evidence and checked selected lacing paths. No whole-candidate independence or current-cycle Claude/Codex subagent contribution. Verify live quota before reuse; do not reload the oversized old Claude history. [Continuous engineering](../continuous-engineering.md) records quota and missed-question safeguards.

## Continue from here

1. Verify the refreshed HEIC #701 exact head, actual reports and release gates. Preserve GuardedMetadataRenderers, container/ID3/timed guards, UNSUPPORTED subtitles, disabled VIDEO/IMAGE/TEXT, raw gain/Tracks, both controller/queue wrappers, all three notice adapters and raw-PCM admission. Current root alone resolves the owned integration; check status before any takeover.
2. Record exact source/run/artifact evidence and accepted 32 MiB preamble restriction. Future ordinary JPEG/HEIC audio, seek/reconnect/saved/gain and other formats need later authorized acceptance; no phones in this run.
3. The normal AGP-plugin Guava constraint passed standalone inventory/Android; verify its refreshed combination. Assess the distinct Gradle-distribution caller/upstream route without reopening old advisory pairs or forcing AGP/bundled dependencies. Plugin graph and R8-selected paths do not clear every tool loader.
4. Continue useful broader engineering until live 20%-remaining with reserve; unanswered decisions must be repeated at final. [Source-boundary evidence](../audits/2026-10-25-post-rc-source-boundaries.md) prevents repeated speculative findings.

Owned persistent worktrees under `/home/avery/.codex/worktrees/`: `muon-post-rc-acceptance-oct25` (#701), `audio-only-selection-oct28/muon` (#751), `media3-stable-patch-oct28/muon` (#753), `playlist-occurrence-oct28/muon` (#749), `artwork-text-contrast-oct28/muon` (#747), `guava-deserialization-oct29/muon` (#755), plus prior topic paths in their receipts. Root owns these; verify live clean/idle state before writing. The old `muon-post-rc-state-oct25` branch is closed#716. Task attachment cap100 is full; only obsolete verified-merged PR links were removed (#205/#206/#209/#210/#212/#217/#219/#221/#224/#227), without changing GitHub history. Do not recreate existing worktrees or archive unrelated work. Temporary files/local sessions are conveniences, not recovery prerequisites.
