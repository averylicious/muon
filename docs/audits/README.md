# Main-track audit

Start with the [maintained issue-state index](issues/README.md), [finite release gates](../release-plan.md) and [coordinator entry point](../coordinator-handoff.md). These current summaries replace an append-only index of every session. Verify live heads/checks before resuming.

## Current release and audit boundary

Stable v0.2.0 is published. Aggregate-memory/storage work through #648 is implemented and accepted within its recorded scope; do not replay the old #634/#636 promotion gates. The user is daily-driving frozen M3E RC .1420 while broader post-RC source/CI auditing continues separately. [Current continuation](../handoffs/2026-10-24-post-rc-continuation.md) and the issue-state index hold exact candidates and evidence.

- CI signing and Benchmark-boundary hardening landed through #685; its replacement superseded #641/#644.
- Discovery #638, collection workers #639, recorder #83 and the separate #646 error-classification correction have verified draft PRs, with acceptance still pending. They do not silently join the frozen RC.
- Historical notification artwork #207, SD latency #401 and other deferrals stay outside the frozen gates. They may receive separate post-RC source investigation under the user's renewed broad-audit instruction; a new finding is not itself permission to change the installed RC.
- No phones are authorized in the resumed cycle. CI/source evidence is distinct from playback, storage, visual or hardware acceptance. A passing build is not whole-app certification.

Older reports retain their inspected commits and inventory counts as historical evidence, not current candidate or open-PR counts. Refresh live state before acting.

## Finding and reporting rules

For a finding, record inspected commit, affected symbols, trigger, actual/expected behavior, impact, confidence, supporting reproduction/source evidence, proposed fix and remaining verification. Separate confirmed defects from hypotheses. Reuse existing issues and [maintain their status files in place](issues/README.md). Do not automatically expand frozen release gates with speculative hardening.

Phone QA needs current explicit authorization. Preserve originals, app identities and experimental ownership. Automated CI/source/selected device evidence does not replace user acceptance. See [AGENTS.md](../../AGENTS.md), [parallel tracks](../parallel-tracks.md) and [continuous engineering](../continuous-engineering.md).

## Targeted historical references

- [Initial repository map and audit](2026-09-27-main.md).
- [Network/controller source findings](2026-09-30-network-entry-points.md).
- [Latest selected dependency advisory refresh](2026-10-25-dependency-advisory-refresh.md).
- [Build/dependency trust disposition](2026-10-07-build-trust-disposition.md); later user decisions in the release plan control the current gate.
- [Current post-RC checkpoint](../handoffs/2026-10-24-post-rc-continuation.md); older execution receipts remain linked from the issue files and archive.
- [Archived detailed audit index and workstream map](../handoffs/2026-10-21-status-archive/audit-index.md). It preserves all older report links; inspect only the relevant entry, with its dated commit and limitations.
