# Network/reconnect QA candidate — 2026-10-01

## Scope and ownership

GPT-6, Codex desktop (effort not reported), owns `codex/network-qa-candidate` in its isolated persistent audit worktree. No Claude assignment or phone access. The user/Claude experiment remains separate and untouched. This checkpoint is on the open candidate branch; a successor must fetch it rather than assume main has it.

Main baseline: `d60fe09fa0d401e69049a1d1da6d1084237ec0c1`. [Main run 430](https://github.com/averylicious/muon/actions/runs/36750627173) succeeded and [Canary .430](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.430) has APK, BUILD.txt and SHA256SUMS at that commit. It excludes the pending app fixes and experimental UI. Do not recommend it over an experimental build just because its version is newer.

## Included original heads

Merged into this candidate with merge commits, preserving the originals for evidence:

| PR | Original head | Behavior |
| --- | --- | --- |
| #205 | `82d65a9cedd9617facf1ba2ed28b4a916a37dd10` | Private playback service and packaged-manifest export gate |
| #206 | `d85c602eb2bc27b7852e14f75c4e229c73cf1c63` | Finite metadata/cover call deadline and cancellable API/artwork/probe reads |
| #209 | `0cfdf05b3abd6886b8ebbcaeaeaa1b4da925ea47` | Load token ownership across cancellation/retry |
| #245 | `4d74f194eea7d6743353f82fe876efe3cf7d62d6` | Complete playlist/track JSON-to-DTO projection on IO |
| #252 | `4289a2be447141eaadaf431ae770bc1d538e206a` | Depth-64 JSON preflight before recursive platform parsing |
| #250 | `9b8de1ae3bc06dd6646e35117a029d8ab2d0f3d7` | Permission-denied discovery stays idle and does not auto-connect |

The #206/#209 documentation conflicts retained current state/authorization pointers and their separate focused handoffs. TauonApi's source conflict retained cancellable byte reading with response closure before parsing, then depth preflight and DTO projection on IO. No production file was wholesale chosen from either side.

Integration found another parser route: LanProbe's 4 KiB cap still permits deep nesting in ignored fields. It now calls the same guarded parser; malformed/deep responses produce no discovered server through its existing exception path. `answer` becomes internal for two actual loopback HTTP integration cases in TauonApiProjectionTest: public API rejects deep fields after cancellable reads; discovery accepts a normal version reply and rejects a deeply nested reply under 4 KiB. This is source-backed and disposable JVM testing, not a device crash reproduction.

## Verification and gates

- Source integration self-check completed; no independent reviewer assigned. Final commit/checks/artifact are recorded on the candidate PR after push. CI is the first Android compilation.
- Nine packaged-manifest Python tests and `git diff --check` passed locally. The first Python invocation lacked PYTHONPATH and failed import; the corrected invocation passed. No local Android build.
- Original #206/#209 overall workflows failed artifact upload despite build stages completing. Do not call them successful. A full successful combined-head build is required.
- **Leave the candidate and original app PRs open pending user phone QA.** No issue closed, app fix merged, Stable release/tag or forward-sync implied. Installing the candidate updates the same Canary data/package and can replace experimental features. Branch artifacts expire after 14 days and are not Obtainium releases.

## Focused manual QA (pending user testing)

1. Connect, refresh, lyrics and cover loading; a normal library remains complete. Disconnect during loading, reconnect/retry rapidly: no stale library/error publication or stuck loading.
2. Play beyond 30 seconds and download audio: audio continues beyond the finite metadata deadline. Downloaded covers and offline fallback still work.
3. Background/lock playback, notification/lock-screen transport and seek, Bluetooth/headset controls, unplug and reopen/reconnect. Required companion apps that directly bind must be identified; private binding is intentionally denied.
4. Deny/revoke local network access: idle permission guidance, no phantom searching/auto-connect. Grant access and scan/retry again. Fresh-install discovery A5 is not declared fixed by this change.
5. Stalled/dribbling server and deeply nested replies need a separate controlled fixture; ordinary successful playback does not validate those failure paths. No helper app or device test has been run.

## Continued audit evidence and next work

- #254 source/build-crypto report merged as `5997212ece3049b5b6e5c49d3cebd4a1ccc2928f`; docs-only run425/main426 generated no APK. Crypto reachability remains qualified, not a complete supply-chain clearance.
- #255 merged as `b14bc8f0635f5fc754caeade13d535bcceb3f2c9`: [run427](https://github.com/averylicious/muon/actions/runs/36747976290), 406 tests per variant, zero failures/errors/skips. [Metadata payload report](../audits/2026-10-01-metadata-payload.md) characterizes real Media3 serialization sizes; #253 production limits remain open. No Binder-kernel/device failure asserted.
- #256 merged as the baseline above: [run429](https://github.com/averylicious/muon/actions/runs/36749414340), 409 tests per variant, zero failures/errors/skips. [Card-index report](../audits/2026-10-01-card-index.md) demonstrates different empty cards sharing stale completion/offline metadata, with same-card and distinct-index controls. #179 remains unfixed; no hot-swap/phone data loss claimed.
- Pinned Media3 extractor 1.11.0 VorbisComment source normalizes keys to uppercase; a suspected ReplayGain tag case mismatch was withdrawn before any issue/fix. Actual audibility/focus/headset behavior remains QA.
- Progress remains approximately 70% of the planned source first pass excluding phone QA, a planning estimate rather than a security score or Stable readiness. Tests/builds do not alone increase that estimate.
- Next source slices: #253 field/item/aggregate resource policy; #179 volume/cache/index/service preservation design; #213 retained-track identity; #230 partial-target cleanup ownership. Each needs focused evidence and preservation semantics rather than an open-ended refactor.
- Other app PRs (#248/#237/#240/#242/#234 and earlier parked fixes) are excluded. See the [previous checkpoint](2026-10-01-source-audit.md) and [progress checklist](../audits/2026-09-30-progress.md). Reconcile overlaps and rerun final-head CI before later combined QA candidates.

Coordinator is active at creation; record final head/run and active/idle ownership on #181/#40 before yielding. No raw credentials, quotas, session IDs or private logs are needed to resume.
