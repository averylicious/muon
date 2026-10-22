# Main-track audit

Start with the [maintained issue-state index](issues/README.md), [finite release gates](../release-plan.md) and [coordinator entry point](../coordinator-handoff.md). These current summaries replace an append-only index of every session. Verify live heads/checks before resuming.

## Current release scope

- [#253](issues/253.md): aggregate memory, bounded storage owners and opt-in migration; final verification/promotion remains.
- [#401](issues/401.md): selected preservation checks passed; user accepted the measured delays for this release. Performance follow-up stays open/deferred.
- User deferred #207's notification-only stale-cover case. Actual Stable configuration and defined data/interaction/hardware/accessibility acceptance remain required in the release plan.
- Existing user-approved SD lifecycle/provenance deferrals remain unresolved maintenance items. A passing build is not a security certification or Stable approval.

The frozen cumulative candidate is [#636](https://github.com/averylicious/muon/pull/636), `1d73ecda6be93bcb9c17c3f1e48f1047d52c7fec`; .1334 CI/APK verification passed and user retesting remains. Actual main is `6eaeb766604d22e5e4f70b7fbacbb82ad924b648`. Verify live heads; accumulated component PRs are not independent remaining defects.

## Separate next-point audit

The user authorized further audits on October23, including outside the old scope, without phone access. [Post-RC owner state](issues/181.md) and [evidence report](2026-10-23-post-rc.md) track #638, #639, #640 and the test-execution inventory. These are future maintenance work, not automatic additional gates for the frozen candidate.

## Finding and reporting rules

For a finding, record inspected commit, affected symbols, trigger, actual/expected behavior, impact, confidence, supporting reproduction/source evidence, proposed fix and remaining verification. Separate confirmed defects from hypotheses. Reuse existing issues and [maintain their status files in place](issues/README.md). Do not automatically expand frozen release gates with speculative hardening.

Phone QA needs current explicit authorization. Preserve originals, app identities and experimental ownership. Automated CI/source/selected device evidence does not replace user acceptance. See [AGENTS.md](../../AGENTS.md), [parallel tracks](../parallel-tracks.md) and [continuous engineering](../continuous-engineering.md).

## Targeted historical references

- [Initial repository map and audit](2026-09-27-main.md).
- [Network/controller source findings](2026-09-30-network-entry-points.md).
- [Build/dependency trust disposition](2026-10-07-build-trust-disposition.md); later user decisions in the release plan control the current gate.
- [Latest prior complete execution checkpoint](../handoffs/2026-10-19-wrap-up.md), with later execution state in the coordinator entry point.
- [Archived detailed audit index and workstream map](../handoffs/2026-10-21-status-archive/audit-index.md). It preserves all older report links; inspect only the relevant entry, with its dated commit and limitations.
