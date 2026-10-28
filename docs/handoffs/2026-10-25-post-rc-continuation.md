# Post-RC audit continuation

Updated2026-10-29. Continue authorized engineering to **80% weekly used /20% remaining**, with recovery reserve. A verified PR/checkpoint alone does not end the run. Missing five-hour readings are unavailable; no reset activation.

## Scope and ownership

GPT-6 / Codex desktop is sole writer of the owned drafts, author self-check unless a scoped review is explicitly attributed. **No phones**, installs, music/app-data or host/network changes. Main application baseline20579cf1, current documentation mainf9263f07, alphaee7ddb75 and frozen M3E RC .1420/source6b1a767c remain preserved. Published Stablev0.2.0 is separate. No runtime main merge, alpha landing, Stable tag or frozen-RC replacement; runtime PRs remain open for later acceptance.

## Exact current boundary

- **Verified combined #701/fdf3e2692b96f877604be6288f101da6f72861a8 / [Android .1650](https://github.com/averylicious/muon/actions/runs/40726532168):** 33 topics, actual 1,996 cases/variant, zero failures/errors/skips, lint 0 errors/62 warnings plus 3 hints. Actual release source/checksum/signer/identity/export/profile gates passed; APK `1575fa39b64ea1477f373deb8c49107702bb461314d34fa8c5e0dfea646ce132`, uninstalled. Inventory128 has identical app/build tree and patched Media3/Guava graph.
- **New HEIC integration:** standalone #722/9576b2f767789e7aeead41640c93a6bb07fc7bc1 / [Android .1651](https://github.com/averylicious/muon/actions/runs/40729077587) passed actual 1,867 cases/variant and six nested methods, zero failures/errors/skips/lint errors. The combined refresh replaces the JPEG-only input; topic count stays 33. Its own compile/reports/APK are pending. User accepted the 32 MiB rare-preamble restriction on 2026-10-29.
- Audio-only #751/9688fb60/.1643, stable Media3 #753/ec3543ba/.1644 and Guava #755/3f1575be/.1647 are included in verified .1650. Their [issue state](../audits/issues/README.md) retains exact standalone reports/APK/graph/source limits. The stable-library raw-PCM fast path correction and native known/unknown ID3 witnesses passed both variants.
- Offline exact frozen .1420 APK inspection narrows the selected Guava serialization gadget paths after R8; no broad vulnerability clearance or device inspection. [#754](../audits/issues/754.md). [Selected advisory refresh](../audits/2026-10-29-dependency-advisory-refresh.md) returns zero patched-runtime matches; the one newly added build-Guava match remains a separate unresolved caller question. Prior build findings are not new duplicates.
- Docs #716 merged mainf9263f07; main .1641 passed lightweight checks with no APK/application change. Do not reuse that closed PR.

[Maintained input table and integration contracts](2026-10-25-combined-post-rc-candidate.md) holds all 33 exact topic heads, receipts and policy limits. Standalone passes do not verify later combinations. Actions is the Android compile; no local Gradle or phone execution.

## Decisions and pending questions

**No unanswered user questions.** The 32 MiB HEIC preamble read-ahead limit was accepted 2026-10-29 with rare-file disclosure. Audio-only playback was approved2026-10-28; mixed-file audio/artwork stays, image/video-only sources have no playable audio. User-approved4MiB unfinished timed tags/text packets,64KiB lacing headers, provisional long-AAC/live-WebM limits and unused-subtitle skipping remain. HEVC stays playable with unresolved#730. #646 reset/SD refusal, #401 latency, #671 buffering, deep eject/restart and provenance retain their existing qualifications/deferrals. Future UAT is separate from unanswered decisions.

Fresh user-approved Opus5.5/Claude Code/explicitly High is idle. Its earlier scoped reviews found the full-player action-adapter omission (fixed403 and rechecked), motivated stronger native JPEG evidence and checked selected lacing paths. No whole-candidate independence or current-cycle Claude/Codex subagent contribution. Verify live quota before reuse; do not reload the oversized old Claude history. [Continuous engineering](../continuous-engineering.md) records quota and missed-question safeguards.

## Continue from here

1. Verify the refreshed HEIC #701 exact head, actual reports and release gates. Preserve GuardedMetadataRenderers, container/ID3/timed guards, UNSUPPORTED subtitles, disabled VIDEO/IMAGE/TEXT, raw gain/Tracks, both controller/queue wrappers, all three notice adapters and raw-PCM admission. Current root alone resolves the owned integration; check status before any takeover.
2. Record exact source/run/artifact evidence and accepted 32 MiB preamble restriction. Future ordinary JPEG/HEIC audio, seek/reconnect/saved/gain and other formats need later authorized acceptance; no phones in this run.
3. Assess build-tool Guava reachability/compatible correction without reopening prior already-assessed advisory pairs or forcing AGP dependencies. Runtime graph patch and R8-selected paths do not clear build tools.
4. Continue useful broader engineering until live 20%-remaining with reserve; unanswered decisions must be repeated at final. [Source-boundary evidence](../audits/2026-10-25-post-rc-source-boundaries.md) prevents repeated speculative findings.

Owned persistent worktrees under `/home/avery/.codex/worktrees/`: `muon-post-rc-acceptance-oct25` (#701), `audio-only-selection-oct28/muon` (#751), `media3-stable-patch-oct28/muon` (#753), `playlist-occurrence-oct28/muon` (#749), `artwork-text-contrast-oct28/muon` (#747), `guava-deserialization-oct29/muon` (#755), plus prior topic paths in their receipts. Root owns these; verify live clean/idle state before writing. The old `muon-post-rc-state-oct25` branch is closed#716. Task attachment cap100 is full; only obsolete verified-merged PR links were removed (#205/#206/#209/#210/#212/#217/#219/#221/#224/#227), without changing GitHub history. Do not recreate existing worktrees or archive unrelated work. Temporary files/local sessions are conveniences, not recovery prerequisites.
