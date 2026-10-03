# Main audit: generation owners and acceptance refresh — 2026-10-04

## Scope and ownership

Continue the user-authorized stable-main audit. The user experiment and its checkout remain untouched. No phone access this turn; historical device permission is not inherited. Pixel lockscreen remains deferred. Application PRs stay open until user acceptance. Test/documentation-only merges require exact-head green checks, current main, strict protection and a recorded self-review. No Stable tag/release, auto-merge or experiment-to-main integration.

Coordinator: GPT-6 / Codex desktop, effort not reported. Allocated audit Claude: Opus5.5 (`claude-opus-5-5`), High explicitly requested; runtime confirms the model but does not expose effort. It completed its ownership-map assignment and subsequent bounded read-only public-workflow review in separate checkouts and is idle. Runtime stream snapshot: 68% five-hour /6% weekly used; its model response did not expose those readings. Refresh before new assignments. Coordinator independently reviewed Claude's tests/code/map; own integration and documentation are self-reviewed.

## Verified merged controls and publication

- #309 final `ce08eeb4d05547650155f3278972f9e582217fb6`, merged `19313fcb7fde291c32e7463c1891e22e77178118`: Android535, 452 tests/variant, zero failures/errors/skips; both actual Loader controls executed. Main536 publication/BUILD.txt verified.
- #310 final `0e3712b85c9bb99515085f3457de2f31756281be`, merged `ae25fe2149040879f10f95760135a90249db298d`: Android537, 455 tests/variant, all three real source-close controls executed, zero failures/errors/skips. Main538 publication/BUILD.txt verified.
- #311 final `e187f941e03479b6a86a6a968eac9e6f59d87081`, merged `12ab1e44f4e4de9d91eff383bdd8f117f29818e0`: [Android539](https://github.com/averylicious/muon/actions/runs/37149037473), 461 tests/variant, all nine source-close/reader controls executed, zero failures/errors/skips.
- [Main Android540](https://github.com/averylicious/muon/actions/runs/37149793941) passed at `12ab1e44f4e4de9d91eff383bdd8f117f29818e0`; [Canary.540](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.540) target/prerelease/BUILD.txt/APK/SHA256SUMS and actual BUILD.txt commit/run/version verified. This main APK excludes every pending app-fix candidate.

Reports: [Loader](../audits/2026-10-04-loader-release-control.md), [source close](../audits/2026-10-04-source-close-control.md), [per-open prototype](../audits/2026-10-04-reader-admission-prototype.md). These are disposable JVM controls with synthetic faults, not device/card loss, completed production recovery or performance measurements.

## App fixes and combined acceptance

[#312](https://github.com/averylicious/muon/pull/312) stays OPEN at `9fa135f4d4eebfae1738034749af02dfed7c5c8d`: explicitly shuts down the one-shot startup executor after submitting its task, without interrupting it or waiting on main. [Android541](https://github.com/averylicious/muon/actions/runs/37149904085) passed; downloaded XML 461 tests/variant with zero failures/errors/skips and debug artifact11284215249 BUILD.txt exact commit/run/Canary.541 verified. It does not include other app candidates; prefer the combined acceptance APK below. No measured leak/jank benefit is claimed.

[#302](https://github.com/averylicious/muon/pull/302) was closed without merging at 2026-10-03T19:57:48Z. The user reports they did not close it and explicitly authorized reopening/refreshing. It was reopened at 20:09:06Z. GitHub records actor `averylicious`, no app attribution or commit; the client/cause is unknown. No closing command was identified in repository workflows or allocated audit-Claude tool calls, including the retained session around that time. Do not blame an agent/client or call this a CI bug without evidence. #205 is still OPEN; its earlier absence from a default open-PR page was pagination, not closure.

Coordinator uses dedicated `codex/acceptance-oct4` in its own persistent worktree and fast-forwards the existing remote PR source `codex/artwork-acceptance`; the old acceptance checkout remains clean and untouched. Current refreshed head `51493a7a083ebdbb82d88e65b0b064e9759a0aed` contains main `12ab1e44f4e4de9d91eff383bdd8f117f29818e0`, #312, and exact previous component heads:
- #290 `d004ab4046bcd7e67c0873cac7d37a4ced540cfe`;
- #300 `ebbf409a478fdc4d74c6e96400de4ce0474696e0`;
- #210 `1ecc1c5592876c7a8a817d04451beca6e68abab5`;
- #221 `642d8c94ea6877cc48c22125e7ed9bef6f465a22`.

The only production/Gradle delta since verified .531 is the startup executor wrapper. Its one conflict was resolved preserving #240's newer-event filter and `changed = null` cleanup inside the task. [Android542](https://github.com/averylicious/muon/actions/runs/37150918774), Branch direction and [unsigned dependency24](https://github.com/averylicious/muon/actions/runs/37151012735) passed at that exact head. Downloaded XML: 593 tests per variant, zero failures/errors/skips; actual Loader/source-close/artwork/notification/service controls executed. [Debug artifact11284101669](https://github.com/averylicious/muon/actions/runs/37150918774/artifacts/11284101669), `app-debug-51493a7a083ebdbb82d88e65b0b064e9759a0aed`; downloaded BUILD.txt exact head/run542/version0.1.0-canary.542 verified. Same Canary package/data, 14-day branch artifact, not Obtainium; no experimental libraries. Fresh dependency JSON retains no BC Maven coordinate in app runtime, BC1.81 in tests and BC1.79 in build; SDK file observations match the previously inspected public archive. That is not provider/runtime clearance. [Final integration receipt](https://github.com/averylicious/muon/pull/302#issuecomment-5973174450). Leave this and every app component open pending user testing. If main advances, refresh and reverify before any later merge.

## Next bounded source work

Use the [reviewed generation owner map](../audits/2026-10-04-generation-ownership-map.md). Downloaders, service commands/helper callbacks, playback, copier/mover, index readers and posted publication callbacks all need separate ownership and a composed stop receipt. The phone copier reads the card index, and playback routing can query multiple shelves before choosing its source. A fixed-shelf source prototype alone is insufficient.

Do not add unused always-admitting production scaffolding or call quarantine behavior inert: failed-close quarantine changes future admission even without explicit closure. Manager release/callback cancellation must precede exposing downloader refusal; a live remove task can otherwise erase its index row. Any incomplete gate grants no cache release, migration, capture or adoption. #179 durable catalog/card identity/preservation, #213 retained ambiguous bytes and #230 shared/stale target cleanup remain unresolved. #253 resource budgets/measurement and #207 notification identity/retry are separate work. SDK/JDK/cache/native/provider provenance, fresh discovery, hardware compatibility and controlled performance #83 remain qualified/open.

Manual acceptance on the fresh combined artifact: reconnect/library/LAN permission, duplicate queue selection/removal/Undo, scroll restoration and sheet cancellation; download/remove/startup totals and offline playback; safe mounted-card moves on disposable copies; played-copy clear/budget/rapid skip; metadata identity/in-flight artwork and oversized/corrupt covers; normal notification/transport/Bluetooth/headset behavior. Pixel lockscreen deferred. No eject/replacement/failure injection on existing card downloads; keep originals backed up. Tests do not waive these checks.

## Portable recovery

All work is committed in separate branches; inspect live heads, checks and ownership before resuming. Claude is idle, no device task running, original experiment unchanged. Local log/session paths are optional, not dependencies. Final PR/#181/#40 receipts supersede any pending state here. Check current usage at a slice boundary; earlier snapshots become stale. Do not deliberately enter another slice without reserve for fixes and handoff.

## Public-repository checks requested during this cycle

The user's permanent issue/PR creation restrictions are still enabled: GraphQL reports both `COLLABORATORS_ONLY`. REST's empty temporary interaction-limit response is a different setting, not evidence the permanent controls were lost. Public fork policy currently requires approval for first-time contributors, not all external contributors. Read-default token/disabled PR approval, pinned workflow actions, no self-hosted runner, strict main checks and enabled secret-scanning/push protection were read back. No account, token, permission or repository setting was changed; no credential rotation is implied by an unattributed closure. User requests notification of another unexpected closure encountered during ongoing work.

Current main signed APK/benchmark workflows trigger only same-repository push/manual dispatch, not opening an outside PR. The only PR job is read-only Branch policy; main runs destination policy, experiment still runs candidate policy and lacks recent wrapper/publication guard additions. This is a remaining forward-integration need, not proof of secret access or a deliberate removal. Read the [public workflow report](../audits/2026-10-04-public-workflow-boundary.md) for final review and limits; experimental owner should land a reviewed integration at a clean boundary. Preserve the experiment and user session.
