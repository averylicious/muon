# Download bootstrap ownership fix — 2026-09-30

Follows [#239](https://github.com/averylicious/muon/issues/239) and [merged characterization #238](https://github.com/averylicious/muon/pull/238), main `a58049bda54820a3f346478aa24c78099ec3bf69`. Run404 verified all three old-output cases in both variants (399 tests per variant; zero failures/errors/skips).

`OfflineStore.watch` now records IDs touched by live Media3 change/removal callbacks while its initial index snapshot is pending. When that captured snapshot reaches the main looper it skips touched IDs, preserving newer live state. Unchanged records still bootstrap; the set is released afterward, including exceptional publication, so subsequent live events do not accumulate permanent tombstones. The background cursor never touches the ownership set. Actual production managers and the bootstrap Handler use the application/main looper; creation callers initialize the store before background index reads.

This only changes stale mark/byte publication. It does not remove files/index entries, change Media3 download commands or audio routes, introduce schemas/IDs, suppress later redownloads, or claim card-manager recovery. Cross-shelf stale events/lifecycle, retained identity #213, SD preservation #179 and move partial cleanup #230 remain separate.

The three actual watcher/manager/index/cache tests become regressions; a fourth verifies removal and later stopped-state add callbacks after bootstrap. All use native SQLite, disposable real cache bytes, the original watcher/manager and an empty-state cursor-close gate. The controlled downloader only removes fixture cache resources; network/download requests are forbidden. Callback state is observed without copying the production UI closure. CI is the first Android compile/execution; final-head report evidence belongs on the PR. Phone UI/rendering/playback QA remains pending; leave the app PR open.

Manual QA: start with existing downloads; confirm marks/counts settle correctly and unchanged songs remain playable offline. Remove/cancel/redownload during initial loading when practical, then confirm no stale Done/count returns and later deliberate downloads still work. No precise timing or reproduced user-data loss is asserted.

OfflineStore also changes in #212/#234/#237: reconcile all scoped fixes in any combined candidate/forward sync. Experimental checkout not touched. Attribution: GPT-6 / Codex desktop / effort not reported (Sol), implementation/source self-check; no independent review or Claude session.
