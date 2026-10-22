# October22 release-configuration acceptance checkpoint

User resumed after the model-settings pause. This checkpoint records the completed earlier minified-storage checks and the resumed memory/legacy checks. All selected resumed checks and restoration are complete. No active storage worker, test listener or parked application-code edit remains. Resume from the [maintained #253 state](../audits/issues/253.md), not the full issue timeline. No app changes, merge, Stable tag/publication or experimental integration occurred in this cycle.

## Exact source and ownership

- Cumulative candidate: DRAFT/OPEN [#636](https://github.com/averylicious/muon/pull/636), current PR head `0e110692c186a5c4b7672dfaf600e699dedf24fd`, app/build source `af70c50c226cfafb5320052bf71950a40eef60a2`, including the five-file opt-in implementation `3e609759ef1eedf3e0c3ba19276f700b6f55f188`. Later checkpoint commits change documents only; verify live heads before resuming.
- Verified documentation base/main before this report: `ec02b64f41996ec4a456c764ed94360675570df1`. Experimental remote `e1bf045c1fa7139c4966e480f2f06941a703ddfc`; original checkout `cdfa05167af942bad5dd29c9a1b9e4e7bf92bc8c` untouched. Do not reset an owner's checkout to a remembered remote head.
- Coordinator GPT-6 / Codex desktop (exact variant/effort not reported), source/device author self-check. Allocated audit Claude Opus5.5 (`claude-opus-5-5`) / Claude Code, explicitly invoked High; completed read-only native/session and library/artwork closure passes, now idle. Six file-read/search calls finished the latter pass. This is focused review, not an independent audit of every cumulative component or a whole-process memory guarantee.
- Persistent coordinator checkout: `~/.codex/worktrees/muon-stable-storage-opt-in-oct21`, branch `codex/stable-storage-opt-in-oct21`. No other writer or parked application-code edits. The report uses its own persistent worktree/branch `muon-storage-acceptance-report-oct22` / `codex/storage-acceptance-report-oct22` from main; the app branch stays unchanged. Final restoration passed; verify live state again before any takeover. No successor needs local Claude identifiers to resume.

## Actual CI and artifacts

The first two runs built exactly `af70c50c226cfafb5320052bf71950a40eef60a2`:

| Run | Actual evidence |
| --- | --- |
| Ordinary [.1323](https://github.com/averylicious/muon/actions/runs/39619334938) | Success, required branch-direction check passed. Actual reports: **1,792 tests each Debug/Release**, zero failures/errors/skips; lint zero errors/66 warnings each. Actual APK package, certificate, non-debuggable flag, exports, BUILD commit/version and SHA256 verified |
| Latest app head [.1325](https://github.com/averylicious/muon/actions/runs/39790810530) | Success at `0e110692c186a5c4b7672dfaf600e699dedf24fd`; required branch checks passed. Difference from the runtime-tested app source is documentation only. Actual reports again1,792 tests per variant, zero failures/errors/skips, lint0errors/66warnings each. Actual minified .1325 APK package/certificate/non-debuggable/exports/BUILD/hash verified, not installed; do not describe it as another device acceptance pass |
| Diagnostic [.1324](https://github.com/averylicious/muon/actions/runs/39784955224) | Success, same actual test/lint totals. Separate debuggable Diagnostic package, certificate, exports, BUILD/version and SHA256 verified |

- Ordinary [Canary artifact12355095673](https://github.com/averylicious/muon/actions/runs/39619334938/artifacts/12355095673): `app-debug-af70c50c226cfafb5320052bf71950a40eef60a2`, version `0.1.0-canary.1323`, SHA256 `105d2f54e781338207578b505fcbeaefa0b3ba9e9aab0203620a3475f3d3f5ec`. **Not installed**; it would replace everyday experimental Canary.
- Actual minified [release artifact12355240699](https://github.com/averylicious/muon/actions/runs/39619334938/artifacts/12355240699): `app-release-af70c50c226cfafb5320052bf71950a40eef60a2`, version0.1.0/code1323, SHA256 `300af762eaf8226aa86c746e59dec658afb6ef3eb226e662a6c707dc476e75be`. Installed fresh into the absent Stable identity for authorized acceptance; **not a published Stable**.
- Separate [Diagnostic artifact12428848315](https://github.com/averylicious/muon/actions/runs/39784955224/artifacts/12428848315): `app-diagnostic-af70c50c226cfafb5320052bf71950a40eef60a2`, version `0.1.0-canary.1324-diagnostic`, SHA256 `6a3fd96a67e53207e5d62d7a95c44ea83ab183db79c19216ea37fbffed95fdc6`.
- Current-head acceptance build: [.1325 release artifact12432580029](https://github.com/averylicious/muon/actions/runs/39790810530/artifacts/12432580029), `app-release-0e110692c186a5c4b7672dfaf600e699dedf24fd`, version0.1.0/code1325, SHA256 `99000fcf9b4441e80cf5294183a5495a3cf608ac14f209976cd6e961d413ece3`. Locally verified actual APK, not installed. [Canary artifact12431996776](https://github.com/averylicious/muon/actions/runs/39790810530/artifacts/12431996776) is CI-produced, not locally re-inspected; it replaces experimental Canary.
- No Android build ran locally. Benchmark configuration was source-checked against pinned AGP sources in the previous checkpoint, not built here. Later document-only head checks must be reported separately from these actual APK sources.

## Fresh-ROM device baseline and selected release results

The user renewed rooted POCO testing after a clean flash. Current Android17/API37 root shell works. `run-as` still fails its target-SDK37 SELinux context lookup; no policy or root-manager setting was changed. The flash removed installed Muon apps/private indexes but left SD folders. With explicit approval, the old Diagnostic-only folder was renamed to a separate Android/data sibling. Before/after relative file/type/content proof passed; the regular Muon folder stayed unchanged. Final hashes of both protected old folders also passed after current tests.

The minified release and separate Diagnostic were installed into absent identities, with actual installed APK hashes verified. This is **fresh-install evidence, not an existing-private-data upgrade test**. The pre-flash interrupted experiment cannot be retroactively proved after its private data was erased; keep it historical/incomplete and use the new baseline for future tests.

Selected minified-release acceptance used one generated silent20-second Opus fixture (211,941bytes), served only at device loopback; no user music or public/network configuration was changed:

1. Fresh normal startup kept **legacy mode**. Installing did not select partition mode automatically.
2. Stable opt-in wording and explicit consent appeared. Saving the choice required a full restart; it did not replace live owners.
3. After restart, the original saved row remained readable. Its playback UI started, and saved playback made no extra network audio request. Silent output was not an audible-quality check.
4. Per-copy optimization reached `Replacement verified. Original copy kept.` Stopped proof found the unchanged full original and matching partition replacement SHA256; complete legacy saved-request rows were unchanged. Optimization correctly keeps one visible saved row; an initial test expectation of two visible rows was corrected, not treated as an app bug.
5. Explicit Phone→SD Move completed. Stopped proof checked full request/data/completion fields, fresh target key/UID/native ownership, exact audio/metadata at destination, and retirement of both phone original and replacement payloads.
6. Explicit Delete completed; neither shelf retained the disposable saved row or audio spans.
7. The release connection was backed up and restored **only on the phone**, verified byte-for-byte there. Original media volume restored, app stopped, owned loopback listener/files removed. An initial host-export attempt was automatically rejected; no private connection contents were exported. No active/uncertain Move remains.

These are selected happy-path automation checks, not full release acceptance of every failure/refusal, nonoptimized legacy action, upgrade, Bluetooth or spoken/focus TalkBack path. #401 timing stays explicitly deferred; no speed investigation was reopened.

## #253 finite source closure and interrupted memory case

Focused source checks found no confirmed new blocker in the inspected paths:

- Production partition owners share the six-native-instance admission budget; all three per-shelf pools open through it, and uncertain ownership keeps its permit quarantined. The old pre-opt-in index remains outside that budget by design.
- Final MediaSession player checks every growing overload against the resulting queue (2,048 items/4MiB counted metadata), with app-side refusal before full queue construction and a second final session check. Pure/real-session tests exist; this is logical accounting, not a Binder/process-heap guarantee.
- Production library load charges playlist/occurrence/metadata/artist-membership budgets including old-library fallback. Grouping uses the same artist splitter, deduplicates tracks, clears superseded graphs and checks cancellation.
- Actual artwork LRU charges allocation/key payload plus32KiB minimum, at most384 cache keys in12MiB; two concurrent loads, bounded decodes, no recycle on eviction. Coordinator read the full two actual-cache regression tests; CI ran them. Visible/shared bitmaps, refresh overlap and parsed response peaks remain outside exact logical retained-cache accounting.

### Resumed tiny-artwork result — passed

Diagnostic .1324, same verified APK/source as above. Fresh generated1×1 PNG fixture; one→50,000 distinct tracks/artists/albums→one. **603 distinct image requests in69scrolls**, exceeding the384-key cache capacity. Requested-GC managed heap structure parsed fully at all three stages:

| Stage | TauonTrack / LibraryArtist / LibraryAlbum instances | ArtworkMemoryKey instances | Bitmap instances |
| --- | --- | --- | --- |
| Initial one-record library | 1 / 1 / 1 | 1 | 7 |
| Large library after browsing | 50,000 / 50,000 / 50,000 | 384 | 411 |
| Replaced by one record | 1 / 1 / 1 | 384 | 392 |

This establishes the selected key-retention and old-library-release case. It does not say eviction immediately clears the cache, that all bitmaps belong to it, or that encoded/object counts are exact ART/native retained bytes. Sparse selected PSS maxima were Java113,424KiB, native20,356KiB and total263,947KiB; these are samples, not exact peaks. Zero audio requests; cleanup passed (connection originally absent, original volume/denied Diagnostic permission restored, app stopped, owned listener/files removed). Protected old card hashes matched.

Earlier runs stopped for USB loss, the user's pause, and corrected helper assumptions. Count formatting, remembered tab, album sort order and closed TCP TIME_WAIT entries were test-driver issues, not established Muon failures. The completed fresh-ID run above supersedes those incomplete coverage attempts. Keep earlier evidence historical rather than perpetuating an impossible pre-flash private-index proof as a live blocker. Private raw heaps/indices/receipts and optional scripts remain ignored locally; none uploaded.

### Resumed unoptimized legacy action result — passed

Diagnostic .1324 started in Legacy mode. Created exactly two disposable silent copies, one PHONE and one SD, then explicitly opted in/restarted **without per-copy optimization**. Full saved rows and both audio hashes stayed unchanged; neither partition resource directory contained an optimized replacement.

- Explicit Delete of the unoptimized PHONE copy removed its row/bytes and preserved the SD record/payload exactly.
- Explicit Move of the still-unoptimized SD copy to PHONE preserved request/data/completion fields and full audio hash, created one partition destination, and retired the SD source. This tests the legacy source path, not optimization before Move.
- Final explicit Delete left both shelves without disposable saved rows/audio spans. Stopped storage-only snapshots exclude connection preferences. App, original volume, originally absent connection/denied Diagnostic permission, own listener/files all restored; no active/uncertain storage worker remains.

This covers selected shared-code legacy paths on Diagnostic; it is not an existing-user-data upgrade or proof of every minified failure/recovery path.

### Actual minified queue refusal — passed

Release .1323 loaded a synthetic2,049-song playable selection. Tapping a song showed `Selection too large to queue`, explained the limit and offered `Play this song` or Cancel. Cancel kept all2,049 songs visible; no audio HTTP request occurred. This checks the app-side refusal/smaller-selection offer, not2,049 incremental controller edits or the saved-library fallback. Connection restored byte-exact only on-device; volume restored, app/listener/fixture stopped/removed.

Final root checks found no disposable `.exo` audio payloads in either test app's phone/card storage, no owned listener, both apps stopped, original media volume restored and Diagnostic local-network permission back to its original denied state. Full prior file hashes of the regular Muon and separately preserved old Diagnostic card folders matched. No run-as/SELinux/root-manager/network policy was changed. Current authorization covers this resumed task, not every future session.

## Remaining release boundary — acceptance, not a fresh engineering backlog

No remaining implementation defect was confirmed in the locked inspected #253 paths. Final tiny-artwork verification, selected unoptimized legacy actions and minified queue refusal now pass. No application code or unit tests were added in this continuation. This does not independently clear every cumulative component or promise exact whole-process memory bounds.

Keep #636 DRAFT/OPEN for these **finite remaining user/RC acceptance groups**:

1. **Existing-data actual-release upgrade:** install the selected release-identity RC over an existing Stable installation, without uninstall/clear-data. Confirm saved rows/bytes/settings remain; installing alone must not opt in. On disposable backed-up copies, explicit opt-in/restart and relevant legacy Delete/Move/identity/refusal/recovery feedback should remain correct. Current POCO was clean-flashed, so fresh-install/Diagnostic results cannot prove this upgrade path.
2. **Interaction acceptance:** actual-release queue duplicates/Undo, library/artist scroll restoration, player sheet cancellation/Back/reopening, discovery and layout. Earlier selected evidence remains evidence; final user acceptance is not replaced by source reading or silent fixtures.
3. **Hardware/accessibility acceptance:** Bluetooth/headset controls and complete spoken/focus TalkBack behavior. Rooted UI-tree checks cannot establish audible output or a screen-reader user's experience. Respect the existing Pixel lockscreen-testing deferral; earlier POCO lockscreen evidence is retained rather than changing Pixel settings.

Low-space/error paths have unit/source evidence; selected phone happy paths do not establish every injected filesystem failure. The explicitly deferred deeper SD lifecycle #179 is not silently reopened as a gate. #401 performance, #207 notification-only artwork and provenance remain unresolved approved maintenance follow-ups.

After acceptance, integrate only the reviewed current app head under the required-check policy, verify main Canary publication, and publish Stable **only on explicit user request**. Separate main→M3E integration remains with the experimental owner. The documentation-only report may land under standing merge authorization; that does not merge or release #636. Resume from the maintained issue state and live heads, not an older paused paragraph on the app branch.
