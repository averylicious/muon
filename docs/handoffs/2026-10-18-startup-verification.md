# October 18 storage and startup verification

## Current state and ownership

SOLO GPT-6 / Codex desktop; exact variant/effort not reported. No Claude or subagents. The user renewed rooted USB POCO diagnostics for this task. Original experiment checkout remains clean and untouched; main is `a2db02b8ba0b4959f28aecf5fa89196cf92622bc`. Application PRs remain **OPEN for cumulative acceptance**. No main/experiment merge, Stable release/tag or automatic activation.

Both POCO apps are **STOPPED**: ordinary Canary **.1229**, separate diagnostic **.1226**. Music volume **11** was restored and separately read back. Full disposable diagnostic copy is on **PHONE**; the older partial remains on **CARD**. No active Move, profile, debugger, forward or probe. Original music and both parked pre-ROM inventories remain unchanged.

Current source is **#608**, first ordinary compile pending. **#607 timing .1233** is also building. Next: verify and capture #607 timing, then restore the newer verified #608 ordinary APK when ready. A fresh #608 diagnostic Phone-to-Card Move follows only after its reports/APK pass and normal playback has stopped. Never overlap phone operations.

Prior complete history: [October 17 POCO checkpoint](2026-10-17-poco-android17-storage.md). This file supersedes its latest statuses, not its preservation evidence. [Continuous engineering](../continuous-engineering.md) remains applicable: continue useful authorized work across green boundaries, reserve capacity for fixes/handoff. Latest live quota: **61% five-hour / 10% weekly USED**. Refresh at meaningful boundaries; do not infer another account's limit.

## Exact sources

