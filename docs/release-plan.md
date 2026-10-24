# Release plan

Updated 2026-10-24. Published Stable v0.2.0 and a separate M3E next-release candidate under delta audit. Workstream names are not version numbers.

## Current release: v0.2.0

The user accepted RC .1352 plus the .1353 chevron/TalkBack-focus retest, then authorized merging/cleanup, main-to-M3E integration and delegated Stable version/publication. The accepted source is `f6d1be9591ca8e38bdc417906f75c3029c2e3e82`, merged through [#648](https://github.com/averylicious/muon/pull/648) as `8c456abe408ebd6fcaffe586bd9c6b73fa57ad18`.

| Gate | Status |
| --- | --- |
| Locked engineering and actual-release acceptance | Complete within recorded scope; .1353 CI passed 1,821 cases per variant, lint 0 errors / 66 warnings. Both installed release APK hashes verified; user acceptance recorded on #648 |
| Main integration | #648 merged with ancestry preserved; main .1354 build and Canary publication passed; docs #649 passed lightweight checks |
| Stable publication | v0.2.0 published/Latest at79b5e70e; release .1358, actual signed APK checksum/manifest/metadata and tests verified |
| Experimental transfer | #651 merged after its own .1360 checks; main baseline included, experimental pins/features preserved with documented Save-copy UI adaptation. Final cleanup documentation sync is separate; user visual/gesture UAT remains |

Real-user upgrade from an old saved-library state was unavailable. The user approved controlled compatibility instead; published v0.1.0 was streaming-only. Selected actual minified storage/Move/Delete/queue checks and memory/legacy fixtures passed. This evidence is not a universal memory guarantee, every filesystem-failure test, or proof of the lost reset-related refusal.

## Open follow-ups, not fixed claims

- **#646:** user explicitly keeps the historical post-reset/SD-card Delete refusal open and unresolved. Current-copy deletion passes; the original private state was cleared, and no historical fix is claimed. Retain exact-route evidence if it recurs.
- **#401:** measured saved-card startup/Move delays accepted for this release; further performance work deferred.
- **#207:** notification-only stale artwork after track-ID reuse explicitly deferred; in-app fix is in the candidate.
- **#179:** deeper SD eject/live-process/restart recovery deferred.
- Dependency verification and runner/SDK/cache provenance limits explicitly deferred, with no compromise demonstrated.
- Missing-art placeholder mockups and animation/UI polish belong to the next design cycle.
- #650 numerical test-suite reduction was withdrawn by the user after independent review; the issue is closed not planned. Keep meaningful regression coverage.

Do not reopen a general audit for this release. A confirmed security/privacy/data-loss/crash defect or regression against accepted behavior can interrupt it; speculative hardening and ordinary polish go to maintenance. Keep deferred issues open instead of labelling them fixed.

Latest publication/integration/cleanup receipts: [release checkpoint](handoffs/2026-10-23-stable-020-release.md). The original audit/design umbrella is closed; retained maintenance issues remain open.

## M3E RC preparation — 2026-10-24

The user reports all [focused M3E UAT](handoffs/2026-10-24-m3e-focused-acceptance.md) passed and requests an RC for the next Stable. Accepted combined source: `209247be9634cd60145525ed15a98af46b5f47fc` / Diagnostic .1415, containing #674 and #675. Frozen #678 was integrated on M3E, and minified release-package .1420 was verified/installed on both phones. [Exact RC receipt](handoffs/2026-10-24-frozen-m3e-rc.md). Promotion policy #679 is CI-passed/open; final release-configuration acceptance and separately checked main integration remain. Distribute the RC through existing Canary artifacts; no new RC channel/tag or Stable publication is implied. #671 has not recurred and stays open/unresolved as a non-blocking follow-up. #646, #83 and all other recorded deferrals stay outside this finite RC gate. Do not resume maintenance drafts as a prerequisite.

## Next cycle

The released baseline is integrated into M3E. The user now considers its design mature; audit candidate `1ba260fb` and affected shared contracts against v0.2.0 using the [finite M3E delta gates](audits/2026-10-24-m3e-delta.md). #674/#675 prepare diagnostics and focused lifecycle fixes; #671 remains unexplained. No wholesale re-audit, numerical test-reduction gate, promotion-policy bypass or new Stable publication is implied. Current Stable keeps its pinned dependencies.

Follow [parallel tracks](parallel-tracks.md) for expected-head checks, isolated writers and owner-controlled experiment integration, and [release naming](release-naming.md) for version rules. Main/M3E Canary builds share a package; preserve the user's everyday Canary during agent testing by using Diagnostic when explicitly authorized.

Earlier inventory and detailed evidence limits: [dated gate ledger](handoffs/2026-10-23-pre-release-gate-ledger.md), [issue state](audits/issues/README.md), [release-configuration acceptance](handoffs/2026-10-22-release-acceptance.md), and [the final RC checkpoint](handoffs/2026-10-23-saved-copies-ui.md).
