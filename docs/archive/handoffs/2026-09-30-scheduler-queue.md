# Main audit checkpoint: scheduler, startup state and queue identity

Coordinator: GPT-6 / Codex desktop (Sol), effort not reported. No independent reviewer or audit Claude session was dispatched. This checkpoint records an active audit boundary; refresh ownership and GitHub before takeover. Original Muon checkout and user/Claude experimental session remain untouched. No phone access, release tag, artifact deletion, secret access or history rewriting occurred.

## Verified baseline and scope

Inspected main: `8d056b655cdab4c3a82157ca443d7b5ea1532d73`; experiment remote: `e1bf045c1fa7139c4966e480f2f06941a703ddfc`. Main [run410](https://github.com/averylicious/muon/actions/runs/36733608878) succeeded and [Canary .410](https://github.com/averylicious/muon/releases/tag/0.1.0-canary.410) publication/asset set was verified. It excludes every parked app fix and experimental UI. Branch artifacts below update the same Canary app and expire after 14 days; do not uninstall/change signing to bypass downgrade restrictions.

Approximately **70% of the planned first source pass** is covered. This is a workstream planning estimate, not line coverage, a security score, resolution of every finding or Stable readiness. Phone QA is excluded. Network entry points, CI/publication and many library/queue/storage races now have source evidence and focused regressions; SD preservation, retained identity, aggregate memory, dependency integrity/reachability and full playback/lifecycle coverage remain incomplete.

The user requires any app PR needing phone QA to remain open. Test/tooling-only, blocker-free PRs may be self-merged at green/current-main heads with recorded self-review. No Stable release/tag is authorized.

## Merged evidence, no app fix implied

| PR | Source head / squash commit | Verification |
| --- | --- | --- |
| [#236](https://github.com/averylicious/muon/pull/236) optional-copy characterization | `ad92cf79334966315b01f550e5f1637d9332950a` / `d1dcfe9c11fbff05032c6bc73c04340c8b856723` | Run401: 396 tests per variant, zero failures/errors/skips; all four real CacheWriter/cache/index cases pass. Main .402 publication verified. |
| [#238](https://github.com/averylicious/muon/pull/238) startup snapshot characterization | `749496f62cb45d92711ec581359454b3f4473ebc` / `a58049bda54820a3f346478aa24c78099ec3bf69` | Run404: 399 tests per variant, zero failures/errors/skips; all three actual manager/index/watch cases pass. Main .405 publication verified. |
| [#222](https://github.com/averylicious/muon/pull/222) resolved dependency audit inventory | `e5a4200dca96ce9df6f369ba306eaf17823c84ee` / `8d056b655cdab4c3a82157ca443d7b5ea1532d73` | Run408: 399 tests per variant, zero failures/errors/skips. Inventory run3 succeeded and JSON validated for exact source head; module/edge counts match historical graph. Main run410 and inventory4 succeeded; .410 published. No new advisory scan or artifact-integrity guarantee. |

## New app boundaries: open for phone QA

These are separate main-based branches, not one combined tested candidate. Tests/lint/signing/identity checks pass on the heads below; phone behavior remains pending. Counts are independently read from downloaded XML, both variants, zero failures/errors/skips.

| PR / branch | Exact head | Final Actions / Canary artifact | Evidence and gate |
| --- | --- | --- | --- |
| [#237](https://github.com/averylicious/muon/pull/237), `codex/played-copy-ownership` | `b38e17f250d2c7a133a6ed8ee16bb2e3195a9609` | [run403](https://github.com/averylicious/muon/actions/runs/36724429510), [debug artifact](https://github.com/averylicious/muon/actions/runs/36724429510/artifacts/11101158844), .403 | 405 tests/variant, four real network/cache and nine scheduler cases. One active optional copy plus latest pending; job-scoped two-minute cancellation lets clear/resize proceed. Phone playback/offline/clear/resize/explicit-download QA. |
| [#240](https://github.com/averylicious/muon/pull/240), `codex/download-bootstrap-ownership` | `0304d629d85e836e3b3ed3a96621d923efc0da9d` | [run406](https://github.com/averylicious/muon/actions/runs/36728900147), [debug artifact](https://github.com/averylicious/muon/actions/runs/36728900147/artifacts/11104490718), .406 | 400 tests/variant, four real manager/index/bootstrap regressions. Startup publication skips IDs with newer live callbacks, drops temporary tracking after publication. Phone startup/remove/download badges/counts/offline QA. |
| [#242](https://github.com/averylicious/muon/pull/242), `codex/queue-removal-undo` | `3d0d4543013c664e08f807a3abf2486958aeabd9` | [run412](https://github.com/averylicious/muon/actions/runs/36735762034), [debug artifact](https://github.com/averylicious/muon/actions/runs/36735762034/artifacts/11107297676), .412 | 410 tests/variant, eleven actual player/controller/occurrence cases. Fresh per-insertion metadata UUID preserves surviving duplicate row keys; removal Undo rejects replaced/reordered/missing/cloned occurrence snapshots, restores with a new row key. Phone duplicates/swipes/Undo/new queue/controller/shuffle/progression QA. |
| [#245](https://github.com/averylicious/muon/pull/245), `codex/tauon-metadata-projection` | `4d74f194eea7d6743353f82fe876efe3cf7d62d6` | [run413](https://github.com/averylicious/muon/actions/runs/36738008241), [debug artifact](https://github.com/averylicious/muon/actions/runs/36738008241/artifacts/11108930191), .413 | 404 tests/variant, five real loopback API cases preserve projection rules and identifier rejection. Playlist/track projection kept off main. Phone connect/refresh/progress/metadata/error QA; no measured speedup or aggregate-heap bound. |

Artifact names are `app-debug-<full head SHA>`; each ZIP has app-debug.apk, BUILD.txt and SHA256SUMS. Branch artifacts are not Obtainium releases. The source reports are [copy ownership](../audits/2026-09-30-played-copy-ownership.md), [bootstrap ownership](../audits/2026-09-30-download-bootstrap-ownership.md), [queue removal/identity](../audits/2026-09-30-queue-removal-undo.md), and [metadata projection](../audits/2026-09-30-tauon-projection.md) on their respective PR branches. Main does not contain these app fixes/reports yet.

Run407 failed a real connected-controller acknowledgement regression; the original timeline-generation approach was corrected, not papered over. Run409 passed the intermediate nine-case head; run411 was superseded after destination advanced. Only run412 validates final #242. Historical quota/upload failures in older PRs remain failures, not newly certified passes.

## Integration and remaining work

- Earlier parked #205/#206/#209/#210/#212/#215/#217/#219/#221/#224/#227/#229 and #234 retain their heads/check/QA evidence in [the progress report](../audits/2026-09-30-progress.md). #222 is now merged and supersedes its old table gate. #234 still needs phone QA.
- Refresh destination/history and required checks before any merge. Most older branches are behind main; do not merge using an old green policy snapshot. Do not blanket rerun every unchanged branch or call the separate branch artifacts a combined candidate.
- Preserve overlaps: #212/#234/#237/#240 in OfflineStore; #219/#242 in QueueScreen; #221/#242 in PlaybackService's media-item factory; #224 must retain occurrence extras; #206/#245 in TauonApi; existing #206/#210 Artwork overlap remains.
- #225 optional scheduler has a QA-pending fix #237; #239 startup state has #240; #241 removal Undo and #243 duplicate rows have #242; #244 metadata projection has #245. Keep issues open until the fix lands and disposition is clear.
- #179 production SD disappearance/reinsertion preservation and #213 retained numeric-ID identity remain significant unresolved issues. #230 partial target cleanup/concurrent writer preservation is not fixed by #234. Do not delete retained bytes or unconditionally release missing-card caches to tidy these up.
- Next useful source work: aggregate library/metadata and background-worker resource bounds; finish playback/controller/focus/lifecycle and dependency reachability/integrity review. DownloadArt's serial fetch/remove ordering and finite-call fix #206 already cover part of artwork lifetime; do not file a duplicate stale-resurrection claim without reproduction.
- No combined candidate or Stable readiness claim. Eventually integrate selected fixes, rerun exact-head checks, and request focused user QA before promotion. No phone access is implied.

## Ownership / resumption

Audit writer is active at this checkpoint; no Claude assignment or phone task is running. Every app slice above is committed/pushed. The persistent audit checkout is local convenience, not a dependency; a successor uses its own worktree and refreshes live state. Recheck processes/branch/diff if interrupted. User owns experiment; no automatic forward sync occurred.

Account snapshot at this boundary: 70% weekly used / 30% remaining, Codex app telemetry; no five-hour reading. It is not a cutoff or exhaustion claim. Continue bounded source work while capacity permits, save subsequent evidence on #181/#40, and leave an explicit idle/final pointer when yielding.