| PR | Exact head | Branch / persistent worktree suffix | Scope |
| --- | --- | --- | --- |
| [#605](https://github.com/averylicious/muon/pull/605) | `dda7faff681e0ace96a1e6aa5a4cf013b36701f4` | `codex/saved-startup-phase-timing-oct18` / `muon-saved-startup-phase-timing-oct18` | Bounded explicit startup phases; diagnostic clock failures cannot block admission cleanup. |
| [#606](https://github.com/averylicious/muon/pull/606) | `099b15edc0718db9f065577247cdb6029d3f95dc` | `codex/saved-digest-stage-timings-oct18` / `muon-saved-digest-stage-timings-oct18` | Bounded digest stage measurements; actual bytes/binding/known-close tests. |
| [#607](https://github.com/averylicious/muon/pull/607) | `3960604a998fa5d05a5887ff4b2f415e17b054d0` | `codex/owned-partition-volume-boundary-oct18` / `muon-owned-partition-volume-boundary-oct18` | Fresh volume-only callback inside unchanged full canonical child/inode/journal guards. Startup/bootstrap/generic/legacy callbacks unchanged. Parent-alias regression preserves the canonical-path check. |
| [#608](https://github.com/averylicious/muon/pull/608) | `439c9cdab163636ced9d0fe0396f6a0e07c57c60` | `codex/saved-digest-read-block-oct18` / `muon-saved-digest-read-block-oct18` | Fixed 1 MiB full-digest buffer; every actual read still guarded, full length/EOF/binding/close semantics retained. Adds 768 KiB per active digest; cancellation may wait through a larger bounded read. Ordinary playback and copy/comparison buffers unchanged. |

Each follows the preceding verified source. Worktrees are under `~/.codex/worktrees/`; one writer each. #565 documentation branch is `codex/poco-a17-checkpoint-oct17`. It stays open so documentation does not move main underneath the acceptance stack. A new contributor verifies live heads, checks, applicable user authorization and ownership first; temporary files/chat are not prerequisites.

## Verified builds and artifacts

All rows below: actual downloaded Debug/Release reports have zero failures/errors/skips; lint has zero errors and 66 warnings per variant. Selected APK identity, original certificate, debuggable/export policy, BUILD and checksum were checked. Release APK artifacts were not downloaded/inspected. Counts record verification scope, not quality/progress scores. Branch direction passed at current source heads.

| Source / run | Mode / tests per variant | Selected artifact / APK SHA256 |
| --- | --- | --- |
| #605 [.1221 / 39100385970](https://github.com/averylicious/muon/actions/runs/39100385970) | Explicit non-debuggable timing / 1,728 | [app-debug-dda7faff681e0ace96a1e6aa5a4cf013b36701f4](https://github.com/averylicious/muon/actions/runs/39100385970/artifacts/12125649551), reports12125744226; `4319a9efe0cd020c798167a76e472d4650033cbc1b1b65054e79471a77c1abfa`. Installed temporarily, replaced by ordinary .1224. |
| #606 [.1224 / 39100765303](https://github.com/averylicious/muon/actions/runs/39100765303) | Ordinary / 1,734 | [app-debug-099b15edc0718db9f065577247cdb6029d3f95dc](https://github.com/averylicious/muon/actions/runs/39100765303/artifacts/12126881234), reports12127265138; `19a1714bcc539ed69edb8faa6a356ac236398a8574b0e7a0a8f6f6d25f8e375d`. Installed/tested, replaced by ordinary .1229. |
| #606 [.1226 / 39101566965](https://github.com/averylicious/muon/actions/runs/39101566965) | Separate debuggable diagnostic / 1,734 | [app-diagnostic-099b15edc0718db9f065577247cdb6029d3f95dc](https://github.com/averylicious/muon/actions/runs/39101566965/artifacts/12126564821), reports12126584639; `7e55c56a5d2cc4059245464b72db087ceabfe4332d1b723dab0b70ecc0455c8d`. Installed; fresh Move and full proof passed. |
| #607 [.1229 / 39101775459](https://github.com/averylicious/muon/actions/runs/39101775459) | Ordinary / 1,738 | [app-debug-3960604a998fa5d05a5887ff4b2f415e17b054d0](https://github.com/averylicious/muon/actions/runs/39101775459/artifacts/12127182139), reports12127446245; `f209a69019d1c67eadee58cf1046148c7dbdee3feb32b0788c5eb535be82c318`. Installed/tested. |

Pending: #607 **timing .1233 / [39102561385](https://github.com/averylicious/muon/actions/runs/39102561385)** at `3960604`, expected 1,738 per variant; #608 **ordinary .1235 / [39103003081](https://github.com/averylicious/muon/actions/runs/39103003081)** at `439c9cd`, expected 1,739. Do not install unverified artifacts. #607 old `ad106d1` / .1227 was confirmed cancelled after a test-only parent-alias strengthening; no failure/pass attributed to that cancelled run. No local Android build claimed; CI was the actual compile.

Branch artifacts expire after 14 days and are not Obtainium releases. Normal `app-debug.apk` updates the existing Canary package/data; diagnostic is a separate package. Preserve signing/package IDs and monotonic version codes. No uninstall, reset or downgrade.

## Completed startup observations

#605 timing .1221: host Playing **5,935 ms** with 250 ms polling/ADB overhead; PREPARATION **894 ms**, ADMISSION **106 ms**. Three ROUTE_CARD stages **0 ms**; OPEN_CARD **939 / 1,007 / 1,086 ms**; FIRST_READ_CARD **579 / 450 / 281 ms**; READY **4,526 ms** overlaps those operations. Three latest inner-reader snapshots: projection315/213/348 ms, checks98/80/114 ms, read257/6/1 ms; two closed, third open at capture. No failed reader; not proof of Loader drain. The single emitted MIXED_ROUTE367 ms sample is not the whole route cost. Root counters include pre-Play navigation and overlap nested phases.

Ordinary fresh-process host Playing observations: .1224 **5,622 / 5,593 / 5,343 ms**; .1229 **5,244 / 5,519 / 5,224 ms**. These include polling/ADB and uncontrolled caches/compilation/thermal conditions. Ranges overlap: **no demonstrated controlled startup speedup**. #607 timing is needed for stage comparison.

After each completed capture/ordinary series, stopped comparison preserved the exact normal completed request, full native metadata, all legacy audio and own cover; no changed request fields and no completed phone row. Music11 restored/separateGET. No original changes or raw private evidence uploads.

Pinned Media3 1.11.0 [extractor sources](https://dl.google.com/dl/android/maven2/androidx/media3/media3-extractor/1.11.0/media3-extractor-1.11.0-sources.jar) checked: `DefaultOggSeeker.read()` around97–109 seeks near the final page for duration then returns; `StreamReader.readPayload()` around199–203 requests RESULT_SEEK. This is consistent with three opens, not proof of each runtime offset. Source JAR SHA `76404d34549e50b93f16c94119aace19dee06be9068c45a3d4f8ed5289e4eb3e`. Do not remove valid seeks or cache unvalidated routing authority.

## Completed .1226 disposable Move and evidence

A NEW UI Card-to-Phone Move completed. Last busy **324.064 s**, FIRST idle **341.711 s**, second idle359.444 s is not the upper bound: exploratory **5.4–5.7 minutes**, including polling/ADB and uncontrolled caches.

All13 Move observations successful, total97,067 ms: admission7,234/open7,222/metadata707/audio66,380/after4,541/close6,994/final3,989. All13 digest observations successful, total66,371 ms: binding8,654/checkpoint56,292/create0/open1,216/read64/hash121/close4. **Checkpoints dominate, not hashing.** Digest totals overlap enclosing Move/root phases; do not add them or infer all wall time from their sum.

Full stopped proof passed: all request fields, complete3,149,055-byte C14 audio, full native metadata, fresh allocation UID/key, own cover and source allocation/row/cover retirement. Older Move/Delete rows, partial copy and unrelated covers unchanged. Both parked pre-ROM inventories match every backup path/size/SHA again: normal16files38,335,541bytes; diagnostic3files4,532,167bytes. No manual index repair, old Complete replay, original deletion or card eject.

The first host helper expected “Move 1 download to the phone?”; actual UI said “Move 1 download back to the phone?”. It stopped BEFORE confirmation and restored volume. The exact existing dialog was then freshly inspected and confirmed once. This was a driver wording mismatch, not an app defect. One transient null UI root was retried; no ROM cause established.

## Next verification procedure

1. Preserve one writer/phone driver. Verify exact CI head, reports, APK BUILD/hash/certificate/package/flags/export and expected installed version before each install. Use the renewed root grant only within this task; no security/SELinux/network changes.
2. After #607 timing passes, install it without reset, verify its timing-only Settings notice, keep diagnostic stopped and root-mute/separately verify music. Fresh process: Settings → Saved copies → same preserved normal legacy-card control. Capture only fixed numeric stage/outcome/duration/elapsed fields and bounded root/reader counters, including OWNED_CARD_VOLUME. Restore volume/stop in finally; then full stopped normal request/native metadata/audio/cover comparison. No raw logs/addresses/audio/private indices uploaded.
3. Restore a higher-version verified ordinary #608 build, check ordinary playback and full preservation. Do not downgrade to .1229 after timing .1233.
4. After a separately verified #608 diagnostic build, preserve a stopped private/card baseline and confirm a NEW one-copy Phone-to-Card Move. Current full copy is PHONE; older partial CARD must stay. Monitor bounded digest/Move events; force-stop/snapshot only after known idle. Compare all request fields, full audio SHA/native metadata, fresh UID/key/cover, source retirement and unchanged older journals/partial/originals. Restore original media11/separateGET even on host failure; never force-stop an active Move at a monitor bound.
5. #608 changes both the digest block and inherits #607 root optimization. Compare with direction/cache caveats; avoid claiming isolated causal speedup. One fixed1MiB buffer is per active digest under the production exclusive gate, not a whole-process memory proof.

Private helper/snapshot paths are optional conveniences; portable steps and PR receipts above are sufficient. USB device currently `3b34b8e6`, but recheck device identity on resume. No Pixel operations.

## Remaining engineering and acceptance

#401 startup/Move latency remains engineering: follow measured phases and preserve full guards. Do not call this UAT-only or declare Stable-ready. Direct read-only native-index backend is not implemented. Large-library native/managed memory/performance characterization remains unproved. Hardware Bluetooth/headset, spoken/focus TalkBack, subjective gestures/listening and remaining queue/library acceptance are distinct user-led checks. Source/CI and selected rooted proofs do not replace them. #179 deep SD lifecycle and tool/dependency provenance remain user-approved post-release deferrals. Stable promotion/activation requires separate review and explicit release authorization.

## Test-growth concern and PR backlog

User approved continuing **necessary tests for distinct audit failure paths/safety contracts**, with consolidation afterward. This supersedes the temporary hold. Do not inflate progress by count or duplicate coverage without reason. [Measured suite-cost assessment](../audits/2026-10-18-test-suite-cost.md): .1224 contains544plain JVM /1,190Robolectric executions per variant; plain tests sum~3.5s, Android simulation dominates145.7/174.7s. Summed XML durations are not full CI wall time. No coverage reduction or scheduling change made. Follow-up evaluates overlap and routine one-primary-variant/full release-gate coverage while retaining both APK/signing/manifest safeguards.

At resumption there were246open PRs, all main-targeted;225exact heads were ancestors of#605. This measures ancestry, not readiness, and the other21may include equivalent integrated changes. Current stack adds#606–#608. Distinguish application fixes, diagnostic-only measurements, combined QA candidates and documentation. Identify/close superseded PRs as reviewed acceptance work lands; do not merge solely to reduce count. Chat attachments hit100identity cap; preserve existing attachments and use direct links.
