# Coordinator handoff

Updated 2026-10-23. This short resume entry points to maintained [issue state](audits/issues/README.md); history is evidence, not permission.

## Current boundary

The user accepted the focused .1343 corrections in initial testing, including Queue Undo with Shuffle on/off and no blinking queue. Saved-copy spacing/actions and first-song normalization then produced the new #645/#646 feedback. [#648](https://github.com/averylicious/muon/pull/648) is the open successor candidate, including #636 plus the approved On this device frontend and missing-loudness notice. Read [the current implementation checkpoint](handoffs/2026-10-23-saved-copies-ui.md), [#645 evidence](audits/issues/645.md) and [#646 evidence](audits/issues/646.md). Exact current head/check/artifact receipts are maintained on #648; no app merge/release is authorized while its gates remain.

UI-only head `65eebe4d16c2e462a48cf0bd01e3b027be87c620` passed .1347 (1,817 cases per variant). Normalization/notice head `0cfedb6ee29148e366440d91351433119de60c59` passed .1348 (1,820 per variant, zero failure/error/skip; lint 0 errors / 66 warnings), and its release APK identity/checksum/certificate/new notice bytecode were inspected. A later reset fixture/documentation commit needs its own final-head CI. No current-head notice visual/TalkBack acceptance is implied.

Main is `c9f2345aa6729360d90ddc79197eb85ffa9bb171` after approved design/docs #647; current app work remains unmerged. Experiment remains untouched. GPT-6 owns `codex/saved-copies-ui-oct23` in its isolated worktree; allocated Claude is idle after Opus5.5 High read-only notice advice. Root integrated/fixed its earlier UI implementation. Do not resume the user's experimental Claude session.

Current-task POCO root testing was explicitly authorized, including Diagnostic reset/playback/toggle and later regular-Muon saved-copy deletion. Only Diagnostic data was cleared; one backed-up regular listening copy was deleted successfully under that later permission. No Pixel access or OS security/volume/SD-mount changes this run. User source music was tagged only for the single specifically authorized track; private tag backup and unchanged PCM were verified. This is evidence, not standing device permission for the next conversation.

#646's historical failure remains unconfirmed after user Clear data. The factory-reset/card-files explanation is being tested as a hypothesis, not an SSAID finding. Existing approved performance/notification-cover/SD lifecycle/dependency provenance deferrals remain deferred. Separate future drafts #641–#644 stay outside the RC.

## Resume order

1. Read [current state](STATE.md), [fixed release gates](release-plan.md) and only the relevant [#253](audits/issues/253.md) / [#401](audits/issues/401.md) snapshot. Open the latest execution checkpoint and specific supporting evidence as needed; do not stream the entire issue timeline.
2. Verify live main/experimental/PR heads and required checks, worktree dirt/ownership, active processes and actual current user authorization. Preserve dirty work and uncertain storage operations. A clean checkout alone does not prove its writer is idle.
3. Use one writer per isolated branch/worktree/session. Do not resume the user's experimental Claude session. An allocated audit session must be explicitly assigned and idle; select the requested actual model/effort and record attribution. Local session IDs are optional conveniences, not required recovery state.
4. Check available usage at meaningful boundaries; missing readings are unavailable, not zero. Keep account/usage/private device details local. Reserve capacity for validation, fixes and durable handoff while continuing authorized useful work.
5. Keep exact-head CI/report/APK, selected automated device results and user acceptance distinct. Do not install over an active/uncertain Move. Diagnostic results do not establish actual release-configuration acceptance.
6. Update the relevant current issue file in place. Leave a concise checkpoint at recovery boundaries, link it once here, and use GitHub comments only for needed decisions/blockers/review/material results. Follow the [reporting contract](audits/issues/README.md).

## Integration and release

Follow [parallel tracks](parallel-tracks.md). Current protected-main checks and expected-head merge rules still apply. App PRs retain pending acceptance. Main Canary publication is separate from an explicitly requested Stable release. Experimental owners normally land forward-sync PRs at a clean boundary; the current alpha ancestry guard remains in force pending a separately reviewed future promotion.

[Historical coordinator chronology](handoffs/2026-10-21-status-archive/coordinator-handoff.md) preserves old prompts, role instructions and dated receipts for targeted lookup. Do not copy that chronology back into this entry point.
