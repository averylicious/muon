# Muon current state

Updated 2026-10-23. Read this short entry point, the relevant [current issue snapshot](audits/issues/README.md) and latest checkpoint; do not load the full historical chronology by default.

## Release generations

One published Stable is available through Obtainium. The current main-track audit candidate and the M3E experiment are separate development generations, not two additional released Stables. Follow the [agreed release sequence and finite gates](release-plan.md): audit → frozen RC/acceptance → explicitly approved Stable → checked M3E forward integration → next candidate and delta-focused audit.

## Active work

- **Accepted v0.2.0 source:** RC .1353 / `f6d1be9591ca8e38bdc417906f75c3029c2e3e82`, merged via [#648](https://github.com/averylicious/muon/pull/648) as `8c456abe408ebd6fcaffe586bd9c6b73fa57ad18`. User accepted the remaining .1352 checklist and .1353 chevrons/focus; installed release hashes were verified on both phones.
- User green-lit functionality, merging/cleanup and main-to-M3E transfer, and delegated Stable version/publication. **v0.2.0** was selected. Main build/Canary verification, final tag publication checks and separate tested M3E integration remain execution steps; do not describe an uncompleted publication as released.
- [#253 memory/storage](audits/issues/253.md), compatibility and interaction acceptance are complete within the recorded scope; the unavailable real-user upgrade was replaced by the user-approved controlled compatibility check.
- [#645 normalization](audits/issues/645.md): missing-tags readiness/notice and live-toggle acceptance landed in #648. The user-approved single-track tagging preserved PCM.
- [#646](audits/issues/646.md) stays **open and unresolved by user decision**. Current-copy Delete passes; the lost post-reset/SD-card failure is not claimed fixed.
- [#401 latency](audits/issues/401.md), #207 notification-only stale artwork, #179 deeper SD lifecycle and dependency/tool/cache provenance retain explicit release deferrals.
- Separate next-point drafts #641–#644 stay outside this release. Animation polish, missing-art mockups and Material3 Beta work belong on the later M3E cycle. CI test-suite reduction toward 100–500 actual cases needs its own overlap review and evidence.
- See [the concise release ledger](release-plan.md) and [coordinator handoff](coordinator-handoff.md) for current execution state. The original experimental checkout is not reset or reused.

## Working rules

[AGENTS.md](../AGENTS.md), [parallel tracks](parallel-tracks.md) and [continuous engineering](continuous-engineering.md) apply. Each writer uses an isolated persistent worktree. The original checkout belongs to the experiment. Agents may merge their own blocker-free PRs at a verified current head under standing authorization; forward-sync PRs normally wait for the experimental owner. No auto-merge, Stable tag/publication or device access follows from this page.

Main and experimental Canary builds share `dev.avery.muon`; one replaces the other. Authorized agent automation uses the separate `dev.avery.muon.diagnostic` app to preserve everyday Canary. Actual Stable configuration/upgrade acceptance remains necessary. Package/signing/feed changes are not authorized here.

## Resume and history

[Coordinator entry point](coordinator-handoff.md) records the latest execution boundary. [Features](features.md), [roadmap](roadmap.md) and [design references](design/current/README.md) describe product behavior; they are not audit acceptance receipts. The [historical STATE archive](handoffs/2026-10-21-status-archive/STATE.md) preserves earlier decisions and evidence; consult a specific entry only when needed. Maintain current issue files in place and keep GitHub comments for meaningful changes.
