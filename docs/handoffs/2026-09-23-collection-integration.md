# Collection redesign integration checkpoint

User authorized sound, reviewed merges on 2026-09-23, superseding the earlier leave-open restriction. Stable publication still requires an explicit request. No phone access is authorized; user performs QA.

## Inputs

- Frontend stack through PR94: `0e4bf9fe6cf7cda29ffe7d8bfd7e99caa6165e8e`.
- Main after CI optimization PR67 and naming PR92: `9efc66b34a6d83999bc6bed41144116b944f8bf5`.
- Integration merge is conflict-free and adds no app-code changes beyond that frontend head. This checkpoint is added with the merge.
- PR90/run114 and corrected PR91/run117 passed exact-head CI and downloaded artifact checks. PR94 corrected run121 is pending at writing. The integration head needs its own full Android run before promotion.
- Astra independently reviewed Claude's PR91 and PR94 and requested fixes for mid-animation drag takeover, zero-delta drag teardown, same-frame preview cancellation, stale tokens and preview accessibility. Corrected source has no remaining blocker identified within those slices. Policy tests are not device-rendering evidence.
- Claude Opus5.5 Medium is performing the previously deferred independent source review of Astra-authored PR79 (filename fallback), including its integration in PR94. Obtain its result before promoting this integration. Do not infer success from a process exit code alone.

## Promotion checks

Verify all input heads, the current integration head, latest-head CI, the signed artifact SHA256SUMS and BUILD.txt, and Claude's pending PR79 review. Inspect the merge diff against PR94: only the already-reviewed main CI/docs changes and this checkpoint should be new. Do not merge with unresolved review findings or failed checks. Record final results in the PR and issue40; those records supersede pending statements in this immutable checkpoint.

Most frontend ancestor PRs already have recorded reviews. Merging their original commits through this integration should mark included PRs merged; verify rather than manually closing unrelated work. Independent album metadata/grouping, discovery and queue-duration branches are not included. Design mockup and coordinator-documentation PRs also need separate final-head review. Close feature issues only when their whole requested behavior is delivered; #43 includes motion requirements still requiring verification/tuning.

## User decisions and next work

- Milestone1 is now **Collection redesign**, formerly UI/UX rework / UI2.0. Historical design paths remain. See `docs/release-naming.md`; workstream names are separate from APK versions.
- Sequence: finish player boundary -> artwork/collection browsing -> queue/actions -> Search/Connect -> stabilization.
- Baseline Profiles are deferred to a later point release; blur to a future design rework. Neither is a current implementation requirement.
- New #93 tracks shuffle/repeat preference persistence across process death, force-stop/manual relaunch and reboot/manual relaunch. It is a separate backend slice before Stable reliability sign-off; no queue/position restoration or auto-play-at-boot is implied.
- PR67 is merged. Hosted main run120 passed docs-only checks and skipped Android/signing/artifacts/publication. Run119 was cancelled by the newer docs push. Feature/integration branches containing app changes must still build fully.

## Manual QA still pending

Latest player opening follows the finger, preserving the48dp raw-travel threshold. The250ms curve remains; spring tuning is not implemented. Test short/cancelled/committed opening, Back during preview while keeping the finger down, rapid restart/reopen, grabbing during opening/settle, controller/queue loss, resize, TalkBack and animations disabled. Also retain prior insets, Lyrics, artwork swipe, volume, pure-black and playback checks. Earlier user LGTM on .113 is not validation of newer changes.

## Agents and quotas

Claude: Opus5.5 Medium, session `ead1e638-518f-430a-bc0b-c1af646868d5`; last observed around78% five-hour used and42% weekly before read-only promotion review. Astra's main account has weekly quota only; last observed18% used, may be stale. Always recheck actual status and process ownership before resuming. Local logs are optional, shared GitHub records and committed code are the handoff. No new feature should start without implementation/review/checkpoint reserve.
