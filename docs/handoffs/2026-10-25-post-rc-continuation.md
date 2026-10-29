# Post-RC audit continuation

Updated2026-10-30. User activated a banked reset and resumed broad source/CI auditing. Use the normal **80%weekly used /20%remaining** stopping boundary; the previous temporary5%-remaining cycle is over. Continue useful authorized work after verified boundaries. Missing five-hour telemetry is unavailable, not zero.

## Scope and owners

Root GPT-6 / Codex desktop owns the isolated maintenance/topic worktrees and author self-checks. No phones, installs, app/music data, host settings, runtime main/alpha landing, release tag or frozen-RC replacement. Published Stablev0.2.0 and daily-driver M3E RC .1420/source6b1a767c stay separate. Main application's baseline20579cf1 is unchanged; latest documentation main is2ec8b698.

- Combined branch `codex/post-rc-acceptance-oct25`, worktree `/home/avery/.codex/worktrees/muon-post-rc-acceptance-oct25`: root is integrating verified#758 and current main; **new combination checks pending**. All34 topics retain ancestry. Preserve raw-PCM admission, guarded metadata/renderers and container/timed guards; VIDEO/IMAGE/TEXT disabled policy, raw gain/Tracks, both controller/queue wrappers and all three notice adapters remain.
- MP4#722 branch `codex/mp4-metadata-admission-oct27`, worktree `/home/avery/.codex/worktrees/mp4-cancellation-oct29/muon`: clean e22402b5, verified .1661, idle.
- Cleanup#758 branch `codex/completed-save-cleanup-oct29`, worktree `/home/avery/.codex/worktrees/completed-save-cleanup-oct29/muon`: clean5f5e76fb, verified .1663, idle. [Current #757 state](../audits/issues/757.md).
- Guava worktree `/home/avery/.codex/worktrees/guava-deserialization-oct29/muon` now holds clean docs branch `codex/gradle-binary-evidence-oct30`/143df0e6, merged#759. Original runtime#755 remains a7ec5ca6. Do not push the merged docs branch. Original user checkout is untouched. The old MP4 checkout path is gone.
- Allocated Opus5.5 / Claude Code / explicitly High reviews are complete/idle; no Claude writer, subagent, device or storage operation is active. Do not reload the oversized historical Claude session. Portable state does not depend on local session IDs or /tmp.

## Verified boundaries

- Combined d1462a1c970a4b11135bef32d61e457d7a66f067 / [Android .1662](https://github.com/averylicious/muon/actions/runs/40905616983): downloaded **1,999cases/variant**, zero failures/errors/skips; both new MP4 methods and seek/restart control ran. Lint0errors/62warnings plus3hints. Report digest `de93ee7d0129c3a63b92c12cb06040d102c0dad0da9a81168137489c625c8ae0` matches API. Actual release BUILD/source/checksum/pinned signer/manifest/export/non-debuggable/profile gates passed; APK `6f8211aba5d27230d1bb1173d7d4b4677ea902579c2a9826748165979d1d02c4`, uninstalled. This remains the last verified combination until the new integration passes.
- MP4 e22402b5 / [Android .1661](https://github.com/averylicious/muon/actions/runs/40902775706): actual1,869cases/variant, zero failures/errors/skips; lint0errors/65warnings plus3hints. Report digest `e0262c9db5a6f512dd00400be638846545ef0ae939a60ac9ba7bb24b9be93b42`. Cached native sniff cancellation and read-stage HEIC cutover are verified in selected native fixtures; no decoding/OOM/phone claim. [#720 state](../audits/issues/720.md).
- Cleanup5f5e76fb / [Android .1663](https://github.com/averylicious/muon/actions/runs/40906829125): actual1,822cases/variant and the new native failure method both variants, zero failures/errors/skips; lint0errors/65warnings plus3hints. Report digest `7af42d88e40086c20dd231b2118fd786aad64360af3304564068d7d4401cddd5`. Actions APK gates passed; standalone APK bytes not separately inspected. Uncertain release now stays primary, preserving pool slot/pin accounting; native-owner containment already prevented new opens.
- Documentation#759/143df0e6 and main2ec8b698 / [main .1665](https://github.com/averylicious/muon/actions/runs/40908302195) passed documentation-only checks, no APK. Fresh official Gradle ZIP matches the wrapper pin; selected bundled Guava/serializer bytecode supports the unresolved core-tool follow-up. No Gradle execution, exploit, pin/workflow/host change or provenance clearance. [#754 state](../audits/issues/754.md).

Artifacts expire after14days and update existing Canary; none was installed or published as a new release. Detailed inputs, prior receipts and future acceptance stay in the [combined receipt](2026-10-25-combined-post-rc-candidate.md) and maintained issue files rather than repeated timelines.

## Review limits and next work

Fresh Opus cutover review inspected supplied repository code/tests but its client could not read external native sources; root separately checked the pinned HEIC loop and fixed4/8-byte read peeks. Saved-reader review found#757; root independently confirmed it and authored the correction/test. A tools-disabled supplied-source review found no final-patch blocker but did not inspect every fixture/native helper. These are scoped reviews, not independent whole-candidate or phone approval.

1. Discover the exact new#701 head/run, inspect reports and resolve actual failures. Preserve all34 ancestors and composed guards. Do not substitute the preceding .1662 pass for this new head.
2. Continue useful broader source/engineering work while capacity permits; record nonfindings and qualified limits without adding speculative parser bans or redundant tests. No phone permission this cycle. Later playback/storage/accessibility/gesture acceptance remains separate.
3. Gradle's bundled Guava is an actual inspected affected binary, but no lower-trust writer/exploit or compatible8.14 patch was established. Preserve the explicit dependency/provenance deferral; do not silently force a major toolchain upgrade or replace bundled files.

## User decisions and unanswered questions

**No unanswered user questions.** Claude usage question was answered; its dashboard reading is not a continuing live meter. The32MiB HEIC preamble, provisional long-AAC/live-WebM restrictions,4MiB timed/text limits,64KiB lacing-header limit, skipped unused subtitles, audio-only playback and bounded control metadata remain accepted policies. HEVC#730 stays playable/unresolved. #646 historical reset/SD refusal, #401 latency, #671 buffering, deep eject/restart and tool provenance retain their stated deferrals. Future UAT is not an unanswered policy decision.
