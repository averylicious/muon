# Coordinator handoff

Updated 2026-10-21. This is a short resume entry point; detailed current findings belong in the [issue-state index](audits/issues/README.md). Older handoffs are evidence, not permission.

## Current boundary

Workflow documentation: #635 / branch `codex/stable-closure-oct21`, coordinator GPT-6 / Codex desktop (exact variant/effort not reported). App candidate remains [#634](https://github.com/averylicious/muon/pull/634), `feaa0636c5c034eaa5070d163f2400d856c33bee`. Main verified `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`. App stack OPEN; experiment checkout untouched; no Stable release or production activation.

Latest prior completed checkpoint: [October19 wrap-up](handoffs/2026-10-19-wrap-up.md). October21 .1318 diagnostic update, CARD reader and Card→Phone Move preservation checks passed; full disposable copy now PHONE. A bounded tiny-artwork check is active in the separate Diagnostic app. The final execution checkpoint will replace this paragraph when that operation ends. Do not assume the phone is idle from an older receipt.

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
