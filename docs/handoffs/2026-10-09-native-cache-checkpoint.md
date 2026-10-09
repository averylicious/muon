# October9: native cache journal, admission, durable metadata and reader lifetime

This supersedes the [earlier bounded-holder checkpoint](2026-10-09-bounded-holders-migration-checkpoint.md). Engineering continues for REQUIRED #253 native-cache redesign; this is not a UAT-only gate or Stable readiness. No phone/ADB or experiment/session commands this cycle. Leave every application PR OPEN pending acceptance. A docs-only checkpoint may merge under protected author self-review; no Stable publication/tag/auto-merge/bypass.

## Exact boundary and ownership

Main at start: `e164a340b2a0367c8277f7d927ced8b6a7401bfa`. Refreshed experiment remote: `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; no experimental changes were made. Root implementation/source self-review: GPT-6 / Codex desktop, exact variant/effort not exposed. The explicitly allocated audit Claude session was idle before use and initialized as Claude Opus5.5 / High. It implemented #434; root independently reviewed that output against the actual pinned published sources, fixed defects and added five regression controls. Root's own fixes are author self-check, not independent review.

| PR | Exact head | Successful/pending Android run | Boundary |
| --- | --- | --- | --- |
| [#433](https://github.com/averylicious/muon/pull/433) | `47ca13c1c8268056c4f9a4235733935646c9e531` | [.828/37895437894](https://github.com/averylicious/muon/actions/runs/37895437894), actual951tests each variant | Persistent restart journal and clean close/reopen full comparison before ready publication |
| [#434](https://github.com/averylicious/muon/pull/434) | `e128cc06da1f5cc3da40a5e7ab8a74b84313c610` | [.829/37895528521](https://github.com/averylicious/muon/actions/runs/37895528521), actual967tests each variant | Combined #433 plus bounded one-resource native mutation admission |
| [#435](https://github.com/averylicious/muon/pull/435) | `c8e661995f48ba8df8cba2ffe7c3186b212b6e60` | [.830/37896881036](https://github.com/averylicious/muon/actions/runs/37896881036), actual982tests each variant | Durable exact metadata outside native byte directory; complete successor to #434 |
| [#436](https://github.com/averylicious/muon/pull/436) | `ef796ba4228ef897c47e50a17ffefdbcbd2020e5` | [.831/37897658461](https://github.com/averylicious/muon/actions/runs/37897658461), pending; expected993tests each variant | Cached-file read lifetime pinned through clean close; complete successor to #435 |

All app PRs remain OPEN. #436 is the newest complete candidate, but its compile/test/artifacts are not yet verified; do not call pending CI a pass. #435/.830 is the most recent verified complete APK at this checkpoint's initial draft. Earlier #432 and all inherited app reductions are included. Main-only docs still do not install the app stack; main's latest published app remains .734 until app integration occurs. Newer main docs heads need a fresh integrated app build before any application merge.

Own persistent worktrees: `muon-partition-journal-oct9`, `muon-partition-resource-admission-oct9`, `muon-partition-metadata-oct9`, `muon-partition-reader-lifetime-oct9` under the contributor's .codex/worktrees. Each is committed, pushed and idle at this boundary. Their paths are convenience only: portable branch/PR/head evidence above is sufficient. The main-only documentation worktree is `muon-partition-engineering-checkpoint-oct9`; verify its final head/run/merge on the PR, not an inferred self-referential SHA in this file.

## Actual report and APK receipts

Actual downloaded .828/.829/.830 reports passed951/967/982tests PER variant, with0failures/errors/skips and0lint errors;61/61/64warnings respectively. BUILD.txt/full commit/run, SHA256SUMS, existing signer, Canary package ID, ordinary non-debuggable manifest and all three private services were checked. Actions was the first Android compile/test/lint; no local Android build, physical-device/heap/startup-speed or power-loss proof is claimed.

| Run | Canary artifact/name | APK SHA256 |
| --- | --- | --- |
| .828 | [11600686351](https://github.com/averylicious/muon/actions/runs/37895437894/artifacts/11600686351), `app-debug-47ca13c1c8268056c4f9a4235733935646c9e531` | `c13f4cf3d40a50a050d353faeda76d5e650d72cec5ba3affd2666722912c338f` |
| .829 | [11600442568](https://github.com/averylicious/muon/actions/runs/37895528521/artifacts/11600442568), `app-debug-e128cc06da1f5cc3da40a5e7ab8a74b84313c610` | `e389f615ed6cb98ccca84364e9c6b430f35e61c57851304105eb9ebdbef0b106` |
| .830 | [11601096613](https://github.com/averylicious/muon/actions/runs/37896881036/artifacts/11601096613), `app-debug-c8e661995f48ba8df8cba2ffe7c3186b212b6e60` | `d77de2ea6f440d1737dc14238a497f0dfdb254de7663e597c737ab80c134031c` |

Branch artifacts expire after14days, update the existing signed Canary/data and are NOT Obtainium releases or separate per-PR apps. These additions remain disabled; inherited playback/download/move/queue/library/accessibility UAT is still pending. No app was installed this cycle.

## New engineering evidence

- [Journal/publication](2026-10-09-partition-journal.md): persistent exact Copying/Verified/Ready/Uncertain records, stale-ticket refusal and fresh-directory retries preserving originals and uncertain targets. Ready requires a fresh current-process receipt, clean native close/reopen, stable UID and another complete byte/metadata comparison. A source-alias factory is refused without closing the original. Ready never authorizes source removal.
- [Native admission](2026-10-09-partition-resource-admission.md): all Cache calls over one exclusively owned known-native instance, exact foreign-key/file/hole refusal, hard metadata/key/native-span/pending-file/lock/listener budgets, exact staged-file length and overflow checks, callback mutation refusal and failure poisoning. Coalesced migration ranges and native spans have different limits. It cannot bound an already-open legacy/untrusted index.
- [Durable metadata](2026-10-09-partition-metadata.md): actual Media3 cleanup loses metadata-only content; native startup also scans/deletes unknown files. Separate exact UID/key-bound SQLite chunks outside the scanned bytes folder preserve unknown fields and zero-byte metadata through real clean native reopen. Missing/corrupt payload refuses, fresh creation cannot overwrite/recreate a missing old store, and interrupted writes roll back. Logical declared length cannot hide retained bytes; incompatible legacy data remains untouched.
- [Reader lifetime](2026-10-09-partition-reader-lifetime.md): the actual read-only CacheDataSource has no upstream/sink and retains its pool pin through EOF/read failures until clean close. Unknown close retains bounded native capacity. Pool stop does not force-close active cached-file readers. This still needs production routing/factory ownership.

Pinned source evidence: Google Maven datasource sources JAR SHA256 `a54ddd9858ed2de57e07c5461dcebdae7a53d92a60210a2a3f5bf501398a5e4a`; root byte-matched inspected extracted SimpleCache/CachedContent/CachedContentIndex/DefaultContentMetadata files and read Cache/DataSource/mutation/source contracts. Gradle remains version authority. No dependency or native schema edits.

## Finite remaining Stable gate

| Gate | Status | Remaining action |
| --- | --- | --- |
| #253 app-owned queues/holders | Implemented on open stack | User acceptance of counts/badges/refusal/selection/delivery/move/Undo/restart and responsiveness |
| #253 native-cache architecture | REQUIRED engineering; tested foundations still disabled | Production owner/pre-open volume/UID/index validation, bounded routing and downloader/played integration; real source mutation/removal/availability barrier; space/cancel/deadline worker; request/cover handover; opt-in controls; controlled production recovery/preservation tests |
| #401 saved-card startup | Cause/fix unresolved | Authorized diagnostic parent/candidate/card/internal comparisons; timings do not establish a cause or fix |
| #230 move/preservation | Defined engineering complete on open stack | User acceptance; migration must preserve its complete-copy and source-removal rules |
| Hardware/queue/library/accessibility | OPEN acceptance | Bluetooth/headset, notifications, duplicates/Undo, scroll/sheet cancellation, TalkBack spoken/focus checks; prior ADB/source checks are limited evidence |
| Full #179 SD recovery | Explicit user deferral, unresolved | Separate follow-up/emulator or device checks; not cleared by narrow preservation tests |
| Dependency/tool/cache provenance | Both explicitly user-deferred for this release | Planned post-release maintenance after main ships/mature M3 integration; inventory is not publisher authentication |
| Stable publication | Not authorized | Accepted final app integration/checks and explicit Stable version/tag/release request |

## Next bounded implementation

1. **Production owner/pre-open admission.** Establish exact current-volume/native UID/layout ownership before native initialization. Opening an unknown directory first and then inspecting keys is insufficient: the pinned SimpleCache constructor can initialize/drop indexes or remove unrecognized spans before admission. A surviving UID marker alone does not prove a present/correct content index. Add missing/changed UID/index/layout and over-budget tests proving originals are not touched BEFORE routing. Prefer supported APIs; any read-only native-format preflight must be pinned-source-verified and refuse unknown formats, never edit native schemas. The separate metadata DB must stay outside the native scanned byte directory. Global/per-store residency must count migration factories as well as readers/writers; do not hide extra instances outside the budget.
2. **Actual routing and writer lifetime.** Connect the bounded owner/pool/read source to saved playback, downloads and played cache without silent streaming fallback, forced eviction or closing an active source. DefaultDownloaderFactory takes a concrete final CacheDataSource.Factory, so a tested supported downloader lifecycle adapter is still needed. Keep request/index/cover ownership and explicit destructive command admission.
3. **Opt-in migration integration.** Hold the real source availability/writer/reader/removal/eviction barrier, require temporary space and bounded cancel/deadline/progress, preserve legacy fallback and all uncertain/overbudget originals, atomically hand over verified ready routing. Any optional cleanup requires another byte/identity/ownership check; journal Ready alone never permits deletion. Interrupted Verified rows cannot simply be promoted on restart.
4. **Controlled then device acceptance.** Native integration tests must force open/write/metadata/close/publication/removal/restart failures. Only then request renewed POCO rooted-ADB access for disposable-data private-index/card identity/cancel/restart/byte-preservation analysis. Legacy import still opens the full old index and can have a high peak; partial migration is not a completed memory redesign.

No POCO is needed to validate disabled helpers. Root should tell the user when a production-routed opt-in candidate is ready; #401 measurements can be separately authorized. Do not assume historical root/ADB permission. Refresh live PR heads/checks, main/experiment ownership and quota before resuming; use a fresh own worktree from the complete successor.

## Quota and safe continuation

Claude finished normally/idle at78%five-hour/92%weekly USED this assignment; no observed hard cutoff. A resumed large context consumed much of its five-hour window before implementation, so no new broad Claude assignment was made. Root continued solo; account exposed48%weekly USED at the most recent mid-boundary read and nofive-hour field. These are dated readings, not a promise of current quota. No code is left uncommitted or dependent on temporary logs. PR bodies/final coordinator follow-up record final checks and idle ownership. Main protection was read back with strict/up-to-date required Build,test and sign plus Branch direction, admin enforcement on; verify again before any merge. No app PR merged.
