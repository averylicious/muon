# Storage reader/move checkpoint — 2026-10-06

## User decision and current scope

The user moved full SD-card eject/reinsert and live-process/restart recovery out of the current Stable gate. Keep #179 open as a deferred follow-up. The user may set up an emulator later; no emulator setup is requested. This is a scope deferral, not a fixed issue or a Stable-release request. General retained identity (#213) and move preservation (#230), aggregate resources (#253), remaining application acceptance and hardware QA remain separate gates.

Latest user decision is recorded on #179: https://github.com/averylicious/muon/issues/179#issuecomment-6016418604 . Earlier reports listing full #179 as a current Stable blocker are historical and superseded by this decision.

## Physical eject explanation and evidence

The previously captured private POCO log records vold signaling the diagnostic process with SIGINT, then Zygote recording the same PID exiting due to signal 2. This is an OS termination receipt, not an uncaught Kotlin exception. Android 16 upstream vold attempts unmounting before signaling processes holding volume references. The exact custom-ROM implementation and held descriptor were not inspected; an open playback file does not alone prove a leak. Kotlin try/catch can handle I/O failures in a surviving process, but cannot prevent OS termination. No signal-handler or platform/security-setting workaround is proposed.

Source: https://android.googlesource.com/platform/system/vold/+/refs/heads/android16-release/Utils.cpp#1796 . Root-assisted saved-row/file receipts remain in the prior root-index checkpoint; they do not establish every card-recovery path.

## Reviewable production slices

Both branches are based on integration 7aa2ec225b8a8d7fb2b549e96d8301c04afd8856: main c4d48a145421ffde5ebff4cadc0e8cb400cdd059 plus open #302 at 54f2cd390cd8e364cebc0a11c0be0c7c4ebe6521. They contain the unmerged candidate's ancestry. Do not merge either independently merely because its own small patch is green.

| PR | Focused head | Actual behavior | Latest-head verification |
| --- | --- | --- | --- |
| #362 | c346ad803b1c6b5818d31208130f458f5a08f7ed | OfflineDataSource checks before source creation/non-empty reads; observed unavailable opens stay invalid until close/fresh open. Zero-byte reads preserve Media3 contract. Seven actual route/cache/file-reader tests. | Android681 / https://github.com/averylicious/muon/actions/runs/37464374019 PASS; actual XML664 tests per variant, zero failures/errors/skips, including all7 reader cases each. Canary .681 artifact11413828889. |
| #363 | 5052dbb8eba9b7586c1830496d6d7adb5747f537 | OfflineStore.copy rejects mismatching or over-length destination spans before CacheWriter fills their holes. Matching fragments still resume; full post-copy check retained. Three added actual-cache move cases and stronger mismatch assertions. | Android682 / https://github.com/averylicious/muon/actions/runs/37464446727 PASS; actual XML660 tests per variant, zero failures/errors/skips, including all13 move cases each. Canary .682 artifact11413978943. |

Matching published Google Maven Media3 datasource/common 1.11.0 sources were inspected for reader contract, factory creation, listener propagation, EOF, read-only/no-upstream sink behavior and close behavior. No local Kotlin compile/tests were run; Actions was the first compile. Both uploaded Canary APKs independently passed BUILD.txt/full-head/run/version, SHA256SUMS, signing-certificate, package identity, non-debuggable flag and three private service checks. Reader .681 and move .682 each include #302 but exclude the sibling slice; they are branch artifacts, not published Obtainium updates. Neither was installed. Artifact links are on the corresponding PR. Patch notes: ../audits/2026-10-06-card-reader-containment.md and ../audits/2026-10-06-move-span-preflight.md are on their respective app branches, not yet main.

Neither slice completes full #179/#230. Reader checks are snapshots and cannot stop already-active I/O or an OS kill; per-read storage lookup cost is unmeasured. Move preflight cannot exclude a late writer or repair every failed partial copy. Existing source/target bytes are preserved on known preflight refusal, not deleted to satisfy a check. No schema, cache key, signing/package or update-feed change.

## Ownership and attribution

GPT-6 / Codex desktop (exact variant/effort not exposed) authored #363 and self-reviewed it. Claude Opus 5.5 / Claude Code, model claude-opus-5-5 verified in runtime, High selected at launch (runtime effort not emitted), authored #362 and its original fixtures. GPT-6 independently reviewed Claude-authored code and added zero-length read compatibility plus its own regression test. Its own additions are self-review, not independent review.

The allocated audit Claude session completed a short independent source review of #363 at 5052dbb and found no introduced blocker within the promised preflight scope. Its first turn stopped at a maximum-turn guard; a report-only continuation completed successfully. The coordinator separately verified that matching SimpleCache.getCachedSpans returns a new TreeSet snapshot. Late writers, stale-span/index effects, mid-copy output and device/performance remain unverified as documented. Actual review receipt is on #363; Claude is idle, with no further assignment. The user's experimental Claude session and original experimental checkout are untouched. No new phone operations were performed in these source slices. Latest previous phone state: POCO card mounted, media volume restored, diagnostic .677 stopped, ordinary .652 unchanged; Pixel untouched. Verify live state before any future device task.

## Next boundary

1. Exact-head app CI and artifact/XML receipts are complete above. Refresh current destination and required checks before any future integration/merge; PR body edits retrigger Branch direction. Documentation-only checkpoint checks/merge receipts belong on its PR, not an invented APK. Leave #302/#362/#363 open for their outstanding application acceptance/prerequisites.
2. Do not continue full SD lifecycle recovery as a Stable blocker. #179 retains the source/fixture/device evidence for a later focused session.
3. For #213, reuse the existing saved-production boundary and owner map instead of creating another general audit. A coherent implementation must stream uncertain live entries while separately exposing Unverified saved copies with exact cache-only provenance/actions/covers and non-colliding future identity. Do not ship a route-only change that hides existing saved bytes, destructive migration, or unused production scaffolding. #230 late-writer/partial-output ownership remains coupled to that contract.
4. Continue #253 and the remaining non-SD release checklist in independently reviewable slices, retaining hardware Bluetooth/audibility/TalkBack and other manual acceptance gaps. No Stable tag/release.

All exact-head build/QA receipts belong on live PRs and #181/#40. Temporary raw logs, DB snapshots, audio and local Claude session IDs are optional private conveniences, not required for handoff.
