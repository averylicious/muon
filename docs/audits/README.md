# Main-track audit

Start with the [maintained issue-state index](issues/README.md), [finite release gates](../release-plan.md) and [coordinator entry point](../coordinator-handoff.md). These current summaries replace an append-only index of every session. Verify live heads/checks before resuming.

## Current release scope

- [#253](issues/253.md): aggregate memory, bounded storage owners and opt-in migration; final verification/promotion remains.
- [#401](issues/401.md): measured saved-card startup and Move latency; preservation checks passed for selected cases, latency remains.
- Known partial #207 notification artwork, actual Stable configuration and the defined data/interaction/hardware/accessibility acceptance gates remain in the release plan.
- Existing user-approved SD lifecycle/provenance deferrals remain unresolved maintenance items. A passing build is not a security certification or Stable approval.

The cumulative app candidate is #634, `feaa0636c5c034eaa5070d163f2400d856c33bee`; inspected main is `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`. These are dated snapshots, not a substitute for live verification. The 275 open PRs at inventory time are mostly accumulated components, not independent remaining defects.

## Finding and reporting rules

For a finding, record inspected commit, affected symbols, trigger, actual/expected behavior, impact, confidence, supporting reproduction/source evidence, proposed fix and remaining verification. Separate confirmed defects from hypotheses. Reuse existing issues and [maintain their status files in place](issues/README.md). Do not automatically expand frozen release gates with speculative hardening.

Phone QA needs current explicit authorization. Preserve originals, app identities and experimental ownership. Automated CI/source/selected device evidence does not replace user acceptance. See [AGENTS.md](../../AGENTS.md), [parallel tracks](../parallel-tracks.md) and [continuous engineering](../continuous-engineering.md).

## Targeted historical references

- [Initial repository map and audit](2026-09-27-main.md).
- [Network/controller source findings](2026-09-30-network-entry-points.md).
- [Build/dependency trust disposition](2026-10-07-build-trust-disposition.md); later user decisions in the release plan control the current gate.
- [Latest prior complete execution checkpoint](../handoffs/2026-10-19-wrap-up.md), with later execution state in the coordinator entry point.
- [Archived detailed audit index and workstream map](../handoffs/2026-10-21-status-archive/audit-index.md). It preserves all older report links; inspect only the relevant entry, with its dated commit and limitations.
