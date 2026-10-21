# Coordinator handoff

Updated 2026-10-21. This is a short resume entry point; detailed current findings belong in the [issue-state index](audits/issues/README.md). Older handoffs are evidence, not permission.

## Current boundary

Workflow documentation #635 MERGED as `ec02b64f41996ec4a456c764ed94360675570df1`; main .1322 documentation-only checks passed with zero artifacts/no publication. Prepared application successor is on `codex/stable-storage-opt-in-oct21`, implementation `3e609759ef1eedf3e0c3ba19276f700b6f55f188`; source/CI review and acceptance still apply. Coordinator GPT-6 / Codex desktop (exact variant/effort not reported), Claude Opus5.5 High coding completed/idle. App candidate remains [#634](https://github.com/averylicious/muon/pull/634), `feaa0636c5c034eaa5070d163f2400d856c33bee`. Main verified `ec02b64f41996ec4a456c764ed94360675570df1`. App stack OPEN; experiment checkout untouched; no Stable release or production activation.

Latest execution checkpoint: [October21 workflow and device restoration](handoffs/2026-10-21-release-state-workflow.md). Selected .1318 update/reader/Move preservation checks passed, disposable full copy now PHONE. Tiny-artwork verification was interrupted by USB loss; restoration completed, both apps stopped and no Move/helper remains. User paused device audit; fresh-attempt final saved-data proof remains pending. User requested early wrap-up for host updates; finish only the already prepared source/CI checkpoint, then stop.

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
