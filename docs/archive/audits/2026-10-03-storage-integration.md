# Combined storage acceptance candidate — 2026-10-03

Main baseline `69d78619875042077cdc104a1714ce43796454c3`; branch `codex/storage-qa-accounting`. GPT-6 / Codex desktop, effort not reported: integration/resolution and source self-review. Claude Opus5.5 High authored the accounting component in #297; GPT-6 independently reviewed that component. Independent review of integration pending at drafting. No device work in this continuation. Candidate remains open for user acceptance; does not land/supersede component PRs yet.

## Exact source ancestry included

| Source | Head | Boundary |
| --- | --- | --- |
| #289 storage candidate | `3635908752b1f572bd15d22c1fd26ad43ee7bcf3` | Includes current #264 availability, #281 byte verification and #234 late move-publication ownership |
| #248 metadata codec | `ef382d87b4575fd954deee4927085dfd7c3ca565` | Ordinary saved bytes unchanged; exceptional NUL tags use version2; no audio rewrite/deletion |
| #240 startup ownership | `0304d629d85e836e3b3ed3a96621d923efc0da9d` | Skip captured records superseded by live callbacks; discard temporary touched-ID set afterward |
| #212 played-copy budget | `70527a8772dea72841bb1ddeefbae0e98a05f15a` | Existing selected byte budget, initial trim and partial overbudget cleanup only for played copies |
| #237 played-copy cancellation | `b38e17f250d2c7a133a6ed8ee16bb2e3195a9609` | Single active/latest waiting copy, coalesced maintenance, cancellable network/writer and lifetime |
| #297 accounting | `105ef01fba5d4aacc961f83573fcd0163f593eff` | Equivalent per-ID completed byte total without repeated full sum |

#290 network/queue/library and #210/#221 artwork are **not** included. This remains the main UI/dependency track; no experimental alpha source. Tests-only main receipts do not establish acceptance of this app-changing combination.

## Reviewed conflict resolutions

1. **watch:** retain #240's `changed` bookkeeping at both live callbacks and filtered initial publication; retain #289's `leftoverCopies` availability guard instead of restoring unconditional cross-shelf removal. One ID declaration before bookkeeping, no duplicate shadow variable. Callback ordering otherwise preserved.
2. **copyPlayed:** use #237's cancellable `Call.Factory`, per-copy token and atomic late writer installation, with #212's actual budget-enforcing `CacheWriter`. The byte helper now accepts a defaulted `onWriterCreated` hook before its existing trailing budget lambda. Both cancellation and budget operate on the same writer, before `cache()` entry. Explicit-download and move writers do not use this played-copy budget.
3. **Canonical docs:** keep current permission/state/index text over historical #212 additions. The source report/handoff remains included separately; do not replace October3 ownership with September30 status.
4. **Accounting:** merged without conflict; keep original unique-ID map semantics and both shelf guards, no new cap or state-ownership promise.

## Verification

All exact source heads above and baseline are checked as ancestors of the candidate; source self-review covers resolutions and relevant old/new paths. `git diff --check` and current-destination branch preflight must pass before push. CI is first compilation/run of the combination; pending at drafting.

Existing real bootstrap, move, availability, codec, byte-budget and network/cache cancellation fixtures are retained. Two new integration regressions require actual `OfflineStore.copyPlayed` to reject declared oversize without releasing a held HTTP body, and require cancellation installed on the budget writer to prevent any source open. Existing real maintenance-cancellation cases ensure the new hook stays wired. The loopback fixture sends its initial headers/body prefix together so a header-only rejection cannot race a separate second write. No phone audio, performance timing, SD eject/replacement or user-data-loss simulation.

## Pending user acceptance

Use disposable copied songs with originals protected. Existing downloads/counts and total size should survive startup/offline use. New explicit download and removal adjust totals once; moving between mounted phone/card retains total, bytes and metadata. Normal played copies remain usable offline; rapid track changes and clearing/reducing played-cache size remain responsive while explicit downloads survive. Exceptional tag fixtures are automated; do not edit the real library merely to force NUL metadata.

Card loss/replacement/recovery #179, numeric-ID reuse #213, partial move cleanup #230 and wider aggregate bounds #253 remain unresolved. S1 does not prove in-flight safety or restore a lost index. Existing Pixel lockscreen deferment and hardware/manual gates remain. No Stable release or main app merge until acceptance and final-head/current-destination checks.
