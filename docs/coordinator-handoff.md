# Coordinator handoff

Updated 2026-10-28. This short resume entry points to maintained [issue state](audits/issues/README.md); history is evidence, not permission.

## Current boundary

**Stable v0.2.0 is published and verified.** The user accepted .1352/.1353 UAT, authorized landing/cleanup, delegated version/publication and requested M3E synchronization. Accepted #648 and documentation #649 landed; final tag79b5e70e / release1358 APK hash/signature/manifest/metadata and1,821 cases/variant verified. [Current release and cleanup checkpoint](handoffs/2026-10-23-stable-020-release.md) holds exact evidence/limits, retained issues, cleanup counts and owners.

**M3E is now a frozen daily-driver RC**, separate from published Stable v0.2.0. The user reports all [focused UAT](handoffs/2026-10-24-m3e-focused-acceptance.md) passed on 2026-10-24. Frozen source `6b1a767c180a8b9e531bf1468c9e72e348635b74` (#678) contains #674/#675 and matches accepted Diagnostic .1415 app/build code; changes afterward must not silently alter that freeze. [Current RC receipt](handoffs/2026-10-24-frozen-m3e-rc.md) holds exact CI/artifact/install evidence and the finite remaining release gates. #671 has not recurred and stays open/unresolved as a non-blocking follow-up; #646/#83 and prior deferrals remain outside this RC. No final Stable publication/tag is requested.

#679 prepares a narrow policy exception for a future main-promotion branch, pinned to the exact frozen commit and unchanged accepted app tree. Leave it open until actual release-configuration acceptance and coordinated promotion; do not disable the existing guard, squash alpha history, or merge a moving experiment tip. A local future integration is optional convenience; the policy PR and receipt contain portable reconstruction instructions. Current main app still matches v0.2.0. The user's original checkout is preserved.

Later audits/RCs may use separate branches and Diagnostic builds while the user daily-drives the frozen RC. A confirmed serious security/privacy/data-loss/crash regression can still block final Stable; ordinary maintenance does not silently expand this frozen scope. No test-count target remains (#650 closed not planned).

**Broader post-RC auditing continues on isolated drafts.** Main205 and alphaEE are unchanged. Prior24-topic #701/8819/.1579 passed downloaded1,958cases/variant and actual signed-release APK gates, uninstalled. New25-topic #701/4721dd91 adds verified JSON discard #741/020a/.1580; combined .1581/40578821287 passed actual1,962cases/variant and signed-release APK gates, uninstalled. Gain/reminder #696/f08 and external raw-track exclusion #712/e615 are included. HEVC #730 remains an explicitly accepted unresolved library follow-up; playback remains enabled. [Current continuation](handoffs/2026-10-25-post-rc-continuation.md) holds exact evidence, owners, approved metadata policy and next action.

Resumed2026-10-28 after the live allowance reset, under the active broad-audit goal. Root is sole integration/receipt writer; allocated Opus5.5 / Claude Code / explicitly High owns only approved #738 implementation/tests in the existing isolated #739 branch. Main/alpha/original checkout and frozen .1420 stay preserved. #719/ccbfa920/.1584 passed downloaded1,825cases/variant and root integrated it into25-topic #701/b02a; new .1589/40648234154 checks are pending. Prior4721/.1581 remains the verified actual release-artifact boundary. Metadata implementation/initial+dynamic session tests are pending first compile and root review. Keep unanswered questions in the current checkpoint and repeat them at final/early handoff; none remain now. **No phones, Stable publication/tag or alpha landing.** Continue useful work to the live20%-remaining boundary with recovery reserve.

## Resume order

1. Read [current state](STATE.md), [fixed release gates](release-plan.md) and only the relevant [#253](audits/issues/253.md) / [#401](audits/issues/401.md) snapshot. Open the latest execution checkpoint and specific supporting evidence as needed; do not stream the entire issue timeline.
2. Verify live main/experimental/PR heads and required checks, worktree dirt/ownership, active processes and actual current user authorization. Preserve dirty work and uncertain storage operations. A clean checkout alone does not prove its writer is idle.
3. Use one writer per isolated branch/worktree/session. Do not resume the user's experimental Claude session. An allocated audit session must be explicitly assigned and idle; select the requested actual model/effort and record attribution. Local session IDs are optional conveniences, not required recovery state.
4. Check available usage at meaningful boundaries; missing readings are unavailable, not zero. Keep account/usage/private device details local. Reserve capacity for validation, fixes and durable handoff while continuing authorized useful work.
5. Keep exact-head CI/report/APK, selected automated device results and user acceptance distinct. Do not install over an active/uncertain Move. Diagnostic results do not establish actual release-configuration acceptance.
6. Update the relevant current issue file in place. Leave a concise checkpoint at recovery boundaries, link it once here, and use GitHub comments only for needed decisions/blockers/review/material results. Follow the [reporting contract](audits/issues/README.md).

## Integration and release

Follow [parallel tracks](parallel-tracks.md). Current protected-main checks and expected-head merge rules still apply. The frozen M3E RC was integrated on the experimental track and installed as an unreleased Stable-package test APK; main is still the published v0.2.0 app. Later changes retain their own verification/acceptance requirements. Main Canary publication is separate from an explicitly requested Stable release. Experimental owners normally land forward-sync PRs at a clean boundary; the current alpha ancestry guard remains in force pending a separately reviewed future promotion.

[Historical coordinator chronology](handoffs/2026-10-21-status-archive/coordinator-handoff.md) preserves old prompts, role instructions and dated receipts for targeted lookup. Do not copy that chronology back into this entry point.
