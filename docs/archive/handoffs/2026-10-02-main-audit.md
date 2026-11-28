# Main audit checkpoint — 2026-10-02

## Scope and ownership

Continue the authorized main correctness/security/privacy/performance/CI audit. GPT-6 / Codex desktop (effort not reported) coordinated, reviewed Claude contributions and authored integration/qualification edits; those own edits are self-checks. Allocated audit Claude was explicitly `claude-opus-5-5`, Opus5.5 High (runtime model verified; effort selected, not exposed by runtime). Original experiment checkout/session remained untouched. Current-cycle Wireless ADB permission is not future permission. No Stable release/tag, experimental merge, automatic cleanup/migration or cache release.

Own assignments use isolated persistent worktrees and dedicated `codex/*` branches. All earlier candidates remain recoverable. The final #181/#40 receipt records the exact checkpoint commit, checks and idle ownership after finishing; refresh live state before resuming. No raw sessions, private screenshots, credentials, endpoints or music metadata are required for takeover.

## Main and protection

Main at preparation: `c896958712c3f5241a53b44df3b6e691cee6925d`. Merged #276 resource characterization, #277 volume identity source report, #280 partial-target characterization and #282 public-span feasibility; app-QA PRs below remain open. Prior Oct1 source fixes remain included.

Initial protection preflight returned unprotected main (cause/timing unknown). Restored the previously documented strict/up-to-date required `Build, test and sign` and `Branch direction` checks from GitHub Actions, admin enforcement, zero approval requirement, no force-push/deletion. Read-back verified before merges. This is configuration evidence, not evidence of account compromise. [Receipt on #181](https://github.com/averylicious/muon/issues/181#issuecomment-5947547879). Recheck live protection at takeover; do not bypass gates.

| Merged evidence | Verified source head / run | Meaning |
| --- | --- | --- |
| [#276](https://github.com/averylicious/muon/pull/276) | `a3716b87c7d373802dcb2f99a0b0af076b121278`, [469](https://github.com/averylicious/muon/actions/runs/36980799092),433 tests/variant,0 failure/error/skip | Actual API/list/group/queue codec resource fixtures; counts are not heap/Binder/GC or device performance measurements. Main ad1fcf1. |
| [#277](https://github.com/averylicious/muon/pull/277) | `038ce454924e715c8bc6ca9e4fe84116025414c1`, [471](https://github.com/averylicious/muon/actions/runs/36982894753),docs-only,0 artifacts | S2 volume/cache identity source evidence; no migration/catalog implementation. Main e24fa86; its [472](https://github.com/averylicious/muon/actions/runs/36984530610) was docs-only,0 artifacts. |
| [#280](https://github.com/averylicious/muon/pull/280) | `52e67c964bcbf6655ba432471526851c44f6b4ec`, [476](https://github.com/averylicious/muon/actions/runs/36986339563),435 tests/variant,0 failure/error/skip | All seven actual move cases passed, including reuse of an old differing target prefix and preservation after failure. Captured Add intents are not delivered services; no real audio corruption/data loss claim. Main b1df6cc. |

Latest published main at this checkpoint: [Canary .480](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.480), [main run480](https://github.com/averylicious/muon/actions/runs/36990213747), target `c896958712c3f5241a53b44df3b6e691cee6925d`. Publication target and APK/BUILD.txt/SHA256SUMS assets verified; BUILD.txt full SHA/run/version checked. It excludes all open candidates and the experiment. Keep the phone's network candidate rather than replacing it just because main's version is higher.

#282 public-span snapshot/import control: head `246897018c61cb53940636f654b02658d80c8b7c`, [run479](https://github.com/averylicious/muon/actions/runs/36989317720),436 tests/variant,0 failure/error/skip; all six removable-cache cases passed. Captured healthy public span fields imported intact fixture files into a fresh cache without reopening the old directory, retained the original, and survived new-cache reopen. Test-only result is not durable catalog/recovery implementation. Merged at `c896958712c3f5241a53b44df3b6e691cee6925d`; [main run480](https://github.com/averylicious/muon/actions/runs/36990213747) and its published release/BUILD.txt target/assets verified. Previous .477 remains historical.

## Open app candidates

| Candidate | Latest inspected head / evidence | Remaining gate |
| --- | --- | --- |
| [#257 network](https://github.com/averylicious/muon/pull/257) | `555bd0c2117a7e963cc5522ec7e7272acf733802`, [474](https://github.com/averylicious/muon/actions/runs/36985557522),474 tests/variant,0 failure/error/skip; includes e24fa86 and #279 | Phone compatibility/user acceptance still pending. Main advanced through test-only #280; refresh destination and final-head CI before any merge. |
| [#279 grant callback](https://github.com/averylicious/muon/pull/279) | `cd7853387842f1bf518d5ab861e0fda5428e2522`, [473](https://github.com/averylicious/muon/actions/runs/36985231642),433 tests/variant,0 failure/error/skip | Coordinator checked the identical callback on #257/.474; user acceptance pending. Resume path was already guarded, only permission-result callback changed. #278 stays open until landing. Refresh destination before merge. |
| [#281 byte guard](https://github.com/averylicious/muon/pull/281) | `2b7ad536acd2b4f500a9a38418e1d3a5cface2b3`, [478](https://github.com/averylicious/muon/actions/runs/36988398700),438 tests/variant,0 failure/error/skip; all ten move cases passed | Reject move mismatch before Add using two64KiB cache-only buffers; identical prefix/later-block cases. Extra full reads and snapshot limitations; user storage QA pending, Pixel lacks removable card. |
| [#264 card S1](https://github.com/averylicious/muon/pull/264) | `215f2ea762fb10d1083607ddd38d3ec0c96c68ec`,historical445,428 tests/variant | Disposable removable-card/service lifecycle QA and main refresh; no SD card testing performed on Pixel. Not the #179 preservation fix. |
| [#267 queue](https://github.com/averylicious/muon/pull/267) | `a6974c32a7cc674a3c756c678a4e5ea51be65f86`,historical461,451 tests/variant | Queue/snackbar/reconnect/a11y interaction QA and current main refresh. |
| [#273 library](https://github.com/averylicious/muon/pull/273) | `18542f7593076925ce9f9a0726faf6f295bd7a5f`,historical460,432 tests/variant | Sheet/cancel/double-tap/rotation/mode/scroll/small viewport QA and current main refresh. |

Original constituent app PRs stay open until their combination actually lands. #257 includes #205/#206/#209/#245/#250/#252/#258 and #279; excludes card/queue/library interaction, notification artwork bounds and experiment. Older parked #210/#212/#221/#234/#237/#240/#248 still need their own integration/QA. Old green runs do not verify future combined heads.

## Authorized phone evidence and test build

Phone left on **Canary .474**, commit `555bd0c2117a7e963cc5522ec7e7272acf733802`, [app-debug-555bd0c2117a7e963cc5522ec7e7272acf733802](https://github.com/averylicious/muon/actions/runs/36985557522/artifacts/11217391816). BUILD.txt/SHA256 checked before `install -r`; no uninstall/data wipe/signing change. Branch ZIP updates existing Canary data, expires14days, and is not an Obtainium release.

Coordinator .474 checks: LAN denied → saved offline library/guidance; Disconnect → empty address/discovery idle; Allow → native grant started discovery without parser error, then approved single-server auto-connect loaded the library. Saved-address offline Allow → native grant retried successfully. These are coordinator observations, **not user acceptance**. Whitespace/invalid typed input, multiple servers, Settings return and fresh-install A5 are source/manual follow-ups, not fully device-tested here.

Earlier .468 checks remain historical: loaded-library refresh/reconnect/discovery; LAN-denied cached playback >110seconds with local media muted; direct shell start of private PlaybackService denied. Shell is not a hostile helper app or required third-party controller. .474 flow checks started no playback; do not carry .468 transport proof to every later head. Lockscreen, Bluetooth/headset/unplug, required companions and explicit/long download compatibility remain pending user testing.

Final device state: LAN permission granted with original flags, local media13/25 unmuted unchanged from cycle baseline, playback stopped, Home shown, coordinator XML removed. Stable0.1.0/code173 unchanged; no Wi-Fi/firewall/font/theme/rotation/music-file change. Recheck live device/serial/settings before any newly authorized QA.

## Remaining audit and next boundary

- [Resource report](../audits/2026-10-02-resource-budget-inventory.md): #253 remains open. Per-response/item limits do not bound aggregate retained objects/session extras/heap. Interning by numeric ID alone can conflate different metadata. No unapproved cap or forced device OOM.
- [Volume report](../audits/2026-10-02-volume-catalog-evidence.md): #179 remains open. A missing volume is not empty/safe to rebuild; public UUID is not unique physical identity, cache construction is not a read-only probe, and span APIs may scan/change state. The [public-span feasibility control](../audits/2026-10-02-cache-snapshot-feasibility.md) demonstrates one intact-file import path. Next S3 must establish durable capture, supported metadata, nonmutating legacy attribution and ownership before migration/hot-swap. A filename snapshot can become stale on touch; no crash/mount/capture completeness is proved. Android17/OEM mount lifecycle still needs appropriate disposable-card evidence.
- [Partial-target report](../audits/2026-10-02-partial-target-preservation.md): #230 remains open. Old partial target bytes can be reused by a production move. Do not use key-wide deletion as cleanup; it lacks ownership proof for explicit downloads. #213 retained numeric Tauon identity is separate; no content fingerprint API or production identity migration is implemented.
- Dependency/supply-chain review remains incomplete: action SHAs and wrapper ZIP checksum are pinned; unsigned inventory lists selected versions, not verification of every artifact byte or proof no vulnerable execution path exists. Preserve the qualified prior advisory report.
- Source-pass planning estimate roughly70% is historical and unmeasured; not a security score, performance measurement or Stable-readiness percentage. Stable promotion remains an explicit user request after outstanding defects/QA.

Claude is idle after finishing #281 at explicitly selected Opus5.5 High. Coordinator continues final checkpoint/publication work, then records idle yield on #181/#40. Do not resume another session or depend on a local session ID.

At each takeover verify latest main/PR heads/checks/protection, clean ownership and current quota using permitted telemetry/dashboard/tools. Do not extract OAuth credentials or depend on local logs. Reserve fixes/handoff capacity; no hard-limit event is claimed merely because an agent stops conservatively.

## Final checkpoint boundary

All owned source/test branches are committed and clean; allocated Claude is idle, no further assignment after #281. Coordinator finishes latest-head docs checks/expected-head merge and final receipts, then yields idle. Next source slice is S3 durable public snapshot capture/metadata/path validity and generation-scoped non-destructive import design; this fixture does not settle it. App candidates require user/device acceptance and a current-main/final-CI refresh before merging. No hard-limit exhaustion or complete Stable audit is claimed.
