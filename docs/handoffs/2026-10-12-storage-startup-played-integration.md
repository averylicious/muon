# Storage startup and played-copy integration — October 12

Current #253 continuation. User requested continued solo engineering; **no Claude or phones this cycle**. Experimental checkout/session remains user-owned. Application PRs remain OPEN for inherited acceptance. Production partition selection remains disabled; no Stable release/tag or live migration was performed.

## Exact source and verification

All source successors target main and include `2a275286f28db78083567465a521b7ea46f32640`. Most complete source candidate: #503, currently `75ce12e4494a122e8d7097c9e4aab7cddc899bb2`; final Android .982 verification is pending at this checkpoint revision. Check its live head/run before resuming; do not substitute an earlier green build.

| PR | Exact head | Android run | Actual report tests per variant | Artifact ID |
| --- | --- | --- | --- | --- |
| [#497](https://github.com/averylicious/muon/pull/497) | `14db08d00e30b2e576cd42de1d59714074afdb52` | [.970](https://github.com/averylicious/muon/actions/runs/38230056469) passed | 1,339 | 11738278418 |
| [#498](https://github.com/averylicious/muon/pull/498) | `0a7d1ced76723b2ff847a740f171518d4e879fae` | [.971](https://github.com/averylicious/muon/actions/runs/38231483362) passed | 1,344 | 11739391378 |
| [#499](https://github.com/averylicious/muon/pull/499) | `a6cb27542cb9e6adc73fb957073c95b2d9992ac3` | [.973](https://github.com/averylicious/muon/actions/runs/38233802652) passed | 1,351 | 11740047792 |
| [#500](https://github.com/averylicious/muon/pull/500) | `aef816493e4f65b51cd24c30a80d6922c4dfebaf` | [.975](https://github.com/averylicious/muon/actions/runs/38235008897) passed | 1,354 | 11740726360 |
| [#501](https://github.com/averylicious/muon/pull/501) | `14f37e68fd82e6280150abbb93819ec025becb5a` | [.976](https://github.com/averylicious/muon/actions/runs/38236160895) passed | 1,357 | 11740767706 |
| [#502](https://github.com/averylicious/muon/pull/502) | `c56569efeadaf6153d8606a633cdad2fc5402dff` | [.978](https://github.com/averylicious/muon/actions/runs/38237894369) passed | 1,361 | 11741604705 |
| [#503](https://github.com/averylicious/muon/pull/503) | `75ce12e4494a122e8d7097c9e4aab7cddc899bb2` | [.982](https://github.com/averylicious/muon/actions/runs/38239901961) pending | not yet verified | pending |

For .970–.978 above, Branch direction passed at each final head. Downloaded reports showed zero test failures/errors/skips, lint zero errors (68 warnings per variant through .971, 69 thereafter). Actual APK BUILD commit/run/version, SHA256SUMS, package, signing certificate, nondebuggable state and exported-component policy were verified. Artifact names are `app-debug-<full-head>`, contain app-debug.apk/SHA256SUMS/BUILD.txt, expire after 14 days and update existing Canary. They are not Obtainium releases and do not contain the user's Expressive branch.

SHA-256 receipts:

- .970: `c5e31cb0b19e8d1c3264c2ffc0158b1e5af6da4b65295cf1964c9f19e1f7910b`
- .971: `9b6516f65420391093505f18ca6f28e623f980bbb64bd74d925d08fd328ffb15`
- .973: `0d614c77abfe6ad73a5ebcf8acc76e3dd59a51e6a4154090bf54f03678aa08a5`
- .975: `6d17765c067a65cb569e8d43cfc2d6c20f058c0bc821b838ce90fa76d4e7558b`
- .976: `b00c4c967bf0849fe2a3c893549e6efeae8345302683b46b4a39515b32a528b0`
- .978: `c3266cd0a628b64564f3afa28331a10f0e1809e37b51b6fbb93cb53eb89c1981`

Earlier CI failures are not final passes: startup compile/worker fixture errors were corrected before .970; .972 exposed the played-key/index-ID mismatch corrected by scalar holdsName before .973; .977 wrapped the expected concurrent IOException in ExecutionException, corrected before .978. .979's cross-shelf test expected Opening rather than the actual unstarted Reserved phase; corrected in #503's current head. Superseded .974/.980/.981 were cancelled or cancellation requested. Final latest-head .982 still requires its own receipt.

## What changed

- **#497:** application-owned asynchronous startup; native/DB/filesystem work off main, managers on main, no second owner on caller cancellation/failure. UI/storage commands fail visibly while initialization is pending. Live playback does not wait for saved storage. Cold download-service foreground obligations are preserved while waiting, without retaining/replaying arbitrary original Intents.
- **#498:** bounded migration/save recovery pages and exact-record explicit retry. Changed/stale consent cannot replace another attempt; old originals/partial destinations remain.
- **#499:** actual owned played-copy writers and mixed playback after restart; manager completion is not fabricated for played copies. Actual source/native close precedes publication. Unknown close retains exact owners and the shared gate.
- **#500–#501:** deletion/Clear for new owned played copies, refusing active/EOF readers, manager aliases and protected originals. Closed remains a durable no-fallback claim after bytes are gone. Clear visits fixed pages, counts actual deleted bytes, and reports protected/unknown remaining usage truthfully.
- **#502:** whole mixed played-byte census, one aggregate admission through actual copy closure, headroom limit/cancellation and failed-fresh-copy cleanup. Protected legacy bytes consume budget. Conservative policy declines optional caching when full; it is **not LRU eviction**. Existing originals are preserved on limit reduction, and a final unknown-length read block can transiently exceed headroom before known cleanup.
- **#503, verification pending:** actual OfflineStore partition preparation/common binder, two explicit non-overlapping root/volume plans, exact cross-shelf pending/empty/index/original name claims and optional absent-index disposition. New tests cover actual app binding/bootstrap/usage, not just isolated roots. A source-backed guard also protects the legacy backend: pinned Media3 DefaultDownloadIndex.ensureInitialized drops unknown-version tables and hydrates all v2 rows; unsupported existing schemas now refuse before that initializer, rather than risk records. New empty indices remain allowed. Unknown/orphaned/view schemas are not repaired.

Pinned published sources checked: Google Maven Media3 1.11.0 `media3-exoplayer-1.11.0-sources.jar`, DownloadService foreground/lifecycle behavior and DefaultDownloadIndex.java 285–314 startup schema behavior. JVM CI/native SQLite/source inspection do not establish phone, mount, visual or performance behavior. Exact attribution: GPT-6, Codex desktop, implementation/source self-check; variant/effort not reported. No independent review claimed.

## Finite remaining engineering and acceptance

| Area | Current disposition / next work |
| --- | --- |
| #253 app startup | Actual factory/common binder prepared in #503; final CI pending. Production choice still refuses PartitionV1 before legacy allocation. Connect a validated persisted explicit plan only after remaining mutation/recovery paths are ready. |
| #253 played policy | Actual copy/removal/Clear/aggregate admission integrated. Complete automatic eviction/resize policy without deleting protected originals or closing active owners; conservative admission is not the original LRU behavior. |
| #253 general removal/move | New played deletion only. General saved/legacy/Ready-original retirement and move paths still need ownership/routing engineering; downloader refusal alone must never be used to justify manager row deletion. |
| #253 opt-in/recovery | Exact retry, fixed pages and real migration worker exist. Still need user-visible space warning, selection/progress/cancel/retry, persisted Fresh/Resume intent and full-restart activation/recovery. No silent upgrade activation. |
| #253 final verification | Compose full production paths and failure/restart tests after integration. Request renewed device scope then; no phone work is authorized this cycle. |
| Application acceptance | All app PRs remain OPEN. Hardware controls/Bluetooth, TalkBack spoken/focus experience, queue/library interactions and new changed behavior still need user acceptance; earlier limited device receipts remain historical. |
| #401 | Saved-card startup timing/performance evidence remains unresolved and separate. |
| #230 | Defined engineering scope complete on acceptance ancestry; UAT pending. Do not reopen a whole rewrite solely because partition integration is still unfinished. |
| #179 / build trust | Full SD lifecycle recovery and dependency/tool/cache provenance reviews are user-deferred follow-ups, not newly reopened current blockers. |
| Stable release | Not authorized. A passing prototype build is neither #253 completion nor release permission. |

## Ownership / recovery

Only this Codex agent wrote these branches this cycle. Claude and phones were not accessed. The user owns the experimental checkout and session. Verified main at this checkpoint: `2a275286f28db78083567465a521b7ea46f32640`; remote experiment observed `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; local experiment remains unchanged at `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c`. Do not switch/update that checkout. Main protection was read back: Build, test and sign + Branch direction, strict/up-to-date and admin enforcement enabled.

Persistent worktrees use `/home/avery/.codex/worktrees/`:

- #497 `muon-storage-startup-dispatch-oct12`, branch `codex/storage-startup-dispatch-oct12`.
- #498 `muon-partition-recovery-controls-oct12`, branch `codex/partition-recovery-controls-oct12`.
- #499 `muon-partition-played-routing-oct12`, branch `codex/partition-played-routing-oct12`.
- #500 `muon-partition-played-removal-oct12`, branch `codex/partition-played-removal-oct12`.
- #501 `muon-partition-played-clear-oct12`, branch `codex/partition-played-clear-oct12`.
- #502 `muon-partition-played-budget-oct12`, branch `codex/partition-played-budget-oct12`.
- #503 `muon-partition-store-preparation-oct12`, branch `codex/partition-store-preparation-oct12`.

PR links and #253 receipts are portable evidence. Codex app attachment attempts hit its existing 100-identity capacity; unrelated attachments were not removed. Local temporary receipts are optional conveniences, not a handoff requirement.

Latest account reading: 14% weekly USED, no five-hour reading exposed; quota was not exhausted. Recheck live readings. Follow continuous-engineering.md: green CI, a PR or a checkpoint is not a stop condition. Current next action is verify/fix #503's latest head, save actual report/APK evidence, then continue the next actionable engineering task while capacity permits. Before a real stop, update active/idle/dirty state and any unfinished checks here. See the [October 11 lifetime checkpoint](2026-10-11-partition-lifetime-integration.md) for previous stack receipts and earlier history.
