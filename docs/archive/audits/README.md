# Main-track audit (archived)

> **Archived.** These reports are historical evidence from the main-track audit (2026-09-27 to 2026-11-10), each tied to its inspected commit. They are not current release, candidate or permission state. Current state: [STATE](../../STATE.md), [open issue state](../../issues/README.md), [release plan](../../release-plan.md).

## Finding and reporting rules

For a finding, record inspected commit, affected symbols, trigger, actual/expected behavior, impact, confidence, supporting reproduction/source evidence, proposed fix and remaining verification. Separate confirmed defects from hypotheses. Reuse existing issues and [maintain their status files in place](../../issues/README.md). Do not automatically expand frozen release gates with speculative hardening.

Phone QA needs current explicit authorization. Preserve originals, app identities and experimental ownership. Automated CI/source/selected device evidence does not replace user acceptance. See [AGENTS.md](../../../AGENTS.md) and [parallel tracks](../../parallel-tracks.md).

## Targeted historical references

- [Initial repository map and audit](2026-09-27-main.md).
- [Network/controller source findings](2026-09-30-network-entry-points.md).
- [Post-RC source boundaries and live policy readback](2026-10-25-post-rc-source-boundaries.md).
- [Qualified credential history/text-blob rescan](2026-10-25-credential-rescan.md).
- [Latest selected dependency advisory refresh](2026-10-29-dependency-advisory-refresh.md).
- [Build/dependency trust disposition](2026-10-07-build-trust-disposition.md); later user decisions in the release plan control the current gate.
- [Last post-RC checkpoint](../handoffs/2026-10-25-post-rc-continuation.md); older execution receipts remain linked from the issue files and archive.
- [Archived detailed audit index and workstream map](../handoffs/2026-10-21-status-archive/audit-index.md). It preserves all older report links; inspect only the relevant entry, with its dated commit and limitations.
