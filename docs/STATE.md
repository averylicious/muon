# Muon current state

Updated 2026-10-23. Read this short entry point, the relevant [current issue snapshot](audits/issues/README.md) and latest checkpoint; do not load the full historical chronology by default.

## Release generations

One published Stable is available through Obtainium. The current main-track audit candidate and the M3E experiment are separate development generations, not two additional released Stables. Follow the [agreed release sequence and finite gates](release-plan.md): audit → frozen RC/acceptance → explicitly approved Stable → checked M3E forward integration → next candidate and delta-focused audit.

## Active work

- Current user UAT: release-identity .1343 was installed on both phones. Initial retest accepted the slider/album-return/buffering/storage-clarity corrections; user additionally reported Queue Undo with Shuffle on/off working and no blinking queue. New #645/#646 observations led to approved Saved UI work in [#648](https://github.com/averylicious/muon/pull/648). Read [the current checkpoint](handoffs/2026-10-23-saved-copies-ui.md); this approval is not acceptance of the new implementation.

- [#253 memory/storage](audits/issues/253.md): cumulative redesign on open #636; focused source closure and final selected memory/minified-storage/legacy/queue automation passed. User-approved controlled compatibility replacement passed (no old Stable data exists); interaction and hardware/accessibility acceptance remain; no app merge/release yet.
- [#401 startup/Move latency](audits/issues/401.md): selected data-preservation comparison passed; user accepted the measured delays for this release. Further performance work is deferred, not fixed.
- User explicitly deferred #207's notification-only stale-cover case. In-app implementation still needs landing/acceptance. Existing storage/identity/hardware/accessibility acceptance remains in the [release gate list](release-plan.md).
- Current app candidate: DRAFT/OPEN #648, successor to #636 and including its pending changes. Head `0cfedb6ee29148e366440d91351433119de60c59` passed .1348, 1,820 cases per variant; later commits require their own exact-head checks. Main `c9f2345aa6729360d90ddc79197eb85ffa9bb171` contains approved docs #647, not this app work. No Stable release or experimental change.
- [#645](audits/issues/645.md): first-song behavior traced to absent ReplayGain tags and no remembered fallback; one user-approved track was tagged, and immediate 0/−12/0 dB toggle was verified after another fresh Diagnostic reset. Readiness/reminder follow-up is implemented in #648; new-notice acceptance remains.
- [#646](audits/issues/646.md): historical Delete refusal after reset/card survival is not reproduced on the now-cleared state. Fresh/cloned Diagnostic and one backed-up ordinary .1343 deletion passed. Controlled reset fixture and explicit final disposition remain; do not claim a backend fix.

- Separate next-point drafts #641–#644 preserve their own source/CI evidence and stay outside current RC acceptance. Their latest maintained PR/issue bodies may postdate committed snapshots; no future app branch is installed.
- Full SD lifecycle #179 and dependency/tool/cache provenance retain user-approved release deferrals. Keep them unresolved follow-ups; do not automatically reopen the release scope.

## Working rules

[AGENTS.md](../AGENTS.md), [parallel tracks](parallel-tracks.md) and [continuous engineering](continuous-engineering.md) apply. Each writer uses an isolated persistent worktree. The original checkout belongs to the experiment. Agents may merge their own blocker-free PRs at a verified current head under standing authorization; forward-sync PRs normally wait for the experimental owner. No auto-merge, Stable tag/publication or device access follows from this page.

Main and experimental Canary builds share `dev.avery.muon`; one replaces the other. Authorized agent automation uses the separate `dev.avery.muon.diagnostic` app to preserve everyday Canary. Actual Stable configuration/upgrade acceptance remains necessary. Package/signing/feed changes are not authorized here.

## Resume and history

[Coordinator entry point](coordinator-handoff.md) records the latest execution boundary. [Features](features.md), [roadmap](roadmap.md) and [design references](design/current/README.md) describe product behavior; they are not audit acceptance receipts. The [historical STATE archive](handoffs/2026-10-21-status-archive/STATE.md) preserves earlier decisions and evidence; consult a specific entry only when needed. Maintain current issue files in place and keep GitHub comments for meaningful changes.
