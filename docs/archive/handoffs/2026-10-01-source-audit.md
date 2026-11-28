# Main audit checkpoint: parser, discovery, offline metadata and service QA

GPT-6 / Codex desktop (Sol), effort not reported. Author investigation/self-check; no independent agent was dispatched. Audit remains active at this boundary. The user/Claude experiment is a separate owner/session/checkout. Refresh live ownership, branches and checks before takeover; local chat history or temporary files are not prerequisites.

## Baseline, authorization and progress

Inspected main: `17309b11dd339dd833a8bf135b1b30b2d0f273a7`. Remote experiment: `e1bf045c1fa7139c4966e480f2f06941a703ddfc`. Main [run418](https://github.com/averylicious/muon/actions/runs/36741603466) succeeded; [Canary .418](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.418) publication and APK/BUILD/SHA256 asset set were verified. It excludes parked app fixes and experimental UI. This checkpoint is based on that main; check its PR for its own final head/checks.

About **70% of the planned first source pass**, excluding phone QA, is covered. This is a workstream estimate, not line coverage, a security score or Stable readiness. New builds/regressions do not by themselves increase that estimate. Significant remaining work is listed below.

Any PR needing phone QA stays open under the user's 2026-09-30 instruction. Test/tooling/docs-only blocker-free PRs can be self-merged at green, current-main heads with recorded self-review. No Stable release/tag, auto-merge, device access, secret access, artifact deletion or history rewriting is authorized by this checkpoint. No phone, user's Music library or Claude session was used in this continuation.

## Merged source evidence

The [previous scheduler/queue checkpoint](2026-09-30-scheduler-queue.md) records merged #236/#238 real cache/manager characterizations and #222 resolved-dependency inventory with exact heads/results. Its .410 and parked-branch status are historical.

[#215](https://github.com/averylicious/muon/pull/215) retained-identity characterization is now merged. Final source `251fddf7d729f939d73b4a2f04866c9d41b01517`, squash `17309b11dd339dd833a8bf135b1b30b2d0f273a7`; [run416](https://github.com/averylicious/muon/actions/runs/36740370815) passed 403 tests per variant, zero failures/errors/skips. All four actual Media3 cache/index/source-routing cases demonstrate old A bytes selected under live B metadata after numeric-ID reuse. Production routing logic was extracted without changing its behavior. **#213 is not fixed**, and no real Tauon rebuild/device data loss is claimed.

## New or refreshed app candidates: phone QA pending

These are separate branches, not a combined candidate. Final Android checks include both signed variants, lint and APK/publication identity safeguards. Counts were read from downloaded XML, zero failures/errors/skips per variant. Every artifact below is `app-debug-<full head SHA>` and contains app-debug.apk, BUILD.txt and SHA256SUMS. Branch artifacts expire after 14 days, update the same Canary app and are not Obtainium releases; identify the track before installing over experimental work.

| PR / branch | Exact head | Verified build / artifact | Evidence and manual gate |
| --- | --- | --- | --- |
| [#248](https://github.com/averylicious/muon/pull/248), codex/song-record-delimiters | `ef382d87b4575fd954deee4927085dfd7c3ca565` | [run417](https://github.com/averylicious/muon/actions/runs/36740920802), [Canary .417 artifact](https://github.com/averylicious/muon/actions/runs/36740920802/artifacts/11110008575) |403 tests/variant; all 11 offline codec cases. Ordinary v1 bytes preserved, exceptional NUL-containing text uses compatible v2 Base64 field encoding. Does not repair already ambiguous records. QA existing/new downloads and offline labels; exceptional fixtures only on a separately authorized disposable server. |
| [#250](https://github.com/averylicious/muon/pull/250), codex/discovery-permission-state | `9b8de1ae3bc06dd6646e35117a029d8ab2d0f3d7` | [run420](https://github.com/averylicious/muon/actions/runs/36742241590), [Canary .420 artifact](https://github.com/averylicious/muon/actions/runs/36742241590/artifacts/11110439936) |406 tests/variant; all 14 discovery reducer cases. Denied permission returns idle/empty, stops old scan and suppresses auto-connect; fresh preflight required. QA deny/grant/revoke/return and older Android. Confirms phantom-search source bug, not the cause of fresh-install A5. |
| [#252](https://github.com/averylicious/muon/pull/252), codex/tauon-json-depth | `4289a2be447141eaadaf431ae770bc1d538e206a` | [run423](https://github.com/averylicious/muon/actions/runs/36743573231), [Canary .423 artifact](https://github.com/averylicious/muon/actions/runs/36743573231/artifacts/11111174043) |417 tests/variant;14 parser cases across SDK28/34. Iterative JSONTokener preflight caps structural depth at 64 before recursive DOM parsing; no inheritance/parser fork. QA normal connect/refresh/lyrics and recoverable malformed/deep response. No aggregate-heap or measured speedup claim. |
| [#205](https://github.com/averylicious/muon/pull/205), codex/private-playback-service | `82d65a9cedd9617facf1ba2ed28b4a916a37dd10` | [run424](https://github.com/averylicious/muon/actions/runs/36744479099), [Canary .424 artifact](https://github.com/averylicious/muon/actions/runs/36744479099/artifacts/11112430585) |403 tests/variant; manifest export gate and nine local policy regressions. Refreshed against main17309b, preserves current production changes. QA notification/lock-screen/headset/Bluetooth/reconnect and required companion binding; direct foreign-UID binding intentionally denied. No device/Binder negative reproduction. |

#248 needs a main refresh before merging. Even current-main candidates need live destination/check refresh later. #205's original finding attribution remains Claude Sonnet5.5 / Claude Code; author source review is not independent review. Run419 was superseded; parser runs421/422 failed a Kotlin escape compile error corrected at 423. Neither failed/superseded run is a pass. Latest PR descriptions contain final-head evidence; editing descriptions renews the lightweight branch-policy check.

#237/#240/#242/#245 remain open at the exact heads/artifacts in the previous checkpoint. #234 remains open at head370f74d with successful run393. Older #206/#209/#210/#212/#217/#219/#221/#224/#227/#229 retain historical heads and upload-quota failures in [progress](../audits/2026-09-30-progress.md), not current verified artifacts. Do not blanket rerun every branch or substitute another branch's green check.

## Findings disposition and integration

- #247/#248 follows the original #203 NUL-record concern; #249/#250 follows its permission-timing hypothesis. Preserve prior finding attribution instead of counting rediscovery as new coverage.
- #251/#252 bounds parser recursion; [#253](https://github.com/averylicious/muon/issues/253) tracks the existing #203 Q2 aggregate resource concern. Large flat responses, retained multi-playlist snapshots, metadata fields and IPC payloads remain unbounded beyond the wire-size cap. No observed phone OOM, Binder failure or performance measurement is claimed.
- [Build-crypto reachability](../audits/2026-10-01-build-crypto.md) qualifies pinned builder/sdk-common/AGP callers against primary BC advisory mechanisms. No affected route established in those callers; transitive/test/provider/integrity investigation remains incomplete. No dependency override/upgrade.
- Preserve overlaps when forming a later combined QA candidate: #212/#234/#237/#240 in OfflineStore plus main's #215 helper extraction; #219/#242 QueueScreen; #221/#242 PlaybackService media-item factory; #224 occurrence extras; #206/#245/#252 TauonApi; #206/#250 LanProbe; #206/#210 Artwork. Do not resolve by choosing whole files from one side.
- #205 private-service flag is still absent from main; #204 controller-policy fix alone does not close the direct start route. All app fixes stay separate/open until QA. Experimental owner lands forward integrations at their boundary.

## Next source work

1. Follow #253 with bounded retained-object/transport source inventory and disposable payload characterization before proposing field/item/aggregate limits. Preserve partial-load and legitimate-library behavior; do not silently truncate or delete songs.
2. #179: design volume/index/service ownership to preserve downloaded bytes through SD disappearance/reinsertion. Actual Media3 loss mode is characterized, production fix remains unimplemented; do not unconditionally release an absent-card cache or delete spans to tidy state.
3. #213: choose a stable identity/preservation policy before changing retained routes/schema; path alone is not content identity. #215 is evidence, not a migration/fix.
4. #230: partial-target cleanup remains unresolved. A simplistic new-span delete can erase bytes now used by concurrent explicit downloads. #234 blocks stale publication only; #237 does not settle cleanup ownership.
5. Continue scoped dependency/provider reachability and independently trusted integrity design; finish full playback/focus/lifecycle source coverage. Keep device measurement and compatibility QA separate.

Ownership at this checkpoint: coordinator active in its isolated persistent audit worktree, all app changes committed/pushed on their branches. No live audit Claude task or phone process. User-led experimental activity is not asserted or interrupted. Record final PR/head/check and a new ownership status on shared issues before yielding; reserve capacity rather than leaving uncommitted work at a quota cutoff.
