# Muon current state

Updated 2026-10-24. [Current release/cleanup checkpoint](handoffs/2026-10-23-stable-020-release.md) · [Issue state](audits/issues/README.md) · [Release plan](release-plan.md).

## Shipped and integrated

- **[Stable v0.2.0](https://github.com/averylicious/muon/releases/tag/v0.2.0) is published and Latest.** Tag source `79b5e70e7c10719eeb9e42b7b1e517c85f70acbf`, release .1358. Published APK/signature/checksum/manifest/BUILD verified;1,821 tests per variant passed. Accepted RC source and phone UAT are recorded separately in the checkpoint.
- Accepted cumulative #648 landed; concise README/release docs #649 landed. Frozen audit/design issues and completed Collection redesign milestone closed. Superseded PRs/remote refs reconciled by ancestry or reviewed equivalence, not blind deletion.
- **M3E #651 landed**, merged `4750c876886edc1780f558236a91bd51812fd901`, including the released main baseline. Its tested head .1360 passed1,853 cases/variant and latest-head benchmark tools. Post-merge/final-doc sync checks must be read live; combined experimental visual/gesture UAT remains pending. Original owner checkout untouched; fetch/fast-forward before editing.

## Remaining maintenance

- **#646 stays open and unresolved** by user decision; current-copy deletion passing does not prove the lost reset/card case fixed.
- #401 saved-card/Move latency, #207 notification-only stale cover, deeper SD lifecycle and dependency/tool/cache provenance retain explicit deferrals.
- #638/#639/#640 and draft PRs #641–#644 are next-point work, not included in this release. Refresh them against released main before future testing/merge.
- #650 is closed not planned at user request after independent agents opposed numerical test-suite reduction. No tests removed.
- The user considers M3E design mature; [next-RC delta audit](audits/2026-10-24-m3e-delta.md) is active on `1ba260fb`, with #674 diagnostics and #675 lifecycle fixes in separate worktrees. #671 buffering has no established cause. Current main/Stable are unchanged by experimental patches.

## Working rules

[AGENTS.md](../AGENTS.md), [parallel tracks](parallel-tracks.md) and [continuous engineering](continuous-engineering.md) apply. Use isolated writers, current-head checks and concise maintained issue snapshots. Forward-sync/release permission is task-specific; the v0.2.0 delegation does not authorize arbitrary future Stable tags. Main and experimental Canary share a package; preserve everyday Canary during separately authorized Diagnostic tests. No device access follows from this page.

[Coordinator handoff](coordinator-handoff.md) is the resume entry. The [historical archive](handoffs/2026-10-21-status-archive/STATE.md) and linked dated reports preserve earlier evidence; do not replay old pending gates that were later accepted, completed or explicitly deferred.
