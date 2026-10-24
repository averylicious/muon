# Post-RC source/CI continuation — 2026-10-25

## Scope and owners

The user authorized continued broad audits outside the frozen RC; use the live usage meter and stop with the requested20% reserve unless a reset restores capacity. A passing PR/checkpoint is not a stopping condition. **No phones this cycle.** No ADB, installs, target-data resets, host/network changes, Stable tag/publication or alpha landing. Ordinary branch artifacts are not Obtainium releases.

GPT-6 / Codex desktop is the sole current writer/integrator (exact variant/effort not reported), in isolated worktrees. Allocated audit Claude Opus5.5 (`claude-opus-5-5`) / Claude Code / High explicitly selected completed its assigned coding/source reviews and is idle. Runtime reports model, not effort telemetry. Refresh its actual capacity before reuse; another user-owned Claude session is separate. This record is portable without local chat/session IDs.

The original owner checkout remains local `claude/m3-expressive-alpha` at `2ad986d56876050a64b6858f75480d9f7e14741a`, clean at the latest inspection; do not switch/pull/reset it. Remote alpha is `ee7ddb7521855a7422036d7cd4f384d11b308caa`. Main baseline `dd8782a0426437093a99ea3f7205878b6af775bc` includes security #685/#694 and report #699; app source still matches published Stable v0.2.0. Verify live refs/checks before resuming; this document is a snapshot, not a lock.

## Preserve the daily-driver boundary

Published Latest remains **v0.2.0**, tag79b5e70e / APK1358. The installed unreleased M3E release-package RC **.1420** remains frozen at `6b1a767c180a8b9e531bf1468c9e72e348635b74`, APK SHA256 `6dd8e03861967502b708f7c12b0e611d7d8f6e67ca16d7bf869d6590a613a19c`. User focused UAT passed; actual release-configuration daily-driving/promotion is separate. [Frozen receipt](2026-10-24-frozen-m3e-rc.md). #679 stays draft pending coordinated promotion; no future Stable release is authorized.

## Current candidates / evidence

| Candidate | Exact head / outcome | Next boundary |
| --- | --- | --- |
| [#701](https://github.com/averylicious/muon/pull/701), twelve runtime topics | `75eafaab2747464acfe052a5dbbd4da127245336`, [Android .1484](https://github.com/averylicious/muon/actions/runs/40125355737) passed. Downloaded1,863executions/variant, zero failures/errors/skips; lint0errors/65warnings. Actual release APK SHA256 `b03e7a5312e7798087266c0cce0f52e8fba495bcded669353aec7c2d9d689272`, source/BUILD/checksum, single pinned release signer, package/version/non-debug/export/profile assets checked; not installed. | Draft/open for source review and user acceptance. [Exact input heads, conflict resolutions and qualified checks](2026-10-25-combined-post-rc-candidate.md). Keep individual topic PRs open until deliberate landing/supersession. |
| [#710](https://github.com/averylicious/muon/pull/710), legacy Move callback admission | `836acfe9ba0dbf6f5dc07c7bfdc8cc24785a3536`, [Android .1485](https://github.com/averylicious/muon/actions/runs/40126060599) pending first compile. | Inspect real held-worker/SQLite/cache case; repair any failure, then separately combine with #701 and retest. Not included in .1484. [#401](../audits/issues/401.md). |
| [#683](https://github.com/averylicious/muon/pull/683), recorder/measurement tools | `906707b51a867b8f8fd1968f531c70896f1dc69c`: Android .1473 and both tools.47/.48 passed; both downloaded pairs and actual gate cases verified. Recording helper minimumAPI34 inspected. | Draft/open; real recording/ART/performance still unrun. No automatic profile replacement. [#83](../audits/issues/83.md). |
| [#692](https://github.com/averylicious/muon/pull/692), main→M3E security/CI | `e7a352a00bdb3d08c0102b2f26a87cb3e0562915`, [Android .1468](https://github.com/averylicious/muon/actions/runs/40115183849) passed:1,883cases/variant, zero failures/errors/skips, lint0errors/71warnings. Includes DD main; app/build track files match EE alpha. | Owner-landed only at a clean boundary. If main advances, refresh this integration head/checks first; do not move alpha as a side effect. [#693](../audits/issues/693.md). |

Main Canary .1464 publication from DD was verified against BUILD/source/version/API digest/checksums (SHA256 `3e759d061608ce07e6ee6fa9420691dafae707378aa97198de14a0394d309c2d`). Its downloaded reports passed1,821cases/variant, lint0errors/65warnings. No app install or independent device acceptance followed.

## Findings versus limits

New source-confirmed corrections are in maintained [#702](../audits/issues/702.md) (MP3 ID3 ReplayGain), [#704](../audits/issues/704.md) (declared ID3 allocation before body reading), [#706](../audits/issues/706.md) (user removal preparation before saver enqueue), [#708](../audits/issues/708.md) (saved-check admission across service callback replacement) and #646's bulk unreadable-shelf reporting. All standalone heads and the twelve-input combination passed. Tests use pinned framework/native fixtures, not the phones. No OOM, exploit, audible gain, handover or performance claim.

The initial lowercase-Xiph ReplayGain hypothesis was withdrawn: Media3 already uppercases those keys. The valid format gap is ID3 TXXX. The4MiB leading-ID3 policy deliberately refuses unusually large valid tags/padding; it is not a universal decoder/heap cap. The suspected saved/live asynchronous overwrite is not a confirmed defect: pinned Media3 serializes per-controller commands and the app revokes its preparation token on replacement. These checks do not establish every multi-controller/platform path.

#401 timing, #646's cleared factory-reset/SD refusal, #671 intermittent buffering, deeper eject/restart testing and supply-chain provenance deferrals remain unresolved. Do not quietly add them to frozen-RC gates or claim source notices solved the original incidents. Remaining ReplayGain history retention and derived-cache temporary-write cleanup are narrower source follow-up questions, not demonstrated security/privacy incidents.

## Resume / handoff discipline

1. Read this entry and relevant maintained issue files; verify current PR heads, required checks, main/alpha refs and sole-writer ownership. No need to stream whole issue timelines.
2. Check #710's final-head CI/case results first. Integrate only verified source, preserving both #686 notices and #707 pre-enqueue/application-context behavior in OfflineStore. Add no universal memory/latency guarantee.
3. Continue useful source/CI audit work while actual capacity permits. Reserve wrap-up room; detect a reset from the live meter. Claude review of code it authored is not independent; root follow-ups keep self-review attribution.
4. Keep application/tool PRs draft/open pending later acceptance. Never install over .1420 without a new request. Benchmark tests need their own explicit device/reset authorization.
5. Update this snapshot and issue state in place. PR/issue comments are for actual decisions, blockers, review or material verification/merge changes. Keep private logs, addresses and runtime dumps local.

Owned active worktrees: `muon-post-rc-state-oct25` (documentation), `muon-move-completion-admission-oct25` (source/CI) and `muon-post-rc-acceptance-oct25` (combined CI). Other listed topic worktrees are clean/parked, not available for another writer without live ownership checks. No ADB or audit-Claude operation is active. This is an ongoing checkpoint, not a claim that all audit work is finished.
