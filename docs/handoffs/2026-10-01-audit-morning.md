# Main audit continuation — 2026-10-01 morning

## Scope and ownership

The user resumed the main audit, permitted blocker-free merges and explicitly authorized Wireless ADB this cycle. This does not grant future standing phone access or a Stable release/tag. The coordinator is GPT-6, Codex desktop, effort not reported. Main and the user/Claude Expressive experiment remain separate under the parallel-track protocol.

Coordinator owns `codex/audit-morning-checkpoint` in its persistent audit checkout and `codex/download-estimate-bounds` in a separate persistent worktree. The clean `codex/network-qa-candidate` head is parked, not abandoned. Claude's allocated audit session finished two bounded card assignments at explicitly selected `claude-opus-5-5`, High effort; the readiness result reported that model. Its dedicated `codex/card-availability-containment` checkout is clean and idle. Do not resume the user's experimental session. No new Claude task is dispatched near quota. Local session IDs/tool logs are optional and not required to recover.

## Verified main and arithmetic boundary

- Main at inspection: `065d1c0bf691d61a0215e1392820ba62414c99ca`, includes merged [#262](https://github.com/averylicious/muon/pull/262) duration arithmetic. Final source head `ab2986fd0f1dacbd58a06978917bbd11b981b8e2` passed [run442](https://github.com/averylicious/muon/actions/runs/36808913282), 413 tests per variant, no failures/errors/skips, lint and signed APK safeguards. Source self-review recorded on the PR; not independent review. #261 is closed as fixed.
- [Main run443](https://github.com/averylicious/muon/actions/runs/36809583424) succeeded; [Canary .443](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.443) publication at that main SHA verified with APK/BUILD.txt/SHA256SUMS. No Stable publication.
- Independent [#265](https://github.com/averylicious/muon/pull/265), `codex/download-estimate-bounds`, head `6dfff20d2f07b4296d94f3ed7bcb8ede016d68e7`, includes that main. Fixes overflow of approximate Download all sizes only. Three new JVM methods use an exact-arithmetic oracle; no download/cache/schema change. [Run446](https://github.com/averylicious/muon/actions/runs/36811893228) was pending at document preparation. Check live result/artifact before calling it compiled or merging. Pure displayed arithmetic does not need phone compatibility QA. See [focused evidence](https://github.com/averylicious/muon/blob/6dfff20d2f07b4296d94f3ed7bcb8ede016d68e7/docs/audits/2026-10-01-download-estimate.md) on its PR branch.

## Combined network/metadata candidate: still open

[#257](https://github.com/averylicious/muon/pull/257), `codex/network-qa-candidate`, final head `d688d7e10f2e5553004bf8fd34666d155d28d372`. Includes main065d1c0 plus #258 incoming metadata guard `11edc09af719b61485b38600799f820ac512b363`. Earlier .431 results are historical. Source integration retained cancellable response reads/closure, guarded parsing and IO DTO projection rather than choosing one overlapping file wholesale.

[Run444](https://github.com/averylicious/muon/actions/runs/36810214914) succeeded:454 tests per variant, zero failures/errors/skips, lint, signing/identity/export/publication safeguards. Branch direction and dependency inventory passed. Signed [app-debug-d688d7e10f2e5553004bf8fd34666d155d28d372](https://github.com/averylicious/muon/actions/runs/36810214914/artifacts/11138613669), Canary .444, checksum/build SHA verified, installed data-preservingly on the authorized Pixel8/Android17. No local Android build.

Coordinator phone evidence on .444, distinct from user QA:
- Normal962-song library/refresh; Disconnect, discovered-server Use and reconnect.
- Explicit in-app offline mode listed132 saved songs; cached song played; returned online. Wi-Fi was never disabled: not network-isolation proof.
- Background playback beyond30seconds, notification artwork/play/pause/next/seek. Direct shell-UID media-button service start was denied as non-exported, not a hostile helper-app test.
- Playback paused; original speaker media volume14/25 and unmuted state verified restored. Stable unchanged; no uninstall, data clear, user music changes, firewall or network settings changed.

**Still pending user testing:** lockscreen and real headset/Bluetooth transport/unplug, required direct-binding companions, explicit download compatibility and fresh-install/local-network permission denial/grant. Leave #257 and original #205/#206/#209/#245/#250/#252/#258 open for the remaining compatibility gate. None of those findings is declared fixed on main. Deadline/cap regressions are JVM fixtures, not phone adversarial reproduction. #179/#213/#230/#253 remain open. This APK excludes #264 and all other parked app fixes/experimental UI.

## Card containment: still open

[#264](https://github.com/averylicious/muon/pull/264), `codex/card-availability-containment`, head `215f2ea762fb10d1083607ddd38d3ec0c96c68ec`, includes main065d1c0. Claude Opus5.5 High implemented and revised it; GPT-6 supplied source review/corrections and integration. Contributor review is not independent authorship-free review.

[Run445](https://github.com/averylicious/muon/actions/runs/36810393120) succeeded:428 tests per variant, zero failures/errors/skips, lint and signed APK safeguards. Includes11 pure decision cases and4 actual disposable cache/index/route fixtures. Signed [app-debug-215f2ea762fb10d1083607ddd38d3ec0c96c68ec](https://github.com/averylicious/muon/actions/runs/36810393120/artifacts/11139562969), Canary .445. **Not installed**: it excludes #257's fixes and would replace .444's app code.

[Focused card report](https://github.com/averylicious/muon/blob/215f2ea762fb10d1083607ddd38d3ec0c96c68ec/docs/audits/2026-10-01-card-availability-containment.md) is on #264's branch, not necessarily main. Missing card is unavailable, not empty or successfully deleted; new decisions skip it, and late cleanup checks the originating shelf/both move endpoints. No cache release/recreate, helper-map clear or directory-adoption guarantee. Does not prevent mid-operation index loss, distinguish same-path replacement, adopt late insertions or solve service-manager reuse. #179 remains open. Leave #264 open for separately safe removable-card QA; Pixel has no card. Do not eject/remove the user's sole copies for testing.

## Stable notification icon report

Installed Stable0.1.0/code173 was inspected without replacing it. Its actual compiled `ic_notification` is Muon's solid three-bar mark; Canary's variant contains the dot. Fresh Stable playback notification showed the solid mark, and .444 showed Canary's dotted mark. Generic/dev music icon report was **not reproduced**. No icon code change or confirmed issue filed. OS-restored/resumption cards/other installed builds remain untested; do not dismiss a later screenshot based on this one observation.

## Continuation and limits

- Finish #265 final-head verification/source self-review; merge only if required checks are green and destination/head current. Then verify main Canary publication. Refresh #257/#264 before any later merge if main moves; old artifacts remain old code.
- Continue offline preservation and resource audit with existing #179/#213/#230/#253 reports. Preserve #234/#237/#240/#248 ownership/codec fixes when integrating; do not duplicate #239 bootstrap work. No automatic destructive partial-span cleanup or retained-record migration.
- Source-read `PlaybackState`, `PlaybackProgress`, `PlaybackModes`, `QueueShuffleOrder`, `MainActivity`, `ReplayGain`, `DownloadArt` and preferences at main065d1c0 while tracing arithmetic. No new confirmed playback/settings defect filed. DownloadArt finite deadline is already included in #257; do not duplicate it merely because main still lacks that open fix. Phone audibility, focus/route changes and process death remain unverified.
- Estimate remains roughly70% of planned first source pass excluding phone QA; planning only, not measured coverage, security score or Stable readiness. Blockers/data-preservation findings remain.
- Last coordinator quota reading:33% five-hour used/5%weekly used. Claude user's latest report93%five-hour/77%weekly used; idle, not claimed exhausted. Refresh on resumption. Stored OAuth extraction for quota was auto-review rejected; use permitted dashboard/tool readings, do not bypass that rejection.
- GitHub GraphQL briefly returned rate exhaustion; REST reads/writes worked. Keep required checks, no bypass. No Stable tag/release or experiment movement.

Coordinator remains active after this document's initial commit. Later PR/issue#181/#40 evidence records final heads/checks, publication and idle ownership. Before yielding, update this checkpoint or issue pointers; a successor first verifies live branches/PRs/ownership rather than replaying already completed commands. Branch artifacts expire after14days and are not Obtainium releases.
