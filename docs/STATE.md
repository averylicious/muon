# Muon current state

Updated 2026-10-23. Read this short entry point, the relevant [current issue snapshot](audits/issues/README.md) and latest checkpoint; do not load the full historical chronology by default.

## Release generations

One published Stable is available through Obtainium. The current main-track audit candidate and the M3E experiment are separate development generations, not two additional released Stables. Follow the [agreed release sequence and finite gates](release-plan.md): audit → frozen RC/acceptance → explicitly approved Stable → checked M3E forward integration → next candidate and delta-focused audit.

## Active work

- Current user UAT: tried Bluetooth controls passed; shuffled queue Undo, TalkBack labels/grouping and storage-setup wording need corrections/retest. See the [current correction checkpoint](handoffs/2026-10-22-uat-corrections.md). #636 remains draft/open; no new Stable release.

- [#253 memory/storage](audits/issues/253.md): cumulative redesign on open #636; focused source closure and final selected memory/minified-storage/legacy/queue automation passed. User-approved controlled compatibility replacement passed (no old Stable data exists); interaction and hardware/accessibility acceptance remain; no app merge/release yet.
- [#401 startup/Move latency](audits/issues/401.md): selected data-preservation comparison passed; user accepted the measured delays for this release. Further performance work is deferred, not fixed.
- User explicitly deferred #207's notification-only stale-cover case. In-app implementation still needs landing/acceptance. Existing storage/identity/hardware/accessibility acceptance remains in the [release gate list](release-plan.md).
- Current RC: DRAFT/OPEN #636 at `1d73ecda6be93bcb9c17c3f1e48f1047d52c7fec`; .1334 CI and downloaded APK checks passed after queue/TalkBack corrections. User will test later. Main is still `6eaeb766604d22e5e4f70b7fbacbb82ad924b648`; no app merge/release.
- Separate user-authorized next-point work: [post-RC audit #181](audits/issues/181.md). No phone access; do not automatically convert its new findings into frozen RC gates.
- Full SD lifecycle #179 and dependency/tool/cache provenance retain user-approved release deferrals. Keep them unresolved follow-ups; do not automatically reopen the release scope.

## Working rules

[AGENTS.md](../AGENTS.md), [parallel tracks](parallel-tracks.md) and [continuous engineering](continuous-engineering.md) apply. Each writer uses an isolated persistent worktree. The original checkout belongs to the experiment. Agents may merge their own blocker-free PRs at a verified current head under standing authorization; forward-sync PRs normally wait for the experimental owner. No auto-merge, Stable tag/publication or device access follows from this page.

Main and experimental Canary builds share `dev.avery.muon`; one replaces the other. Authorized agent automation uses the separate `dev.avery.muon.diagnostic` app to preserve everyday Canary. Actual Stable configuration/upgrade acceptance remains necessary. Package/signing/feed changes are not authorized here.

## Resume and history

[Coordinator entry point](coordinator-handoff.md) records the latest execution boundary. [Features](features.md), [roadmap](roadmap.md) and [design references](design/current/README.md) describe product behavior; they are not audit acceptance receipts. The [historical STATE archive](handoffs/2026-10-21-status-archive/STATE.md) preserves earlier decisions and evidence; consult a specific entry only when needed. Maintain current issue files in place and keep GitHub comments for meaningful changes.
