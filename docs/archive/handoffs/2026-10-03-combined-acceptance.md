# Main audit: combined acceptance and source follow-ups — 2026-10-03

Start with [preceding checkpoint](2026-10-03-drain-and-storage-qa.md), [audit map](../audits/README.md), [remaining work](../audits/2026-10-03-remaining-work.md) and current PR/#181/#40 receipts. About75%±10 first-source-pass planning estimate remains qualitative, not test coverage/fix completion or Stable readiness. New controls/reports narrow questions; they do not mechanically advance a percentage.

## Ownership

Main at drafting `20ea53a1fbfb3f117efc2cc093ded8eab268fa58`. GPT-6 / Codex desktop, effort not reported: coordinator source/integration/tests/reports, own changes self-reviewed. Allocated audit Claude idle after its completed #300 narrow review, no unfinished assignment. Opus5.5/High was explicitly selected and runtime model verified previously; effort not exposed by runtime. Refresh actual quota before another assignment; five-hour and weekly resets are separate. Private telemetry/session history is not a takeover prerequisite and is not stored here.

User experiment `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c` remained independently owned/clean. No switches/writes/session takeover and no phone work in this continuation. Historical ADB authorization is not standing; Pixel lockscreen deferred. All new code is committed in isolated coordinator branches; documentation final checks/merge are recorded on its PR. No component app PR was closed or merged.

## Merged non-app evidence

- #301 docs checkpoint: head `7453541b55463c5dd40f79466e56e55332b449ff`, [docs run516](https://github.com/averylicious/muon/actions/runs/37131730170), no APK; merged `26c88eaed9d481f210010808f4fd35172c9724c8`. Main [docs run517](https://github.com/averylicious/muon/actions/runs/37132341771) passed with SDK/Gradle/signing/APK/publication skipped. No new release from those docs runs.
- #303 notification cache control: head `1b13234c104c5295f3ebbc0a48467e57488e74c3`, [run519](https://github.com/averylicious/muon/actions/runs/37132851183), downloaded XML448/variant zero failures/errors/skips and both new actual CacheBitmapLoader tests executed. Merged `20ea53a1fbfb3f117efc2cc093ded8eab268fa58`. Own test/report self-review at [receipt](https://github.com/averylicious/muon/pull/303#issuecomment-5970581742), live strict required checks/admin enforcement verified, exact head merge. No application fix or device claim.

Main [run520](https://github.com/averylicious/muon/actions/runs/37133522873) passed; [.520](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.520) published at20ea53a, target/prerelease/three expected assets verified. This main release excludes all open behavior-changing candidates, unlike branch.521. No Stable tag/release or auto-merge.

## One complete acceptance build: #302 (OPEN)

[PR302](https://github.com/averylicious/muon/pull/302), branch `codex/artwork-acceptance`, head `e04c4ecacd8f3917b4b471eed9b8141888106d1b`, includes main20ea53a and exact ancestors:

| Component | Exact head | Included scope |
| --- | --- | --- |
| #290 | `d004ab4046bcd7e67c0873cac7d37a4ced540cfe` | Network/controller/permission, queue/library/interaction fixes; contains #279/#273/#267/#258/#257/#252/#250/#245/#242/#229/#227/#224/#219/#217/#209/#206/#205 |
| #300 | `ebbf409a478fdc4d74c6e96400de4ce0474696e0` | Storage/card/move/metadata/startup/played-copy/accounting; contains #289/#248/#240/#212/#237/#297 and contained #264/#281/#234 |
| #210 | `1ecc1c5592876c7a8a817d04451beca6e68abab5` | Compose identity refresh/stale publication |
| #221 | `642d8c94ea6877cc48c22125e7ed9bef6f465a22` | Notification encoded/decode bounds |

Deliberate Artwork merge keeps finite/cancellable body reading plus captured identity rejection. PlaybackService merge keeps notification caps plus finite metadata client, and audio keeps streaming client. Canonical checkpoint docs retained over obsolete component status text; dated component handoffs preserved. Storage joins without production conflict.

New integration tests: actual artworkBitmap late-response and cancellation routes through loopback HTTP; accepted NUL-safe records through MediaItem extras and rejection when safe encoding expands beyond incoming byte cap. Existing storage copy-budget/cancel controls from #300 and all queue/network/artwork tests retained. Report at [immutable candidate blob](https://github.com/averylicious/muon/blob/e04c4ecacd8f3917b4b471eed9b8141888106d1b/docs/audits/2026-10-03-artwork-integration.md); it is not a main file until that app candidate lands.

[Run521](https://github.com/averylicious/muon/actions/runs/37133533082) and Branch direction passed at e04c4ec: both signed variants, unit tests/lint/identity/safeguards. Downloaded XML580 tests/variant, zero failures/errors/skips, three new integration regressions executed in both. [Canary debug artifact11277644234](https://github.com/averylicious/muon/actions/runs/37133533082/artifacts/11277644234), `app-debug-e04c4ecacd8f3917b4b471eed9b8141888106d1b`, downloaded BUILD.txt exact commit/run521/version0.1.0-canary.521 verified. Not Obtainium; updates same Canary package/data, no experimental UI/libraries. Older artwork/network-only.518 (532/variant) and storage-only.514 (491/variant) are historical subsets, not preferred complete acceptance builds.

Coordinator [integration self-review](https://github.com/averylicious/muon/pull/302#issuecomment-5970718147) is not independent review of its own resolutions/tests. Claude's earlier narrow independent #300 review remains that scope only. **#302 and ALL component app PRs remain open for user acceptance.** Green CI/source and prior coordinator device observations do not waive it. Refresh current destination/head/CI and source resolution before any later merge; no blanket release clearance.

## Source questions narrowed

- [Notification identity](../audits/2026-10-03-notification-identity.md): actual pinned cache tests confirm same URI with changed tags reuses old/failing future. Both session/provider add outer caches. #210/#221/#302 do not remediate this separate #207 follow-up. No OS notification rendering or small-icon regression claimed.
- [Cache writer scopes](../audits/2026-10-03-cache-scope.md): inspected Gradle jobs use trusted push/manual triggers; pinned action saves by default only on default branch, all278 listed cache refs were main. No untrusted-to-main cache writer route established, but archive payload/creator provenance and compromised trusted writers were not verified. Wrapper JAR guard does not authenticate unpacked/dependency/tool caches.
- [API37 legacy queue](../audits/2026-10-03-legacy-queue-source.md): actual SDK client sends ParcelableListBinder (older Media3 comment says ParceledListSlice), checks before whole-item write and receiver accumulates complete list. Media3 compat retains queue plus conversion. Matching system-server/OEM retention and practical heap/latency remain unverified; #253 not closed or capped.
- [Test crypto provider](../audits/2026-10-03-test-crypto-provider.md): fresh dependency19 confirms BC in test/build, absent as Maven coordinate in app runtime. Actual Robolectric setup installs Conscrypt first and BC fallback; no direct vulnerable crypto input route found in inspected Muon tests. apkzlib facade delegates apksig; remaining transitive signing/provider routes are not cleared. No new advisory scan/provider modification.

## User acceptance and next work

For .521: library refresh/permission/reconnect; duplicate queue selection/removal/Undo, scroll restore and sheet cancel; changed undownloaded identity cover/player colour with prior request in flight; normal/offline and corrupt/large covers/notification/transport; download/remove/startup byte totals; ordinary metadata/offline playback; mounted moves using disposable copied audio; played-copy cache limit/clear/rapid skip cancellation while explicit downloads remain. Keep originals protected. No eject/replacement/mid-I/O failure injection on existing card downloads. Pixel lockscreen deferred, headset/Bluetooth/focus remain hardware/manual gates.

Source/fix work remains meaningful: #179 production generation/service/read/writer/callback drain and durable catalog/adoption; #213 preserve retained bytes under ambiguous reused ID; #230 partial-target ownership/cleanup; #253 compatible peak-parse/aggregate retained budgets; notification identity/retry; remaining apksig/tool/provider/artifact provenance; controlled performance #83 and unresolved hardware/fresh-discovery/accessibility flows. Source controls are evidence, not production preservation. Avoid an unsafe cache release, blind deletion/rekey or speculative cap.

Next bounded source task may qualify apksig/tool crypto or advance #179 read/service ownership design before implementing a production barrier. Main and experiment forward reconciliation remains an experimental-owner boundary. PR attachment attempts failed the desktop's100-identity cap; direct GitHub links preserve access, no attachments removed to bypass it. Final main/docs PR receipts supersede drafting states.
